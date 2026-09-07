package xyz.aethersx2.android.shim;

/** Keeps a resolution change from rebuilding framegen against the retired GS. */
final class IrRebuildGate {
    private String target;
    private double baseIr;
    private int baseW, baseH;

    synchronized void request(String ir, String appliedIr, int w, int h) {
        if (ir == null || ir.equals(target)) return;
        // Keep the last stable basis across several clicks before GS catches up.
        if (target == null) {
            baseIr = multiplier(appliedIr);
            baseW = w;
            baseH = h;
        }
        target = ir;
    }

    synchronized boolean pending() { return target != null; }

    synchronized boolean settled(String appliedIr, int w, int h, int pendingW, int pendingH) {
        if (w <= 0 || h <= 0 || pendingW > 0 || pendingH > 0) return false;
        if (target == null) return true;
        if (!target.equals(appliedIr)) return false;
        double requested = multiplier(target);
        // Without a confirmed baseline, only the core and native debounce can
        // confirm progress. Never invent a universal 512x448 game resolution.
        if (baseIr <= 0 || requested <= 0 || baseW <= 0 || baseH <= 0) return true;
        double ratio = requested / baseIr;
        return Math.abs(w - baseW * ratio) <= 16
                && Math.abs(h - baseH * ratio) <= 16;
    }

    synchronized void clear() { target = null; baseIr = 0; baseW = baseH = 0; }

    synchronized void contextReady(String appliedIr, int gsW, int gsH, int pendingW,
            int pendingH, boolean captureMatches) {
        if (captureMatches && settled(appliedIr, gsW, gsH, pendingW, pendingH)) clear();
    }

    private static double multiplier(String ir) {
        try {
            double n = Double.parseDouble(ir);
            return Double.isFinite(n) && n > 0 ? n : 0;
        } catch (RuntimeException ignored) { return 0; }
    }
}
