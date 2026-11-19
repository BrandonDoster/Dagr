![Dagr](docs/dagr_banner.png)

# Dagr

Alarm and timer app for Android. Group alarms, skip a single day, and set a timer in one tap.

## Features

**Alarms**
- Repeat days, one-time or a specific date, name, sound (system tone or your own file), volume with optional fade-in, vibration pattern
- Snooze interval (3–30 min or custom) and repeat count (1–5 or forever); ±5 min on the ring screen
- **Groups**: switch a whole set of alarms on or off at once
- **Skip once**: switch off a repeating alarm and tap *Turn back on for tomorrow*; it skips the next ring and turns itself back on
- Upcoming-alarm notification (5/15/30 min ahead) with *Turn off for today*
- Full-screen ring over the lock screen, heads-up when the phone is in use
- Rings after a reboot before the first unlock, with your chosen sound
- Optional read-aloud of time and name

**Timer**
- Pick a time on the wheels and tap Start
- Presets, up to 20 timers at once, live countdown in the notification
- Works with Google Assistant and other apps that send `SET_TIMER`

## Requirements

Android 17 (API 37). Built and tested on a Pixel 11 Pro.

## Install

1. Download `dagr-<version>.apk` from [Releases](../../releases) and open it on the phone.
2. Allow notifications when asked. If the app shows a red warning card, tap **Fix**.

Check a download against the release's `SHA256SUMS`. Release APKs are signed with this certificate (Android refuses updates signed with anything else):

```
SHA-256: 93:79:C5:4D:A0:1C:60:A5:1B:9A:25:FF:13:BE:B2:F2:22:C5:CA:47:BB:08:29:9C:FF:F1:C5:DD:DD:47:2D:98
```

## Build

Needs JDK 21 and the Android SDK with `platforms;android-37.0`.

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pushing a `vMAJOR.MINOR.PATCH` tag builds, signs and publishes a release. A debug build can't install over a release build or the other way around; uninstalling deletes all alarms.

Local release builds read `keystore.properties` (not in git):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```
