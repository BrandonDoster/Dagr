package cc.doster.dagr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import java.util.Date

object Notifs {
    // Channel ids are fixed forever: importance can't be changed in code after creation.
    const val CH_RING = "ringing_v1"
    const val CH_UPCOMING = "upcoming_v1"
    const val CH_SNOOZED = "snoozed_v1"
    const val CH_MISSED = "missed_v1"
    const val CH_TIMER = "timer_v1"
    const val ID_RING = 1

    private const val TAG_UP = "up"
    private const val TAG_SNZ = "snz"
    private const val TAG_TMR = "tmr"
    private const val TAG_MISSED = "missed"

    fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)!!

    fun createChannels(ctx: Context) {
        val ring = NotificationChannel(CH_RING, "Ringing alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm(ctx).createNotificationChannels(listOf(
            ring,
            NotificationChannel(CH_UPCOMING, "Upcoming alarms", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_SNOOZED, "Snoozed alarms", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_TIMER, "Timer", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        ))
    }

    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    fun openApp(ctx: Context, tab: Int = 0, req: Int = 0) = PendingIntent.getActivity(
        ctx, 100 + tab * 1000 + req, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, title: String, act: String, id: Long = 0, unlock: Boolean = false) =
        Notification.Action.Builder(null, title, Sync.pi(ctx, act, id)).setAuthenticationRequired(unlock).build()

    private fun notice(ctx: Context, id: Int, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_MISSED).setSmallIcon(R.drawable.ic_alarm).setContentTitle(title)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentText(text).setAutoCancel(true).build()
        nm(ctx).notify("notice", id, n)
    }

    fun storageProblem(ctx: Context) = notice(ctx, 1, "Alarm data couldn't be saved", "Storage may be full. Alarms keep working until the phone restarts; free up space so changes are kept.")

    fun dataRestored(ctx: Context) = notice(ctx, 3, "Alarm data restored from backup", "The alarm file couldn't be read, so the last good copy was loaded. Recent changes may be missing; check your alarms.")

    fun dataReset(ctx: Context) = notice(ctx, 2, "Alarm data couldn't be read", "Your alarms were reset. The unreadable file was kept in the app's storage.")

    fun label(a: Alarm) = a.name.ifBlank { "Alarm" }

    fun missedAlarm(ctx: Context, a: Alarm) {
        val n = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Missed alarm")
            .setContentText("${time(ctx, if (a.alertAt > 0) a.alertAt else System.currentTimeMillis())}, ${label(a)}")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(TAG_MISSED, a.id.toInt(), n)
    }

    fun timerExpiredWhileOff(ctx: Context, t: Timer) {
        val n = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Timer expired")
            .setContentText("${t.name.ifBlank { TimerLogic.autoName(t.inputMs) }} ended while the phone was off or the app was stopped")
            .setContentIntent(openApp(ctx, 1))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(TAG_MISSED, -t.id.toInt(), n)
    }

    /** DND currently silences alarms. PRIORITY: alarms category per policy if readable, else assume allowed. */
    fun dndBlocksAlarms(ctx: Context): Boolean {
        val n = nm(ctx)
        return when (n.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
                n.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
            }.getOrDefault(false)
            else -> false
        }
    }

    /** Upcoming, snoozed and timer notifications, recomputed from state; stale ones are cancelled. */
    fun syncStatus(ctx: Context, db: Db, now: Long, lead: Long) {
        val nm = nm(ctx)
        val want = mutableMapOf<Pair<String, Int>, Notification>()
        val showing = nm.activeNotifications.map { it.tag to it.id }.toSet()
        val ringing = db.ringQueue.map { it.alarmId }.toSet()

        // upcoming
        if (lead >= 0) {
            val due = db.alarms.filter {
                it.active == ACTIVE_ON && it.id !in ringing && it.alertAt > now && it.alertAt - lead <= now
            }
            val doneGroups = mutableSetOf<Long>()
            for (a in due.sortedBy { it.alertAt }) {
                val key = TAG_UP to a.id.toInt()
                val gKey = TAG_UP to (-a.groupId).toInt()
                // don't pop a brand-new upcoming notice for an alarm under 58 s away
                if (a.alertAt - now < 58_000 && key !in showing && gKey !in showing) continue
                val today = java.time.LocalDate.now()
                val members = if (a.groupId == 0L) emptyList() else db.alarms.filter {
                    it.groupId == a.groupId && it.active == ACTIVE_ON && it.alertAt > now &&
                        java.time.Instant.ofEpochMilli(it.alertAt).atZone(Store.zone()).toLocalDate() == today
                }.sortedBy { it.alertAt }
                if (members.size > 1) {
                    if (!doneGroups.add(a.groupId)) continue
                    val g = db.group(a.groupId)
                    want[gKey] = Notification.Builder(ctx, CH_UPCOMING)
                        .setSmallIcon(R.drawable.ic_alarm)
                        .setContentTitle("Turn off upcoming group alarms?")
                        .setContentText(g?.name ?: "")
                        .setStyle(Notification.InboxStyle().also { s -> members.forEach { m -> s.addLine("${time(ctx, m.alertAt)}, ${label(m)}") } }.setSummaryText(g?.name))
                        .setContentIntent(openApp(ctx))
                        .setOngoing(true)
                        .addAction(action(ctx, "Turn off next", EventReceiver.UP_GROUP_NEXT, a.groupId, unlock = true))
                        .addAction(action(ctx, "Turn off group", EventReceiver.UP_GROUP_OFF, a.groupId, unlock = true))
                        .build()
                } else {
                    want[key] = Notification.Builder(ctx, CH_UPCOMING)
                        .setSmallIcon(R.drawable.ic_alarm)
                        .setContentTitle("Turn off upcoming alarm?")
                        .setContentText("${time(ctx, a.alertAt)}, ${label(a)}")
                        .setContentIntent(openApp(ctx))
                        .setOngoing(true)
                        .addAction(action(ctx, if (a.repeating) "Turn off for today" else "Turn off", EventReceiver.UP_OFF, a.id, unlock = true))
                        .build()
                }
            }
        }

        // snoozed
        for (a in db.alarms.filter { it.active == ACTIVE_SNOOZED && it.id !in ringing && it.alertAt > now }) {
            want[TAG_SNZ to a.id.toInt()] = Notification.Builder(ctx, CH_SNOOZED)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(label(a))
                .setContentText("Snoozed alarm will go off at ${time(ctx, a.alertAt)}.")
                .setWhen(a.alertAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setContentIntent(openApp(ctx))
                .setOngoing(true)
                .addAction(action(ctx, "Dismiss", EventReceiver.SNZ_DISMISS, a.id, unlock = true))
                .build()
        }

        // timers: up to 3, newest first; promote only the soonest running one
        val c = Store.clock(ctx)
        val active = db.timers.filter { it.state != TIMER_EXPIRED }
        val promoteId = active.filter { it.state == TIMER_RUNNING }.minByOrNull { TimerLogic.remaining(it, c) }?.id
        for (t in active.sortedByDescending { it.createdAt }.take(3)) {
            val name = t.name.ifBlank { TimerLogic.autoName(t.inputMs) }
            val left = TimerLogic.remaining(t, c)
            val b = Notification.Builder(ctx, CH_TIMER)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(name)
                .setContentIntent(openApp(ctx, 1, t.id.toInt()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .addAction(action(ctx, "Cancel", EventReceiver.T_CANCEL, t.id))
            if (t.state == TIMER_RUNNING) {
                b.setWhen(now + left).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setContentText("Ends at ${time(ctx, now + left)}")
                    .addAction(action(ctx, "Pause", EventReceiver.T_PAUSE, t.id))
                if (t.id == promoteId) b.setRequestPromotedOngoing(true).setShortCriticalText(TimerLogic.hms(left).removePrefix("00:"))
            } else {
                b.setContentText("Paused · ${TimerLogic.hms(t.remainMs)}").setShowWhen(false)
                    .addAction(action(ctx, "Resume", EventReceiver.T_RESUME, t.id))
            }
            want[TAG_TMR to t.id.toInt()] = b.build()
        }

        for (sbn in nm.activeNotifications) {
            if (sbn.tag in setOf(TAG_UP, TAG_SNZ, TAG_TMR) && (sbn.tag to sbn.id) !in want) nm.cancel(sbn.tag, sbn.id)
        }
        // Re-posting identical ongoing notifications is cheap; setOnlyAlertOnce keeps them quiet.
        for ((k, n) in want) nm.notify(k.first, k.second, n)
    }

    /** Ringing notification: the foreground-service notification, with the full-screen intent. */
    fun ringing(ctx: Context, db: Db): Notification {
        val head = db.ringQueue.firstOrNull()
        val fsi = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CH_RING)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fsi, true)
            .setContentIntent(fsi)
            .setDeleteIntent(Sync.pi(ctx, EventReceiver.RING_SWIPED))
        if (head != null) {
            val a = db.alarm(head.alarmId)
            b.setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(a?.let { label(it) } ?: "Alarm")
                .setContentText(time(ctx, head.firedAt))
            if (head.canSnooze) b.addAction(action(ctx, "Snooze", EventReceiver.SNOOZE, head.alarmId))
            b.addAction(action(ctx, "Dismiss", EventReceiver.DISMISS, head.alarmId))
        } else {
            val exp = db.timers.filter { it.state == TIMER_EXPIRED }
            b.setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(if (exp.size > 1) "${exp.size} timers done" else "Time's up")
                .setContentText(exp.joinToString { it.name.ifBlank { TimerLogic.autoName(it.inputMs) } })
            exp.minByOrNull { it.endWall }?.let { b.setWhen(it.endWall).setShowWhen(true).setUsesChronometer(true) }
            if (exp.size > 1) b.addAction(action(ctx, "Dismiss all", EventReceiver.T_DISMISS_ALL))
            else {
                b.addAction(action(ctx, "Dismiss", EventReceiver.T_DISMISS_ALL))
                b.addAction(action(ctx, "Restart", EventReceiver.T_RESTART_LAST))
            }
        }
        return b.build()
    }

}
