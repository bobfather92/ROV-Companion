package uk.co.rovcompanion;

/** Production-controller regression: every commanded target, including loop setup, stays inside clearance. */
public final class QuietEndTests {
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    static void checkTargets(LoopFlowTests.Rig rig, int left, int right) {
        for (int target : rig.repositionTargets) check(target==left || target==right, "Single/positioning target must be a quiet endpoint");
        check(!rig.repositionTargets.contains(0) && !rig.repositionTargets.contains(5800), "No physical-endpoint target");
    }
    public static void main(String[] args) {
        for (int api : new int[]{31,36}) {
            android.os.Build.VERSION.SDK_INT=api;
            for (int clearance : new int[]{2,5,15}) {
                LoopFlowTests.Rig rig = new LoopFlowTests.Rig(0);
                check(rig.controller.setEndClearancePercent(clearance), "Clearance can change while ready");
                Protocol.TravelRange range=Protocol.quietRange(5800,clearance);
                rig.controller.move(true,25,20,false);rig.until(RovBluetooth.State.MOVING);
                check(rig.target==range.right, "Right move stops before physical end");
                check(!rig.controller.setEndClearancePercent(10), "Clearance cannot change under active motion");
                check(rig.controller.endClearancePercent==clearance, "In-flight range remains stable");
                rig.stopAt(range.right);
                rig.controller.move(false,25,20,false);rig.until(RovBluetooth.State.MOVING);
                check(rig.target==range.left, "Left move stops before physical end");
                rig.stopAt(0); // Also exercise connecting/restarting from a physical end left by an earlier build.
                rig.controller.move(true,25,20,true);rig.until(RovBluetooth.State.POSITIONING);
                check(rig.target==range.left && !rig.nativeLoop, "Loop setup moves inward from hard stop before enabling loop");
                // Being at the physical stop cannot pass endpoint verification for the quiet start.
                rig.announceIdleAt(0);rig.respond();
                check(rig.loopSpans.isEmpty(), "Physical stop must not be accepted as the quiet loop anchor");
                rig.announceIdleAt(range.left);rig.until(RovBluetooth.State.MOVING);
                check(rig.loopSpans.get(0)==range.right-range.left, "Native loop captures only quiet travel");
                rig.stopAt(range.left+50);
                rig.controller.move(false,25,20,true);rig.until(RovBluetooth.State.POSITIONING);
                rig.announceIdleAt(range.right);rig.until(RovBluetooth.State.MOVING);
                check(rig.target==range.left, "Reverse loop also stays short of physical end");
                check(rig.loopSpans.get(1)==range.right-range.left, "Restart preserves clearance at both ends");
                checkTargets(rig,range.left,range.right);
            }
        }
        System.out.println("PASS: quiet-end regressions - both APIs/directions, 2/5/15% clearance, all move/setup targets inside limits, protected loop anchors, restarts, immutable active range");
    }
}
