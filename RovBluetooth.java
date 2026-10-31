package uk.co.rovcompanion;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Service-owned controller; every operation and state transition runs on the main looper. */
@SuppressLint("MissingPermission")
final class RovBluetooth {
    enum State { DISCONNECTED, SCANNING, CONNECTING, READY, CONFIGURING, POSITIONING, MOVING, STOPPING, ERROR }
    interface Listener {
        void changed();
        void found(NearbyDevice device);
        void log(String line);
    }
    static final class NearbyDevice {
        final BluetoothDevice device;
        final String name, address;
        final int rssi;
        final boolean possibleRov, advertisesRov;
        NearbyDevice(BluetoothDevice device, String name, int rssi, boolean possibleRov, boolean advertisesRov) {
            this.device = device; this.name = name; this.address = device.getAddress();
            this.rssi = rssi; this.possibleRov = possibleRov;
            this.advertisesRov = advertisesRov;
        }
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scanDeadline;
    private int scanGeneration;
    private final Map<String, String> scanDescriptions = new HashMap<>();
    private BluetoothGatt gatt;
    private OperationQueue queue;
    private int motionToken;
    private long moveAt;
    private boolean stopAcknowledged, disconnectAfterStop, statusReadable;
    private Runnable connectionDeadline, stopDeadline, positioningDeadline;
    private static final class LoopSetup {
        final int token, start, target, speed, ramp, tolerance;
        boolean checking, positioningSent;
        LoopSetup(int token, int start, int target, int speed, int ramp, int tolerance) {
            this.token = token; this.start = start; this.target = target; this.speed = speed; this.ramp = ramp;
            this.tolerance = tolerance;
        }
    }
    private LoopSetup pendingLoop;
    State state = State.DISCONNECTED;
    String message = "Power on your slider, then scan.";
    int travel, position = -1, minSpeed = 256, maxSpeed = 900, lastStatus;
    int endClearancePercent = Protocol.DEFAULT_END_CLEARANCE;
    int rangeStartPercent=Protocol.DEFAULT_END_CLEARANCE, rangeEndPercent=100-Protocol.DEFAULT_END_CLEARANCE;

