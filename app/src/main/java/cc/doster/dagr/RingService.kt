package cc.doster.dagr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One owner of ring audio: the head of the alarm queue rings; expired timers sound only when no
 * alarm is ringing. Foreground only while something rings. Every step is guarded: a failure falls
 * back to a beep, never to silence; a watchdog alarm in Sync ends a ring this service can't.
 */
class RingService : Service() {
    companion object {
        private const val TAG = "Ring"
        private const val ACTION_REPOST = "repost"
        private const val CALL_BEEP_MS = 10_000L
        private const val COMM_MODE_GIVE_UP_MS = 60_000L
        private var handoff: PowerManager.WakeLock? = null
        const val TIMER_SOUND_MS = 5 * 60_000L
        val VIB_PATTERNS = listOf(
            "Basic" to longArrayOf(0, 800, 800),
            "Heartbeat" to longArrayOf(0, 150, 150, 150, 900),
            "Rapid" to longArrayOf(0, 200, 200),
            "Long" to longArrayOf(0, 1500, 500),
        )

        /** Called from the alarm broadcast (exempt from background FGS limits). Holds the CPU until the service runs. */
        fun start(ctx: Context) {
            if (handoff?.isHeld != true) {
                handoff = ctx.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarm:handoff").apply { acquire(30_000) }
            }
            try {
                ctx.startForegroundService(Intent(ctx, RingService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "could not start ring service", e)
                handoff?.let { if (it.isHeld) it.release() }
                handoff = null
                // at least show the full-screen notification; the watchdog alarm ends the ring.
                runCatching { Notifs.nm(ctx).notify(Notifs.ID_RING, Notifs.ringing(ctx, Store.db.value)) }
            }
        }

        fun repost(ctx: Context) = runCatching { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_REPOST)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var cpu: PowerManager.WakeLock
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null
    private var playingKey: String? = null
    private var collecting = false
    private var gain = 1f
    private var ducked = false
    private var inCall = false
    private var callSince = 0L
    private var vibPattern: LongArray? = null
    private var callBeeps: Runnable? = null
    private val modeListener = AudioManager.OnModeChangedListener { onMode(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        // a foreground service doesn't keep the CPU up; Handler timeouts must not stall in deep sleep.
        cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarm:ring")
            .apply { setReferenceCounted(false) }
        runCatching { audio.addOnModeChangedListener(mainExecutor, modeListener) }
        inCall = isCallMode(audio.mode)
        if (inCall) callSince = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(Notifs.ID_RING, Notifs.ringing(this, Store.db.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            runCatching { Notifs.nm(this).notify(Notifs.ID_RING, Notifs.ringing(this, Store.db.value)) }
        }
        handoff?.let { if (it.isHeld) it.release() }
        handoff = null
        if (!collecting) {
            collecting = true
            scope.launch { Store.db.collectLatest { evaluate(it) } }
        } else if (intent?.action == ACTION_REPOST) {
            runCatching { Notifs.nm(this).notify(Notifs.ID_RING, Notifs.ringing(this, Store.db.value)) }
        }
        return START_NOT_STICKY
    }

    private fun evaluate(db: Db) {
        val head = db.ringQueue.firstOrNull()
        val timers = db.timers.filter { it.state == TIMER_EXPIRED }
        val key = when {
            head != null -> "a:${head.alarmId}:${head.firedAt}"
            timers.isNotEmpty() -> "t"
            else -> null
        }
        if (key == null) { stopSelf(); return }
        runCatching { Notifs.nm(this).notify(Notifs.ID_RING, Notifs.ringing(this, db)) }
        if (key == playingKey) return
        stopPlayback()
        playingKey = key
        val hold = if (head != null) db.alarm(head.alarmId)?.let { AlarmLogic.ringLengthMs(it, head) } ?: 60_000L else TIMER_SOUND_MS
        cpu.acquire(hold + 60_000)
        try {
            if (head != null) ringAlarm(db, head) else ringTimers(db)
        } catch (t: Throwable) {
            Log.e(TAG, "ring failed, last-resort beep", t)
            stopPlayback(keepKey = true)
            lastResort()
            if (head != null) main.postDelayed({ Ops.timeoutRinging(this, head.alarmId) }, 60_000)
            else main.postDelayed({ stopPlayback(keepKey = true) }, TIMER_SOUND_MS) // timer beeps mute like the tone
        }
    }

    // ---------- alarm ----------

    private fun ringAlarm(db: Db, e: RingEntry) {
        val a = db.alarm(e.alarmId) ?: run { Ops.dismissRinging(this, e.alarmId); return }
        val left = AlarmLogic.ringEnd(a, e) - System.currentTimeMillis() // from when this entry started sounding
        main.postDelayed({ Ops.timeoutRinging(this, a.id) }, left.coerceAtLeast(1_000))
        val v = a.volume.coerceIn(0, 15)
        if (a.vibrate) vibrate(VIB_PATTERNS.getOrElse(a.vibPattern) { VIB_PATTERNS[0] }.second)
        if (a.soundOn && v > 0) play(a.sound, v, AlarmLogic.START_GAIN[v], AlarmLogic.rampMs(a, e))
        else if (!a.vibrate) lastResort() // nothing at all would ring: never silent
        if (a.tts) main.postDelayed({ speak(a) }, 5_000)
    }

    // ---------- timer ----------

    private fun ringTimers(db: Db) {
        val s = db.settings
        val now = SystemClock.elapsedRealtime()
        // A start time from a previous boot is meaningless.
        val start = if (s.timerAlertStart in 1..now) s.timerAlertStart else now.also { t -> Ops.settings(this) { it.copy(timerAlertStart = t) } }
        val left = TIMER_SOUND_MS - (now - start)
        if (left <= 0) return // muted after 5 min; alert stays until dismissed
        val v = s.timerVolume.coerceIn(0, 15)
        if (s.timerVibrate) vibrate(VIB_PATTERNS[0].second)
        if (s.timerSoundOn && v > 0) play(s.timerSound, v, AlarmLogic.START_GAIN[v], 2_000)
        main.postDelayed({ stopPlayback(keepKey = true) }, left)
    }

    // ---------- audio ----------

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private fun newPlayer() = MediaPlayer().apply {
        setAudioAttributes(attrs)
        isLooping = true
        setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
    }

    private fun play(sound: String?, volume: Int, startGain: Float, rampMs: Long) {
        setStream(volume)
        runCatching {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { audio.requestAudioFocus(it) }
        }
        val mp = runCatching { open(sound) }.getOrNull() ?: runCatching { openFallback() }.getOrNull()
        if (mp == null) { lastResort(); return }
        mp.setOnErrorListener { bad, _, _ ->
            // mid-ring decoder/provider error: switch to the bundled tone instead of going quiet (only if still current)
            if (player === bad) {
                Log.w(TAG, "player error, switching to fallback")
                player = runCatching {
                    openFallback().also { fb ->
                        fb.setOnErrorListener { f, _, _ -> if (player === f) { player = null; lastResort() }; runCatching { f.release() }; true }
                        if (!inCall) fb.start()
                    }
                }.getOrNull()
                setGain(gain)
                if (player == null) lastResort()
            }
            runCatching { bad.release() }
            true
        }
        player = mp
        if (inCall) { startCallBeeps(); return }
        mp.start()
        ramp(startGain, rampMs)
    }

    /** The device-protected copy first (no content provider on the ring path), then the URI, then the bundled tone. */
    private fun open(sound: String?): MediaPlayer {
        val mp = newPlayer()
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        val cached = if (sound?.startsWith("file:") == true) null else SoundCache.get(sound)
        try {
            when {
                sound != null && sound.startsWith("file:") -> mp.setDataSource(sound.removePrefix("file:"))
                cached != null -> mp.setDataSource(cached.path)
                !unlocked -> error("no cached copy before unlock")
                sound != null && isSafeUri(sound) -> mp.setDataSource(this, Uri.parse(sound))
                sound != null -> error("unsupported sound URI")
                else -> mp.setDataSource(this, RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) ?: error("no default"))
            }
            mp.prepare()
            return mp
        } catch (e: Exception) {
            mp.release()
            throw e
        }
    }

    private fun openFallback(): MediaPlayer {
        val mp = newPlayer()
        try {
            resources.openRawResourceFd(R.raw.fallback_tone).use { mp.setDataSource(it) }
            mp.prepare()
            return mp
        } catch (e: Exception) {
            mp.release()
            throw e
        }
    }

    /** Nothing playable: repeating ToneGenerator beep + vibration. Uses no stored data, so it can't fail on it. */
    private fun lastResort() {
        runCatching { vibrate(VIB_PATTERNS[0].second) }
        val beep = object : Runnable {
            override fun run() {
                if (!inCall) runCatching {
                    (tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tone = it }).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_500)
                }
                main.postDelayed(this, 2_000)
            }
        }
        main.post(beep)
    }

    private fun ramp(from: Float, ms: Long) {
        if (ms <= 0) { setGain(1f); return }
        val t0 = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val f = ((SystemClock.elapsedRealtime() - t0).toFloat() / ms).coerceIn(0f, 1f)
                // sine ease-in: 1 + sin(270° → 360°)
                val eased = 1f + sin(Math.toRadians(270.0 + 90.0 * f)).toFloat()
                setGain(from + (1f - from) * eased)
                if (f < 1f) main.postDelayed(this, 250)
            }
        }
        main.post(tick)
    }

    private fun setGain(g: Float) {
        gain = g
        val v = if (ducked) g * 0.1f else g
        runCatching { player?.setVolume(v, v) }
    }

    /** Save the user's alarm volume once, then set the stream to the stored 0..15 scaled to the device range. */
    private fun setStream(volume: Int) = runCatching {
        val st = AudioManager.STREAM_ALARM
        if (Store.db.value.settings.savedAlarmStream < 0) {
            val cur = audio.getStreamVolume(st)
            Ops.settings(this) { it.copy(savedAlarmStream = cur) }
        }
        val min = audio.getStreamMinVolume(st)
        val max = audio.getStreamMaxVolume(st)
        audio.setStreamVolume(st, ceil(volume / 15.0 * max).toInt().coerceIn(min, max), 0)
    }

    private fun restoreStream() {
        val saved = Store.db.value.settings.savedAlarmStream
        if (saved >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
            Ops.settings(this) { it.copy(savedAlarmStream = -1) }
        }
    }

    private fun vibrate(pattern: LongArray) = runCatching {
        vibPattern = pattern
        getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createWaveform(pattern, 0), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    private fun stopVibrate() = runCatching { getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }

    // ---------- in-call ----------

    private fun isCallMode(m: Int) = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE

    private fun onMode(mode: Int) {
        val call = isCallMode(mode)
        if (call == inCall) return
        inCall = call
        val p = player
        if (call) {
            callSince = SystemClock.elapsedRealtime()
            runCatching { p?.pause() }
            if (p != null) startCallBeeps()
        } else {
            main.postDelayed({ if (!inCall && player === p) runCatching { p?.start() } }, 500)
        }
    }

    /**
     * During a call: vibration continues, a beep every 10 s. MODE_IN_COMMUNICATION can be held by apps outside a
     * real call, so after 60 s of it the ringtone starts anyway.
     */
    private fun startCallBeeps() {
        callBeeps?.let { main.removeCallbacks(it) } // one chain at a time
        val p = player
        val r = object : Runnable {
            override fun run() {
                if (!inCall || player !== p) return
                if (audio.mode == AudioManager.MODE_IN_COMMUNICATION && SystemClock.elapsedRealtime() - callSince > COMM_MODE_GIVE_UP_MS) {
                    runCatching { p?.start() }
                    return
                }
                runCatching { (tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 60).also { tone = it }).startTone(ToneGenerator.TONE_PROP_BEEP2, 600) }
                main.postDelayed(this, CALL_BEEP_MS)
            }
        }
        callBeeps = r
        main.post(r)
    }

    // ---------- TTS ----------

    private fun speak(a: Alarm) {
        if (playingKey?.startsWith("a:${a.id}:") != true) return
        val text = listOf(a.name.trim(), "It's " + Notifs.time(this, System.currentTimeMillis())).filter { it.isNotEmpty() }.joinToString(". ")
        val undo = Runnable { ducked = false; setGain(gain) }
        main.postDelayed(undo, 10_000) // timeout: never leave the alarm ducked
        tts = runCatching {
            TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) { main.post(undo); return@TextToSpeech }
                ducked = true; setGain(gain)
                runCatching {
                    tts?.apply {
                        language = Locale.getDefault()
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { main.post(undo) }
                            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post(undo) }
                        })
                        speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm")
                    }
                }.onFailure { main.post(undo) }
            }
        }.getOrNull()
    }

    private fun stopPlayback(keepKey: Boolean = false) {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        runCatching { tts?.shutdown() }; tts = null
        runCatching { tone?.release() }; tone = null
        ducked = false
        stopVibrate()
        if (!keepKey) playingKey = null
    }

    override fun onDestroy() {
        stopPlayback()
        restoreStream()
        runCatching { audio.removeOnModeChangedListener(modeListener) }
        scope.cancel()
        if (cpu.isHeld) cpu.release()
        super.onDestroy()
    }
}

/** Only sounds the system ringtone picker hands out. */
fun isSafeUri(s: String) = s.startsWith("content://") || s.startsWith("android.resource://")
