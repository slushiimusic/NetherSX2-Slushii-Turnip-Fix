package xyz.aethersx2.android.shim;

public final class IrRebuildGateTest {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        IrRebuildGate gate = new IrRebuildGate();
        // Thor 59 trace: pref changes while paused, layered JNI confirms on
        // resume, native still exposes the retired size until its quiet/debounce.
        gate.request("2.000000", "2.500000", 1280, 1120);
        check(!gate.settled("2.500000", 1280, 1120, 0, 0), "paused old GS must not rebuild");
        check(!gate.settled("2.000000", 1280, 1120, 0, 0), "JNI confirmation alone is too early");
        check(!gate.settled("2.000000", 1280, 1120, 1024, 896), "native resize still pending");
        check(gate.settled("2.000000", 1024, 896, 0, 0), "correct wide IR decrease can rebuild");
        gate.clear();
        check(!gate.pending(), "completion releases the gate");
        gate.request("2.500000", "2.000000", 1024, 896);
        check(!gate.settled("2.500000", 1024, 896, 0, 0), "4:3 increase cannot rebuild old size");
        // A repeated pause after native settle must retain the pre-click basis.
        gate.request("2.500000", "2.500000", 1280, 1120);
        check(gate.settled("2.500000", 1280, 1120, 0, 0), "settle before teardown must work");
        check(gate.pending(), "native settle must retain the baseline through asynchronous context init");
        gate.request("3.000000", "2.500000", 1024, 896);
        gate.contextReady("3.000000", 1536, 1344, 0, 0, false);
        check(gate.pending(), "completion of an obsolete context cannot clear the newer request");
        check(!gate.settled("3.000000", 1280, 1120, 0, 0), "stale intermediate resize cannot release latest request");
        check(!gate.settled("2.500000", 1536, 1344, 0, 0), "size alone cannot confirm the latest request");
        check(gate.settled("3.000000", 1536, 1344, 0, 0), "latest rapid request uses original stable basis");
        gate.request("2.000000", "3.000000", 1536, 1344);
        check(gate.settled("2.000000", 1024, 896, 0, 0), "reverting to original IR can recover without another resize");
        gate.contextReady("2.000000", 1024, 896, 0, 0, true);
        check(!gate.pending(), "matching replacement context completes recovery");
        gate.request("1.500000", "2.000000", 1280, 960);
        check(gate.settled("1.500000", 960, 720, 0, 0), "game-specific 640x480 native base is preserved");
        check(gate.settled("1.500000", 960, 714, 0, 0), "existing small target jitter tolerance is preserved");
        check(!gate.settled("1.500000", 768, 672, 0, 0), "universal 512x448 guess is not accepted");
        gate.clear();
        gate.request("2.000000", null, 0, 0);
        check(!gate.settled(null, 1024, 896, 0, 0), "unknown boot baseline still needs core confirmation");
        check(gate.settled("2.000000", 1024, 896, 0, 0), "unknown baseline must not invent a required extent");
        gate.clear();
        check(gate.settled(null, 512, 448, 0, 0), "ordinary startup without IR request is unaffected");
        check(!gate.settled(null, 0, 0, 0, 0), "startup still needs an observed target");
        System.out.println("PASS: paused IR request, delayed native resize, 4:3/wide changes, early settle, rapid clicks, non-512 base, jitter and startup");
    }
}
