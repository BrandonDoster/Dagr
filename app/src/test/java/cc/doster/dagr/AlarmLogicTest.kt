package cc.doster.dagr

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmLogicTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().toString()
    private val everyDay = 0b1111111
    private val weekdays = 0b0011111

    @Test fun oneTimeTodayOrTomorrow() {
        val a = Alarm(1, 7, 0)
        assertEquals("2026-06-10T07:00", local(AlarmLogic.nextFire(a, at("2026-06-10T06:00"), zone)))
        assertEquals("2026-06-11T07:00", local(AlarmLogic.nextFire(a, at("2026-06-10T07:00"), zone)))
    }

    @Test fun weekdaysSkipWeekend() {
        val a = Alarm(1, 6, 0, days = weekdays)
        // 2026-06-12 is a Friday
        assertEquals("2026-06-15T06:00", local(AlarmLogic.nextFire(a, at("2026-06-12T06:30"), zone)))
    }

    @Test fun springForwardGapShiftsForward() {
        // 2026-03-08 02:00 -> 03:00 in Chicago
        val a = Alarm(1, 2, 30, days = everyDay)
        val f = AlarmLogic.nextFire(a, at("2026-03-08T00:00"), zone)
        assertEquals("2026-03-08T03:30", local(f))
        assertEquals("2026-03-09T02:30", local(AlarmLogic.nextFire(a, f, zone)))
    }

    @Test fun fallBackOverlapFiresOnce() {
        // 2026-11-01 01:00-01:59 happens twice in Chicago
        val a = Alarm(1, 1, 30, days = everyDay)
        val first = AlarmLogic.nextFire(a, at("2026-11-01T00:00"), zone)
        val second = AlarmLogic.nextFire(a, first, zone)
        assertEquals(LocalDate.of(2026, 11, 2), java.time.Instant.ofEpochMilli(second).atZone(zone).toLocalDate())
    }

    @Test fun specificDate() {
        val a = Alarm(1, 9, 0, dateEpochDay = LocalDate.of(2026, 7, 4).toEpochDay())
        assertEquals("2026-07-04T09:00", local(AlarmLogic.nextFire(a, at("2026-06-10T06:00"), zone)))
        val on = AlarmLogic.turnOn(a, at("2026-07-05T06:00"), zone)
        assertEquals(null, on.dateEpochDay) // past date -> plain one-time
        assertEquals("2026-07-05T09:00", local(on.alertAt))
    }

    @Test fun skipNextThenRestore() {
        val now = at("2026-06-10T22:00") // Wednesday night
        val a = AlarmLogic.turnOn(Alarm(1, 6, 0, days = weekdays), now, zone)
        val s = AlarmLogic.skipNext(a, now, zone)
        assertTrue(s.skipped)
        assertEquals(ACTIVE_OFF, s.active)
        assertEquals("2026-06-11T06:00", local(s.skippedAt))
        assertEquals("2026-06-12T06:00", local(s.alertAt))
        assertEquals("Turn back on for Friday", AlarmLogic.skipButton(a, now, zone))
        // morning of: skipping today's 06:00 comes back tomorrow
        assertEquals("Turn back on for tomorrow", AlarmLogic.skipButton(a, at("2026-06-11T05:00"), zone))
        // before skipped time: stays skipped
        assertTrue(AlarmLogic.restoreSkipped(s, at("2026-06-11T05:59"), zone).skipped)
        val r = AlarmLogic.restoreSkipped(s, s.skippedAt + 2_000, zone)
        assertFalse(r.skipped)
        assertEquals(ACTIVE_ON, r.active)
        assertEquals("2026-06-12T06:00", local(r.alertAt))
    }

    @Test fun explicitOffClearsSkip() {
        val now = at("2026-06-10T22:00")
        val s = AlarmLogic.skipNext(AlarmLogic.turnOn(Alarm(1, 6, 0, days = weekdays), now, zone), now, zone)
        val off = AlarmLogic.turnOff(s, now, zone)
        assertFalse(off.skipped)
        assertEquals(off, AlarmLogic.restoreSkipped(off, at("2026-06-11T07:00"), zone))
    }

    @Test fun oneTimeSkipIsOff() {
        val now = at("2026-06-10T22:00")
        val s = AlarmLogic.skipNext(AlarmLogic.turnOn(Alarm(1, 6, 0), now, zone), now, zone)
        assertFalse(s.skipped)
        assertEquals(ACTIVE_OFF, s.active)
    }

    @Test fun snoozeCycleExhausts() {
        var a = AlarmLogic.turnOn(Alarm(1, 6, 0, snoozeMin = 5, snoozeRepeat = 2), at("2026-06-10T05:00"), zone)
        var t = a.alertAt
        val e1 = AlarmLogic.fire(a, t, zone).also { a = it.first }.second
        assertTrue(e1.canSnooze); assertEquals(ACTIVE_SNOOZED, a.active)
        t += 60_000; a = AlarmLogic.snooze(a, t) // from the press
        assertEquals(t + 300_000, a.alertAt)
        t = a.alertAt
        val e2 = AlarmLogic.fire(a, t, zone).also { a = it.first }.second
        assertTrue(e2.canSnooze); assertTrue(e2.snoozeRing)
        t = a.alertAt
        val e3 = AlarmLogic.fire(a, t, zone).also { a = it.first }.second
        assertFalse(e3.canSnooze) // limit reached: last ring has no snooze
        assertEquals(ACTIVE_OFF, a.active) // one-time -> off
    }

    @Test fun dismissCancelsSnoozeRepeating() {
        val now = at("2026-06-10T06:00")
        val a = AlarmLogic.turnOn(Alarm(1, 6, 0, days = everyDay), now - 1, zone)
        val (fired, _) = AlarmLogic.fire(a, now, zone)
        val d = AlarmLogic.dismiss(fired, now + 10_000, zone)
        assertEquals(ACTIVE_ON, d.active)
        assertEquals("2026-06-11T06:00", local(d.alertAt))
        assertEquals(0, d.snoozeCount)
    }

    @Test fun recomputeAfterBoot() {
        val a = AlarmLogic.turnOn(Alarm(1, 6, 0, days = everyDay), at("2026-06-10T05:00"), zone)
        // missed by an hour -> advance + missed
        val (r, missed) = AlarmLogic.recompute(a, at("2026-06-10T07:00"), zone)
        assertTrue(missed); assertEquals("2026-06-11T06:00", local(r.alertAt))
        // one-time missed -> off + missed
        val o = AlarmLogic.turnOn(Alarm(2, 6, 0), at("2026-06-10T05:00"), zone)
        val (ro, mo) = AlarmLogic.recompute(o, at("2026-06-10T07:00"), zone)
        assertTrue(mo); assertEquals(ACTIVE_OFF, ro.active)
        // 5 min late: kept, rings now
        val (rl, ml) = AlarmLogic.recompute(a, at("2026-06-10T06:05"), zone)
        assertFalse(ml); assertEquals(a.alertAt, rl.alertAt)
    }

    @Test fun headerAndToast() {
        val now = at("2026-06-10T22:00")
        val a = AlarmLogic.turnOn(Alarm(1, 6, 30), now, zone)
        assertEquals("Alarm in 8 hours 30 minutes", AlarmLogic.header(Db(alarms = listOf(a)), now, zone))
        assertEquals("All alarms are off", AlarmLogic.header(Db(), now, zone))
        assertEquals("Alarm set for 8 hours and 30 minutes from now.", AlarmLogic.setToast(a.alertAt, now, zone))
        assertEquals("Alarm will go off soon", AlarmLogic.header(Db(alarms = listOf(a)), a.alertAt - 30_000, zone))
    }

    @Test fun queuedAlarmGetsFullRingWhenPromoted() {
        val a = Alarm(1, 6, 0, snoozeMin = 5)
        val q = listOf(RingEntry(1, true, false, 1_000), RingEntry(2, true, false, 1_000))
        val s1 = AlarmLogic.stampHead(q, 1_000)
        assertEquals(1_000L, s1[0].startedAt); assertEquals(0L, s1[1].startedAt)
        val s2 = AlarmLogic.stampHead(s1.drop(1), 200_000) // first dismissed after 199 s
        assertEquals(200_000L, s2[0].startedAt)
        assertEquals(200_000L + 298_000L, AlarmLogic.ringEnd(a, s2[0]))
        assertEquals(s2, AlarmLogic.stampHead(s2, 999_999)) // stamped once
    }

    @Test fun ringLengths() {
        val a = Alarm(1, 6, 0, snoozeMin = 10)
        assertEquals(298_000L, AlarmLogic.ringLengthMs(a, RingEntry(1, true, false, 0)))
        assertEquals(60_000L, AlarmLogic.ringLengthMs(a, RingEntry(1, true, true, 0)))
        assertEquals(15_000L, AlarmLogic.rampMs(a.copy(increasing = true), RingEntry(1, true, false, 0)))
    }
}
