# RŌV Companion

**Modern Android control for the Rhino RŌV Pro Everyday camera slider.**

RŌV Companion is an independent Android application built to keep an existing camera slider useful on newer phones. It connects directly over Bluetooth Low Energy and provides a fresh control panel for movement, speed, easing and repeatable shots.

The project began when the original RŌV Motion app became unreliable on a newer Android phone. Companion provides an alternative controller with automatic connection, adjustable travel limits and background operation.

**Version 1.0.0 · Android 12+ · Bluetooth LE · Works offline

## Features

- **Automatic discovery and connection.** Open the app with your slider switched on. Companion remembers a previously verified slider and prefers it on later connections.
- **A clear connection flow.** A slider photo and animated tick confirm a successful connection before the controls appear.
- **Movement in either direction.** Move to A or B with adjustable speed and ease in/out.
- **Custom travel limits.** Set A and B anywhere from 0–100% of the calibrated rail, or leave clearance at either end.
- **Repeatable loops.** Loop between your selected positions, with starting-position checks when restarting a sequence.
- **Background operation.** Keep the connection and movement active when switching apps or turning off the screen.
- **Notification controls.** Stop movement or disconnect without returning to the app.
- **Saved settings and local diagnostics.** Keep your preferred range and controls, and copy connection logs when troubleshooting.

## Compatibility

| Requirement | Support |
| --- | --- |
| Android | Android 12 / API 31 or later; targets Android 16 / API 36 |
| Phone hardware | Bluetooth Low Energy |
| Slider | Developed for the Rhino RŌV Pro Everyday |
| Other RŌV models | Compatibility has not been verified |
| Accounts or Internet | Neither is required by the app |

Earlier versions were tested with a real RŌV Pro Everyday and a Samsung S24 running Android 16. Version 1.0.0 has passed software and emulator checks; its latest full-range and background changes still need confirmation on the physical phone and slider.

## Install and connect

1. Download **[ROV-Companion-1.0.0.apk](dist/ROV-Companion-1.0.0.apk)** onto your phone. On GitHub, open the APK file and choose **Download raw file**.
2. Install the APK, approving Android's installation prompt when requested. Existing Companion installations can update in place using this release's signing certificate.
3. Close the original RŌV Motion app and switch on the slider.
4. Open Companion. Allow **Nearby devices** and **precise Location while using the app**, and enable Bluetooth and the Location switch when prompted.
5. Allow **notifications** to use Stop and Disconnect from the notification drawer or lock screen.
6. Wait for the connected tick and control panel. Connecting alone does not start the motor.

If the slider is not found, use **Try again** or **Connection help**. The settings menu also lets you reconnect or choose a different slider. Discovery runs for up to 20 seconds per attempt.

## Set up your shot

Tap **Adjust** on the Travel Range card to choose A and B. The default range is **5–95%**, and existing saved ranges are preserved when updating. Select **0–100%** to use the full calibrated travel. The positions must be at least 10 percentage points apart.

Saving positions does not move the carriage. Select **To A** or **To B**, set speed and easing, enable **Loop** if wanted, then press **Start**. The action changes to **Stop** during setup and movement.

For a loop, Companion first moves to the opposite selected endpoint and confirms that it is idle there before beginning the repeating sequence. Range and motion settings are locked while a move is active.

**0% and 100% are the physical calibrated endpoints.** Choose a smaller range when you want clearance. Travel limits do not remove noise caused by the motor stopping.

## Keep moving in the background

Once connected, you can switch apps or lock the phone while the slider continues its current operation. An ongoing, silent notification shows its status and provides **Stop** during setup or movement, plus **Disconnect**. Disconnect requests a stop before closing an active connection.

Return to Companion to restore the current controls. If notifications were denied, enable them through **Slider settings → Notification settings**.

Use the app's Stop or Disconnect action to finish a session. Android force-stop and the system active-app Stop button terminate software control. Companion never automatically restarts a movement after process death or a phone reboot.

## Permissions and privacy

| Permission | Purpose |
| --- | --- |
| Nearby devices / Bluetooth scan and connect | Discover and communicate with the slider |
| Precise Location while using the app | Compatibility discovery for the slider's BLE advertisements; GPS coordinates are not collected |
| Notifications | Display connection status and background Stop/Disconnect controls |
| Foreground service and wake lock | Maintain connected-device operation and monitor active movement when the screen is off
