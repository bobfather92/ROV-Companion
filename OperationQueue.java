package uk.co.rovcompanion;

import java.util.ArrayDeque;

/** Single in-flight GATT operation. Timeouts invalidate the link via fault(). */
final class OperationQueue {
    interface Clock {
        void later(Runnable task, long milliseconds);
        void cancel(Runnable task);
    }
    interface Failure { void fault(String message); }
    interface Start { boolean run(); }
    interface Result { void accept(byte[] value); }
    static final class Operation {
        final String key;
        final boolean required;
        final Start start;
        final Result done;
        Operation(String key, boolean required, Start start, Result done) {
            this.key = key; this.required = required; this.start = start; this.done = done;
        }
    }
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private final Clock clock;
    private final Failure failure;
    private Operation active;
    private Runnable deadline;
    private boolean closed;
    OperationQueue(Clock clock, Failure failure) { this.clock = clock; this.failure = failure; }

    void add(Operation op) { if (!closed) { pending.add(op); pump(); } }
    void clearPending() { pending.clear(); }
    void close() {
        closed = true; pending.clear(); active = null;
        if (deadline != null) clock.cancel(deadline);
        deadline = null;
    }
    private void fail(String message) { close(); failure.fault(message); }
    private void pump() {
        if (closed || active != null || pending.isEmpty()) return;
        Operation op = pending.remove();
        active = op;
        deadline = () -> { if (active == op) fail("Bluetooth timed out: " + op.key); };
        clock.later(deadline, 4500);
        try {
            if (!op.start.run()) reply(op.key, false, null);
        } catch (RuntimeException error) {
            fail("Bluetooth error: " + error.getMessage());
        }
    }
    void reply(String key, boolean success, byte[] value) {
        if (closed || active == null || !active.key.equals(key)) return;
        Operation finished = active;
        clock.cancel(deadline); deadline = null; active = null;
        if (!success && finished.required) { fail("Bluetooth operation failed: " + key); return; }
        try { finished.done.accept(success ? value : null); }
        catch (RuntimeException error) { fail("Invalid slider response: " + error.getMessage()); return; }
        pump();
    }
}
