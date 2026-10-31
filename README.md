<p align="center"><img src="icon-preview.png" width="128" alt="WAP Guard icon"></p>

# WAP Guard

A tiny Android app that cleans up after the **"SMS of Death"** ([CVE-2026-58882](https://smsofdeath.com/)): a malformed WAP Push SMS that crashes `com.android.phone`, drops calls, and stays queued so the crash repeats until the message is removed.

WAP Guard **does not prevent the crash**, because only the Android security patch fixes the bug — the October 2026 bulletin, for Android 14–17. What it does is remove the stuck messages from the pending SMS queue so the problem doesn't keep coming back, and so the phone is less likely to end up in a reboot loop.

On **Android 12 and 13 there is no fix**: they run the same code and are out of support, so cleanup is the only mitigation short of upgrading.

## How it works

Through [Shizuku](https://shizuku.rikka.app/) it runs, as the shell user, the same recovery steps the researcher published for ADB:

1. Find rows in `content://sms/raw` whose destination port is WAP Push (2948) and that have been **stuck for more than 2 minutes**. Legitimate WAP Push (MMS notifications, carrier provisioning) leaves the queue within milliseconds, so a message still being delivered is never touched.
2. On **Clear**: read the shell's current `WRITE_SMS` app-op, temporarily allow it, delete exactly those row IDs, **restore the previous mode** (even if the delete fails), then re-query to verify they're gone. Only those IDs — narrower than the `address='<SENDER>'` delete in the published ADB steps, and it doesn't need to know the sender.

A row's age can't simply be read off its `date` column. That column holds the PDU's service-centre timestamp, not a local receipt time — `InboundSmsHandler` passes `sms.getTimestampMillis()` straight into the row — so the sender's SMSC controls it, and a poison message dated years ahead would never look stuck. Rows dated further ahead than a minute of clock skew are therefore timed from when WAP Guard **first saw them** on the local clock instead.

Every shell command has a timeout, and both its exit code and its output are checked. A `WRITE_SMS` grant left behind by a run that was killed mid-clean is revoked on the next check, since `finally` doesn't run when the process dies.

The main screen also compares the device's security patch level with the first fixed level (2026-10-01), and reports Android 12–13 as unpatchable. This is a quick indicator, not a code-level test: a vendor could backport the fix earlier.

## Background checking

One exclusive choice, **Off** by default:

| | Interval | Cost |
|---|---|---|
| **Off** | — | Scans when you open the app |
| **15 min** | WorkManager's floor, longer under Doze | None to speak of |
| **30 s** | Watch mode, a foreground service | Ongoing notification, more battery |

One picker rather than two toggles, because the intervals aren't additive: `WRITE_SMS` is global shell state rather than per-caller, so two cleans running at once fight over it — one restores the previous mode while the other is still deleting, and that delete fails. Cleans are serialised on a lock for the same reason, since the manual **Clear** button can overlap a background check.

Polling is the only mechanism available. There is no event to hook: `WapPushOverSms.dispatchWapPdu` calls `decodeWapPdu` before it builds the `WAP_PUSH_DELIVER_ACTION` intent, so the decode throws inside `com.android.phone` and **no broadcast is ever dispatched** for a poison message. The poll interval is therefore the exposure window, which is the whole reason Watch mode exists — 15 minutes of crash-looping versus one or two crashes.

Whichever you pick, opening the app cleans immediately rather than waiting for the next check.

## If it's happening right now

Clearing the queue ends it. The rows are only dangerous because they sit in `content://sms/raw` and get retried on every boot, so once they're deleted the loop is broken — nothing is left corrupted, and no further cleanup is needed unless more messages arrive.

The catch is that a phone restarting every couple of minutes may not stay up long enough for you to do anything, so it's worth knowing what cutting the radio buys you.

**Airplane mode, or pulling the SIM, stops new copies arriving.** A message the phone never acknowledges gets re-sent by the carrier — the research reports one message causing intermittent outages for up to a week. No radio means no delivery and no retries, which gives you a stable phone to work on.

What it doesn't do:

- **It doesn't clean anything.** Rows already queued are retried on every boot regardless of the radio, so if you're already looping you still have to clear them. Airplane mode blocks the next copy, not the one you already have.
- **The messages wait for you.** Whatever the carrier hasn't delivered stays on its side and arrives when you reconnect.
- **You have no service, including emergency calls.** That's what the attack was taking from you anyway, so treat it as a holding measure.
- **eSIM makes it harder.** You can't pull an eSIM, and disabling one means getting through Settings on a phone that keeps restarting.

Moving the SIM to a non-Android phone is worth considering: that device acknowledges the pending messages, so the carrier stops re-sending, and you can then clear the Android phone without fresh copies landing mid-cleanup. That follows from the retry behaviour rather than being a published step.

As an absolute last resort, `adb shell pm clear com.android.providers.telephony` empties the queue — along with **all your SMS, MMS and APN settings**. Clearing the specific rows, which is what this app does, is almost always enough.

## Limitations

**Nothing here survives a reboot**, because Shizuku doesn't either without root. After the first forced restart you have to re-pair wireless debugging; Watch mode notices the dead binder, switches itself off instead of draining the battery on checks that can only fail, and tells you.

So under a sustained attack the honest answer is that WAP Guard shortens each crash but can't stop it. The real fixes are the October 2026 patch, or your carrier filtering binary SMS to port 2948.

## Requirements

- Android 12+ with Shizuku running (wireless debugging is enough; root isn't needed)
- On Samsung: set the app's battery usage to *Unrestricted*, or background checking gets killed

## Build

```sh
./gradlew assembleRelease
./gradlew testReleaseUnitTest   # row parsing, staleness, app-op mode
```

Kotlin and Jetpack Compose with Material 3 Expressive, minSdk 31, compileSdk 36.

Releases are signed with the **debug key**, so this is a personal sideload build: the debug key is universally known, so don't treat an APK from here as authenticated — build it yourself if that matters to you.

## Credit

Vulnerability research and the ADB recovery procedure: Mark Blaszczyk, [smsofdeath.com](https://smsofdeath.com/).

## License

[MIT](LICENSE)
