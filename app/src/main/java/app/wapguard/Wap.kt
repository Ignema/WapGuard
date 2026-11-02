package app.wapguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** WAP Push port. The raw table stores it OR'd with a 3GPP flag (e.g. 134020), so compare the low 16 bits. */
private const val WAP_PORT = 2948

/**
 * Legitimate WAP Push (MMS notifications, carrier provisioning) leaves the raw queue within milliseconds.
 * Only rows older than this are treated as stuck, so a message mid-delivery is never touched.
 */
private const val STUCK_AFTER_MS = 2 * 60 * 1000L

/**
 * The raw table's `date` is **not** a local receipt time: InboundSmsHandler passes the PDU's service-centre
 * timestamp (`sms.getTimestampMillis()`) into the row, so whoever sends the message controls it. A row dated
 * further ahead than this much skew is therefore not trusted for age, and is timed from when WAP Guard first
 * saw it instead — otherwise a future-dated poison PDU would never be classified as stuck.
 */
private const val SKEW_MS = 60 * 1000L

/** First Android security patch level that carries the fix (CVE-2026-58882, October 2026 bulletin). */
private const val FIXED_PATCH = "2026-10-01"

/**
 * The fix was released for Android 14–17 only. Android 12 and 13 run the same vulnerable code and
 * never receive it, whatever security patch level they report.
 */
private const val FIRST_FIXED_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

private val APP_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")

internal enum class Access { NotRunning, NoPermission, Ready }

internal fun currentAccess() = when {
    !Shizuku.pingBinder() -> Access.NotRunning
    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Access.NoPermission
    else -> Access.Ready
}

/** False on Android 12–13, where no fix is coming. */
internal fun fixExistsForThisVersion() = Build.VERSION.SDK_INT >= FIRST_FIXED_SDK

/** Whether this build's security patch level includes the fix. A heuristic: vendors can backport early. */
internal fun isPatched() = fixExistsForThisVersion() && Build.VERSION.SECURITY_PATCH >= FIXED_PATCH

// ---------------------------------------------------------------------------
// Pure parsing and classification. Kept free of Android calls so it is unit-testable; a silent regression
// here would report "All clear" on an infected phone.
// ---------------------------------------------------------------------------

/** One row of the pending-SMS queue. */
internal data class RawRow(val id: Long, val port: Int, val date: Long)

/** Parses `content query` output. A row missing any projected field is skipped. */
internal fun parseRawRows(output: String): List<RawRow> {
    fun field(row: String, name: String) = Regex("\\b$name=(-?\\d+)").find(row)?.groupValues?.get(1)?.toLongOrNull()
    return output.lines().filter { it.startsWith("Row:") }.mapNotNull { row ->
        RawRow(
            id = field(row, "_id") ?: return@mapNotNull null,
            port = (field(row, "destination_port") ?: return@mapNotNull null).toInt(),
            date = field(row, "date") ?: return@mapNotNull null,
        )
    }
}

internal fun isWapPush(row: RawRow) = row.port and 0xFFFF == WAP_PORT

/**
 * Rows queued for longer than [STUCK_AFTER_MS]. A row dated in the future carries an untrusted timestamp,
 * so it is aged from [firstSeen] — WAP Guard's own first sighting on the local clock — rather than its `date`.
 * The same fallback covers a device clock that runs behind the network's.
 */
internal fun stuckIds(rows: List<RawRow>, firstSeen: Map<Long, Long>, now: Long): List<Long> =
    rows.filter { row ->
        val trusted = row.date <= now + SKEW_MS
        val since = if (trusted) row.date else (firstSeen[row.id] ?: now).coerceAtMost(now)
        now - since > STUCK_AFTER_MS
    }.map { it.id }

/** `id:millis` pairs. Ids that have left the queue are dropped, so the record can't grow without bound. */
internal fun encodeFirstSeen(seen: Map<Long, Long>) = seen.entries.joinToString(";") { "${it.key}:${it.value}" }

internal fun decodeFirstSeen(raw: String): Map<Long, Long> = raw.split(';').mapNotNull { entry ->
    val parts = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    val id = parts[0].toLongOrNull() ?: return@mapNotNull null
    val time = parts[1].toLongOrNull() ?: return@mapNotNull null
    id to time
}.toMap()

/** The shell's WRITE_SMS mode from `cmd appops get` output; `default` when it can't be read. */
internal fun parseAppOpMode(output: String) =
    Regex("WRITE_SMS: (\\w+)").find(output)?.groupValues?.get(1)?.takeIf { it in APP_OP_MODES } ?: "default"

// ---------------------------------------------------------------------------
// Shell access
// ---------------------------------------------------------------------------

/**
 * Runs [cmd] as the shell user via Shizuku and returns its combined output.
 * Throws on timeout, a non-zero exit code, or an error printed by the `content`/`cmd` tools.
 * newProcess is hidden in the 13.x API, hence reflection.
 */
