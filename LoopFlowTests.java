package uk.co.rovcompanion;

import android.os.Handler;
import java.util.*;

/** Regression model: firmware loops between its current start and the programmed target. */
public final class LoopFlowTests {
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    static class Rig extends ControllerFlowTests.Rig {
        int devicePosition, deviceStatus = 3, target;
        boolean nativeLoop;
        final List<Integer> loopSpans = new ArrayList<>(), repositionTargets = new ArrayList<>();
        Rig(int position) { super(true,false,false); devicePosition = position; }
        void respond() {
            String kind = link.kind;
            check(kind != null, "Expected an in-flight operation");
            if (kind.equals("r")) {
                if (link.active.getUuid().equals(Protocol.POSITION)) link.finish(Protocol.int32(devicePosition));
                else if (link.active.getUuid().equals(Protocol.STATUS)) link.finish(new byte[]{(byte)deviceStatus});
                else throw new AssertionError("Unexpected read");
            } else if (kind.equals("w")) {
                int value = Protocol.uintValue(link.lastWritten);
                if (link.active.getUuid().equals(Protocol.TARGET)) target = value;
                if (link.active.getUuid().equals(Protocol.COMMAND)) {
                    if (value == Protocol.LOOP_OFF) { nativeLoop = false; deviceStatus &= ~Protocol.LOOP_ACTIVE; }
                    if (value == Protocol.LOOP_ON) nativeLoop = true;
                    if (value == Protocol.START) {
                        if (nativeLoop) loopSpans.add(Math.abs(target-devicePosition));
                        else repositionTargets.add(target);
                        deviceStatus = 2 | Protocol.MOVING | (nativeLoop ? Protocol.LOOP_ACTIVE : 0);
                    }
                    if (value == Protocol.STOP) deviceStatus = 3;
                }
                link.finish(null);
            } else throw new AssertionError("Unexpected operation " + kind);
        }
        void until(RovBluetooth.State expected) {
            for (int i=0; i<40 && controller.state!=expected; i++) respond();
            check(controller.state == expected, "Expected " + expected + ", got " + controller.state);
        }
        void announceIdleAt(int position) {
            devicePosition = position; deviceStatus = 3;
            notification(3);
        }
        void stopAt(int position) {
            devicePosition = position; controller.stop(); until(RovBluetooth.State.READY);
        }
    }
    public static void main(String[] args) {
        for (int api : new int[]{31,36}) {
            android.os.Build.VERSION.SDK_INT = api;
            Rig rig = new Rig(290);
            rig.controller.move(true,25,20,true); rig.until(RovBluetooth.State.MOVING);
            check(rig.loopSpans.equals(Arrays.asList(5220)), "First loop covers quiet travel, API " + api);
            rig.stopAt(5795);
            // A stale UI position must not replace the required fresh hardware position read.
            rig.controller.position = 0;
            rig.controller.move(true,75,40,true); rig.until(RovBluetooth.State.POSITIONING);
            check(rig.repositionTargets.equals(Arrays.asList(290)), "Restart near right end first positions at quiet left");
            check(rig.loopSpans.size() == 1 && !rig.nativeLoop, "No tiny loop can start during positioning");
            rig.announceIdleAt(5795); rig.respond();
            check(rig.controller.state == RovBluetooth.State.POSITIONING && rig.loopSpans.size() == 1,
                "Stale idle notification away from starting endpoint cannot launch loop");
            rig.announceIdleAt(290); rig.respond();
            // Position alone is insufficient if a fresh status read still says moving.
            rig.deviceStatus = 10; rig.respond();
            check(rig.loopSpans.size() == 1, "Wait for fresh idle status at the verified endpoint");
            rig.announceIdleAt(290); rig.until(RovBluetooth.State.MOVING);
            check(rig.loopSpans.equals(Arrays.asList(5220,5220)), "Second loop spans quiet travel without reconnecting");
            rig.stopAt(5);
            rig.controller.move(false,50,10,true); rig.until(RovBluetooth.State.POSITIONING);
            check(rig.repositionTargets.equals(Arrays.asList(290,5510)), "Leftward loop positions at quiet right endpoint");
            rig.announceIdleAt(5510); rig.until(RovBluetooth.State.MOVING);
            check(rig.loopSpans.equals(Arrays.asList(5220,5220,5220)), "Repeated loop direction changes preserve quiet span");

            Rig cancelled = new Rig(3000);
            cancelled.controller.move(true,25,20,true); cancelled.until(RovBluetooth.State.POSITIONING);
            cancelled.announceIdleAt(290); // Endpoint confirmation read is now in flight.
            cancelled.controller.stop(); cancelled.until(RovBluetooth.State.READY);
            check(cancelled.loopSpans.isEmpty() && !cancelled.nativeLoop, "Stop invalidates in-flight endpoint confirmation");
            cancelled.notification(3);
            check(cancelled.loopSpans.isEmpty(), "Late idle cannot restart a cancelled loop");
        }
        Rig setupCancelled = new Rig(3000);
        setupCancelled.controller.move(true,25,20,true); setupCancelled.controller.stop();
        setupCancelled.until(RovBluetooth.State.READY);
        check(setupCancelled.loopSpans.isEmpty() && setupCancelled.repositionTargets.isEmpty(), "Stop before position read cancels all future movement");
        Rig disconnected = new Rig(3000);
        disconnected.controller.move(true,25,20,true); disconnected.until(RovBluetooth.State.POSITIONING);
        disconnected.controller.disconnect(); disconnected.until(RovBluetooth.State.DISCONNECTED);
        check(disconnected.loopSpans.isEmpty() && disconnected.link.closed, "Explicit disconnect during positioning stops instead of starting loop");
        Rig badRead = new Rig(3000); badRead.link.rejectRead = true;
        badRead.controller.move(true,25,20,true); badRead.until(RovBluetooth.State.ERROR);
        check(badRead.loopSpans.isEmpty(), "Cannot start loop without fresh position");
        Rig timeout = new Rig(3000);
        timeout.controller.move(true,25,20,true); timeout.until(RovBluetooth.State.POSITIONING);
        timeout.deviceStatus = 3; // Idle repeatedly reported, but the carriage never reaches the endpoint.
        for(int i=0;i<100 && timeout.controller.state!=RovBluetooth.State.ERROR;i++) {
            Handler.advance(600);
            for(int j=0;j<12 && timeout.link.kind!=null && timeout.controller.state!=RovBluetooth.State.ERROR;j++) timeout.respond();
        }
        check(timeout.controller.state==RovBluetooth.State.ERROR && timeout.controller.message.contains("starting end"),
            "Positioning has a bounded timeout if endpoint never arrives");
        check(timeout.loopSpans.isEmpty(), "Timeout cannot silently launch a shortened loop");
        System.out.println("PASS: loop regression - full-travel restarts after mid-move Stop, both directions/APIs, fresh position and idle checks, setup cancellation, explicit disconnect, read failure, endpoint timeout");
    }
}
