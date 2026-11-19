package cc.doster.dagr

/** Pure timer clock math. elapsed = SystemClock.elapsedRealtime, wall = currentTimeMillis. */
data class Clock(val elapsed: Long, val wall: Long, val boot: Int)

object TimerLogic {
    const val MAX_MS = 359_999_990L

    fun start(id: Long, name: String, inputMs: Long, c: Clock) = Timer(
        id, name, inputMs, TIMER_RUNNING,
        endElapsed = c.elapsed + inputMs, endWall = c.wall + inputMs, boot = c.boot, createdAt = c.wall,
    )

    /** Running: ms left (may be ≤ 0). Paused: stored. Expired: minus the overtime. */
    fun remaining(t: Timer, c: Clock): Long = when (t.state) {
        TIMER_PAUSED -> t.remainMs
        else -> if (t.boot == c.boot) t.endElapsed - c.elapsed else t.endWall - c.wall
    }

    fun overtime(t: Timer, c: Clock) = (-remaining(t, c)).coerceIn(0, MAX_MS)

    fun pause(t: Timer, c: Clock) =
        if (t.state != TIMER_RUNNING) t else t.copy(state = TIMER_PAUSED, remainMs = remaining(t, c).coerceAtLeast(0))

    fun resume(t: Timer, c: Clock) = if (t.state != TIMER_PAUSED) t else
        t.copy(state = TIMER_RUNNING, endElapsed = c.elapsed + t.remainMs, endWall = c.wall + t.remainMs, boot = c.boot)

    fun restart(t: Timer, c: Clock) =
        t.copy(state = TIMER_RUNNING, endElapsed = c.elapsed + t.inputMs, endWall = c.wall + t.inputMs, boot = c.boot)

    fun expire(t: Timer, c: Clock) = if (t.state != TIMER_RUNNING) t else
        t.copy(state = TIMER_EXPIRED, endElapsed = c.elapsed, endWall = c.wall, boot = c.boot)

    /** After a reboot: re-anchor to the new boot via the wall clock. null = it ran out while the phone was off. */
    fun reanchor(t: Timer, c: Clock): Timer? {
        if (t.boot == c.boot) return t
        if (t.state == TIMER_PAUSED) return t.copy(boot = c.boot)
        val left = t.endWall - c.wall
        if (t.state == TIMER_RUNNING && left <= 0) return null
        return t.copy(endElapsed = c.elapsed + left, boot = c.boot)
    }

    fun autoName(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 60_000) % 60
        val h = ms / 3_600_000
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it h" },
            m.takeIf { it > 0 }?.let { "$it m" },
            s.takeIf { it > 0 }?.let { "$it s" },
        ).joinToString(" ").ifEmpty { "0 s" }
    }

    fun hms(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }
}
