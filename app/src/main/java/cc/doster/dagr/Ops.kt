package cc.doster.dagr

import android.content.Context
import android.widget.Toast

/** Every state change goes through here: update the store, then resync alarms + notifications. */
object Ops {
    private fun now() = System.currentTimeMillis()
    private val zone get() = Store.zone()

    fun edit(ctx: Context, f: (Db) -> Db): Db {
        val old = Store.db.value
        val db = Store.update { d -> f(d).let { it.copy(ringQueue = AlarmLogic.stampHead(it.ringQueue, now())) } }
        try { Sync.run(ctx) } catch (e: Exception) { android.util.Log.e("Ops", "sync failed", e) } // never block a ring
        if (db.alarms.map { it.sound } != old.alarms.map { it.sound } || db.settings.timerSound != old.settings.timerSound) {
            runCatching { SoundCache.refresh(ctx) } // never on the ring path's failure list
        }
        return db
    }

    private fun Db.mapAlarms(ids: Collection<Long>, f: (Alarm) -> Alarm) =
        copy(alarms = alarms.map { if (it.id in ids) f(it) else it })

    fun seedIfNeeded(ctx: Context) {
        if (Store.db.value.seeded) return
        edit(ctx) { db ->
            if (db.seeded) db else if (db.alarms.isNotEmpty()) db.copy(seeded = true) else {
                // first run: preset 06:00 Mon–Fri, off
                val a = Alarm(db.nextId, 6, 0, days = 0b0011111, createdAt = now())
                db.copy(nextId = db.nextId + 1, alarms = listOf(AlarmLogic.turnOff(a, now(), zone)), seeded = true)
            }
        }
    }

    // ---------- alarms ----------

    /** Save from the editor: saving turns the alarm on. Returns the toast text. */
    fun saveAlarm(ctx: Context, a: Alarm): String? {
        var saved: Alarm? = null
        val db = edit(ctx) { db ->
            val isNew = db.alarm(a.id) == null
            if (isNew && db.alarms.size >= MAX_ALARMS) return@edit db
            val id = if (isNew) db.nextId else a.id
            val on = AlarmLogic.turnOn(a.copy(id = id, createdAt = if (isNew) now() else a.createdAt), now(), zone)
            saved = on
            if (isNew) db.copy(nextId = db.nextId + 1, alarms = db.alarms + on)
            else db.copy(alarms = db.alarms.map { if (it.id == id) on else it })
        }
        if (saved == null) return "Can't add more than $MAX_ALARMS alarms"
        return db.alarm(saved.id)?.let { AlarmLogic.setToast(it.alertAt, now(), zone) }
    }

    fun setOn(ctx: Context, ids: Collection<Long>, on: Boolean): Db = edit(ctx) { db ->
        db.mapAlarms(ids) { a -> guarded(a) { if (on) AlarmLogic.turnOn(a, now(), zone) else AlarmLogic.turnOff(a, now(), zone) } }
    }

    fun toastFor(ctx: Context, id: Long) {
        val a = Store.db.value.alarm(id) ?: return
        if (a.active != ACTIVE_OFF) Toast.makeText(ctx, AlarmLogic.setToast(a.alertAt, now(), zone), Toast.LENGTH_SHORT).show()
    }

    fun skip(ctx: Context, ids: Collection<Long>) = edit(ctx) { db -> db.mapAlarms(ids) { a -> guarded(a) { AlarmLogic.skipNext(a, now(), zone) } } }

    fun deleteAlarms(ctx: Context, ids: Collection<Long>) = edit(ctx) { db ->
        db.copy(alarms = db.alarms.filter { it.id !in ids }, ringQueue = db.ringQueue.filter { it.alarmId !in ids })
    }

    fun moveToGroup(ctx: Context, ids: Collection<Long>, groupId: Long) = edit(ctx) { db -> db.mapAlarms(ids) { it.copy(groupId = groupId) } }

    fun restoreSkipped(ctx: Context) = edit(ctx) { db -> db.copy(alarms = db.alarms.map { a -> guarded(a) { AlarmLogic.restoreSkipped(a, now(), zone) } }) }

