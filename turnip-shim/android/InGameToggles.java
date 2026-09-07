package xyz.aethersx2.android.shim;

import android.app.Activity;

/** Overlay removed — use Graphics settings (Enable 60 FPS Mode / FSR x4 Mode). */
public final class InGameToggles {
    private InGameToggles() {}

    public static void attach(Activity activity) {
        /* no-op: floating panel intentionally removed */
    }

    public static void detach(Activity activity) {
        /* no-op */
    }
}
