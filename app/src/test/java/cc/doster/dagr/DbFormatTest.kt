package cc.doster.dagr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a db.json written by an older version must still decode. If this fails after a model change, the change
 * would wipe users' alarms on update: give the new field a default instead of editing the fixture.
 */
class DbFormatTest {
    private val v1 = javaClass.getResource("/db_v1.json")!!.readText()

    @Test fun v1FixtureDecodes() {
        val db = DbJson.decodeFromString<Db>(v1)
        assertEquals(2, db.alarms.size)
        val gym = db.alarm(2)!!
        assertEquals("Gym", gym.name)
        assertTrue(gym.skipped)
        assertEquals(SNOOZE_FOREVER, gym.snoozeRepeat)
        assertEquals(3L, gym.groupId)
        assertEquals("Morning", db.group(3)!!.name)
        assertEquals(TIMER_PAUSED, db.timer(4)!!.state)
        assertEquals(1, db.ringQueue.size)
    }

    @Test fun roundTrip() {
        val db = DbJson.decodeFromString<Db>(v1)
        assertEquals(db, DbJson.decodeFromString<Db>(DbJson.encodeToString(Db.serializer(), db)))
    }

    @Test fun unknownFieldsIgnored() {
        val db = DbJson.decodeFromString<Db>(v1.replace("\"seeded\":true", "\"seeded\":true,\"futureField\":{\"x\":1}"))
        assertTrue(db.seeded)
    }

    @Test fun sanitizeClampsBadRows() {
        val bad = Alarm(1, 25, 99, days = 0xFF, volume = 99, snoozeMin = 0, vibPattern = 42)
        val s = Db(alarms = listOf(bad), settings = Settings(timerVolume = -3)).sanitized()
        val a = s.alarms[0]
        assertEquals(23, a.hour); assertEquals(59, a.minute); assertEquals(0x7F, a.days)
        assertEquals(15, a.volume); assertEquals(1, a.snoozeMin); assertTrue(a.vibPattern in RingService.VIB_PATTERNS.indices)
        assertEquals(0, s.settings.timerVolume)
    }
}
