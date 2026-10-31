package uk.co.rovcompanion;

import android.bluetooth.*;
import android.os.Handler;
import java.util.UUID;

public final class ControllerFlowTests {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static class Rig implements RovBluetooth.Listener {
        RovBluetooth controller;
        BluetoothGatt link;
        public void changed() {}
        public void found(RovBluetooth.NearbyDevice device) {}
        public void log(String line) {}
        Rig(boolean readable, boolean noResponse, boolean rejectFirstRead) {
            Handler.reset();
            controller = new RovBluetooth(new android.content.Context(), this);
            BluetoothDevice device = new BluetoothDevice(); controller.connect(device); link = device.link;
            for (UUID id : new UUID[]{Protocol.POSITION,Protocol.TARGET,Protocol.SPEED,Protocol.RAMP,Protocol.COMMAND,
                    Protocol.MAX_STEPS,Protocol.MIN_SPEED,Protocol.MAX_SPEED}) {
                link.service.chars.put(id,new BluetoothGattCharacteristic(id,2|(noResponse?4:8)));
            }
            link.service.chars.put(Protocol.STATUS,new BluetoothGattCharacteristic(Protocol.STATUS,(readable?2:0)|16));
            link.rejectRead = rejectFirstRead;
            link.callback.onConnectionStateChange(link,0,2); Handler.advance(0);
            link.callback.onServicesDiscovered(link,0); Handler.advance(0);
            if (rejectFirstRead) return;
            link.finish(Protocol.int32(5800)); link.finish(Protocol.int32(256)); link.finish(Protocol.int32(900));
            link.finish(Protocol.int32(0)); link.finish(null);
            if (readable) link.finish(new byte[]{2});
            check(controller.state==RovBluetooth.State.READY,"Initialization ready");
            check(link.writes.isEmpty(),"Connecting must not move carriage or alter configuration");
        }
        void start() {
            controller.move(true,25,20,false);
            for(int i=0;i<5;i++) link.finish(null);
            check(controller.state==RovBluetooth.State.MOVING,"Move started");
        }
        void stopAck() { controller.stop(); link.finish(null); link.finish(null); }
        void notification(int status) {
            BluetoothGattCharacteristic c=link.service.chars.get(Protocol.STATUS);
            byte[] bytes={(byte)status};
            if(android.os.Build.VERSION.SDK_INT>=33)link.callback.onCharacteristicChanged(link,c,bytes);
            else{c.value=bytes;link.callback.onCharacteristicChanged(link,c);}
            Handler.advance(0);
        }
    }
    public static void main(String[] args) {
        for(int api:new int[]{31,36}) {
            android.os.Build.VERSION.SDK_INT=api;
            Rig rig=new Rig(true,false,false);rig.start();rig.stopAck();
            check(rig.controller.state==RovBluetooth.State.STOPPING,"Wait for hardware idle");
            rig.link.finish(new byte[]{2});
            check(rig.controller.state==RovBluetooth.State.READY,"Stop recovers with readback and no notification, API "+api);
            rig.link.finish(Protocol.int32(450));rig.start();
            check(rig.controller.state==RovBluetooth.State.MOVING,"Second sequence without app restart");
            rig.controller.disconnect();rig.link.finish(null);rig.link.finish(null);rig.link.finish(new byte[]{2});
            check(rig.link.closed && rig.controller.state==RovBluetooth.State.DISCONNECTED,"Disconnect waits for Stop idle");
        }
        Rig cancelled=new Rig(true,false,false);
        cancelled.controller.move(true,50,20,false);cancelled.controller.stop();cancelled.link.finish(null);
        cancelled.link.finish(null);cancelled.link.finish(null);cancelled.link.finish(new byte[]{2});
        check(cancelled.link.writes.size()==3,"Stop during setup cancels unsent target/ramp/start");
        check(cancelled.controller.state==RovBluetooth.State.READY,"Cancelled setup recovers");
        Rig timeout=new Rig(true,false,false);timeout.start();timeout.controller.stop();Handler.advance(4500);
        check(timeout.controller.state==RovBluetooth.State.ERROR&&timeout.link.closed,"Missing stop acknowledgement faults instead of freezing");
        timeout.link.callback.onCharacteristicWrite(timeout.link,timeout.link.active,0);Handler.advance(0);
        check(timeout.controller.state==RovBluetooth.State.ERROR,"Late callbacks cannot revive closed link");
        Rig rejected=new Rig(true,false,true);
        check(rejected.controller.state==RovBluetooth.State.ERROR,"Synchronous initialization rejection does not crash");
        Rig noResponse=new Rig(true,true,false);noResponse.start();
        check(noResponse.link.lastWriteType==1,"No-response-only characteristic uses supported type");
        Rig notifyOnly=new Rig(false,false,false);notifyOnly.start();notifyOnly.stopAck();
        check(notifyOnly.controller.state==RovBluetooth.State.STOPPING,"Notification-only firmware waits for fresh idle");
        notifyOnly.notification(2);
        check(notifyOnly.controller.state==RovBluetooth.State.READY,"Notification-only firmware recovers on idle");
        Rig lostNotify=new Rig(false,false,false);lostNotify.start();lostNotify.stopAck();
        // Position polls can still finish while the required idle notification never arrives.
        for(int i=0;i<12;i++){if("r".equals(lostNotify.link.kind))lostNotify.link.finish(Protocol.int32(100));Handler.advance(600);}
        Handler.advance(3000);
        check(lostNotify.controller.state==RovBluetooth.State.ERROR,"Missing idle has a bounded recovery path");
        System.out.println("PASS: production controller simulations — APIs 31/36, Stop without notifications, second sequence, setup cancellation, missing ACK, stale callbacks, rejected initialization, no-response writes, notification-only firmware");
    }
}
