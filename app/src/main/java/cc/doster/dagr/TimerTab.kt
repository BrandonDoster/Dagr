package cc.doster.dagr

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerTab(db: Db, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val timers = db.timers.sortedBy { it.createdAt }.let { if (db.settings.timerNewestFirst) it.reversed() else it }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = {}, windowInsets = WindowInsets(0), actions = {
                if (timers.isNotEmpty()) IconButton(onClick = {
                    if (timers.size >= MAX_TIMERS) Toast.makeText(ctx, "Can't add more than $MAX_TIMERS timers", Toast.LENGTH_SHORT).show()
                    else addDialog = true
                }) { Icon(Icons.Filled.Add, "Add timer") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (db.settings.timerNewestFirst) "✓ Newest first" else "Newest first") },
                        onClick = { menu = false; Ops.settings(ctx) { it.copy(timerNewestFirst = true) } })
                    DropdownMenuItem({ Text(if (!db.settings.timerNewestFirst) "✓ Oldest first" else "Oldest first") },
                        onClick = { menu = false; Ops.settings(ctx) { it.copy(timerNewestFirst = false) } })
                    DropdownMenuItem({ Text("Settings") }, onClick = { menu = false; onSettings() })
                }
            })
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (timers.isEmpty()) TimerSetup(db, Modifier.fillMaxSize()) { }
            else TimerPager(timers, db.settings.timerNewestFirst)
        }
    }
    if (addDialog) {
        Dialog(onDismissRequest = { addDialog = false }) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Add timer", style = MaterialTheme.typography.titleLarge)
                    TimerSetup(db, Modifier) { addDialog = false }
                }
            }
        }
    }
}

@Composable
private fun Dialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(16.dp)) { content() }
    }

