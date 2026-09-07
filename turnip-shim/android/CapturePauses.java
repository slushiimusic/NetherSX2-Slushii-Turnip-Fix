package xyz.aethersx2.android.shim;

import java.util.LinkedHashMap;
import java.util.Map;

/** Each asynchronous operation releases only its own capture pause. */
final class CapturePauses {
    enum Reason {
        IR_CHANGE(1), PIPELINE_START(2), CONTEXT_REBUILD(4), OUTPUT_BIND(8);
        final int bit;
        Reason(int bit) { this.bit = bit; }
    }
    interface Sink { void setReasons(int mask); }
    interface Clock { long now(); }
    private final Sink sink;
    private final Clock clock;
    private final Map<Long, Lease> held = new LinkedHashMap<>();
    private long nextId;
    private int published = -1;

    CapturePauses(Sink sink, Clock clock) { this.sink = sink; this.clock = clock; }

    final class Lease implements AutoCloseable {
        final long id, deadline;
        final Reason reason;
        final String owner;
        Lease(long id, Reason reason, String owner, long deadline) {
            this.id = id; this.reason = reason; this.owner = owner; this.deadline = deadline;
        }
        @Override public void close() {
            synchronized (CapturePauses.this) { held.remove(id); publish(); }
        }
    }

    synchronized Lease acquire(Reason reason, String owner, long timeoutMs) {
        Lease lease = new Lease(++nextId, reason, owner, clock.now() + timeoutMs);
        held.put(lease.id, lease);
        publish();
        return lease;
    }

    /** Deadlines diagnose stalled workers; they never unpause native code mid-call. */
    synchronized String overdue() {
        StringBuilder out = new StringBuilder();
        for (Lease lease : held.values()) {
            if (clock.now() < lease.deadline) continue;
            if (out.length() > 0) out.append(", ");
            out.append(lease.reason).append('/').append(lease.owner);
        }
        publish(); // Retry a failed JNI publication on the next watchdog tick.
        return out.toString();
    }

    synchronized int mask() {
        int mask = 0;
        for (Lease lease : held.values()) mask |= lease.reason.bit;
        return mask;
    }

    private void publish() {
        int mask = mask();
        if (mask == published) return;
        sink.setReasons(mask);
        published = mask;
    }
}