    RovBluetooth(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    boolean enabled() { return adapter != null && adapter.isEnabled(); }
    boolean setEndClearancePercent(int percent) {
        if (percent < Protocol.MIN_END_CLEARANCE || percent > Protocol.MAX_END_CLEARANCE) {
            throw new IllegalArgumentException("Invalid end clearance");
        }
        return setWorkingRange(percent,100-percent);
    }
    boolean setWorkingRange(int startPercent,int endPercent) {
        Protocol.workingRange(10000,startPercent,endPercent);
        if (state != State.READY && state != State.DISCONNECTED && state != State.ERROR) return false;
        rangeStartPercent=startPercent; rangeEndPercent=endPercent;
        endClearancePercent=Math.min(startPercent,100-endPercent);
        logQuietRange();
        listener.changed();
        return true;
    }
    Protocol.TravelRange currentRange() { return Protocol.workingRange(travel,rangeStartPercent,rangeEndPercent); }
    private void logQuietRange() {
        if (travel < 3) return;
        Protocol.TravelRange range = currentRange();
        listener.log("Working travel: " + range.left + ".." + range.right + " / " + travel
            + " steps; A=" + rangeStartPercent + "% B=" + rangeEndPercent + "%");
    }
    private void setState(State next, String detail) {
        state = next; message = detail; listener.log(next + ": " + detail); listener.changed();
    }
    void scan() {
        closeLink();
        if (!enabled()) { setState(State.ERROR, "Enable Bluetooth, then scan again."); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setState(State.ERROR, "Bluetooth scanner is unavailable."); return; }
        scanDescriptions.clear();
        final int generation = ++scanGeneration;
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { receiveScan(generation, result); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                if (results != null) for (ScanResult result : results) receiveScan(generation, result);
            }
            @Override public void onScanFailed(int code) {
                handler.post(() -> {
                    if (generation != scanGeneration || state != State.SCANNING) return;
                    stopScan(code == 6 ? "Android paused scanning after repeated attempts. Wait 30 seconds, then scan."
                        : "Bluetooth scan failed (" + code + "). Toggle Bluetooth and try again.");
                });
            }
        };
        scanDeadline = () -> {
            if (generation != scanGeneration || state != State.SCANNING) return;
            stopScan(scanDescriptions.isEmpty()
                ? "No Bluetooth devices found. Close the original app, restart the slider, then scan again."
                : "Scan finished. " + scanDescriptions.size() + " nearby devices found. Select your slider below.");
        };
        setState(State.SCANNING, "Scanning all nearby Bluetooth devices for 20 seconds…");
        try {
            // Do not require a service UUID in advertising: GATT services are checked after selection.
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
            listener.log("Scan start: unfiltered, foreground, low latency, 20 seconds");
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            handler.postDelayed(scanDeadline, 20000);
        } catch (RuntimeException error) { fault("Scan failed: " + error.getMessage()); }
    }
    private void receiveScan(int generation, ScanResult result) {
        handler.post(() -> {
            if (generation != scanGeneration || state != State.SCANNING || result == null) return;
            try {
                ScanRecord record = result.getScanRecord();
                String name = record == null ? null : record.getDeviceName();
                if (name == null || name.trim().isEmpty()) name = displayName(result.getDevice());
                String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").toLowerCase(Locale.UK);
                boolean likely = normalized.contains("rov") || normalized.contains("rhino");
                String services = record == null || record.getServiceUuids() == null ? "[]" : record.getServiceUuids().toString();
                likely |= services.toLowerCase(Locale.UK).contains("bd08");
                boolean advertisesRov=services.toLowerCase(Locale.UK).contains(Protocol.CARRIAGE.toString());
                NearbyDevice device = new NearbyDevice(result.getDevice(), name, result.getRssi(), likely, advertisesRov);
                String summary = name + " services=" + services + " connectable=" + result.isConnectable();
                String previous = scanDescriptions.put(device.address, summary);
                if (!summary.equals(previous)) {
                    listener.log("Found " + device.address + " " + device.rssi + "dBm " + summary);
                    message = "Scanning… " + scanDescriptions.size() + " nearby devices. Choose your ROV below.";
                    listener.changed();
                }
                listener.found(device);
            } catch (RuntimeException error) { listener.log("Could not read advertisement: " + error.getMessage()); }
        });
    }
    private void stopScan(String detail) {
        ++scanGeneration;
        if (scanDeadline != null) handler.removeCallbacks(scanDeadline);
        if (scanner != null) {
            try { if (scanCallback != null) scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
            scanner = null;
        }
        scanCallback = null;
        if (state == State.SCANNING) setState(State.DISCONNECTED, detail);
    }
    void connect(BluetoothDevice device) {
        stopScan(""); closeLink();
        travel = 0; position = -1; minSpeed = 256; maxSpeed = 900; lastStatus = -1;
        setState(State.CONNECTING, "Connecting to " + displayName(device) + "…");
        queue = new OperationQueue(new OperationQueue.Clock() {
            public void later(Runnable task, long ms) { handler.postDelayed(task, ms); }
            public void cancel(Runnable task) { handler.removeCallbacks(task); }
        }, this::fault);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fault("Could not open Bluetooth connection."); return; }
            connectionDeadline = () -> { if (state == State.CONNECTING) fault("Connection setup timed out. Scan to retry."); };
            handler.postDelayed(connectionDeadline, 20000);
        } catch (RuntimeException error) { fault("Connection failed: " + error.getMessage()); }
    }
    static String displayName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? "Unnamed Bluetooth device" : name;
    }
    private void dispatch(BluetoothGatt source, Runnable action) {
        handler.post(() -> { if (source == gatt) action.run(); });
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int status, int next) {
            dispatch(link, () -> {
                if (status != BluetoothGatt.GATT_SUCCESS || next == BluetoothProfile.STATE_DISCONNECTED) {
                    fault("Disconnected (Bluetooth status " + status + "). Scan to reconnect.");
                } else if (next == BluetoothProfile.STATE_CONNECTED && !link.discoverServices()) {
                    fault("Could not discover slider services.");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt link, int status) {
            dispatch(link, () -> {
                if (status != BluetoothGatt.GATT_SUCCESS) fault("Service discovery failed: " + status);
                else initialize();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic c, byte[] value, int status) {
            byte[] copy = value.clone();
            dispatch(link, () -> reply("r:" + c.getUuid(), status, copy));
        }
        @Override public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = c.getValue();
                byte[] copy = value == null ? null : value.clone();
                dispatch(link, () -> reply("r:" + c.getUuid(), status, copy));
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt link, BluetoothGattCharacteristic c, int status) {
            dispatch(link, () -> reply("w:" + c.getUuid(), status, null));
        }
        @Override public void onDescriptorWrite(BluetoothGatt link, BluetoothGattDescriptor d, int status) {
            dispatch(link, () -> reply("n:" + d.getCharacteristic().getUuid(), status, null));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic c, byte[] value) {
            byte[] copy = value.clone();
            dispatch(link, () -> notification(c.getUuid(), copy));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = c.getValue();
                byte[] copy = value == null ? null : value.clone();
                dispatch(link, () -> notification(c.getUuid(), copy));
            }
        }
    };
    private void reply(String key, int status, byte[] value) {
        listener.log(key + " → " + status);
        if (queue != null) queue.reply(key, status == BluetoothGatt.GATT_SUCCESS, value);
    }
    private BluetoothGattCharacteristic characteristic(UUID id) {
        if (gatt == null) return null;
        BluetoothGattService service = gatt.getService(Protocol.CARRIAGE);
        return service == null ? null : service.getCharacteristic(id);
    }
    private void read(UUID id, boolean required, OperationQueue.Result result) {
        if (queue == null) return;
        queue.add(new OperationQueue.Operation("r:" + id, required, () -> {
            BluetoothGattCharacteristic c = characteristic(id);
            return c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0
                && gatt.readCharacteristic(c);
        }, result));
    }
    private void write(UUID id, byte[] value, boolean required, Runnable complete) {
        if (queue == null) return;
        queue.add(new OperationQueue.Operation("w:" + id, required, () -> {
            BluetoothGattCharacteristic c = characteristic(id);
            if (c == null) return false;
            int type;
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            } else if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            } else return false;
            StringBuilder hex = new StringBuilder();
            for (byte b : value) hex.append(String.format(java.util.Locale.UK, "%02x ", b & 255));
            listener.log("Write " + id + " = " + hex + " type=" + type);
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.writeCharacteristic(c, value, type)
                    == BluetoothStatusCodes.SUCCESS;
            }
            c.setWriteType(type);
            c.setValue(value);
            return gatt.writeCharacteristic(c);
        }, ignored -> complete.run()));
    }
    private void subscribe(Runnable complete) {
        if (queue == null) return;
        queue.add(new OperationQueue.Operation("n:" + Protocol.STATUS, !statusReadable, () -> {
            BluetoothGattCharacteristic c = characteristic(Protocol.STATUS);
            if (c == null || !gatt.setCharacteristicNotification(c, true)) return false;
            BluetoothGattDescriptor descriptor = c.getDescriptor(Protocol.CCCD);
            if (descriptor == null) return false;
            byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
            descriptor.setValue(value);
            return gatt.writeDescriptor(descriptor);
        }, ignored -> complete.run()));
    }
    private void initialize() {
        for (UUID id : new UUID[] {Protocol.COMMAND, Protocol.TARGET, Protocol.SPEED, Protocol.RAMP, Protocol.STATUS, Protocol.MAX_STEPS}) {
            if (characteristic(id) == null) { fault("Slider is missing required control " + id); return; }
        }
        for (BluetoothGattCharacteristic c : gatt.getService(Protocol.CARRIAGE).getCharacteristics()) {
            listener.log("Characteristic " + c.getUuid() + " properties=0x" + Integer.toHexString(c.getProperties()));
        }
        statusReadable = (characteristic(Protocol.STATUS).getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
        read(Protocol.MAX_STEPS, true, value -> {
            int reportedTravel = Protocol.uintValue(value);
            Protocol.workingRange(reportedTravel,rangeStartPercent,rangeEndPercent);
            travel = reportedTravel;
        });
        read(Protocol.MIN_SPEED, false, value -> { if (value != null) minSpeed = Protocol.uintValue(value); });
        read(Protocol.MAX_SPEED, false, value -> { if (value != null) maxSpeed = Protocol.uintValue(value); });
        read(Protocol.POSITION, false, value -> { if (value != null) position = Protocol.uintValue(value); });
        subscribe(() -> {
            if (statusReadable) read(Protocol.STATUS, true, value -> finishInitialize(Protocol.uintValue(value)));
            else finishInitialize(lastStatus);
        });
    }
    private void finishInitialize(int status) {
        if (minSpeed < 1 || maxSpeed < minSpeed || maxSpeed > 100000) throw new IllegalArgumentException("Invalid speed limits");
        handler.removeCallbacks(connectionDeadline);
        logQuietRange();
        setState(State.READY, "Connected. Choose speed and direction.");
        if (status >= 0) acceptStatus(status);
    }
    void move(boolean right, int percent, int ramp, boolean loop) {
        if (state != State.READY) return;
        if (ramp < 0 || ramp > 50) throw new IllegalArgumentException("Invalid ramp");
        int speed = Protocol.speedForPercent(percent, minSpeed, maxSpeed);
        Protocol.TravelRange range = currentRange();
        logQuietRange();
        int token = ++motionToken;
        handler.removeCallbacks(poll);
        queue.clearPending();
        pendingLoop = null;
        if (loop) {
            LoopSetup setup = new LoopSetup(token, right ? range.left : range.right,
                right ? range.right : range.left, speed, ramp, range.tolerance);
            pendingLoop = setup;
            setState(State.CONFIGURING, "Resetting loop; checking starting end…");
            positioningDeadline = () -> {
                if (loopSetupCurrent(setup)) fault("Could not confirm the loop's starting end. Check the slider and reconnect.");
            };
            long estimate = (long)travel * 1000L * (100L + ramp) / (100L * speed);
            handler.postDelayed(positioningDeadline, Math.max(10000L, estimate + 20000L));
            // Restore the original app's endpoint-first setup after an arbitrary stopped position.
            write(Protocol.COMMAND, Protocol.command(Protocol.LOOP_OFF), true, () -> {
                if (!loopSetupCurrent(setup)) return;
                read(Protocol.POSITION, true, value -> {
                    if (!loopSetupCurrent(setup)) return;
                    position = checkedPosition(value);
                    listener.log("Loop setup: position=" + position + " start=" + setup.start + " target=" + setup.target);
                    if (Math.abs(position - setup.start) <= setup.tolerance) {
                        verifyLoopStart(setup);
                    } else {
                        positionForLoop(setup);
                    }
                });
            });
        } else {
            setState(State.CONFIGURING, "Setting up move…");
            sendMove(right ? range.right : range.left, speed, ramp, false, token, false);
        }
    }
    private int checkedPosition(byte[] value) {
        int current = Protocol.uintValue(value);
        if (current < 0 || current > travel) throw new IllegalArgumentException("Position outside device travel");
        return current;
    }
    private boolean loopSetupCurrent(LoopSetup setup) {
        return setup != null && pendingLoop == setup && setup.token == motionToken
            && (state == State.CONFIGURING || state == State.POSITIONING);
    }
    private void positionForLoop(LoopSetup setup) {
        if (!loopSetupCurrent(setup) || setup.positioningSent) return;
        setup.positioningSent = true;
        sendMove(setup.start, setup.speed, setup.ramp, false, setup.token, true);
    }
    private void verifyLoopStart(LoopSetup setup) {
        if (!loopSetupCurrent(setup) || setup.checking) return;
        setup.checking = true;
        read(Protocol.POSITION, true, value -> {
            if (!loopSetupCurrent(setup)) return;
            position = checkedPosition(value);
            if (Math.abs(position - setup.start) > setup.tolerance) {
                setup.checking = false;
                if (!setup.positioningSent) positionForLoop(setup);
                schedulePoll();
                return;
            }
            if (statusReadable) {
                read(Protocol.STATUS, true, statusValue -> {
                    if (!loopSetupCurrent(setup)) return;
                    int status = Protocol.uintValue(statusValue);
                    lastStatus = status;
                    listener.log("Loop start verified: position=" + position + " status=0x" + Integer.toHexString(status));
                    setup.checking = false;
                    if ((status & Protocol.OVERCURRENT) != 0) fault("Slider reports a motor overcurrent error.");
                    else if (Protocol.idle(status)) beginFullLoop(setup);
                    else {
                        if (state == State.CONFIGURING) setState(State.POSITIONING, "Waiting for the loop's starting end to settle…");
                        schedulePoll();
                    }
                });
            } else {
                setup.checking = false;
                if (Protocol.idle(lastStatus)) beginFullLoop(setup);
                else {
                    if (state == State.CONFIGURING) setState(State.POSITIONING, "Waiting for the loop's starting end to settle…");
                    schedulePoll();
                }
            }
        });
    }
    private void beginFullLoop(LoopSetup setup) {
        if (!loopSetupCurrent(setup)) return;
        if (positioningDeadline != null) handler.removeCallbacks(positioningDeadline);
        handler.removeCallbacks(poll);
        queue.clearPending();
        pendingLoop = null;
        setState(State.CONFIGURING, "Starting loop between selected positions…");
        sendMove(setup.target, setup.speed, setup.ramp, true, setup.token, false);
    }
    private void sendMove(int target, int speed, int ramp, boolean loop, int token, boolean positioning) {
        if (token != motionToken || queue == null) return;
        Protocol.TravelRange range = currentRange();
        if (target < range.left || target > range.right) throw new IllegalArgumentException("Target outside working travel");
        write(Protocol.SPEED, Protocol.int32(speed), true, () -> {});
        write(Protocol.COMMAND, Protocol.command(loop ? Protocol.LOOP_ON : Protocol.LOOP_OFF), true, () -> {});
        write(Protocol.RAMP, Protocol.int32(ramp), true, () -> {});
        write(Protocol.TARGET, Protocol.int32(target), true, () -> {});
        write(Protocol.COMMAND, Protocol.command(Protocol.START), true, () -> {
            if (token != motionToken) return;
            moveAt = SystemClock.elapsedRealtime();
            if (positioning) {
                LoopSetup setup = pendingLoop;
                if (setup == null) return;
                setState(State.POSITIONING, "Moving to the starting end before looping. Stop cancels.");
            } else {
                setState(State.MOVING, loop ? "Looping between selected positions. Press Stop to finish."
                    : "Moving " + (target < travel / 2 ? "left" : "right") + "…");
            }
            schedulePoll();
        });
    }
    void stop() {
        if (state != State.CONFIGURING && state != State.POSITIONING && state != State.MOVING && state != State.READY) return;
        ++motionToken;
        pendingLoop = null;
        if (positioningDeadline != null) handler.removeCallbacks(positioningDeadline);
        handler.removeCallbacks(poll);
        queue.clearPending();
        stopAcknowledged = false;
        setState(State.STOPPING, "Stopping; checking slider status…");
        stopDeadline = () -> {
            if (state == State.STOPPING) fault("Stop was not confirmed. Check the slider; scan to reconnect.");
        };
        handler.postDelayed(stopDeadline, 10000);
        // Preserve the original motor command order. A loop-write rejection must not prevent STOP.
        write(Protocol.COMMAND, Protocol.command(Protocol.LOOP_OFF), false, () -> {});
        write(Protocol.COMMAND, Protocol.command(Protocol.STOP), true, () -> {
            stopAcknowledged = true;
            poll.run();
        });
    }
    private void notification(UUID id, byte[] value) {
        if (!Protocol.STATUS.equals(id)) return;
        try { acceptStatus(Protocol.uintValue(value)); }
        catch (IllegalArgumentException error) { fault("Invalid slider status."); }
    }
    private void acceptStatus(int status) {
        lastStatus = status;
        listener.log("Status 0x" + Integer.toHexString(status));
        if ((status & Protocol.OVERCURRENT) != 0) { fault("Slider reports a motor overcurrent error."); return; }
        if (state == State.STOPPING && stopAcknowledged && Protocol.idle(status)) {
            handler.removeCallbacks(stopDeadline); handler.removeCallbacks(poll);
            if (disconnectAfterStop) disconnectNow();
            else setState(State.READY, "Stopped. Ready for another move.");
        } else if (state == State.POSITIONING && Protocol.idle(status)) {
            verifyLoopStart(pendingLoop);
        } else if (state == State.MOVING && Protocol.idle(status)
                && SystemClock.elapsedRealtime() - moveAt >= 900) {
            handler.removeCallbacks(poll);
            setState(State.READY, "Move finished. Ready for another move.");
        } else if (state == State.READY && !Protocol.idle(status)) {
            moveAt = SystemClock.elapsedRealtime();
            setState(State.MOVING, "Slider is moving. Press Stop to finish.");
            schedulePoll();
        }
        listener.changed();
    }
    private final Runnable poll = this::pollStatus;
    private void pollStatus() {
        if (state != State.MOVING && state != State.POSITIONING && !(state == State.STOPPING && stopAcknowledged)) return;
        if (statusReadable) read(Protocol.STATUS, true, value -> {
            acceptStatus(Protocol.uintValue(value));
            schedulePoll();
        });
        read(Protocol.POSITION, false, value -> {
            if (value != null) { position = Protocol.uintValue(value); listener.changed(); }
            if (!statusReadable) {
                if (state == State.POSITIONING && Protocol.idle(lastStatus)) verifyLoopStart(pendingLoop);
                schedulePoll();
            }
        });
    }
    private void schedulePoll() {
        handler.removeCallbacks(poll);
        if (state == State.MOVING || state == State.POSITIONING || state == State.STOPPING) handler.postDelayed(poll, 600);
    }
    void disconnect() {
        if (state == State.MOVING || state == State.POSITIONING || state == State.CONFIGURING || state == State.STOPPING) {
            disconnectAfterStop = true;
            stop();
        } else disconnectNow();
    }
    private void disconnectNow() { closeLink(); setState(State.DISCONNECTED, "Disconnected. Scan to connect."); }
    void pauseDiscovery() { if (state == State.SCANNING) stopScan("Discovery paused. Open the app to scan again."); }
    void close() { closeLink(); }
    private void fault(String reason) { closeLink(); setState(State.ERROR, reason); }
    private void closeLink() {
        stopScan("");
        ++motionToken;
        pendingLoop = null;
        handler.removeCallbacks(poll);
        if (positioningDeadline != null) handler.removeCallbacks(positioningDeadline);
        if (connectionDeadline != null) handler.removeCallbacks(connectionDeadline);
        if (stopDeadline != null) handler.removeCallbacks(stopDeadline);
        if (queue != null) { queue.close(); queue = null; }
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) { }
            try { old.close(); } catch (RuntimeException ignored) { }
        }
        disconnectAfterStop = false;
    }
}
