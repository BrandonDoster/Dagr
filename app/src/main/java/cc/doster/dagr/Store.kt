package cc.doster.dagr

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** JSON format of db.json. Every new field in Model.kt needs a default, or old files stop decoding (DbFormatTest). */
val DbJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * App state in memory, saved atomically as JSON in device-protected storage (readable before first unlock).
 * One JSON file instead of a database: at most ~150 small rows. Move to a DB only if the data grows.
 */
object Store {
    private const val TAG = "Store"
    private lateinit var file: AtomicFile
    private lateinit var backup: File
    private val state = MutableStateFlow(Db())
    val db: StateFlow<Db> get() = state
    /** Device-protected context: use for every file the app writes. */
    lateinit var de: Context
        private set
    /** Set when db.json and its backup were both unreadable and the app started empty. */
    var resetAfterFailure = false
        private set
    /** db.json was unreadable and the last good backup was loaded instead. */
    var restoredFromBackup = false
        private set
    private var writeFailedNotified = false

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        de = ctx.applicationContext.createDeviceProtectedStorageContext()
        file = AtomicFile(File(de.filesDir, "db.json"))
        backup = File(de.filesDir, "db.bak.json")
        state.value = load().sanitized()
    }

    private fun decode(bytes: ByteArray) = DbJson.decodeFromString<Db>(bytes.decodeToString())

    private fun load(): Db {
        if (!file.baseFile.exists() && !backup.exists()) return Db()
        try {
            val bytes = file.readFully()
            val db = decode(bytes)
            runCatching { backup.writeBytes(bytes) } // last known-good copy
            return db
        } catch (e: Exception) {
            Log.e(TAG, "db.json unreadable", e)
            // never wipe silently. Keep the unreadable file, try the backup.
            if (file.baseFile.exists()) file.baseFile.renameTo(File(de.filesDir, "db.corrupt-${System.currentTimeMillis()}.json"))
            pruneCorrupt()
            runCatching { return decode(backup.readBytes()).also { restoredFromBackup = true } }.onFailure { Log.e(TAG, "backup unreadable", it) }
            resetAfterFailure = true
            return Db()
        }
    }

    private fun pruneCorrupt() {
        de.filesDir.listFiles { f -> f.name.startsWith("db.corrupt-") }
            ?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
    }

    /**
     * Never throws. A failed write (full storage) keeps the new state in memory: ringing from stale-on-disk
     * state beats not ringing.
     */
    @Synchronized
    fun update(f: (Db) -> Db): Db {
        val old = state.value
        val new = f(old)
        if (new == old) return new
        val out = try { file.startWrite() } catch (e: Exception) { writeFailed(e); null }
        if (out != null) {
            try {
                out.write(DbJson.encodeToString(Db.serializer(), new).encodeToByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                writeFailed(e)
            }
        }
        state.value = new
        return new
    }

    private fun writeFailed(e: Exception) {
        Log.e(TAG, "db write failed", e)
        if (!writeFailedNotified) { writeFailedNotified = true; runCatching { Notifs.storageProblem(de) } }
    }

    fun clock(ctx: Context) = Clock(
        SystemClock.elapsedRealtime(),
        System.currentTimeMillis(),
        SysSettings.Global.getInt(ctx.contentResolver, SysSettings.Global.BOOT_COUNT, 0),
    )

    fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Clamp every value the ring path indexes or feeds to java.time, so one bad row can't crash a ring. */
fun Db.sanitized() = copy(
    alarms = alarms.map { a ->
        a.copy(
            hour = a.hour.coerceIn(0, 23),
            minute = a.minute.coerceIn(0, 59),
            days = a.days and 0x7F,
            dateEpochDay = a.dateEpochDay?.takeIf { it in 0..200_000 }, // LocalDate.ofEpochDay must not throw
            volume = a.volume.coerceIn(0, 15),
            snoozeMin = a.snoozeMin.coerceIn(1, 60),
            snoozeRepeat = if (a.snoozeRepeat == SNOOZE_FOREVER) a.snoozeRepeat else a.snoozeRepeat.coerceIn(1, 10),
            vibPattern = a.vibPattern.coerceIn(0, RingService.VIB_PATTERNS.size - 1),
            name = a.name.take(40),
        )
    },
    timers = timers.map { it.copy(name = it.name.take(40), inputMs = it.inputMs.coerceIn(1_000, TimerLogic.MAX_MS)) },
    settings = settings.copy(timerVolume = settings.timerVolume.coerceIn(0, 15), upcomingLead = settings.upcomingLead.coerceIn(0, 3)),
)
