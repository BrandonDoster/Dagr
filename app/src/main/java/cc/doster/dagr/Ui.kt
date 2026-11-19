package cc.doster.dagr

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings as SysSettings
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaPlayer
import android.widget.Toast
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlinx.coroutines.flow.filter

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

/** Scroll wheel. Compose has none built in; a snapping LazyColumn, looped by repeating the range. */
@Composable
fun Wheel(
    range: IntRange,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    itemHeight: Dp = 56.dp,
    label: (Int) -> String = { "%02d".format(it) },
) {
    val count = range.count()
    val total = if (loop) count * 1000 else count
    val base = if (loop) count * 500 else 0
    val state = rememberLazyListState(base + value - range.first)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val centered by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 0) 1 else 0 }
    }
    val latest by rememberUpdatedState(onValue)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            latest(range.first + state.firstVisibleItemIndex % count)
        }
    }
    LaunchedEffect(value) {
        val cur = range.first + state.firstVisibleItemIndex % count
        if (cur != value && !state.isScrollInProgress) state.scrollToItem((state.firstVisibleItemIndex + value - cur).coerceIn(0, total - 1))
    }
    LazyColumn(
        modifier.height(itemHeight * 3),
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = itemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(total) { i ->
            val sel = i == state.firstVisibleItemIndex && state.firstVisibleItemScrollOffset < 1 || (state.isScrollInProgress && i == centered)
            Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    label(range.first + i % count),
                    fontSize = if (sel) 40.sp else 28.sp,
                    modifier = Modifier.alpha(if (sel) 1f else 0.35f),
                )
            }
        }
    }
}

/** H:M:S picker: wheels, or typed entry. */
@Composable
fun HmsPicker(h: Int, m: Int, s: Int, onChange: (Int, Int, Int) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!typing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wheel(0..99, h, { onChange(it, m, s) }, Modifier.width(80.dp))
                Text(":", fontSize = 32.sp)
                Wheel(0..59, m, { onChange(h, it, s) }, Modifier.width(80.dp))
                Text(":", fontSize = 32.sp)
                Wheel(0..59, s, { onChange(h, m, it) }, Modifier.width(80.dp))
            }
            Row(Modifier.width(280.dp), horizontalArrangement = Arrangement.SpaceAround) {
                Text("Hours", style = MaterialTheme.typography.labelMedium)
                Text("Minutes", style = MaterialTheme.typography.labelMedium)
                Text("Seconds", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { typing = true }) { Text("Type a time") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(h, 99, "Hours") { onChange(it, m, s) }
                NumField(m, 59, "Minutes") { onChange(h, it, s) }
                NumField(s, 59, "Seconds") { onChange(h, m, it) }
            }
            TextButton(onClick = { typing = false }) { Text("Done") }
        }
    }
}

@Composable
fun NumField(v: Int, max: Int, label: String, onValue: (Int) -> Unit) {
    var text by remember(v) { mutableStateOf(if (v == 0) "" else v.toString()) }
    OutlinedTextField(
        text,
        { t -> val d = t.filter(Char::isDigit).take(2); text = d; onValue((d.toIntOrNull() ?: 0).coerceAtMost(max)) },
        Modifier.width(96.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ---------- sounds ----------

fun soundTitle(ctx: Context, sound: String?, title: String): String = when {
    sound == null -> "Default (" + (runCatching {
        RingtoneManager.getRingtone(ctx, RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM))?.getTitle(ctx)
    }.getOrNull() ?: "alarm") + ")"
    title.isNotBlank() -> title
    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(sound))?.getTitle(ctx) }.getOrNull() ?: "Sound"
}

fun ringtonePickerIntent(current: String?) = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.takeIf { !it.startsWith("file:") }?.let(Uri::parse) ?: SysSettings.System.DEFAULT_ALARM_ALERT_URI)

/** Result of the system ringtone picker: (sound, title). Default → null sound. */
fun pickedRingtone(ctx: Context, data: Intent?): Pair<String?, String>? {
    val uri: Uri = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) ?: return null
    if (uri == SysSettings.System.DEFAULT_ALARM_ALERT_URI) return null to ""
    if (!isSafeUri(uri.toString())) return null
    return uri.toString() to (runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull() ?: "")
}

const val MAX_SOUND_BYTES = 20L * 1024 * 1024

/**
 * Copy a picked file into device-protected storage so it plays before first unlock and survives the original
 * moving. Off the main thread, size-capped, and checked to be playable; a failed copy leaves nothing behind.
 */
suspend fun importSound(ctx: Context, uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "sound"
    val dir = File(Store.de.filesDir, "sounds").apply { mkdirs() }
    val out = File(dir, "${System.currentTimeMillis()}-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64))
    val ok = runCatching {
        copyCapped(ctx.contentResolver.openInputStream(uri)!!, out)
        MediaPlayer().apply { try { setDataSource(out.path); prepare() } finally { release() } }
    }.isSuccess
    if (!ok) { out.delete(); return@withContext null }
    "file:" + out.absolutePath to name.substringBeforeLast('.').take(40)
}

/** Copies at most MAX_SOUND_BYTES; throws (leaving [out] for the caller to delete) when the source is bigger. */
fun copyCapped(input: InputStream, out: File) {
    input.use { i ->
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SOUND_BYTES) throw IllegalArgumentException("sound file too large")
                o.write(buf, 0, n)
            }
        }
    }
}

fun soundRejected(ctx: Context) =
    Toast.makeText(ctx, "That file can't be used as an alarm sound (not audio, or over 20 MB).", Toast.LENGTH_LONG).show()

@Composable
fun ChoiceRow(options: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, (label, sel) -> FilterChip(sel, { onClick(i) }, { Text(label) }) }
    }
}

