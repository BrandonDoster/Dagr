package cc.doster.dagr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Recomputes every OS alarm and status notification from the current state. Idempotent; runs after
 * every change. setAlarmClock is used only for the next real ring. Each section is independent, so one
 * failure can't stop the others.
 */
object Sync {
    private val main = Handler(Looper.getMainLooper())
    private var pendingShort: Runnable? = null

    fun pi(ctx: Context, action: String, id: Long = 0): PendingIntent {
        val i = Intent(ctx, EventReceiver::class.java).setAction(action).setData(Uri.parse("alarm://$action/$id"))
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun leadMs(lead: Int) = when (lead) { 0 -> 299_000L; 1 -> 899_000L; 2 -> 1_799_000L; else -> -1L }

    private inline fun section(name: String, f: () -> Unit) = try { f() } catch (e: Exception) { Log.e("Sync", "$name failed", e) }

    fun run(context: Context) {
        val ctx = context.applicationContext // the delayed Runnable below must not hold an Activity
        val db = Store.db.value
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val ringing = db.ringQueue.map { it.alarmId }.toSet()
        val lead = leadMs(db.settings.upcomingLead)

        section("next ring") {
            val next = db.alarms.filter { it.active != ACTIVE_OFF && it.alertAt > 0 && it.id !in ringing }.minByOrNull { it.alertAt }
            val fire = pi(ctx, EventReceiver.FIRE)
            if (next != null) {
                val show = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(next.alertAt, show), fire)
            } else am.cancel(fire)
        }

        section("skip restore") {
            val restore = db.alarms.filter { it.skipped }.minOfOrNull { it.skippedAt }
            val rpi = pi(ctx, EventReceiver.SKIP_RESTORE)
            if (restore != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, restore + 2_000, rpi) else am.cancel(rpi)
        }

        section("upcoming") {
            val upi = pi(ctx, EventReceiver.UPCOMING)
            val nextUp = if (lead < 0) null else db.alarms
                .filter { it.active == ACTIVE_ON && it.id !in ringing && it.alertAt - lead > now }
                .minOfOrNull { it.alertAt - lead }
            if (nextUp != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextUp, upi) else am.cancel(upi)
        }

        section("watchdog") { // ends a ring the service never started or lost, even if the process died
            val wpi = pi(ctx, EventReceiver.RING_WATCHDOG)
            val head = db.ringQueue.firstOrNull()
            if (head != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, AlarmLogic.ringEnd(db.alarm(head.alarmId), head) + 30_000, wpi)
            else am.cancel(wpi)
        }

        section("timer expiry") { // one alarm, soonest running timer
            val c = Store.clock(ctx)
            val soon = db.timers.filter { it.state == TIMER_RUNNING }.minOfOrNull { TimerLogic.remaining(it, c) }
            val tpi = pi(ctx, EventReceiver.TIMER)
            pendingShort?.let { main.removeCallbacks(it) }
            if (soon != null) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + soon.coerceAtLeast(0), tpi)
                if (soon < 5_000) { // the system may push a <5 s alarm out; also fire in-process
                    val r = Runnable { if (Ops.expireDue(ctx)) RingService.start(ctx) }
                    pendingShort = r
                    main.postDelayed(r, soon.coerceAtLeast(0))
                }
            } else am.cancel(tpi)
        }

        section("notifications") { Notifs.syncStatus(ctx, db, now, lead) }

        section("stale ring notification") { // a directly posted ringing notification must not outlive the ring
            if (db.ringQueue.isEmpty() && db.timers.none { it.state == TIMER_EXPIRED }) Notifs.nm(ctx).cancel(Notifs.ID_RING)
        }
    }
}
