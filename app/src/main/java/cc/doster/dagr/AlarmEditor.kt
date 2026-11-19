package cc.doster.dagr

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val WEEK = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
private val LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditor(original: Alarm, db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = Store.zone()
    var a by remember { mutableStateOf(original) }
    var confirmBack by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var patternMenu by remember { mutableStateOf(false) }
    var customSnooze by remember { mutableStateOf(false) }
    val h24 = DateFormat.is24HourFormat(ctx)
    val now = System.currentTimeMillis()

    fun save() {
        if ((!a.soundOn || a.volume == 0) && !a.vibrate) { // an alarm with no sound and no vibration can't wake anyone
            Toast.makeText(ctx, "Turn on sound or vibration for this alarm.", Toast.LENGTH_LONG).show()
            return
        }
        val msg = Ops.saveAlarm(ctx, a)
        if (!msg.isNullOrEmpty()) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        onClose()
    }
    BackHandler { if (a != original) confirmBack = true else onClose() }

    val sysPick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pickedRingtone(ctx, r.data)?.let { (snd, title) -> a = a.copy(sound = snd, soundTitle = title) }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> scope.launch { importSound(ctx, u)?.let { (snd, title) -> a = a.copy(sound = snd, soundTitle = title) } ?: soundRejected(ctx) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original.id == 0L) "Add alarm" else "Edit alarm") },
                navigationIcon = { TextButton(onClick = { if (a != original) confirmBack = true else onClose() }) { Text("Cancel") } },
                actions = { TextButton(onClick = ::save) { Text("Save") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // time
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (h24) {
                    Wheel(0..23, a.hour, { a = a.copy(hour = it) }, Modifier.width(96.dp))
                } else {
                    Wheel(1..12, (a.hour + 11) % 12 + 1, { h -> a = a.copy(hour = h % 12 + if (a.hour >= 12) 12 else 0) }, Modifier.width(96.dp), label = { it.toString() })
                }
                Text(":", fontSize = 32.sp)
                Wheel(0..59, a.minute, { a = a.copy(minute = it) }, Modifier.width(96.dp))
                if (!h24) Wheel(0..1, if (a.hour >= 12) 1 else 0, { pm -> a = a.copy(hour = a.hour % 12 + pm * 12) }, Modifier.width(80.dp),
                    loop = false, label = { if (it == 0) "AM" else "PM" })
            }
            // date / days
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    a.repeating -> "Repeats"
                    a.dateEpochDay != null -> LocalDate.ofEpochDay(a.dateEpochDay!!).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                    else -> AlarmLogic.dayWord(AlarmLogic.nextFire(a, now, zone), now, zone).replaceFirstChar { it.uppercase() }
                }
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { datePicker = true }) { Icon(Icons.Filled.CalendarMonth, "Set date") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WEEK.forEachIndexed { i, d ->
                    val bit = AlarmLogic.dayBit(d)
                    FilterChip(a.days and bit != 0, { a = a.copy(days = a.days xor bit, dateEpochDay = null) }, { Text(LETTERS[i]) })
                }
            }
            OutlinedTextField(a.name, { a = a.copy(name = it.take(40)) }, Modifier.fillMaxWidth(), label = { Text("Alarm name") }, singleLine = true)

            // group
            Box {
                OutlinedButton(onClick = { groupMenu = true }, Modifier.fillMaxWidth()) {
                    Text("Group: " + (db.group(a.groupId)?.name ?: "Unassigned"))
                }
                DropdownMenu(groupMenu, { groupMenu = false }) {
                    DropdownMenuItem({ Text("Unassigned") }, onClick = { a = a.copy(groupId = 0); groupMenu = false })
                    db.groups.sortedBy { it.order }.forEach { g ->
                        DropdownMenuItem({ Text(g.name) }, onClick = { a = a.copy(groupId = g.id); groupMenu = false })
                    }
                }
            }

            HorizontalDivider()
            SwitchRow("Alarm sound", a.soundOn) { a = a.copy(soundOn = it) }
            if (a.soundOn) {
                Text(soundTitle(ctx, a.sound, a.soundTitle), style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { sysPick.launch(ringtonePickerIntent(a.sound)) }) { Text("System sounds") }
                    TextButton(onClick = { filePick.launch(arrayOf("audio/*")) }) { Text("From file") }
                }
                Text("Volume ${a.volume}", style = MaterialTheme.typography.bodySmall)
                Slider(a.volume.toFloat(), { a = a.copy(volume = it.toInt()) }, valueRange = 0f..15f, steps = 14)
                SwitchRow("Increasing volume", a.increasing) { a = a.copy(increasing = it) }
            }

            HorizontalDivider()
            SwitchRow("Vibration", a.vibrate) { a = a.copy(vibrate = it) }
            if (a.vibrate) Box {
                TextButton(onClick = { patternMenu = true }) { Text("Pattern: " + RingService.VIB_PATTERNS.getOrElse(a.vibPattern) { RingService.VIB_PATTERNS[0] }.first) }
                DropdownMenu(patternMenu, { patternMenu = false }) {
                    RingService.VIB_PATTERNS.forEachIndexed { i, (name, _) ->
                        DropdownMenuItem({ Text(name) }, onClick = { a = a.copy(vibPattern = i); patternMenu = false })
                    }
                }
            }

            HorizontalDivider()
            SwitchRow("Snooze", a.snoozeOn) { a = a.copy(snoozeOn = it) }
            if (a.snoozeOn) {
                Text("Interval", style = MaterialTheme.typography.bodySmall)
                val custom = a.snoozeMin !in AlarmLogic.SNOOZE_INTERVALS
                ChoiceRow(AlarmLogic.SNOOZE_INTERVALS.map { "$it" to (a.snoozeMin == it) } + ((if (custom) "${a.snoozeMin}" else "Custom") to custom)) { i ->
                    if (i < AlarmLogic.SNOOZE_INTERVALS.size) a = a.copy(snoozeMin = AlarmLogic.SNOOZE_INTERVALS[i]) else customSnooze = true
                }
                Text("Repeat", style = MaterialTheme.typography.bodySmall)
                ChoiceRow(AlarmLogic.SNOOZE_REPEATS.map { (if (it == SNOOZE_FOREVER) "Forever" else "${it}×") to (a.snoozeRepeat == it) }) { i ->
                    a = a.copy(snoozeRepeat = AlarmLogic.SNOOZE_REPEATS[i])
                }
            }
            HorizontalDivider()
            SwitchRow("Read time and alarm name aloud", a.tts) { a = a.copy(tts = it) }
        }
    }

    if (datePicker) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (a.dateEpochDay?.let(LocalDate::ofEpochDay) ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        var d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (java.time.ZonedDateTime.of(d, java.time.LocalTime.of(a.hour, a.minute), zone).toInstant().toEpochMilli() <= now) {
                            d = d.plusDays(1)
                            Toast.makeText(ctx, "Can't set alarms for times in the past. Alarm set for tomorrow.", Toast.LENGTH_SHORT).show()
                        }
                        a = a.copy(days = 0, dateEpochDay = d.toEpochDay())
                    }
                    datePicker = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (customSnooze) {
        var v by remember { mutableStateOf(a.snoozeMin.toString()) }
        AlertDialog(
            onDismissRequest = { customSnooze = false },
            title = { Text("Custom interval (1–60 min)") },
            text = { NumField(v.toIntOrNull() ?: 0, 60, "Minutes") { v = it.toString() } },
            confirmButton = { TextButton(onClick = { a = a.copy(snoozeMin = (v.toIntOrNull() ?: 5).coerceIn(1, 60)); customSnooze = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { customSnooze = false }) { Text("Cancel") } },
        )
    }

    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            text = { Text("Save your changes or discard them?") },
            confirmButton = { TextButton(onClick = { confirmBack = false; save() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmBack = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmBack = false; onClose() }) { Text("Discard") }
                }
            },
        )
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(value, onChange)
    }
}
