package cc.doster.dagr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerLogicTest {
    @Test fun runPauseResumeExpire() {
        var c = Clock(elapsed = 1_000, wall = 1_000_000, boot = 5)
        var t = TimerLogic.start(1, "", 60_000, c)
        c = c.copy(elapsed = 11_000, wall = 1_010_000)
        assertEquals(50_000, TimerLogic.remaining(t, c))
        t = TimerLogic.pause(t, c)
        c = c.copy(elapsed = 100_000, wall = 9_000_000)
        assertEquals(50_000, TimerLogic.remaining(t, c))
        t = TimerLogic.resume(t, c)
        c = c.copy(elapsed = 150_000)
        t = TimerLogic.expire(t, c)
        c = c.copy(elapsed = 160_000)
        assertEquals(10_000, TimerLogic.overtime(t, c))
    }

    @Test fun wallClockChangeIgnoredSameBoot() {
        val c = Clock(1_000, 1_000_000, 5)
        val t = TimerLogic.start(1, "", 60_000, c)
        assertEquals(30_000, TimerLogic.remaining(t, Clock(31_000, 99_999_999, 5)))
    }

    @Test fun rebootReanchors() {
        val t = TimerLogic.start(1, "", 600_000, Clock(1_000, 1_000_000, 5))
        val c = Clock(elapsed = 20_000, wall = 1_200_000, boot = 6) // 200 s of wall time later
        val r = TimerLogic.reanchor(t, c)!!
        assertEquals(400_000, TimerLogic.remaining(r, c))
        assertNull(TimerLogic.reanchor(t, Clock(5, 2_000_000, 6))) // ran out while off
    }

    @Test fun names() {
        assertEquals("5 m", TimerLogic.autoName(300_000))
        assertEquals("1 h 2 m 3 s", TimerLogic.autoName(3_723_000))
        assertEquals("1 h 3 s", TimerLogic.autoName(3_603_000))
        assertEquals("01:02:03", TimerLogic.hms(3_723_000))
    }
}
