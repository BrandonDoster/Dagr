package cc.doster.dagr

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Full-screen ring UI: shows over the lock screen, back does nothing while it rings. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        setContent {
            AppTheme {
                val db by Store.db.collectAsState()
                val head = db.ringQueue.firstOrNull()
                val expired = db.timers.filter { it.state == TIMER_EXPIRED }
                LaunchedEffect(head == null && expired.isEmpty()) { if (head == null && expired.isEmpty()) finish() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
                    when {
                        head != null -> db.alarm(head.alarmId)?.let { AlarmRing(it, head) }
                        expired.isNotEmpty() -> TimerRing(expired)
                    }
                }
            }
        }
    }

    @Composable
    private fun AlarmRing(a: Alarm, e: RingEntry) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Text(Notifs.time(this@RingActivity, e.firedAt), fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text(Notifs.label(a), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (e.canSnooze) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { Ops.adjustSnooze(this@RingActivity, a.id, -5) }, enabled = a.snoozeMin > 1) { Text("−5") }
                    Text("${a.snoozeMin} min", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { Ops.adjustSnooze(this@RingActivity, a.id, 5) }, enabled = a.snoozeMin < 60) { Text("+5") }
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { Ops.snoozeRinging(this@RingActivity, a.id) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Snooze") }
                Spacer(Modifier.height(32.dp))
            }
            SwipeToDismiss("Swipe to dismiss alarm.") { Ops.dismissRinging(this@RingActivity, a.id) }
            Spacer(Modifier.height(48.dp))
        }
    }

    @Composable
    private fun TimerRing(expired: List<Timer>) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) { while (true) { delay(200); now = SystemClock.elapsedRealtime() } }
        val c = Store.clock(this).copy(elapsed = now)
        val first = expired.minBy { it.endWall }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Text(if (expired.size > 1) "${expired.size} timers done" else "Time's up", style = MaterialTheme.typography.headlineMedium)
            Text(expired.joinToString { it.name.ifBlank { TimerLogic.autoName(it.inputMs) } }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Text("−" + TimerLogic.hms(TimerLogic.overtime(first, c)), fontSize = 56.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            if (expired.size == 1) {
                Button(onClick = { Ops.restartLastExpired(this@RingActivity) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Restart") }
                Spacer(Modifier.height(32.dp))
            }
            SwipeToDismiss("Swipe to dismiss alert.") { Ops.dismissExpiredTimers(this@RingActivity) }
            Spacer(Modifier.height(48.dp))
        }
    }

    @Composable
    private fun SwipeToDismiss(hint: String, onDismiss: () -> Unit) {
        val limit = with(LocalDensity.current) { 140.dp.toPx() }
        var x by remember { mutableFloatStateOf(0f) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).offset { IntOffset(x.roundToInt(), 0) }
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .draggable(
                        rememberDraggableState { x += it },
                        Orientation.Horizontal,
                        onDragStopped = { if (abs(x) > limit) onDismiss(); x = 0f },
                    ),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "Dismiss", Modifier.size(40.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
