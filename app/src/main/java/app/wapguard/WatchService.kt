package app.wapguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * How often Watch mode polls the queue. The OS gives a third-party app no event to hook — the decode throws
 * inside com.android.phone before WAP_PUSH_DELIVER_ACTION is ever built — so polling is the only option, and
 * the interval is the exposure window. 30s is short enough to end a crash loop after one or two crashes.
 */
internal const val WATCH_INTERVAL_MS = 30_000L

private const val WATCH_CHANNEL = "watch"
private const val WATCH_NOTIFICATION = 3
internal const val ACTION_STOP_WATCH = "app.wapguard.STOP_WATCH"

/**
 * Watch mode: a foreground service that clears stuck WAP Push rows every [WATCH_INTERVAL_MS], instead of
 * waiting for WorkManager's 15-minute floor. Off by default — it keeps a notification up and costs battery.
 *
 * It cannot survive a reboot, because Shizuku doesn't either without root. If the binder dies the service
 * stops rather than burning battery on checks that can only fail — but it leaves the Watch pref on, so the
 * choice survives for [BootReceiver] and [MainActivity] to resume once Shizuku is back.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    companion object {
        /**
         * Whether the service is live, for [watchStalled]. A foreground service keeps its process alive, so
         * this is only ever true while the service really is running: if the process dies the flag dies with
         * it, and the next read correctly says the service is gone.
         */
        @Volatile
        internal var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WATCH) {
            setWatch(this, false)
            return START_NOT_STICKY
        }
        if (!isWatch(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        post("Watching the SMS queue", "Checking every ${WATCH_INTERVAL_MS / 1000}s")
        running = true
        // onStartCommand runs again every time the app re-asserts the service; keep a single loop.
        if (loop == null) loop = scope.launch { watch() }
        // Not sticky: a relaunch with a null intent would only resume polling a Shizuku that a kill or reboot
        // already took away. Resuming is BootReceiver's and MainActivity's job, once access is real again.
        return START_NOT_STICKY
    }

    private suspend fun CoroutineScope.watch() {
        while (isActive) {
            val outcome = runCatching {
                check(currentAccess() == Access.Ready) { "Shizuku isn't running" }
                runCatching { revokeLeakedShellWriteSms() }
                clearWapPush(applicationContext)
            }
            outcome.fold(
                onSuccess = { removed ->
                    val text = if (removed == 0) "All clear" else "Removed $removed stuck message${if (removed == 1) "" else "s"}"
                    recordCheck(applicationContext, text, ok = true)
                    post("Watching the SMS queue", "$text · checked just now")
                    if (removed > 0) {
                        postNotification(
                            applicationContext, NOTIFY_CLEARED, "Malformed WAP Push cleared",
                            "Removed $removed stuck message${if (removed == 1) "" else "s"} from the SMS queue.",
                        )
                    }
                },
                onFailure = { e ->
                    val reason = e.message ?: "Unknown error"
                    recordCheck(applicationContext, "Watch failed: $reason", ok = false)
                    if (!Shizuku.pingBinder()) {
                        // Shizuku won't come back on its own, so stop instead of polling a dead binder. The
                        // pref stays on: Watch mode is paused, not something the user turned off.
                        postNotification(
                            applicationContext, NOTIFY_WATCH_STOPPED, "Watch mode is paused",
                            "$reason. Start Shizuku, then open WAP Guard to resume.",
                        )
                        stopSelf()
                        return
                    }
                    post("Watch hit an error", reason)
                },
            )
            delay(WATCH_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        loop = null
        super.onDestroy()
    }

    /** The ongoing foreground notification. Low importance and alert-once, so it stays silent. */
    private fun post(title: String, text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, "Watch mode", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, WatchService::class.java).setAction(ACTION_STOP_WATCH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, WATCH_NOTIFICATION, notification, type)
    }
}
