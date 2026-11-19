package cc.doster.dagr

import kotlinx.serialization.Serializable

/** Whole app state. Small (≤100 alarms, ≤20 timers), so it lives in memory and is saved as one JSON file. */
@Serializable
data class Db(
    val version: Int = 1,
    val nextId: Long = 1,
    val alarms: List<Alarm> = emptyList(),
    val groups: List<Group> = emptyList(),
    val timers: List<Timer> = emptyList(),
    val presets: List<Preset> = DEFAULT_PRESETS,
    val settings: Settings = Settings(),
    /** Alarms ringing or waiting to ring. Head = the one on screen. Persisted so a crash mid-ring is detectable. */
    val ringQueue: List<RingEntry> = emptyList(),
    val seeded: Boolean = false,
) {
    fun alarm(id: Long) = alarms.firstOrNull { it.id == id }
    fun timer(id: Long) = timers.firstOrNull { it.id == id }
    fun group(id: Long) = groups.firstOrNull { it.id == id }
}

const val ACTIVE_OFF = 0
const val ACTIVE_ON = 1
const val ACTIVE_SNOOZED = 2
const val SNOOZE_FOREVER = -1
const val MAX_ALARMS = 100
const val MAX_GROUPS = 6
const val MAX_TIMERS = 20
const val MAX_PRESETS = 20

@Serializable
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    /** Bit (DayOfWeek.value - 1): Mon = bit 0 … Sun = bit 6. 0 = one-time. */
    val days: Int = 0,
    /** Specific date (epoch day) for a one-time alarm, or null. */
    val dateEpochDay: Long? = null,
    val name: String = "",
    val active: Int = ACTIVE_OFF,
    /** Epoch ms of the next fire (normal or snooze). -1 = none. Kept for display while off. */
    val alertAt: Long = -1,
    val snoozeCount: Int = 0,
    /** "Turn back on for …": off, auto-returns at skippedAt + 2 s. */
    val skipped: Boolean = false,
    val skippedAt: Long = -1,
    val soundOn: Boolean = true,
    /** null = system default alarm tone; "file:<path>" = copied custom file; else a content URI. */
    val sound: String? = null,
    val soundTitle: String = "",
    /** 0..15, scaled to the device alarm stream at ring time. */
    val volume: Int = 11,
    val increasing: Boolean = false,
    val vibrate: Boolean = true,
    val vibPattern: Int = 0,
    val snoozeOn: Boolean = true,
    val snoozeMin: Int = 5,
    /** 1,2,3,5 or SNOOZE_FOREVER. */
    val snoozeRepeat: Int = 3,
    val tts: Boolean = false,
    val groupId: Long = 0,
    val createdAt: Long = 0,
) {
    val repeating get() = days != 0
}

@Serializable
data class Group(val id: Long, val name: String, val order: Int)

const val TIMER_RUNNING = 1
const val TIMER_PAUSED = 2
const val TIMER_EXPIRED = 3

@Serializable
data class Timer(
    val id: Long,
    val name: String,
    val inputMs: Long,
    val state: Int,
    /** Paused: remaining ms. */
    val remainMs: Long = 0,
    /** Running: end instant. Expired: the moment it expired. Both clocks + boot count. */
    val endElapsed: Long = 0,
    val endWall: Long = 0,
    val boot: Int = 0,
    val createdAt: Long = 0,
)

@Serializable
data class Preset(val id: Long, val name: String, val inputMs: Long)

val DEFAULT_PRESETS = listOf(Preset(-1, "", 600_000), Preset(-2, "", 900_000), Preset(-3, "", 1_800_000))

@Serializable
data class RingEntry(
    val alarmId: Long,
    val canSnooze: Boolean,
    val snoozeRing: Boolean,
    val firedAt: Long,
    /** When this entry became the head and started sounding (alarms due at the same time queue up); 0 = still waiting. */
    val startedAt: Long = 0,
)

@Serializable
data class Settings(
    /** 0 = 5 min, 1 = 15 min, 2 = 30 min (default), 3 = off. */
    val upcomingLead: Int = 2,
    val timerSoundOn: Boolean = true,
    val timerSound: String? = null,
    val timerSoundTitle: String = "",
    val timerVolume: Int = 11,
    val timerVibrate: Boolean = false,
    /** true = newest first. Default: oldest first. */
    val timerNewestFirst: Boolean = false,
    val timerLastInputMs: Long = 0,
    /** STREAM_ALARM index saved at ring start; -1 = nothing to restore. */
    val savedAlarmStream: Int = -1,
    /** elapsedRealtime when the current timer alert started sounding; -1 = none. */
    val timerAlertStart: Long = -1,
)
