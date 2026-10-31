package uk.co.rovcompanion;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.Handler;
import android.os.ParcelUuid;
import java.util.*;

/** Discovery regression: the scan must surface devices even without the ROV service in advertising. */
public final class ScanFlowTests implements RovBluetooth.Listener {
    final List<RovBluetooth.NearbyDevice> devices = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    public void changed() {}
    public void found(RovBluetooth.NearbyDevice device) { devices.add(device); }
    public void log(String line) { logs.add(line); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static BluetoothDevice device(String name, String address) {
        return new BluetoothDevice() { public String getName() { return name; } public String getAddress() { return address; } };
    }
    public static void main(String[] args) {
        Handler.reset();
        ScanFlowTests listener = new ScanFlowTests();
        RovBluetooth controller = new RovBluetooth(new android.content.Context(), listener);
        controller.scan();
        BluetoothLeScanner scanner = BluetoothLeScanner.latest;
        check(scanner.filters == null || scanner.filters.isEmpty(), "No service UUID may hide the slider before discovery");
        check(scanner.settings.mode == 2 && scanner.settings.delay == 0 && scanner.settings.type == 1, "Foreground scan returns immediate matches");
        ScanResult rov = new ScanResult(device(null,"11:22:33:44:55:66"), new ScanRecord("RŌV",null));
        scanner.callback.onScanResult(1,rov); Handler.advance(0);
        check(listener.devices.size() == 1 && listener.devices.get(0).possibleRov, "Accented ROV advertising name works without service UUID");
        check(listener.devices.get(0).name.equals("RŌV"), "Use advertised name when cache has no name");
        ScanResult unnamed = new ScanResult(device(null,"22:33:44:55:66:77"),null);
        scanner.callback.onBatchScanResults(Arrays.asList(unnamed,rov)); Handler.advance(0);
        check(listener.devices.size() == 3, "Batched and unnamed results remain visible");
        check(listener.devices.get(1).name.equals("Unnamed Bluetooth device"), "Do not mislabel every unnamed device as ROV");
        Handler.advance(20000);
        check(scanner.stopped && controller.message.contains("2 nearby devices"), "Scan ends with unique-device count");
        scanner.callback.onScanResult(1,rov);Handler.advance(0);
        check(listener.devices.size() == 3, "Ignore results after timeout");
        controller.scan();BluetoothLeScanner next = BluetoothLeScanner.latest;
        scanner.callback.onScanFailed(3);scanner.callback.onScanResult(1,rov);Handler.advance(0);
        check(controller.state == RovBluetooth.State.SCANNING && listener.devices.size() == 3, "Old callbacks cannot affect a new scan");
        next.callback.onScanFailed(6);Handler.advance(0);
        check(controller.message.contains("30 seconds"), "Rate limiting is reported as failure with retry guidance");
        Handler.advance(20000);
        check(controller.message.contains("30 seconds"), "Deadline cannot overwrite scan error");
        controller.scan(); Handler.advance(20000);
        check(controller.message.startsWith("No Bluetooth devices found"), "Empty scan explains that no advertisements were delivered");
        System.out.println("PASS: scan simulation - no UUID filter, advertising names, accented ROV, unnamed/batched devices, unique count, timeout, stale callbacks, rate limit");
    }
}
