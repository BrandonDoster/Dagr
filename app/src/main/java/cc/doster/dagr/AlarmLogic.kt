package cc.doster.dagr

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Pure alarm rules. No Android types, so they are unit-tested. */
object AlarmLogic {
    const val LATE_LIMIT_MS = 15 * 60_000L
    const val EARLY_LIMIT_MS = 2_000L
    val SNOOZE_INTERVALS = listOf(3, 5, 10, 15, 30)
    val SNOOZE_REPEATS = listOf(1, 2, 3, 5, SNOOZE_FOREVER)
    /** Start gain of the volume ramp, indexed by the stored 0..15 volume. */
    val START_GAIN = floatArrayOf(1f, 1f, 1f, 1f, .7f, .6f, .4f, .3f, .25f, .2f, .1f, .05f, .04f, .04f, .04f, .04f)

    fun dayBit(d: DayOfWeek) = 1 shl (d.value - 1)

    /**
     * Next occurrence strictly after [after]. Walks calendar dates (never adds 24 h), so a DST gap
     * pushes the time forward and a DST overlap fires once, at the earlier offset.
     */
    fun nextFire(a: Alarm, after: Long, zone: ZoneId): Long {
        val t = LocalTime.of(a.hour, a.minute)
        a.dateEpochDay?.let { ed ->
            val ms = ZonedDateTime.of(LocalDate.ofEpochDay(ed), t, zone).toInstant().toEpochMilli()
            if (ms > after) return ms
            // past specific date: behaves as a plain one-time alarm
        }
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (d in 0L..8L) {
            val date = start.plusDays(d)
            if (a.repeating && a.days and dayBit(date.dayOfWeek) == 0) continue
            val ms = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
            if (ms > after) return ms
        }
        error("no occurrence")
    }

    private fun dateAhead(a: Alarm, now: Long, zone: ZoneId) = a.dateEpochDay != null &&
        ZonedDateTime.of(LocalDate.ofEpochDay(a.dateEpochDay), LocalTime.of(a.hour, a.minute), zone).toInstant().toEpochMilli() > now

    fun turnOn(a: Alarm, now: Long, zone: ZoneId): Alarm {
        val b = a.copy(dateEpochDay = a.dateEpochDay?.takeIf { dateAhead(a, now, zone) })
        return b.copy(active = ACTIVE_ON, snoozeCount = 0, skipped = false, skippedAt = -1, alertAt = nextFire(b, now, zone))
    }

    /** Any explicit off clears the skip. Repeating keeps its next time for display. */
    fun turnOff(a: Alarm, now: Long, zone: ZoneId) = a.copy(
        active = ACTIVE_OFF, snoozeCount = 0, skipped = false, skippedAt = -1,
        alertAt = if (a.repeating) nextFire(a, now, zone) else -1,
    )

    /**
     * Turn off once: repeating = skip the next occurrence and come back after it;
     * one-time = off. Used by the list button, "Turn off for today" and group actions.
     */
    fun skipNext(a: Alarm, now: Long, zone: ZoneId): Alarm {
        if (!a.repeating) return turnOff(a, now, zone)
        val occ = if (a.active == ACTIVE_ON && a.alertAt > now) a.alertAt else nextFire(a, now, zone)
        return a.copy(active = ACTIVE_OFF, skipped = true, skippedAt = occ, snoozeCount = 0, alertAt = nextFire(a, occ, zone))
    }

    /** Skipped alarms whose skipped occurrence has passed (within 2 s) turn back on. */
    fun restoreSkipped(a: Alarm, now: Long, zone: ZoneId) =
        if (a.skipped && a.skippedAt <= now + 2_000) turnOn(a, now, zone) else a

    fun snoozeLeft(a: Alarm) = a.snoozeOn && (a.snoozeRepeat == SNOOZE_FOREVER || a.snoozeCount < a.snoozeRepeat)

    /** End this occurrence with no snooze: repeating = next normal occurrence, one-time = off. */
    fun finish(a: Alarm, now: Long, zone: ZoneId) =
        if (a.repeating) a.copy(active = ACTIVE_ON, snoozeCount = 0, alertAt = nextFire(a, now, zone), dateEpochDay = null)
        else a.copy(active = ACTIVE_OFF, snoozeCount = 0, alertAt = -1, dateEpochDay = null)

    /**
     * At fire time the state advances first, so a crash mid-ring still leaves a snooze ring or
     * the next occurrence scheduled. Returns the advanced alarm and its ring entry.
     */
    fun fire(a: Alarm, now: Long, zone: ZoneId): Pair<Alarm, RingEntry> {
        val snoozeRing = a.active == ACTIVE_SNOOZED
        return if (snoozeLeft(a)) {
            a.copy(active = ACTIVE_SNOOZED, snoozeCount = a.snoozeCount + 1, alertAt = now + a.snoozeMin * 60_000L) to
                RingEntry(a.id, canSnooze = true, snoozeRing = snoozeRing, firedAt = now)
        } else {
            finish(a, now, zone) to RingEntry(a.id, canSnooze = false, snoozeRing = snoozeRing, firedAt = now)
        }
    }

    /** Snooze interval counts from the press / timeout moment. */
    fun snooze(a: Alarm, now: Long) = a.copy(active = ACTIVE_SNOOZED, alertAt = now + a.snoozeMin * 60_000L)

