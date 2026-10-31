package app.wapguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** WAP Push port. The raw table stores it OR'd with a 3GPP flag (e.g. 134020), so compare the low 16 bits. */
private const val WAP_PORT = 2948

internal enum class Access { NotRunning, NoPermission, Ready }

internal fun currentAccess() = when {
    !Shizuku.pingBinder() -> Access.NotRunning
    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Access.NoPermission
    else -> Access.Ready
}

/** Runs [cmd] as the shell user via Shizuku. newProcess is hidden in the 13.x API, hence reflection. */
private fun sh(cmd: String): String {
    val newProcess = Shizuku::class.java.getDeclaredMethod(
        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
    ).apply { isAccessible = true }
    val process = newProcess.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
    return process.inputStream.bufferedReader().readText().also { process.waitFor() }
}

/** Counts not-yet-processed WAP Push segments waiting in the telephony raw queue. */
internal fun pendingWapPush(): Int {
    val out = sh("content query --uri content://sms/raw --projection _id:destination_port --where \"deleted=0\"")
    val rows = out.lines().filter { it.startsWith("Row:") }
    if (rows.isEmpty() && "No result found" !in out) error(out.trim().ifEmpty { "Query failed" })
    return rows.count { row ->
        val port = Regex("destination_port=(-?\\d+)").find(row)?.groupValues?.get(1)?.toIntOrNull()
        port != null && port and 0xFFFF == WAP_PORT
    }
}

/** Mirrors the researcher's ADB recovery: briefly grant shell WRITE_SMS, delete WAP Push rows, revoke. */
internal fun clearWapPush() {
    sh(
        "cmd appops set com.android.shell WRITE_SMS allow; " +
            "content delete --uri content://sms/raw --where \"(destination_port & 65535)=$WAP_PORT\"; " +
            "cmd appops set com.android.shell WRITE_SMS default",
    )
}

private const val AUTO_WORK = "auto-clean"
private const val PREFS = "settings"
private const val KEY_AUTO = "auto"
private const val CHANNEL = "cleaned"

internal fun isAutoClean(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

internal fun setAutoClean(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    val work = WorkManager.getInstance(context)
    if (on) {
        val request = PeriodicWorkRequestBuilder<AutoCleanWorker>(15, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(AUTO_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    } else {
        work.cancelUniqueWork(AUTO_WORK)
    }
}

/** Every 15 min (Android's minimum): if Shizuku is up, clear stuck WAP Push rows and say so. */
class AutoCleanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A cold process receives the Shizuku binder asynchronously; skip this round if it never arrives.
        if (!awaitShizuku() || currentAccess() != Access.Ready) return Result.success()
        val cleared = runCatching {
            withContext(Dispatchers.IO) { pendingWapPush().also { if (it > 0) clearWapPush() } }
        }.getOrDefault(0)
        if (cleared > 0) notifyCleared(cleared)
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

    private fun notifyCleared(count: Int) {
        val context = applicationContext
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cleaned messages", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Malformed WAP Push cleared")
            .setContentText("Removed $count stuck message${if (count == 1) "" else "s"} from the SMS queue.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }
}