/** Picker + presets + Start. Used for the empty state and the "Add timer" dialog. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerSetup(db: Db, modifier: Modifier, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val last = db.settings.timerLastInputMs
    var h by remember { mutableIntStateOf((last / 3_600_000).toInt()) }
    var m by remember { mutableIntStateOf((last / 60_000 % 60).toInt()) }
    var s by remember { mutableIntStateOf((last / 1000 % 60).toInt()) }
    var selectedPreset by remember { mutableStateOf<Long?>(null) }
    var before by remember { mutableStateOf(Triple(0, 0, 0)) }
    var selectMode by remember { mutableStateOf<Set<Long>?>(null) }
    var presetDialog by remember { mutableStateOf<Preset?>(null) } // id 0 = add
    val ms = (h * 3600L + m * 60L + s) * 1000L

    fun setPicker(v: Long) { h = (v / 3_600_000).toInt(); m = (v / 60_000 % 60).toInt(); s = (v / 1000 % 60).toInt() }

    Column(modifier.verticalScrollIfNeeded(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        HmsPicker(h, m, s) { a, b, c -> h = a; m = b; s = c; if (selectedPreset != null && (a * 3600L + b * 60L + c) * 1000 != db.presets.firstOrNull { it.id == selectedPreset }?.inputMs) selectedPreset = null }
        Spacer(Modifier.height(16.dp))

        // presets
        val sel = selectMode
        if (sel != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (sel.isEmpty()) "Select presets" else "${sel.size} selected", Modifier.weight(1f))
                TextButton(onClick = { selectMode = null }) { Text("Done") }
            }
        } else {
            Text("Presets", Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(db.presets, key = { it.id }) { p ->
                val picked = if (sel != null) p.id in sel else p.id == selectedPreset
                Card(
                    Modifier.combinedClickable(
                        onClick = {
                            when {
                                sel != null -> selectMode = if (p.id in sel) sel - p.id else sel + p.id
                                p.id == selectedPreset -> { selectedPreset = null; h = before.first; m = before.second; s = before.third }
                                else -> { if (selectedPreset == null) before = Triple(h, m, s); selectedPreset = p.id; setPicker(p.inputMs) }
                            }
                        },
                        onLongClick = { if (sel == null) selectMode = setOf(p.id) },
                    ),
                    colors = if (picked) CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (p.name.isNotBlank()) Text(p.name, style = MaterialTheme.typography.labelMedium)
                        Text(TimerLogic.hms(p.inputMs), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (sel == null) item {
                OutlinedCard(onClick = {
                    if (db.presets.size >= MAX_PRESETS) Toast.makeText(ctx, "Can't add more than $MAX_PRESETS preset timers", Toast.LENGTH_SHORT).show()
                    else presetDialog = Preset(0, "", ms)
                }) { Box(Modifier.padding(16.dp)) { Icon(Icons.Filled.Add, "Add preset timer") } }
            }
        }
        if (sel != null && sel.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sel.size == 1) {
                    val id = sel.first()
                    TextButton(onClick = { presetDialog = db.presets.first { it.id == id } }) { Text("Edit") }
                    TextButton(onClick = { Ops.movePreset(ctx, id, -1) }) { Text("Move left") }
                    TextButton(onClick = { Ops.movePreset(ctx, id, 1) }) { Text("Move right") }
                }
                TextButton(onClick = {
                    Ops.deletePresets(ctx, sel)
                    if (selectedPreset in sel) selectedPreset = null
                    selectMode = null
                }) { Text(if (sel.size == db.presets.size) "Delete all" else "Delete") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val name = db.presets.firstOrNull { it.id == selectedPreset }?.name.orEmpty()
                Ops.startTimer(ctx, ms, name)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                onStarted()
            },
            enabled = ms > 0 && selectMode == null,
            modifier = Modifier.width(200.dp).height(56.dp),
        ) { Text("Start") }
        Spacer(Modifier.height(16.dp))
    }

    presetDialog?.let { p ->
        PresetDialog(p, onClose = { presetDialog = null }) { name, v ->
            val msg = if (p.id == 0L) Ops.addPreset(ctx, name, v) else Ops.editPreset(ctx, p.id, name, v)
            if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            if (p.id == 0L || msg == null) {
                presetDialog = null
                if (p.id == 0L) {
                    val added = Store.db.value.presets.firstOrNull { it.name == name.trim() && it.inputMs == v }
                    if (added != null) { if (selectedPreset == null) before = Triple(h, m, s); selectedPreset = added.id; setPicker(v) }
                } else if (p.id == selectedPreset) setPicker(v)
                selectMode = null
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollIfNeeded() = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun PresetDialog(p: Preset, onClose: () -> Unit, onSave: (String, Long) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var h by remember { mutableIntStateOf((p.inputMs / 3_600_000).toInt()) }
    var m by remember { mutableIntStateOf((p.inputMs / 60_000 % 60).toInt()) }
    var s by remember { mutableIntStateOf((p.inputMs / 1000 % 60).toInt()) }
    val ms = (h * 3600L + m * 60L + s) * 1000L
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (p.id == 0L) "Add preset timer" else "Edit preset timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Preset timer name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(h, 99, "Hours") { h = it }
                    NumField(m, 59, "Minutes") { m = it }
                    NumField(s, 59, "Seconds") { s = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, ms) }, enabled = ms > 0) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun TimerPager(timers: List<Timer>, newestFirst: Boolean) {
    val ctx = LocalContext.current
    val pager = rememberPagerState { timers.size }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(100); elapsed = SystemClock.elapsedRealtime() } }
    // jump to the newest after adding
    LaunchedEffect(timers.size) { pager.scrollToPage(if (newestFirst) 0 else timers.size - 1) }
    var rename by remember { mutableStateOf<Timer?>(null) }
    val c = Store.clock(ctx).copy(elapsed = elapsed, wall = System.currentTimeMillis())

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val t = timers[i]
            val left = TimerLogic.remaining(t, c)
            val expired = t.state == TIMER_EXPIRED
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { rename = t }) {
                    Text(t.name.ifBlank { TimerLogic.autoName(t.inputMs) }, style = MaterialTheme.typography.titleLarge)
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (expired) 0f else (left.toFloat() / t.inputMs).coerceIn(0f, 1f) },
                        modifier = Modifier.size(280.dp), strokeWidth = 8.dp, gapSize = 0.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (expired) "−" + TimerLogic.hms(TimerLogic.overtime(t, c)) else TimerLogic.hms(left.coerceAtLeast(0) + 999),
                            fontSize = 48.sp, fontWeight = FontWeight.Light,
                        )
                        Text(
                            when {
                                expired -> "Time's up"
                                t.state == TIMER_PAUSED -> "Paused"
                                else -> "🔔 " + Notifs.time(ctx, System.currentTimeMillis() + left)
                            },
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { if (expired) Ops.dismissExpiredTimers(ctx) else Ops.cancelTimer(ctx, t.id) }, Modifier.width(140.dp).height(56.dp)) {
                        Text(if (expired) "Dismiss" else "Cancel")
                    }
                    Button(
                        onClick = {
                            when (t.state) {
                                TIMER_RUNNING -> Ops.pauseTimer(ctx, t.id)
                                TIMER_PAUSED -> Ops.resumeTimer(ctx, t.id)
                                else -> Ops.restartTimer(ctx, t.id)
                            }
                        },
                        modifier = Modifier.width(140.dp).height(56.dp),
                    ) { Text(when (t.state) { TIMER_RUNNING -> "Pause"; TIMER_PAUSED -> "Resume"; else -> "Restart" }) }
                }
            }
        }
        if (timers.size > 1) Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(timers.size) { i ->
                Surface(Modifier.size(8.dp), shape = RoundedCornerShape(4.dp),
                    color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
    }

    rename?.let { t ->
        var name by remember { mutableStateOf(t.name) }
        var asPreset by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename timer") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.replace('\n', ' ').take(40) }, singleLine = true, label = { Text("Name") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(asPreset, { asPreset = it })
                        Text("Add preset timer")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Ops.renameTimer(ctx, t.id, name.trim())
                    if (asPreset) Ops.addPreset(ctx, name, t.inputMs)?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    rename = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
}
