package cc.doster.dagr

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val WEEK = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
private val LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlarmTab(db: Db, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val zone = Store.zone()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    /** Row that was just switched off and offers "Turn back on for …" (in memory only). */
    var offerId by remember { mutableLongStateOf(-1) }
    var selected by remember { mutableStateOf<Set<Long>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf<Group?>(null) } // id 0 = create
    var moveDialog by remember { mutableStateOf(false) }
    var deleteGroup by remember { mutableStateOf<Group?>(null) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    editing?.let { a -> AlarmEditor(a, db, onClose = { editing = null }); return }

    fun newAlarm(groupId: Long = 0): Alarm {
        val t = java.time.LocalTime.now().plusMinutes(1)
        return Alarm(0, t.hour, t.minute, groupId = groupId)
    }
    fun toggle(a: Alarm, on: Boolean) {
        Ops.setOn(ctx, listOf(a.id), on)
        if (on) { Ops.toastFor(ctx, a.id); if (offerId == a.id) offerId = -1 }
        else offerId = if (a.repeating) a.id else -1
    }
    val byTime = compareBy<Alarm>({ it.hour * 60 + it.minute }, { it.createdAt })

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val sel = selected
            if (sel != null) {
                TopAppBar(
                    windowInsets = WindowInsets(0),
                    title = { Text(if (sel.isEmpty()) "Select alarms" else "${sel.size} selected") },
                    navigationIcon = {
                        Checkbox(sel.size == db.alarms.size && sel.isNotEmpty(), { all -> selected = if (all) db.alarms.map { it.id }.toSet() else emptySet() })
                    },
                    actions = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
                )
            } else {
                TopAppBar(
                    windowInsets = WindowInsets(0),
                    title = {},
                    actions = {
                        IconButton(onClick = { editing = newAlarm() }) { Icon(Icons.Filled.Add, "Add alarm") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Create group") }, onClick = {
                                menu = false
                                if (db.groups.size >= MAX_GROUPS) Toast.makeText(ctx, "Can't create more than $MAX_GROUPS alarm groups", Toast.LENGTH_SHORT).show()
                                else groupDialog = Group(0, Ops.defaultGroupName(db), 0)
                            })
                            DropdownMenuItem({ Text("Select") }, onClick = { menu = false; selected = emptySet() })
                            DropdownMenuItem({ Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    },
                )
            }
        },
        bottomBar = {
            val sel = selected
            if (sel != null && sel.isNotEmpty()) BottomAppBar {
                TextButton(onClick = { Ops.setOn(ctx, sel, true); selected = null }) { Text("Turn on") }
                TextButton(onClick = { Ops.setOn(ctx, sel, false); selected = null }) { Text("Turn off") }
                TextButton(onClick = { moveDialog = true }) { Text("Move") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { Ops.deleteAlarms(ctx, sel); selected = null }) {
                    Text(if (sel.size == db.alarms.size) "Delete all" else "Delete")
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val next = db.alarms.filter { it.active != ACTIVE_OFF && it.alertAt > 0 }.minByOrNull { it.alertAt }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AlarmLogic.header(db, now, zone), style = MaterialTheme.typography.headlineMedium)
                    when {
                        next?.active == ACTIVE_SNOOZED -> Text("In " + AlarmLogic.plural(((next.alertAt - now) + 59_999) / 60_000, "minute"))
                        next != null -> Text(DateUtils.formatDateTime(ctx, next.alertAt,
                            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL))
                    }
                }
            }
            item { Warnings(db) }
            for (g in db.groups.sortedBy { it.order }) {
                val members = db.alarms.filter { it.groupId == g.id }.sortedWith(byTime)
                val open = expanded[g.id] ?: true
                item(key = "g${g.id}") {
                    GroupCard(g, members, open,
                        onExpand = { expanded[g.id] = !open },
                        onSwitch = { on ->
                            if (members.isEmpty()) editing = newAlarm(g.id)
                            else { Ops.setGroupOn(ctx, g.id, on); offerId = -1 }
                        },
                        onRename = { groupDialog = g },
                        onUngroup = { Ops.ungroup(ctx, g.id); Toast.makeText(ctx, "Your alarms won't be deleted.", Toast.LENGTH_SHORT).show() },
                        onDelete = { deleteGroup = g },
                    )
                }
                if (open) items(members, key = { it.id }) { a ->
                    AlarmRow(a, now, offerId == a.id, selected, Modifier.padding(start = 16.dp),
                        onClick = { if (selected != null) selected = selected!!.toggle(a.id) else editing = a },
                        onLong = { if (selected == null) selected = setOf(a.id) },
                        onSwitch = { toggle(a, it) },
                        onSkip = { Ops.skip(ctx, listOf(a.id)); offerId = -1 })
                }
            }
            val loose = db.alarms.filter { it.groupId == 0L || db.group(it.groupId) == null }.sortedWith(byTime)
            if (db.groups.isNotEmpty() && loose.isNotEmpty()) item {
                Text("Unassigned", Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(loose, key = { it.id }) { a ->
                AlarmRow(a, now, offerId == a.id, selected, Modifier,
                    onClick = { if (selected != null) selected = selected!!.toggle(a.id) else editing = a },
                    onLong = { if (selected == null) selected = setOf(a.id) },
                    onSwitch = { toggle(a, it) },
                    onSkip = { Ops.skip(ctx, listOf(a.id)); offerId = -1 })
            }
        }
    }

    groupDialog?.let { g ->
        GroupNameDialog(db, g, onClose = { groupDialog = null }) { name ->
            if (g.id == 0L) Ops.createGroup(ctx, name) else Ops.renameGroup(ctx, g.id, name)
            groupDialog = null
        }
    }
    deleteGroup?.let { g ->
        val n = db.alarms.count { it.groupId == g.id }
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("Delete ${g.name}?") },
            text = { Text("This will also delete $n alarm(s).") },
            confirmButton = { TextButton(onClick = { Ops.deleteGroup(ctx, g.id); deleteGroup = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("Cancel") } },
        )
    }
    if (moveDialog) {
        val sel = selected.orEmpty()
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text("Select group") },
            text = {
                Column {
                    (listOf(Group(0, "Unassigned", -1)) + db.groups.sortedBy { it.order }).forEach { g ->
                        TextButton(onClick = {
                            Ops.moveToGroup(ctx, sel, g.id); moveDialog = false; selected = null
                            Toast.makeText(ctx, "Alarms moved.", Toast.LENGTH_SHORT).show()
                        }) { Text("${g.name} (${db.alarms.count { it.groupId == g.id }})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveDialog = false }) { Text("Cancel") } },
        )
    }
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    g: Group, members: List<Alarm>, open: Boolean,
    onExpand: () -> Unit, onSwitch: (Boolean) -> Unit, onRename: () -> Unit, onUngroup: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val on = members.count { it.active != ACTIVE_OFF }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).combinedClickable(onClick = onExpand, onLongClick = { menu = true })) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium)
                Text("ON $on · OFF ${members.size - on}", style = MaterialTheme.typography.bodySmall)
            }
            Switch(on > 0, onSwitch)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rename group") }, onClick = { menu = false; onRename() })
                DropdownMenuItem({ Text("Ungroup") }, onClick = { menu = false; onUngroup() })
                DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmRow(
    a: Alarm, now: Long, offer: Boolean, selected: Set<Long>?, modifier: Modifier,
    onClick: () -> Unit, onLong: () -> Unit, onSwitch: (Boolean) -> Unit, onSkip: () -> Unit,
) {
    val ctx = LocalContext.current
    val zone = Store.zone()
    val h24 = DateFormat.is24HourFormat(ctx)
    val on = a.active != ACTIVE_OFF
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) Checkbox(a.id in selected, { onClick() })
            Column(Modifier.weight(1f).alpha(if (on) 1f else 0.55f)) {
                if (a.name.isNotBlank()) Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    val hh = if (h24) a.hour else (a.hour + 11) % 12 + 1
                    Text(if (h24) "%02d:%02d".format(hh, a.minute) else "%d:%02d".format(hh, a.minute), fontSize = 36.sp, fontWeight = FontWeight.Light)
                    if (!h24) Text(if (a.hour < 12) " AM" else " PM", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    a.skipped -> Text("Alarm will sound " + AlarmLogic.dayWord(a.alertAt, now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    a.days == 0b1111111 -> Text("Every day", style = MaterialTheme.typography.bodySmall)
                    a.repeating -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WEEK.forEachIndexed { i, d ->
                            val set = a.days and AlarmLogic.dayBit(d) != 0
                            Text(LETTERS[i], style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (set) FontWeight.Bold else FontWeight.Normal,
                                color = if (set) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    a.dateEpochDay != null -> Text(LocalDate.ofEpochDay(a.dateEpochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val at = if (on && a.alertAt > 0) a.alertAt else AlarmLogic.nextFire(a, now, zone)
                        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                        Text(if (today) "Today" else "Tomorrow", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (selected == null) Switch(on, onSwitch)
        }
        if (offer && !on && a.repeating && !a.skipped && selected == null) {
            TextButton(onClick = onSkip, Modifier.padding(start = 8.dp, bottom = 4.dp)) { Text(AlarmLogic.skipButton(a, now, zone)) }
        }
    }
}

@Composable
fun GroupNameDialog(db: Db, g: Group, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    val err = Ops.validGroupName(db, name, g.id)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (g.id == 0L) "Create group" else "Rename group") },
        text = {
            OutlinedTextField(name, { name = it.take(20) }, label = { Text("Group name") }, singleLine = true,
                isError = err != null && name.isNotEmpty(), supportingText = { if (err != null && name.isNotEmpty()) Text(err) })
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = err == null) { Text(if (g.id == 0L) "Create" else "Rename") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
