# 1.0.0 — full travel and background control

31 October 2026. Package `uk.co.rovcompanion`, version code 6, minimum API 31, target API 36. Direct-install artifact: `dist/ROV-Companion-1.0.0.apk`.

## Changes

- A/B controls and protocol validation accept 0–100%, minimum span 10 percentage points. Full travel targets are exactly zero and reported calibrated travel. Small step-rounding tolerance prevents false positioning timeouts without widening targets. Saved ranges and 5–95% fresh-install defaults remain.
- Slider photo moved to the main header's upper-right corner. Connection photo reveal retained. Endpoint seekbar thumbs stay fully visible at 0/100%.
- `RovService` owns the controller using application context. The Activity attaches while visible and detaches when hidden. Binding destruction does not end a started connection. Discovery alone pauses; connected operation, setup, looping and status polling continue.
- Connected-device foreground type and permissions, silent ongoing notification, immutable explicit service Stop/Disconnect PendingIntents. Disconnect retains the Stop/idle sequence. A time-bounded partial CPU wake lock renews during active work and releases when idle/disconnected. Foreground status ends on disconnect/error. Service is not exported, survives task removal, and uses `START_NOT_STICKY` to prevent motion replay after process death.
- Notification permission requested once, with a settings link for later changes. Denial does not block foreground service use. No background Location, Internet or battery-exemption permission added.
- Version/settings/diagnostics/artifact name updated to 1.0.0; prior signing key retained for upgrade compatibility.

## Validation

Build: six JVM suites passed, covering full-range single moves, full-range loops and reverse restarts, step rounding near B, configuration without motor writes, active range locks and discovery pause preserving movement. Existing queue/Stop/scan/loop/clearance regressions remain covered on API 31/36.

Android 16 emulator checks at normal and 1.3× text verify launch discovery, automatic connection attempt, foreground promotion through scan-to-connect, no premature tick, native photo/tick and controls, upper-right image, 0/100% save/persistence, endpoint thumb fit, Home and screen-off behavior, same service/controller after Activity destruction/recreation, notification Stop reaching the controller while hidden, idle wake-lock release, and disconnect notification cleanup.

Lifecycle, service, wake lock, notification, PendingIntent and UI rendering use the actual Android OS. BLE discovery uses Android, but a matching advertisement and READY/MOVING states are injected. A queue barrier lets the production Stop method queue commands without physical GATT; idle confirmation is injected. These checks do not prove physical motor Stop delivery or Samsung-specific Bluetooth persistence.

Signature, alignment, manifest and absence of test/fake classes are checked separately. Source archive excludes the signing key, SDK downloads, original Rhino binary, private hardware logs and generated builds. Final artifact/evidence metadata is in `dist/validation-1.0.0.json`.

## Physical acceptance

On the S24/real slider: choose 0–100% and a smaller custom range, test both directions and repeated loops, switch apps, lock/unlock, reopen controls, and use notification Stop. Earlier user-confirmed movement/loop/clearance behavior remains the baseline. Force-stop terminates software control; movement is never replayed after process death. Motor stopping noise is unchanged by this work.

This is a packaged direct-install release. No app-store publication or new physical-device test was performed here.
