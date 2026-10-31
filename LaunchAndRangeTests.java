package uk.co.rovcompanion;

import java.util.*;

/** Auto-discovery policy and arbitrary A/B positions without motor movement on configuration. */
public final class LaunchAndRangeTests {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args){
        AutoConnectGate first=new AutoConnectGate("");
        check(!first.candidate("headphones",false),"Names alone cannot auto-connect unrelated devices");
        check(first.candidate("ROV-1",true),"Advertised carriage service triggers automatic connection");
        check(!first.candidate("ROV-1",true)&&!first.candidate("ROV-2",true),"One connection claim per scan");
        first.verified("ROV-1");first.reset();
        check(!first.candidate("ROV-2",true),"Remembered slider takes precedence over another ROV");
        check(first.candidate("rov-1",false),"Previously GATT-verified slider can reconnect without service in this advertisement");
        first.forget();check(first.candidate("ROV-2",true),"Choosing another slider clears preference");
        for(int api:new int[]{31,36}){
            android.os.Build.VERSION.SDK_INT=api;
            LoopFlowTests.Rig rig=new LoopFlowTests.Rig(1160);
            int before=rig.link.writes.size();
            check(rig.controller.setWorkingRange(20,70),"Asymmetric working range accepted");
            check(rig.link.writes.size()==before,"Changing A/B positions must not move the slider");
            rig.controller.move(true,25,20,true);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==4060&&rig.loopSpans.equals(Arrays.asList(2900)),"Loop honors custom 20–70% range");
            check(!rig.controller.setWorkingRange(30,80),"Active movement locks range changes");
            rig.stopAt(2500);rig.controller.move(false,25,20,true);rig.until(RovBluetooth.State.POSITIONING);
            check(rig.target==4060,"Reverse setup uses custom B");
            rig.announceIdleAt(4060);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==1160&&rig.loopSpans.equals(Arrays.asList(2900,2900)),"Reverse loop and restart preserve custom range");
        }
        boolean invalid=false;try{Protocol.workingRange(5800,75,20);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"Reject reversed endpoints");
        invalid=false;try{Protocol.workingRange(5800,50,55);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"Reject too-short working range");
        Protocol.TravelRange full=Protocol.workingRange(5800,0,100);
        check(full.left==0&&full.right==5800&&full.tolerance<=4,"Full travel maps exactly to calibrated physical endpoints");
        invalid=false;try{Protocol.workingRange(5800,-1,100);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"Reject negative endpoint");
        invalid=false;try{Protocol.workingRange(5800,0,101);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"Reject endpoint above physical travel");
        for(int api:new int[]{31,36}){
            android.os.Build.VERSION.SDK_INT=api;
            LoopFlowTests.Rig rig=new LoopFlowTests.Rig(0);rig.controller.setWorkingRange(0,100);
            rig.controller.move(true,25,20,true);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==5800&&rig.loopSpans.equals(Arrays.asList(5800)),"Full-range loop reaches B=100%");
            rig.controller.pauseDiscovery();check(rig.controller.state==RovBluetooth.State.MOVING&&!rig.link.closed,"Hiding discovery does not stop/disconnect active movement");
            rig.stopAt(2400);rig.controller.move(false,25,20,true);rig.until(RovBluetooth.State.POSITIONING);
            check(rig.target==5800,"Reverse full-range loop prepositions at 100%");
            rig.announceIdleAt(5799);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==0&&rig.loopSpans.get(1)>=5799,"Full-range loop targets A=0% with step-rounding tolerance");
            rig.stopAt(2400);rig.controller.move(true,25,20,false);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==5800,"One-way move reaches 100%");
            rig.stopAt(2400);rig.controller.move(false,25,20,false);rig.until(RovBluetooth.State.MOVING);
            check(rig.target==0,"One-way move reaches 0%");
        }
        System.out.println("PASS: auto-connect policy, asymmetric and full 0–100% A/B ranges, no motor writes on edit, loop restart, background movement preservation and active-range locking");
    }
}
