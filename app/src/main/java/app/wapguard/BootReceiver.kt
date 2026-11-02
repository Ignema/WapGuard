package app.wapguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import rikka.shizuku.Shizuku

/**
 * Watch mode is a foreground service, so a reboot or an app update kills it. The user's choice stays in
 * prefs either way: losing it silently is worse than carrying it across, and [watchStalled] uses the gap
 * between "wanted" and "running" to say *paused* rather than letting the picker claim a dead service is
 * still checking every 30s.
 *
 * Restarting the service here only succeeds when Shizuku is already up, which on a phone without root it is
 * not — Shizuku needs a fresh ADB connection after every boot and cannot start itself, since Wireless
 * debugging resets to off and WRITE_SECURE_SETTINGS is signature-level. So in the common case the real work
 * of this receiver is one actionable notification instead of silence; [MainActivity] resumes Watch mode once
 * Shizuku is back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!isWatch(context) || WatchService.running) return
        // A cold process has not been handed the Shizuku binder yet, so this is only true with root-started
        // Shizuku. onReceive cannot wait for it: a receiver has a ~10s budget, and starting the service later
        // from a worker would be a background FGS start and refused outright.
        if (Shizuku.pingBinder()) {
            // BOOT_COMPLETED may start a specialUse service; MY_PACKAGE_REPLACED has no such exemption.
            if (runCatching { setWatch(context, true) }.isSuccess) return
        }
        recordCheck(context, "Watch mode paused by the restart", ok = false)
        postNotification(
            context,
            NOTIFY_WATCH_STOPPED,
            "Watch mode is paused",
            "Start Shizuku, then open WAP Guard to resume checking every ${WATCH_INTERVAL_MS / 1000}s.",
        )
    }
}
