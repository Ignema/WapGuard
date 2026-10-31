<p align="center"><img src="icon-preview.png" width="128" alt="WAP Guard icon"></p>

# WAP Guard

A tiny Android app that cleans up after the **"SMS of Death"** ([CVE-2026-58882](https://smsofdeath.com/)): a malformed WAP Push SMS that crashes `com.android.phone`, drops calls, and stays queued so the crash repeats until the message is removed.

WAP Guard **does not prevent the crash**, because only the Android security patch (November 2026) fixes the bug. What it does is remove the stuck messages from the pending SMS queue so the problem doesn't keep coming back, and so the phone is less likely to end up in a reboot loop.

## How it works

Through [Shizuku](https://shizuku.rikka.app/) it runs, as the shell user, the same recovery steps the researcher published for ADB:

1. Find rows in `content://sms/raw` whose destination port is WAP Push (2948) and that have been **stuck for more than 2 minutes**. Legitimate WAP Push (MMS notifications, carrier provisioning) leaves the queue within milliseconds, so a message still being delivered is never touched.
2. On **Clear**: read the shell's current `WRITE_SMS` app-op, temporarily allow it, delete exactly those row IDs, **restore the previous mode** (even if the delete fails), then re-query to verify they're gone.

Every shell command has a timeout, and both its exit code and its output are checked. With **Auto-clean** turned on it checks roughly every 15 minutes; Android can delay this under Doze. The last result shows on the main screen, and you get a notification when something is removed **or when checks start failing**, for example after a reboot when Shizuku isn't running.

The main screen also compares the device's security patch level with the first fixed level (2026-11-01). This is a quick indicator, not a code-level test: a vendor could backport the fix earlier.

## Requirements

- Android 12+ with Shizuku running (wireless debugging is enough; root isn't needed)
- On Samsung: set the app's battery usage to *Unrestricted* so the background check isn't killed

## Build

```sh
./gradlew assembleRelease
```

Kotlin and Jetpack Compose with Material 3 Expressive, minSdk 31, compileSdk 36.

## Credit

Vulnerability research and the ADB recovery procedure: Mark Blaszczyk, [smsofdeath.com](https://smsofdeath.com/).