    // ---------- groups ----------

    fun validGroupName(db: Db, name: String, exceptId: Long = 0): String? = when {
        name.isBlank() -> "Enter a name"
        db.groups.any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) } -> "Group name already in use."
        else -> null
    }

    fun defaultGroupName(db: Db) = (1..MAX_GROUPS).map { "Group $it" }.first { n -> db.groups.none { it.name == n } }

    fun createGroup(ctx: Context, name: String, members: Collection<Long> = emptyList()): Long {
        var id = 0L
        edit(ctx) { db ->
            if (db.groups.size >= MAX_GROUPS) return@edit db
            id = db.nextId
            db.copy(nextId = id + 1, groups = db.groups + Group(id, name.trim(), db.groups.size)).mapAlarms(members) { it.copy(groupId = id) }
        }
        return id
    }

    fun renameGroup(ctx: Context, id: Long, name: String) = edit(ctx) { db ->
        db.copy(groups = db.groups.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun ungroup(ctx: Context, id: Long) = edit(ctx) { db ->
        db.copy(groups = db.groups.filter { it.id != id }, alarms = db.alarms.map { if (it.groupId == id) it.copy(groupId = 0) else it })
    }

    /** Deleting a group deletes its alarms. */
    fun deleteGroup(ctx: Context, id: Long) = edit(ctx) { db ->
        val ids = db.alarms.filter { it.groupId == id }.map { it.id }.toSet()
        db.copy(groups = db.groups.filter { it.id != id }, alarms = db.alarms.filter { it.id !in ids }, ringQueue = db.ringQueue.filter { it.alarmId !in ids })
    }

    /** Group switch: applies to every member, no memory of individual states. */
    fun setGroupOn(ctx: Context, id: Long, on: Boolean) =
        setOn(ctx, Store.db.value.alarms.filter { it.groupId == id }.map { it.id }, on)

    // ---------- ringing ----------

    /** Alarm broadcast: start every due alarm (rings up to 15 min late; same-time alarms queue). Returns true if something should ring. */
    fun fireDue(ctx: Context): Boolean {
        val t = now()
        val missed = mutableListOf<Alarm>()
        val db = edit(ctx) { db ->
            val queued = db.ringQueue.map { it.alarmId }.toSet()
            var q = db.ringQueue
            val alarms = db.alarms.map { a ->
                guarded(a) {
                    if (a.active == ACTIVE_OFF || a.id in queued || a.alertAt <= 0 || a.alertAt > t + AlarmLogic.EARLY_LIMIT_MS) a
                    else if (t - a.alertAt > AlarmLogic.LATE_LIMIT_MS) { missed += a; AlarmLogic.finish(a, t, zone) }
                    else { val (n, e) = AlarmLogic.fire(a, t, zone); q = q + e; n }
                }
            }
            db.copy(alarms = alarms, ringQueue = q)
        }
        missed.forEach { Notifs.missedAlarm(ctx, it) }
        return db.ringQueue.isNotEmpty()
    }

    fun snoozeRinging(ctx: Context, alarmId: Long) = edit(ctx) { db ->
        val e = db.ringQueue.firstOrNull { it.alarmId == alarmId } ?: return@edit db
        db.copy(
            ringQueue = db.ringQueue - e,
            alarms = db.alarms.map { if (it.id == alarmId && e.canSnooze) AlarmLogic.snooze(it, now()) else it },
        )
    }

    fun dismissRinging(ctx: Context, alarmId: Long) = edit(ctx) { db ->
        val e = db.ringQueue.firstOrNull { it.alarmId == alarmId } ?: return@edit db
        db.copy(ringQueue = db.ringQueue - e, alarms = db.alarms.map { if (it.id == alarmId) AlarmLogic.dismiss(it, now(), zone) else it })
    }

    /** Ring timed out: auto-snooze, or end + missed notification when no snooze is left. */
    fun timeoutRinging(ctx: Context, alarmId: Long) {
        val e = Store.db.value.ringQueue.firstOrNull { it.alarmId == alarmId } ?: return
        if (e.canSnooze) snoozeRinging(ctx, alarmId) else {
            Store.db.value.alarm(alarmId)?.let { Notifs.missedAlarm(ctx, it) }
            dismissRinging(ctx, alarmId)
        }
    }

    /** Watchdog: time out every queued ring past its length + 30 s. Returns true if something still rings. */
    fun endOverdueRings(ctx: Context): Boolean {
        val db = Store.db.value
        val head = db.ringQueue.firstOrNull() ?: return false
        // Only the head is sounding; waiting entries get their full ring once promoted.
        if (now() >= AlarmLogic.ringEnd(db.alarm(head.alarmId), head) + 25_000) timeoutRinging(ctx, head.alarmId)
        return Store.db.value.ringQueue.isNotEmpty()
    }

    /** One bad row must not stop every other alarm: on error the row is left as it was. */
    inline fun guarded(a: Alarm, f: () -> Alarm): Alarm = try { f() } catch (e: Exception) {
        android.util.Log.e("Ops", "alarm ${a.id} skipped", e); a
    }

    fun adjustSnooze(ctx: Context, alarmId: Long, delta: Int) = edit(ctx) { db -> db.mapAlarms(listOf(alarmId)) { AlarmLogic.adjustSnooze(it, delta) } }

    /** Snoozed-notification Dismiss: cancel the snooze. */
    fun dismissSnoozed(ctx: Context, id: Long) = edit(ctx) { db -> db.mapAlarms(listOf(id)) { AlarmLogic.dismiss(it, now(), zone) } }

    /** Process start with a ring queue = crash mid-ring. State was already advanced at fire time. */
    fun recoverOrphans(ctx: Context) {
        val db = Store.db.value
        db.ringQueue.mapNotNull { db.alarm(it.alarmId) }.forEach { Notifs.missedAlarm(ctx, it) }
        // expired timers from a dead process have no alert left; report and drop them.
        val expired = db.timers.filter { it.state == TIMER_EXPIRED }
        expired.forEach { Notifs.timerExpiredWhileOff(ctx, it) }
        if (db.ringQueue.isNotEmpty() || expired.isNotEmpty() || db.settings.timerAlertStart != -1L) edit(ctx) {
            it.copy(ringQueue = emptyList(), timers = it.timers.filter { t -> t.state != TIMER_EXPIRED }, settings = it.settings.copy(timerAlertStart = -1))
        }
    }

    // ---------- reboot / time change ----------

    fun recomputeAll(ctx: Context) {
        val t = now()
        val c = Store.clock(ctx)
        val missed = mutableListOf<Alarm>()
        val lostTimers = mutableListOf<Timer>()
        edit(ctx) { db ->
            db.copy(
                alarms = db.alarms.map { a -> guarded(a) { AlarmLogic.recompute(a, t, zone).also { if (it.second) missed += a }.first } },
                timers = db.timers.mapNotNull { tm -> TimerLogic.reanchor(tm, c).also { if (it == null) lostTimers += tm } },
            )
        }
        missed.forEach { Notifs.missedAlarm(ctx, it) }
        lostTimers.forEach { Notifs.timerExpiredWhileOff(ctx, it) }
    }

    // ---------- timers ----------

    /** [fromUi]: only the picker updates the remembered last input. */
    fun startTimer(ctx: Context, ms: Long, name: String, fromUi: Boolean = true): String? {
        if (Store.db.value.timers.size >= MAX_TIMERS) return "Can't add more than $MAX_TIMERS timers"
        val c = Store.clock(ctx)
        edit(ctx) { db ->
            db.copy(
                nextId = db.nextId + 1,
                timers = db.timers + TimerLogic.start(db.nextId, cleanName(name), ms.coerceIn(1_000, TimerLogic.MAX_MS), c),
                settings = if (fromUi) db.settings.copy(timerLastInputMs = ms) else db.settings,
            )
        }
        return if (Notifs.dndBlocksAlarms(ctx)) "The timer won't sound because Do not disturb is on." else null
    }

    private fun mapTimer(ctx: Context, id: Long, f: (Timer, Clock) -> Timer) {
        val c = Store.clock(ctx)
        edit(ctx) { db -> db.copy(timers = db.timers.map { if (it.id == id) f(it, c) else it }) }
    }

    fun pauseTimer(ctx: Context, id: Long) = mapTimer(ctx, id, TimerLogic::pause)
    fun resumeTimer(ctx: Context, id: Long) = mapTimer(ctx, id, TimerLogic::resume)
    fun restartTimer(ctx: Context, id: Long) = mapTimer(ctx, id, TimerLogic::restart)
    fun cancelTimer(ctx: Context, id: Long) = edit(ctx) { db -> db.copy(timers = db.timers.filter { it.id != id }) }

    fun renameTimer(ctx: Context, id: Long, name: String) = edit(ctx) { db ->
        db.copy(timers = db.timers.map { if (it.id == id) it.copy(name = cleanName(name)) else it })
    }

    /** Names from intents or dialogs: one line, trimmed, 40 chars. */
    fun cleanName(s: String) = s.replace(Regex("\\s+"), " ").trim().take(40)

    /** Expire running timers whose time is up. Returns true if any timer is expired. */
    fun expireDue(ctx: Context): Boolean {
        val c = Store.clock(ctx)
        val db = edit(ctx) { db ->
            db.copy(timers = db.timers.map { if (it.state == TIMER_RUNNING && TimerLogic.remaining(it, c) <= 150) TimerLogic.expire(it, c) else it })
        }
        return db.timers.any { it.state == TIMER_EXPIRED }
    }

    fun dismissExpiredTimers(ctx: Context) = edit(ctx) { db ->
        db.copy(timers = db.timers.filter { it.state != TIMER_EXPIRED }, settings = db.settings.copy(timerAlertStart = -1))
    }

    /** Restart the most recently expired timer; the other expired ones are dismissed. */
    fun restartLastExpired(ctx: Context) {
        val last = Store.db.value.timers.filter { it.state == TIMER_EXPIRED }.maxByOrNull { it.endWall } ?: return
        val c = Store.clock(ctx)
        edit(ctx) { db ->
            db.copy(
                timers = db.timers.filter { it.state != TIMER_EXPIRED || it.id == last.id }.map { if (it.id == last.id) TimerLogic.restart(it, c) else it },
                settings = db.settings.copy(timerAlertStart = -1),
            )
        }
    }

    // ---------- presets ----------

    /** Returns a toast message, or null. Same name + duration = no insert. */
    fun addPreset(ctx: Context, name: String, ms: Long): String? {
        val db = Store.db.value
        if (ms <= 0) return null
        if (db.presets.any { it.name == name.trim() && it.inputMs == ms }) return "${name.trim().ifEmpty { TimerLogic.hms(ms) }} preset already in list. Preset updated."
        if (db.presets.size >= MAX_PRESETS) return "Can't add more than $MAX_PRESETS preset timers"
        edit(ctx) { it.copy(nextId = it.nextId + 1, presets = it.presets + Preset(it.nextId, name.trim(), ms)) }
        return null
    }

    fun editPreset(ctx: Context, id: Long, name: String, ms: Long): String? {
        if (Store.db.value.presets.any { it.id != id && it.name == name.trim() && it.inputMs == ms }) return "${name.trim().ifEmpty { TimerLogic.hms(ms) }} preset already in list. Preset updated."
        edit(ctx) { db -> db.copy(presets = db.presets.map { if (it.id == id) it.copy(name = name.trim(), inputMs = ms) else it }) }
        return null
    }

    fun deletePresets(ctx: Context, ids: Collection<Long>) = edit(ctx) { db -> db.copy(presets = db.presets.filter { it.id !in ids }) }

    fun movePreset(ctx: Context, id: Long, delta: Int) = edit(ctx) { db ->
        val l = db.presets.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) db else { l.add(j, l.removeAt(i)); db.copy(presets = l) }
    }

    fun settings(ctx: Context, f: (Settings) -> Settings) = edit(ctx) { it.copy(settings = f(it.settings)) }
}
