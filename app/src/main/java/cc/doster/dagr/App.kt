package cc.doster.dagr

import android.app.Application
import android.media.AudioManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs in Direct Boot too: only device-protected storage is touched here.
        Store.init(this)
        Notifs.createChannels(this)
        if (Store.resetAfterFailure) Notifs.dataReset(this)
        if (Store.restoredFromBackup) { Notifs.dataRestored(this); Ops.recomputeAll(this) }
        // A fresh process never has a live ring service: anything still marked ringing was cut off,
        // and a saved alarm volume means the stream was never restored.
        Ops.recoverOrphans(this)
        SoundCache.refresh(this)
        val saved = Store.db.value.settings.savedAlarmStream
        if (saved >= 0) {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
            Ops.settings(this) { it.copy(savedAlarmStream = -1) }
        }
    }
}
