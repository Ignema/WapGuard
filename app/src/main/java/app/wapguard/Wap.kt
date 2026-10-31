package app.wapguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** WAP Push port. The raw table stores it OR'd with a 3GPP flag (e.g. 134020), so compare the low 16 bits. */
private const val WAP_PORT = 2948

/**
 * Legitimate WAP Push (MMS notifications, carrier provisioning) leaves the raw queue within milliseconds.
 * Only rows older than this are treated as stuck, so a message mid-delivery is never touched.
 */
private const val STUCK_AFTER_MS = 2 * 60 * 1000L

/** First Android security patch level that carries the fix (CVE-2026-58882, November 2026 bulletin). */
private const val FIXED_PATCH = "2026-11-01"

private val APP_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")

internal enum class Access { NotRunning, NoPermission, Ready }

internal fun currentAccess() = when {
    !Shizuku.pingBinder() -> Access.NotRunning
    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Access.NoPermission
    else -> Access.Ready
}

/** Whether this build's security patch level includes the fix. A heuristic: vendors can backport early. */
internal fun isPatched() = Build.VERSION.SECURITY_PATCH >= FIXED_PATCH

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
    var output = ""
    val reader = thread { output = process.inputStream.bufferedReader().readText() }
    if (!process.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroy()
        error("Timed out after ${timeoutSeconds}s: ${cmd.substringBefore(' ')}")
    }
    reader.join(2_000)
    val exit = process.exitValue()
    if (exit != 0 || "Exception" in output || output.startsWith("Error")) {
        error(output.trim().lineSequence().firstOrNull()?.ifEmpty { null } ?: "Command failed (exit $exit)")
    }
    return output
}

/** IDs of WAP Push rows that have been sitting in the telephony raw queue for longer than [STUCK_AFTER_MS]. */
private fun stuckWapPushIds(): List<Long> {
    val out = sh("content query --uri content://sms/raw --projection _id:destination_port:date --where \"deleted=0\"")
    val rows = out.lines().filter { it.startsWith("Row:") }
    if (rows.isEmpty() && "No result found" !in out) error(out.trim().ifEmpty { "Query failed" })
    val cutoff = System.currentTimeMillis() - STUCK_AFTER_MS
    fun field(row: String, name: String) = Regex("\\b$name=(-?\\d+)").find(row)?.groupValues?.get(1)?.toLongOrNull()
    return rows.mapNotNull { row ->
        val id = field(row, "_id") ?: return@mapNotNull null
        val port = field(row, "destination_port") ?: return@mapNotNull null
        val date = field(row, "date") ?: return@mapNotNull null
        id.takeIf { port.toInt() and 0xFFFF == WAP_PORT && date < cutoff }
    }
}

internal fun pendingWapPush(): Int = stuckWapPushIds().size

/**
 * Mirrors the researcher's ADB recovery for exactly the stuck rows found: temporarily allow shell WRITE_SMS,
 * delete those row IDs, restore the previous app-op mode, then verify they're gone. Returns how many were removed.
 */
internal fun clearWapPush(): Int {
    val ids = stuckWapPushIds()
    if (ids.isEmpty()) return 0
    val previous = Regex("WRITE_SMS: (\\w+)").find(sh("cmd appops get com.android.shell WRITE_SMS"))
        ?.groupValues?.get(1)?.takeIf { it in APP_OP_MODES } ?: "default"
    try {
        sh("cmd appops set com.android.shell WRITE_SMS allow")
        sh("content delete --uri content://sms/raw --where \"_id IN (${ids.joinToString(",")})\"")
    } finally {
        sh("cmd appops set com.android.shell WRITE_SMS $previous")
    }
    val remaining = stuckWapPushIds().intersect(ids.toSet()).size
    if (remaining > 0) error("$remaining stuck message${if (remaining == 1) "" else "s"} couldn't be removed")
    return ids.size
}

private const val AUTO_WORK = "auto-clean"
private const val PREFS = "settings"
private const val KEY_AUTO = "auto"
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
        work.enqueueUniquePeriodicWork(AUTO_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    } else {
        work.cancelUniqueWork(AUTO_WORK)
    }
}

/** Outcome of the most recent background check, for the main screen. */
internal data class LastCheck(val time: Long, val text: String, val ok: Boolean)

internal fun lastCheck(context: Context): LastCheck? {
    val p = prefs(context)
    val time = p.getLong(KEY_LAST_TIME, 0)
    return if (time == 0L) null else LastCheck(time, p.getString(KEY_LAST_TEXT, "").orEmpty(), p.getBoolean(KEY_LAST_OK, true))
}

/**
 * Every ~15 min (Android's minimum; Doze can stretch it): if Shizuku is up, clear stuck WAP Push rows.
 * Every outcome is recorded for the main screen; you're notified on a cleanup, and when checks start failing.
 */
class AutoCleanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A cold process receives the Shizuku binder asynchronously.
        val ready = awaitShizuku() && currentAccess() == Access.Ready
        val outcome = runCatching {
            check(ready) { "Shizuku isn't running" }
            withContext(Dispatchers.IO) { clearWapPush() }
        }
        val wasOk = lastCheck(applicationContext)?.ok ?: true
        outcome.fold(
            onSuccess = { removed ->
                record(if (removed == 0) "All clear" else "Removed $removed stuck message${if (removed == 1) "" else "s"}", ok = true)
                if (removed > 0) notify(1, "Malformed WAP Push cleared", "Removed $removed stuck message${if (removed == 1) "" else "s"} from the SMS queue.")
            },
            onFailure = { e ->
                val reason = e.message ?: "Unknown error"
                record("Check failed: $reason", ok = false)
                if (wasOk) notify(2, "Auto-clean isn't working", "$reason. Open WAP Guard to fix it.")
            },
        )
        return Result.success()
    }

    private fun record(text: String, ok: Boolean) {
        prefs(applicationContext).edit()
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_TEXT, text)
            .putBoolean(KEY_LAST_OK, ok)
            .apply()
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

    private fun notify(id: Int, title: String, text: String) {
        val context = applicationContext
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
}
