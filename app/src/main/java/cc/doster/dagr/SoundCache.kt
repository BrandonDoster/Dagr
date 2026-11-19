package cc.doster.dagr

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.UserManager
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Copies of system/content sounds in device-protected storage, so the chosen tone plays before the first
 * unlock after a reboot and the ring never waits on a content provider.
 */
object SoundCache {
    private const val DEFAULT = "default"
    private val worker = Executors.newSingleThreadExecutor() // one writer at a time
    /** Default alarm URI the cached default copy was made from; re-copy only when it changes. */
    @Volatile private var cachedDefaultUri: Uri? = null

    private fun key(sound: String?) = sound ?: DEFAULT
    private fun dir() = File(Store.de.filesDir, "sound-cache").apply { mkdirs() }
    private fun fileFor(key: String) = File(dir(), "s" + key.hashCode().toUInt().toString(16))

    /** Cached copy for a sound (null = system default alarm), if one exists. */
    fun get(sound: String?): File? = fileFor(key(sound)).takeIf { it.length() > 0 }

    /**
     * While unlocked, in the background: cache every sound in use (the default is refreshed each time, it can
     * change) and delete cached and imported copies nothing uses any more.
     */
    fun refresh(ctx: Context) {
        if (!ctx.getSystemService(UserManager::class.java).isUserUnlocked) return
        val db = Store.db.value
        val inUse = db.alarms.map { it.sound } + db.settings.timerSound
        val uris = (inUse + null).filter { it == null || isSafeUri(it) }.toSet()
        val files = inUse.filterNotNull().filter { it.startsWith("file:") }.map { File(it.removePrefix("file:")).name }.toSet()
        worker.execute {
            for (s in uris) copy(ctx, s, overwrite = false)
            val keep = uris.map { fileFor(key(it)).name }.toSet()
            dir().listFiles()?.forEach { if (it.name !in keep) it.delete() }
            // Imported files: keep anything from the last hour so an editor still open doesn't lose its pick.
            val cutoff = System.currentTimeMillis() - 3_600_000
            File(Store.de.filesDir, "sounds").listFiles()?.forEach { if (it.name !in files && it.lastModified() < cutoff) it.delete() }
        }
    }

    private fun copy(ctx: Context, sound: String?, overwrite: Boolean) {
        val out = fileFor(key(sound))
        val uri = if (sound == null) RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM) ?: return else Uri.parse(sound)
        val stale = sound == null && uri != cachedDefaultUri // the user changed the system default
        if (out.length() > 0 && !overwrite && !stale) return
        val tmp = File(out.parentFile, out.name + ".tmp")
        runCatching {
            copyCapped(ctx.contentResolver.openInputStream(uri)!!, tmp)
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            if (sound == null) cachedDefaultUri = uri
        }.onFailure { tmp.delete(); Log.w("SoundCache", "could not cache $sound", it) }
    }
}
