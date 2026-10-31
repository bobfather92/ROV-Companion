package uk.co.rovcompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM tests of wire compatibility, stop preemption, callbacks, and timeout recovery. */
public final class ControlTests {
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    private static class Clock implements OperationQueue.Clock {
        final List<Runnable> tasks = new ArrayList<>();
        public void later(Runnable task, long ms) { tasks.add(task); }
        public void cancel(Runnable task) { tasks.remove(task); }
        void expire() { new ArrayList<>(tasks).forEach(Runnable::run); }
    }
    private static OperationQueue.Operation operation(String key, List<String> starts) {
        return new OperationQueue.Operation(key, true, () -> { starts.add(key); return true; }, ignored -> {});
    }
    public static void main(String[] args) {
        check(Arrays.equals(Protocol.int32(900), new byte[]{(byte)0x84, 3, 0, 0}), "900 little endian");
        check(Arrays.equals(Protocol.int32(5800), new byte[]{(byte)0xa8, 0x16, 0, 0}), "Everyday nominal steps");
        check(Protocol.uintValue(new byte[]{(byte)0xa8, 0x16, 0, 0}) == 5800, "Read travel");
        check(Protocol.command(Protocol.STOP)[0] == 4, "Stop matches original enum");
        check(!Protocol.idle(8) && !Protocol.idle(32) && Protocol.idle(3), "Idle excludes moving and loop flags");
        check(Protocol.speedForPercent(0, 256, 900) == 256 && Protocol.speedForPercent(100, 256, 900) == 900, "Device speed bounds");
        Protocol.TravelRange quiet = Protocol.quietRange(5800, 5);
        check(quiet.left == 290 && quiet.right == 5510, "Default clearance keeps both targets away from hard stops");
        for (int travel : new int[]{3,4,10,99,100,101,5800,11380,1000000}) {
            for (int percent=Protocol.MIN_END_CLEARANCE;percent<=Protocol.MAX_END_CLEARANCE;percent++) {
                Protocol.TravelRange bounds = Protocol.quietRange(travel, percent);
                check(bounds.left > 0 && bounds.right < travel && bounds.right > bounds.left, "Quiet limits stay ordered inside physical travel");
                check(bounds.left-bounds.tolerance > 0 && bounds.right+bounds.tolerance < travel, "Loop position tolerance cannot accept a physical stop");
            }
        }
        Clock clock = new Clock(); List<String> starts = new ArrayList<>(), errors = new ArrayList<>();
        OperationQueue queue = new OperationQueue(clock, errors::add);
        queue.add(operation("in-flight-speed", starts)); queue.add(operation("target-must-cancel", starts));
        queue.add(operation("start-must-cancel", starts)); queue.clearPending();
        queue.add(operation("loop-off", starts)); queue.add(operation("stop", starts));
        check(starts.equals(Arrays.asList("in-flight-speed")), "Single in-flight operation");
        queue.reply("wrong-callback", true, null);
        check(starts.size() == 1, "Unrelated callbacks cannot advance queue");
        queue.reply("in-flight-speed", true, null); queue.reply("loop-off", true, null);
        check(starts.equals(Arrays.asList("in-flight-speed", "loop-off", "stop")), "Stop cancels pending target/start");
        queue.reply("stop", true, null); clock.expire();
        check(errors.isEmpty() && clock.tasks.isEmpty(), "Acknowledged operations cancel timeouts");
        queue.add(operation("missing-stop-ack", starts)); queue.add(operation("must-not-send", starts));
        clock.expire(); queue.reply("missing-stop-ack", true, null);
        check(errors.size() == 1 && !starts.contains("must-not-send"), "Missing ACK faults link, late ACK cannot resume it");
        Clock c2 = new Clock(); List<String> second = new ArrayList<>();
        OperationQueue q2 = new OperationQueue(c2, errors::add);
        q2.add(new OperationQueue.Operation("optional-loop-off", false, () -> false, ignored -> {}));
        q2.add(operation("required-stop", second));
        check(second.equals(Arrays.asList("required-stop")), "Rejected loop write still permits Stop");
        q2.close(); c2.expire();
        check(errors.size() == 1, "Closing cancels outstanding deadlines");
        System.out.println("PASS: protocol bytes, limits, serialized operations, stop preemption, stale callbacks, timeout recovery");
    }
}