    /** Dismiss a ringing or snoozed alarm: cancel any pending snooze. */
    fun dismiss(a: Alarm, now: Long, zone: ZoneId) = if (a.active == ACTIVE_SNOOZED) finish(a, now, zone) else a

    /** ±5 snooze adjust from the ring screen, clamped 1..60, saved to the alarm. */
    fun adjustSnooze(a: Alarm, delta: Int) = a.copy(snoozeMin = (a.snoozeMin + delta).coerceIn(1, 60))

    /** The head of the ring queue starts sounding now if it hasn't yet. */
    fun stampHead(q: List<RingEntry>, now: Long) =
        if (q.isNotEmpty() && q[0].startedAt == 0L) listOf(q[0].copy(startedAt = now)) + q.drop(1) else q

    /** When the head's ring must end at the latest: its start + ring length. */
    fun ringEnd(a: Alarm?, e: RingEntry) = (if (e.startedAt > 0) e.startedAt else e.firedAt) + (a?.let { ringLengthMs(it, e) } ?: 60_000L)

    /** Ring length: first ring ≈ snooze interval capped at 5 min (5 min if snooze off); snooze rings 1 min. */
    fun ringLengthMs(a: Alarm, e: RingEntry) =
        if (e.snoozeRing) 60_000L else (if (a.snoozeOn) minOf(a.snoozeMin, 5) else 5) * 60_000L - 2_000L

    /** Volume ramp: increasing 15 s first ring / 7 s snooze ring, else 2 s; only above volume 3. */
    fun rampMs(a: Alarm, e: RingEntry) = when {
        a.volume <= 3 -> 0L
        a.increasing -> if (e.snoozeRing) 7_000L else 15_000L
        else -> 2_000L
    }

    /** Reboot / time / timezone change. Returns updated alarm and whether a ring was missed. */
    fun recompute(a: Alarm, now: Long, zone: ZoneId): Pair<Alarm, Boolean> {
        if (a.skipped) {
            val r = restoreSkipped(a, now, zone)
            return (if (r.skipped) r.copy(alertAt = nextFire(r, r.skippedAt, zone)) else r) to false
        }
        if (a.active == ACTIVE_OFF) return (if (a.repeating) a.copy(alertAt = nextFire(a, now, zone)) else a) to false
        if (a.active == ACTIVE_SNOOZED && a.alertAt > now) return a to false
        if (a.alertAt in 1 until now - LATE_LIMIT_MS) return finish(a, now, zone) to true
        if (a.alertAt in (now - LATE_LIMIT_MS)..now) return a to false // still inside the late window: rings now
        val b = a.copy(dateEpochDay = a.dateEpochDay?.takeIf { dateAhead(a, now, zone) })
        return b.copy(active = ACTIVE_ON, snoozeCount = 0, alertAt = nextFire(b, now, zone)) to false
    }

    // ---- text ----

    /** Header line above the alarm list. */
    fun header(db: Db, now: Long, zone: ZoneId): String {
        val next = db.alarms.filter { it.active != ACTIVE_OFF && it.alertAt > 0 }.minByOrNull { it.alertAt }
            ?: return "All alarms are off"
        if (next.active == ACTIVE_SNOOZED) return "Alarm snoozed"
        val mins = ((next.alertAt - now) + 59_999) / 60_000
        if (mins > 1440) {
            val days = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(next.alertAt).atZone(zone).toLocalDate(),
            )
            return "Alarm in $days days"
        }
        if (mins > 60) {
            val h = mins / 60
            val m = mins % 60
            val hs = plural(h, "hour")
            return if (m == 0L) "Alarm in $hs" else "Alarm in $hs ${plural(m, "minute")}"
        }
        if (mins >= 2) return "Alarm in $mins minutes"
        return "Alarm will go off soon"
    }

    /** Toast after turning on / saving. */
    fun setToast(alertAt: Long, now: Long, zone: ZoneId): String {
        val mins = ((alertAt - now) + 59_999) / 60_000
        if (mins >= 1440) {
            val z = Instant.ofEpochMilli(alertAt).atZone(zone)
            return "Alarm set for ${z.format(DateTimeFormatter.ofPattern("h:mm a"))} on ${z.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}."
        }
        val h = mins / 60
        val m = mins % 60
        return when {
            mins <= 1 -> "Alarm set for 1 minute from now."
            h == 0L -> "Alarm set for ${plural(m, "minute")} from now."
            m == 0L -> "Alarm set for ${plural(h, "hour")} from now."
            else -> "Alarm set for ${plural(h, "hour")} and ${plural(m, "minute")} from now."
        }
    }

    fun plural(n: Long, w: String) = if (n == 1L) "1 $w" else "$n ${w}s"

    /** "Turn back on for tomorrow / <weekday> / <date>": the day after the skipped occurrence. */
    fun skipButton(a: Alarm, now: Long, zone: ZoneId) =
        "Turn back on for " + dayWord(nextFire(a, nextFire(a, now, zone), zone), now, zone)

    fun dayWord(at: Long, now: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(today, d)
        return when (diff) {
            0L -> "today"
            1L -> "tomorrow"
            in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        }
    }
}
