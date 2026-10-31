<p align="center"><img src="icon-preview.png" width="128" alt="WAP Guard icon"></p>

# WAP Guard

A tiny Android app that cleans up after the **"SMS of Death"** ([CVE-2026-58882](https://smsofdeath.com/)): a malformed WAP Push SMS that crashes `com.android.phone`, drops calls, and stays queued so the crash repeats until the message is removed.

WAP Guard **does not prevent the crash**, because only the Android security patch (November 2026) fixes the bug. What it does is remove the stuck messages from the pending SMS queue so the problem doesn't keep coming back, and so the phone is less likely to end up in a reboot loop.

## How it works

Through [Shizuku](https://shizuku.rikka.app/) it runs, as the shell user, the same recovery steps the researcher published for ADB:

1. Count rows in `content://sms/raw` whose destination port is WAP Push (2948).
2. On **Clear**: temporarily allow `WRITE_SMS` for the shell, delete only those rows, then reset the permission.

Ordinary pending SMS are left alone. With **Auto-clean** turned on, it checks every 15 minutes in the background while Shizuku is running and notifies you when it removes something.

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
