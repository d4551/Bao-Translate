# Android Bug Reporting Guide

Thank you for helping improve Bao Translate. A complete Android bug report gives maintainers the logs, device state, and system diagnostics needed to investigate crashes, audio routing failures, Bluetooth issues, model download problems, and on-device inference errors.

This guide covers two ways to capture a report:

- The recommended on-device flow for most users.
- The `adb` command-line flow for developers and advanced testers.

Before sharing a report publicly, review it for personal information. Android bug reports can include app names, device metadata, system logs, and recent diagnostic events.

## Recommended: Capture A Full Report On Device

Capture the report immediately after reproducing the issue.

### 1. Enable Developer Options

1. Open the Android **Settings** app.
2. Tap **About phone**.
3. Tap **Build number** seven times.
4. Confirm that Android shows the developer-options confirmation message.

### 2. Take The Bug Report

1. Return to **Settings**.
2. Open **Developer options**. On some devices it appears under **System**.
3. Tap **Take bug report**.
4. Select **Full report**.
5. Tap **Report**.

### 3. Share The Report

1. Wait for the **Bug report captured** notification.
2. Tap the notification.
3. Save or share the generated `.zip` file.
4. Attach it to the issue or share a restricted cloud-storage link.

## Advanced: Capture With ADB

Use this option when USB debugging is enabled and the device is connected to your computer.

### Single Connected Device

```bash
adb bugreport ./bao-translate-bugreport
```

### Multiple Connected Devices

```bash
adb devices
adb -s <device_serial_number> bugreport ./bao-translate-bugreport
```

### Retrieve A Saved Device Report

```bash
adb shell ls /bugreports/
adb pull /bugreports/<bug_report_filename.zip>
```

## What To Include In The Issue

- Device model and Android version.
- Bao Translate app version or commit SHA.
- Whether the device was online or offline.
- Which models were downloaded.
- Source and target languages.
- Audio route used, such as phone speaker, wired headset, or Bluetooth headset.
- Exact steps that reproduce the issue.
- Expected behavior and actual behavior.
- Screenshots or screen recordings when they help explain the problem.
- The bug report `.zip` or a link to it.

## What The File Contains

The generated `.zip` usually includes a `bugreport-*.txt` file with logcat output, system-service diagnostics, crash information, and device state. This is often the most useful artifact for engineering investigation.
