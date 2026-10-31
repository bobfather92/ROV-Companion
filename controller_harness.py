"""Exercise the production controller against a small deterministic fake Android BLE host.

This is a JVM simulation, not Android/emulator or physical-device validation.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'build' / 'controller-harness'
STUBS = {
'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
'android/content/Context.java': '''package android.content; public class Context {
 public final android.bluetooth.BluetoothManager manager = new android.bluetooth.BluetoothManager();
 public <T> T getSystemService(Class<T> c) { return c.cast(manager); }
}''',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT = 36; } }',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now; public static long elapsedRealtime() { return now; } }',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }',
'android/os/ParcelUuid.java': 'package android.os; public class ParcelUuid { java.util.UUID value; public ParcelUuid(java.util.UUID u) {value=u;} public String toString(){return value.toString();} }',
'android/os/Handler.java': '''package android.os; import java.util.*;
public class Handler {
 static class Task { Runnable r; long at; Task(Runnable r,long at){this.r=r;this.at=at;} }
 static List<Task> tasks=new ArrayList<>(); public Handler(Looper l){}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(r,SystemClock.now+ms));return true;}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);}
 public static void advance(long ms){long end=SystemClock.now+ms; int count=0;
  while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at))next=t;
   if(next==null)break; tasks.remove(next);SystemClock.now=next.at;next.r.run();
   if(++count>10000)throw new AssertionError("Scheduler loop");} SystemClock.now=end;}
 public static void reset(){tasks.clear();SystemClock.now=0;}
}''',
'android/bluetooth/BluetoothStatusCodes.java': 'package android.bluetooth; public class BluetoothStatusCodes { public static final int SUCCESS=0; }',
'android/bluetooth/BluetoothProfile.java': 'package android.bluetooth; public interface BluetoothProfile { int STATE_CONNECTED=2, STATE_DISCONNECTED=0; }',
'android/bluetooth/BluetoothManager.java': 'package android.bluetooth; public class BluetoothManager { public BluetoothAdapter getAdapter(){return new BluetoothAdapter();} }',
'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth; public class BluetoothAdapter {
 public boolean isEnabled(){return true;} public android.bluetooth.le.BluetoothLeScanner getBluetoothLeScanner(){return new android.bluetooth.le.BluetoothLeScanner();}
}''',
'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth; public class BluetoothDevice {
 public static final int TRANSPORT_LE=2; public BluetoothGatt link;
 public String getName(){return "Simulated ROV";} public String getAddress(){return "00:00:00:00:00:00";}
 public BluetoothGatt connectGatt(android.content.Context c,boolean auto,BluetoothGattCallback callback,int transport){link=new BluetoothGatt(callback);return link;}
}''',
'android/bluetooth/BluetoothGattService.java': '''package android.bluetooth; import java.util.*;
public class BluetoothGattService { public final Map<UUID,BluetoothGattCharacteristic> chars=new LinkedHashMap<>();
 public BluetoothGattCharacteristic getCharacteristic(UUID id){return chars.get(id);}
 public List<BluetoothGattCharacteristic> getCharacteristics(){return new ArrayList<>(chars.values());}
}''',
'android/bluetooth/BluetoothGattCharacteristic.java': '''package android.bluetooth; import java.util.*;
public class BluetoothGattCharacteristic {
 public static final int PROPERTY_READ=2,PROPERTY_WRITE_NO_RESPONSE=4,PROPERTY_WRITE=8,PROPERTY_NOTIFY=16;
 public static final int WRITE_TYPE_DEFAULT=2,WRITE_TYPE_NO_RESPONSE=1;
 public UUID id; public int properties,type; public byte[] value; public BluetoothGattDescriptor descriptor;
 public BluetoothGattCharacteristic(UUID id,int p){this.id=id;properties=p;descriptor=new BluetoothGattDescriptor(this);}
 public UUID getUuid(){return id;} public int getProperties(){return properties;}
 public BluetoothGattDescriptor getDescriptor(UUID id){return descriptor;}
 public void setWriteType(int t){type=t;} public boolean setValue(byte[] b){value=b;return true;}
 public byte[] getValue(){return value;}
}''',
'android/bluetooth/BluetoothGattDescriptor.java': '''package android.bluetooth;
public class BluetoothGattDescriptor {
 public static final byte[] ENABLE_NOTIFICATION_VALUE={1,0}, ENABLE_INDICATION_VALUE={2,0};
 BluetoothGattCharacteristic c; public BluetoothGattDescriptor(BluetoothGattCharacteristic c){this.c=c;}
 public BluetoothGattCharacteristic getCharacteristic(){return c;} public boolean setValue(byte[] v){return true;}
}''',
'android/bluetooth/BluetoothGattCallback.java': '''package android.bluetooth;
public class BluetoothGattCallback {
 public void onConnectionStateChange(BluetoothGatt g,int s,int n){} public void onServicesDiscovered(BluetoothGatt g,int s){}
 public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v,int s){}
 public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int s){}
 public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int s){}
 public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int s){}
 public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){}
 public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){}
}''',
'android/bluetooth/BluetoothGatt.java': '''package android.bluetooth; import java.util.*;
public class BluetoothGatt {
 public static final int GATT_SUCCESS=0; public final BluetoothGattCallback callback;
 public final BluetoothGattService service=new BluetoothGattService(); public final List<String> writes=new ArrayList<>();
 public BluetoothGattCharacteristic active; public BluetoothGattDescriptor notification; public String kind;
 public boolean rejectRead,closed; public int lastWriteType; public byte[] lastWritten;
 public BluetoothGatt(BluetoothGattCallback cb){callback=cb;}
 public BluetoothGattService getService(UUID id){return service;} public boolean discoverServices(){return true;}
 public boolean readCharacteristic(BluetoothGattCharacteristic c){if(rejectRead)return false;active=c;kind="r";return true;}
 public int writeCharacteristic(BluetoothGattCharacteristic c,byte[] v,int type){active=c;kind="w";lastWriteType=type;lastWritten=v.clone();writes.add(c.id.toString().substring(0,8)+":"+Arrays.toString(v));return 0;}
 public boolean writeCharacteristic(BluetoothGattCharacteristic c){writeCharacteristic(c,c.value,c.type);return true;}
 public boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean on){return true;}
 public int writeDescriptor(BluetoothGattDescriptor d,byte[] v){notification=d;kind="n";return 0;}
 public boolean writeDescriptor(BluetoothGattDescriptor d){notification=d;kind="n";return true;}
 public void disconnect(){} public void close(){closed=true;}
 public void finish(byte[] value){String k=kind;BluetoothGattCharacteristic c=active;BluetoothGattDescriptor d=notification;
  kind=null;if("r".equals(k)){if(android.os.Build.VERSION.SDK_INT>=33)callback.onCharacteristicRead(this,c,value,0);else{c.value=value;callback.onCharacteristicRead(this,c,0);}}
  else if("w".equals(k))callback.onCharacteristicWrite(this,c,0);else if("n".equals(k))callback.onDescriptorWrite(this,d,0);
  else throw new AssertionError("No operation");android.os.Handler.advance(0);}
}''',
'android/bluetooth/le/ScanCallback.java': 'package android.bluetooth.le; public class ScanCallback { public void onScanResult(int t,ScanResult r){} public void onBatchScanResults(java.util.List<ScanResult> r){} public void onScanFailed(int c){} }',
'android/bluetooth/le/ScanResult.java': '''package android.bluetooth.le; public class ScanResult {
 android.bluetooth.BluetoothDevice device; ScanRecord record; public ScanResult(android.bluetooth.BluetoothDevice d,ScanRecord r){device=d;record=r;}
 public android.bluetooth.BluetoothDevice getDevice(){return device;} public int getRssi(){return -65;}
 public ScanRecord getScanRecord(){return record;} public boolean isConnectable(){return true;}
}''',
'android/bluetooth/le/ScanRecord.java': '''package android.bluetooth.le; public class ScanRecord {
 String name; java.util.List<android.os.ParcelUuid> services;
 public ScanRecord(String n,java.util.List<android.os.ParcelUuid> s){name=n;services=s;}
 public String getDeviceName(){return name;} public java.util.List<android.os.ParcelUuid> getServiceUuids(){return services;}
}''',
'android/bluetooth/le/ScanFilter.java': 'package android.bluetooth.le; public class ScanFilter { public static class Builder { public Builder setServiceUuid(android.os.ParcelUuid u){return this;} public ScanFilter build(){return new ScanFilter();} } }',
'android/bluetooth/le/ScanSettings.java': '''package android.bluetooth.le; public class ScanSettings {
 public static int SCAN_MODE_LOW_LATENCY=2,CALLBACK_TYPE_ALL_MATCHES=1; public int mode,type; public long delay;
 public static class Builder { ScanSettings s=new ScanSettings(); public Builder setScanMode(int m){s.mode=m;return this;}
 public Builder setCallbackType(int t){s.type=t;return this;} public Builder setReportDelay(long d){s.delay=d;return this;}
 public ScanSettings build(){return s;} }
}''',
'android/bluetooth/le/BluetoothLeScanner.java': '''package android.bluetooth.le; public class BluetoothLeScanner {
 public static BluetoothLeScanner latest; public java.util.List<ScanFilter> filters; public ScanSettings settings;
 public ScanCallback callback; public boolean stopped;
 public BluetoothLeScanner(){latest=this;}
 public void startScan(java.util.List<ScanFilter> f,ScanSettings s,ScanCallback c){filters=f;settings=s;callback=c;}
 public void stopScan(ScanCallback c){stopped=true;}
}''',
}

for name, source in STUBS.items():
    path = BASE / 'stubs' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
classes = BASE / 'classes'
classes.mkdir(exist_ok=True)
jdk = next((ROOT / '.tools/jdk').glob('*/bin/javac.exe')).parent
production = ROOT / 'app/src/main/java/uk/co/rovcompanion'
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '8', '-d', str(classes),
    *(str(p) for p in (BASE / 'stubs').rglob('*.java')),
    *(str(production / name) for name in ['Protocol.java', 'OperationQueue.java', 'RovBluetooth.java','AutoConnectGate.java']),
    str(ROOT / 'tests/ControllerFlowTests.java'), str(ROOT / 'tests/ScanFlowTests.java'),
    str(ROOT / 'tests/LoopFlowTests.java'), str(ROOT / 'tests/QuietEndTests.java'),str(ROOT/'tests/LaunchAndRangeTests.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(classes), 'uk.co.rovcompanion.ControllerFlowTests'], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(classes), 'uk.co.rovcompanion.ScanFlowTests'], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(classes), 'uk.co.rovcompanion.LoopFlowTests'], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(classes), 'uk.co.rovcompanion.QuietEndTests'], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(classes), 'uk.co.rovcompanion.LaunchAndRangeTests'], check=True)
