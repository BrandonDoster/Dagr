package cc.doster.dagr

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings as SysSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_TAB = "tab" }

    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { tab.intValue = it.getInt(EXTRA_TAB) }
        Ops.seedIfNeeded(this)
        // A recreated activity or a Recents relaunch still carries the old SET_TIMER intent.
        val fresh = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        if (fresh && handleIntent(intent)) { finish(); return }
        setIntent(Intent(this, MainActivity::class.java))
        setContent { AppTheme { Main(tab) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, tab.intValue)
    }

    override fun onResume() {
        super.onResume()
        SoundCache.refresh(this) // the system default tone may have changed
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Standard clock intents (SET_TIMER etc.). Returns true when no UI should be shown. */
    private fun handleIntent(i: Intent?): Boolean {
        i ?: return false
        if (i.hasExtra(EXTRA_TAB)) tab.intValue = i.getIntExtra(EXTRA_TAB, 0)
        when (i.action) {
            AlarmClock.ACTION_SET_TIMER -> {
                tab.intValue = 1
                val secs = i.getIntExtra(AlarmClock.EXTRA_LENGTH, -1)
                if (secs <= 0) return false
                if (secs * 1000L > TimerLogic.MAX_MS) { Toast.makeText(this, "Invalid timer length", Toast.LENGTH_SHORT).show(); return false }
                Ops.startTimer(this, secs * 1000L, i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), fromUi = false)?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
                return i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            AlarmClock.ACTION_SHOW_TIMERS -> tab.intValue = 1
            AlarmClock.ACTION_DISMISS_TIMER -> { Ops.dismissExpiredTimers(this); tab.intValue = 1 }
            AlarmClock.ACTION_SHOW_ALARMS -> tab.intValue = 0
        }
        return false
    }
}

@Composable
fun Main(tab: MutableIntState) {
    val ctx = LocalContext.current
    val db by Store.db.collectAsState()
    var settings by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { // notifications are the ring UI: ask on first run
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab.intValue == 0, { tab.intValue = 0 }, { Icon(Icons.Filled.Alarm, null) }, label = { Text("Alarm") })
                NavigationBarItem(tab.intValue == 1, { tab.intValue = 1 }, { Icon(Icons.Filled.Timer, null) }, label = { Text("Timer") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab.intValue == 0) AlarmTab(db, onSettings = { settings = true }) else TimerTab(db, onSettings = { settings = true })
        }
    }
    if (settings) SettingsDialog(db) { settings = false }
}

/** Anything that would make an alarm ring with nothing on screen. Rechecked on resume. */
@Composable
fun Warnings(db: Db) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose {} }
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    val items = remember(tick, db) {
        buildList {
            val pkg = Uri.parse("package:" + ctx.packageName)
            if (!nm.areNotificationsEnabled()) add("Notifications are off. Alarms can't show or be stopped." to
                Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName))
            else if ((nm.getNotificationChannel(Notifs.CH_RING)?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH) add(
                "The \"Ringing alarms\" notification category is lowered or blocked." to
                    Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(SysSettings.EXTRA_CHANNEL_ID, Notifs.CH_RING))
            if (!nm.canUseFullScreenIntent()) add("Full-screen alarms are not allowed. The alarm won't cover the lock screen." to
                Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            if (Notifs.dndBlocksAlarms(ctx)) add("Alarms will be silenced with Do not disturb on." to Intent(SysSettings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
        }
    }
    items.forEach { (text, intent) ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { runCatching { ctx.startActivity(intent) } }) { Text("Fix") }
            }
        }
    }
}

@Composable
fun SettingsDialog(db: Db, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = db.settings
    val sysPick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pickedRingtone(ctx, r.data)?.let { (snd, title) -> Ops.settings(ctx) { it.copy(timerSound = snd, timerSoundTitle = title) } }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> scope.launch { importSound(ctx, u)?.let { (snd, title) -> Ops.settings(ctx) { it.copy(timerSound = snd, timerSoundTitle = title) } } ?: soundRejected(ctx) } }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Upcoming alarm notification", style = MaterialTheme.typography.titleSmall)
                ChoiceRow(listOf("5 min", "15 min", "30 min", "Off").mapIndexed { i, l -> l to (s.upcomingLead == i) }) { i ->
                    Ops.settings(ctx) { it.copy(upcomingLead = i) }
                }
                HorizontalDivider()
                Text("Timer", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Sound", Modifier.weight(1f))
                    Switch(s.timerSoundOn, { v -> Ops.settings(ctx) { it.copy(timerSoundOn = v) } })
                }
                Text(soundTitle(ctx, s.timerSound, s.timerSoundTitle), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { sysPick.launch(ringtonePickerIntent(s.timerSound)) }) { Text("System sounds") }
                    TextButton(onClick = { filePick.launch(arrayOf("audio/*")) }) { Text("From file") }
                }
                Text("Volume ${s.timerVolume}", style = MaterialTheme.typography.bodySmall)
                Slider(s.timerVolume.toFloat(), { v -> Ops.settings(ctx) { it.copy(timerVolume = v.toInt()) } }, valueRange = 0f..15f, steps = 14)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Vibration", Modifier.weight(1f))
                    Switch(s.timerVibrate, { v -> Ops.settings(ctx) { it.copy(timerVibrate = v) } })
                }
            }
        },
    )
}
