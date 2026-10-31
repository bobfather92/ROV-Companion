# Build and development

RŌV Companion is a native Java Android application. It ships without third-party runtime libraries. The current portable build scripts support **Windows x64** and use **Python 3.10+**, Java 17, Android API 36 and Android Build Tools 36.0.0.

## Build an APK

Run these commands from the repository root:

```powershell
python tools/bootstrap_build.py
python tools/build_apk.py
```

The bootstrap script downloads and verifies portable tools under `.tools`. The build compiles Android resources and Java, runs the six JVM suites, converts classes with D8, aligns the APK and signs it. Output for this version is `dist/ROV-Companion-1.0.0.apk`.

The build creates a local development signing key if `.tools/preview.keystore` is missing. A locally generated key differs from the distributed release certificate, so that APK cannot update an installed release in place. The release signing key is not included in the repository or source ZIP. Keep signing keys private and retain them for future compatible updates.

## Project layout

| Location | Role |
| --- | --- |
| `app/src/main/java/uk/co/rovcompanion/MainActivity.java` | Discovery flow, permissions and native controls |
| `app/src/main/java/uk/co/rovcompanion/RovService.java` | Service-owned session, notification actions and active-work wake lock |
| `app/src/main/java/uk/co/rovcompanion/RovBluetooth.java` | BLE discovery, GATT initialization, movement, looping and Stop state machine |
| `app/src/main/java/uk/co/rovcompanion/OperationQueue.java` | Serialized operations, cancellation and timeouts |
| `app/src/main/java/uk/co/rovcompanion/Protocol.java` | UUIDs, command encoding and validated travel ranges |
| `app/src/main/java/uk/co/rovcompanion/AutoConnectGate.java` | Remembered-device and advertised-service selection policy |
| `tests/` | JVM controller tests and separate Android instrumentation |
| `tools/` | Portable build, emulator and packaging scripts |

## Controller behavior

The service owns one controller using application context. The Activity observes it while visible; leaving or destroying the Activity does not end a started connection. Scanning pauses when the UI is hidden. A connected-device foreground notification exposes Stop and Disconnect; the service is not exported.

Every controller operation runs on the main looper. GATT operations are serialized and have bounded timeouts. Stop cancels unsent setup/movement operations, disables looping, sends Stop and waits for idle confirmation. Disconnect retains this sequence when movement is active.

Travel limits are app-side targets; physical calibration is not rewritten. Loop setup verifies a fresh position and idle status at its starting endpoint before enabling native looping. Callbacks from an old connection cannot revive a closed operation queue. The service uses `START_NOT_STICKY` and never replays movement after process death.

Background behavior follows Android's [BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [connected-device foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) and [notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Tests

`python tools/build_apk.py` runs six suites:

| Suite | Coverage |
| --- | --- |
| `ControlTests` | Protocol encoding, bounds, queue serialization, cancellation and timeouts |
| `ControllerFlowTests` | Stop recovery, stale callbacks and GATT property variations |
| `ScanFlowTests` | Advertising names/services, batched results, stale scans and scan failures |
| `LoopFlowTests` | Stop/restart, direction changes, fresh endpoint checks and setup cancellation |
| `QuietEndTests` | Smaller ranges, clearances and active-range locking |
| `LaunchAndRangeTests` | Auto-connect identity, asymmetric/full ranges and movement preservation |

Controller tests use deterministic fake Android/BLE primitives on API 31/36 hosts. They exercise production controller logic rather than a physical radio or motor.

### Native Android checks

The separate instrumentation APK runs the actual app's UI, service, notification and lifecycle on an Android 16 emulator. It injects readiness/movement states and an in-flight operation to test notification Stop dispatch without a physical GATT link. It checks normal and 1.3× text. Test instrumentation is not included in the distributed app.

Prepare the private emulator after bootstrapping the build tools:

```powershell
python tools/bootstrap_emulator.py
New-Item -ItemType Directory -Path .tools/android-user -Force | Out-Null
$env:ANDROID_AVD_HOME = Join-Path $PWD '.tools/avd'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_SDK_ROOT = Join-Path $PWD '.tools/emulator-sdk'
$env:ANDROID_ADB_SERVER_PORT = '5038'
& '.\.tools\emulator-sdk\emulator\emulator.exe' -avd ROV_Studio -port 5556 -no-window -no-audio -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader_indirect -feature -Vulkan -accel on -cores 2
```

Hardware virtualization and Windows Hypervisor Platform must be available. Once Android has booted, use a second terminal at the repository root:

```powershell
python tools/ui_walkthrough.py --output ui-1.0.0
python tools/ui_walkthrough.py --font-scale 1.3 --output ui-1.0.0-large
```

The runner addresses only `emulator-5556` on ADB port `5038`. It installs the app/test APK, clears the emulator app's settings, grants permissions and captures screenshots/video. Do not redirect it to a personal phone. The evidence is saved under `dist`.

### Release packaging

`tools/package_release.py` verifies the APK manifest, alignment, signature, class inventory and both UI evidence hashes, then packages source and documentation. It expects the existing release certificate, so a fresh developer key will not pass this maintainer-specific check.

It also needs the local analysis dependency:

```powershell
python -m pip install --target .tools/python androguard==4.1.4
python tools/package_release.py
```

Signing keys, downloaded tools, original Rhino binaries, generated builds and private hardware logs are excluded from the source archive.

## Physical testing and contributions

Report the app version, phone/Android version, slider model and exact steps. Distinguish simulated test results from real hardware tests. Changes to commands, travel calculations, Stop handling or service lifecycle should retain the existing regressions and add coverage for the behavior being changed.

For a physical acceptance check, test both directions, a smaller custom range and 0–100% travel, repeated loops after Stop, switching apps, lock/unlock, restoring controls and Stop from the notification. The current release's latest background/full-range behavior still needs that real-device confirmation.
