package cc.doster.dagr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** All of the app's own broadcasts: alarm fires and notification actions. Not exported. */
class EventReceiver : BroadcastReceiver() {
    companion object {
        const val FIRE = "fire"
        const val SKIP_RESTORE = "skip_restore"
        const val RING_WATCHDOG = "ring_watchdog"
        const val UPCOMING = "upcoming"
        const val TIMER = "timer"
        const val SNOOZE = "snooze"
        const val DISMISS = "dismiss"
        const val RING_SWIPED = "ring_swiped"
        const val SNZ_DISMISS = "snz_dismiss"
        const val UP_OFF = "up_off"
        const val UP_GROUP_NEXT = "up_group_next"
        const val UP_GROUP_OFF = "up_group_off"
        const val T_PAUSE = "t_pause"
        const val T_RESUME = "t_resume"
        const val T_CANCEL = "t_cancel"
        const val T_DISMISS_ALL = "t_dismiss_all"
        const val T_RESTART_LAST = "t_restart_last"
    }

    private inline fun ringCheck(f: () -> Boolean): Boolean = try { f() } catch (e: Exception) {
        android.util.Log.e("AlarmEvent", "ring bookkeeping failed", e)
        val db = Store.db.value
        db.ringQueue.isNotEmpty() || db.timers.any { it.state == TIMER_EXPIRED }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: 0
        android.util.Log.i("AlarmEvent", "${intent.action} id=$id")
        val now = System.currentTimeMillis()
        val zone = Store.zone()
        when (intent.action) {
            // whatever fails in the bookkeeping, still try to ring what is queued
            FIRE -> if (ringCheck { Ops.fireDue(ctx) }) RingService.start(ctx)
            TIMER -> if (ringCheck { Ops.expireDue(ctx) }) RingService.start(ctx)
            SKIP_RESTORE -> Ops.restoreSkipped(ctx)
            RING_WATCHDOG -> if (ringCheck { Ops.endOverdueRings(ctx) }) RingService.start(ctx)
            UPCOMING -> Sync.run(ctx)
            SNOOZE -> Ops.snoozeRinging(ctx, id)
            DISMISS -> Ops.dismissRinging(ctx, id)
            RING_SWIPED -> { // swiping the ring away: alarm snoozes (or re-posts), timers dismiss
                val head = Store.db.value.ringQueue.firstOrNull()
                when {
                    head == null -> Ops.dismissExpiredTimers(ctx)
                    head.canSnooze -> Ops.snoozeRinging(ctx, head.alarmId)
                    else -> RingService.repost(ctx)
                }
            }
            SNZ_DISMISS -> Ops.dismissSnoozed(ctx, id)
            UP_OFF -> Ops.skip(ctx, listOf(id))
            UP_GROUP_NEXT, UP_GROUP_OFF -> {
                val today = java.time.LocalDate.now()
                val members = Store.db.value.alarms.filter {
                    it.groupId == id && it.active == ACTIVE_ON && it.alertAt > now &&
                        java.time.Instant.ofEpochMilli(it.alertAt).atZone(zone).toLocalDate() == today
                }.sortedBy { it.alertAt }
                Ops.skip(ctx, (if (intent.action == UP_GROUP_NEXT) members.take(1) else members).map { it.id })
            }
            T_PAUSE -> Ops.pauseTimer(ctx, id)
            T_RESUME -> Ops.resumeTimer(ctx, id)
            T_CANCEL -> Ops.cancelTimer(ctx, id)
            T_DISMISS_ALL -> Ops.dismissExpiredTimers(ctx)
            T_RESTART_LAST -> Ops.restartLastExpired(ctx)
        }
    }
}

/** Reboot, clock and timezone changes, app update. Never starts ringing itself. */
class SystemReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
    )

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in actions) return // exported only for these protected broadcasts
        Ops.recomputeAll(ctx)
    }
}