private fun sh(cmd: String, timeoutSeconds: Long = 15): String {
    val newProcess = Shizuku::class.java.getDeclaredMethod(
        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
    ).apply { isAccessible = true }
    val process = newProcess.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as ShizukuRemoteProcess
    val output = AtomicReference("")
    val reader = thread { output.set(runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")) }
    val finished = process.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)
    // destroy() closes the stream, which unblocks the reader so the thread isn't left behind on timeout.
    if (!finished) process.destroy()
    reader.join(2_000)
    if (!finished) error("Timed out after ${timeoutSeconds}s: ${cmd.substringBefore(' ')}")
    val text = output.get()
    val exit = process.exitValue()
    if (exit != 0 || "Exception" in text || text.startsWith("Error")) {
        error(text.trim().lineSequence().firstOrNull()?.ifEmpty { null } ?: "Command failed (exit $exit)")
    }
    return text
}

private const val KEY_SEEN = "first_seen"

/** IDs of WAP Push rows that have been sitting in the telephony raw queue for longer than [STUCK_AFTER_MS]. */
private fun stuckWapPushIds(context: Context): List<Long> {
    val out = sh("content query --uri content://sms/raw --projection _id:destination_port:date --where \"deleted=0\"")
    if (out.lines().none { it.startsWith("Row:") } && "No result found" !in out) {
        error(out.trim().ifEmpty { "Query failed" })
    }
    val wap = parseRawRows(out).filter(::isWapPush)
    val now = System.currentTimeMillis()
    return stuckIds(wap, trackFirstSeen(context, wap.map { it.id }, now), now)
}

/** Records when each queued row was first seen on the local clock, and forgets rows that have left the queue. */
private fun trackFirstSeen(context: Context, ids: List<Long>, now: Long): Map<Long, Long> {
    val store = prefs(context)
    val previous = decodeFirstSeen(store.getString(KEY_SEEN, "").orEmpty())
    // coerceAtMost guards a clock that has moved backwards since the row was first seen.
    val current = ids.associateWith { (previous[it] ?: now).coerceAtMost(now) }
    if (current != previous) store.edit().putString(KEY_SEEN, encodeFirstSeen(current)).apply()
    return current
}

internal fun pendingWapPush(context: Context): Int = stuckWapPushIds(context).size

/**
 * Undoes a WRITE_SMS grant left behind when a previous clean was killed between granting and restoring —
 * `finally` does not run if the process dies, and this app is most likely to be killed mid-clean on a phone
 * that is actively crash-looping. Revoking to `default` is the safe direction even if something else set it.
 */
internal fun revokeLeakedShellWriteSms() {
    if (parseAppOpMode(sh("cmd appops get com.android.shell WRITE_SMS")) == "allow") {
        sh("cmd appops set com.android.shell WRITE_SMS default")
    }
}

/**
 * WRITE_SMS is global shell state, not per-caller, so two cleans running at once would stomp each other:
 * one restores the previous mode while the other is still deleting, and that delete fails. The manual
 * button, the periodic worker and Watch mode all share this process, so one lock is enough.
 */
private val cleanLock = Any()

/**
 * Mirrors the researcher's ADB recovery for exactly the stuck rows found: temporarily allow shell WRITE_SMS,
 * delete those row IDs, restore the previous app-op mode, then verify they're gone. Returns how many were removed.
 */
internal fun clearWapPush(context: Context): Int = synchronized(cleanLock) { clearLocked(context) }

private fun clearLocked(context: Context): Int {
    val ids = stuckWapPushIds(context)
    if (ids.isEmpty()) return 0
    // An `allow` already in place is a grant leaked by an earlier killed run; restoring it would make the
    // grant permanent, so fall back to `default`.
    val previous = parseAppOpMode(sh("cmd appops get com.android.shell WRITE_SMS"))
        .let { if (it == "allow") "default" else it }
    try {
        sh("cmd appops set com.android.shell WRITE_SMS allow")
        sh("content delete --uri content://sms/raw --where \"_id IN (${ids.joinToString(",")})\"")
    } finally {
        sh("cmd appops set com.android.shell WRITE_SMS $previous")
    }
    val remaining = stuckWapPushIds(context).intersect(ids.toSet()).size
    if (remaining > 0) error("$remaining stuck message${if (remaining == 1) "" else "s"} couldn't be removed")
    return ids.size
}

private const val AUTO_WORK = "auto-clean"
private const val PREFS = "settings"
private const val KEY_AUTO = "auto"
private const val KEY_WATCH = "watch"
private const val KEY_LAST_TIME = "last_time"
private const val KEY_LAST_TEXT = "last_text"
private const val KEY_LAST_OK = "last_ok"
private const val CHANNEL = "cleaned"

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

internal fun isAutoClean(context: Context) = prefs(context).getBoolean(KEY_AUTO, false)

internal fun setAutoClean(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    val work = WorkManager.getInstance(context)
    if (on) {
        val request = PeriodicWorkRequestBuilder<AutoCleanWorker>(15, TimeUnit.MINUTES).build()
        // UPDATE, not KEEP: a later change to the interval or constraints must reach existing installs.
        work.enqueueUniquePeriodicWork(AUTO_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    } else {
        work.cancelUniqueWork(AUTO_WORK)
    }
}

internal fun isWatch(context: Context) = prefs(context).getBoolean(KEY_WATCH, false)

/**
 * Watch mode polls every [WATCH_INTERVAL_MS] instead of WorkManager's 15-minute floor, at the cost of an
 * ongoing notification and battery.
 */
internal fun setWatch(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean(KEY_WATCH, on).apply()
    val intent = Intent(context, WatchService::class.java)
    if (on) context.startForegroundService(intent) else context.stopService(intent)
}

/**
 * Watch mode is switched on but its service isn't running — after a reboot, an app update, or Shizuku going
 * away. The pref deliberately stays on so the choice survives, which means it alone can't be trusted to
 * describe what is happening; this is what keeps the picker from promising checks that nothing is performing.
 */
internal fun watchStalled(context: Context) = isWatch(context) && !WatchService.running

/**
 * How often WAP Guard checks on its own. One choice rather than two switches: Watch mode polls every 30s,
 * so a 15-minute tick alongside it does nothing except race it for the shell app-op.
 */
internal enum class Checks { Off, Periodic, Watch }

internal fun checks(context: Context) = when {
    isWatch(context) -> Checks.Watch
    isAutoClean(context) -> Checks.Periodic
    else -> Checks.Off
}

internal fun setChecks(context: Context, mode: Checks) {
    setWatch(context, mode == Checks.Watch)
    setAutoClean(context, mode == Checks.Periodic)
}

internal const val NOTIFY_CLEARED = 1
internal const val NOTIFY_AUTO_FAILED = 2
internal const val NOTIFY_WATCH_STOPPED = 4

/** A one-shot notification. Watch mode's ongoing foreground notification is built in [WatchService]. */
internal fun postNotification(context: Context, id: Int, title: String, text: String) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "WAP Guard", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

/** Outcome of the most recent background check, for the main screen. */
internal data class LastCheck(val time: Long, val text: String, val ok: Boolean)

internal fun lastCheck(context: Context): LastCheck? {
    val p = prefs(context)
    val time = p.getLong(KEY_LAST_TIME, 0)
    return if (time == 0L) null else LastCheck(time, p.getString(KEY_LAST_TEXT, "").orEmpty(), p.getBoolean(KEY_LAST_OK, true))
}

internal fun recordCheck(context: Context, text: String, ok: Boolean) {
    prefs(context).edit()
        .putLong(KEY_LAST_TIME, System.currentTimeMillis())
        .putString(KEY_LAST_TEXT, text)
        .putBoolean(KEY_LAST_OK, ok)
        .apply()
}

/**
 * Every ~15 min (Android's minimum; Doze can stretch it): if Shizuku is up, clear stuck WAP Push rows.
 * Every outcome is recorded for the main screen; you're notified on a cleanup, and when checks start failing.
 *
 * This cannot keep up with an attack in progress — see the README. A phone being crash-looped reboots faster
 * than the 15-minute floor, and Shizuku does not survive a reboot without root, so the realistic value here is
 * catching a single poison message that arrived while you weren't looking.
 */
class AutoCleanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A cold process receives the Shizuku binder asynchronously.
        val ready = awaitShizuku() && currentAccess() == Access.Ready
        val outcome = runCatching {
            check(ready) { "Shizuku isn't running" }
            withContext(Dispatchers.IO) {
                runCatching { revokeLeakedShellWriteSms() }
                clearWapPush(applicationContext)
            }
        }
        val wasOk = lastCheck(applicationContext)?.ok ?: true
        outcome.fold(
            onSuccess = { removed ->
                recordCheck(applicationContext, if (removed == 0) "All clear" else "Removed $removed stuck message${if (removed == 1) "" else "s"}", ok = true)
                if (removed > 0) {
                    postNotification(
                        applicationContext, NOTIFY_CLEARED, "Malformed WAP Push cleared",
                        "Removed $removed stuck message${if (removed == 1) "" else "s"} from the SMS queue.",
                    )
                }
            },
            onFailure = { e ->
                val reason = e.message ?: "Unknown error"
                recordCheck(applicationContext, "Check failed: $reason", ok = false)
                if (wasOk) {
                    postNotification(
                        applicationContext, NOTIFY_AUTO_FAILED, "Auto-clean isn't working",
                        "$reason. Open WAP Guard to fix it.",
                    )
                }
            },
        )
        return Result.success()
    }

    private suspend fun awaitShizuku() = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    if (cont.isActive) cont.resume(true)
                }
            }
            cont.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
            Shizuku.addBinderReceivedListenerSticky(listener)
        }
    } ?: false
}
