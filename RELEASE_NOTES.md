# RŌV Companion 1.0.0

Modern Android control for the Rhino RŌV Pro Everyday camera slider, with automatic connection, custom travel positions and background operation.

## Highlights

- **Open and connect.** Automatic discovery and connection to a compatible slider, with preference for a previously verified device.
- **Fresh controls.** A light interface, blue motion controls, a slider photo in the top-right corner, and an animated connection confirmation.
- **Choose your shot.** Adjustable speed, ease in/out, movement to either selected endpoint and looping between them.
- **Full travel or a custom section.** Set A and B from **0–100%**, with at least 10 percentage points between them. Saved ranges are retained; fresh installations start at 5–95%.
- **Keep filming while using your phone.** Connections and movement continue when switching apps or turning off the screen.
- **Stop from the notification.** An ongoing, silent notification provides movement status, Stop and Disconnect.
- **Keep your preferences.** Range, speed, easing, direction and Loop settings are stored on the phone.

Loop setup checks the starting endpoint before repeating a sequence. Stop cancels pending setup, and Disconnect requests a stop before closing an active connection. Saving travel limits does not move the slider.

## Install

Download **ROV-Companion-1.0.0.apk** from the release assets and install it on an Android 12+ phone with Bluetooth LE. This release updates existing Companion installations in place with the same signing certificate.

Close the original RŌV Motion app before connecting. Allow Nearby devices and precise foreground Location for compatibility discovery, and enable Bluetooth and Location when prompted. Allow notifications for background Stop/Disconnect controls.

## Validation and current limits

The signed APK passes signature and alignment checks. Six JVM suites and Android 16 emulator checks pass, including full-range control logic, loop restarts, service lifecycle, screen-off behavior, notification Stop dispatch and larger text rendering.

The emulator injects Bluetooth readiness and movement state. The latest full-range and background features still need confirmation on a physical phone/slider. Earlier versions were tested with a real RŌV Pro Everyday and Samsung S24 on Android 16. Other slider models are unverified.

Time-lapse, shutter triggering and phone camera features are not included. Full-range endpoints use physical calibrated travel; motor stopping noise may remain. Force-stopping the app terminates software control, and movement is never automatically restarted after process death or reboot.

## Release files

- `ROV-Companion-1.0.0.apk` — Android installer.
- `ROV-Companion-1.0.0-source.zip` — source and documentation, without signing keys or private diagnostics.
- `SHA256SUMS.txt` — APK and source archive checksums.

APK SHA-256:

```text
cf7fc14735ec4a126d2baaba9515ed79ad387cf4502660cbb885d28066fb266e
```

An independent project, unaffiliated with Rhino.
