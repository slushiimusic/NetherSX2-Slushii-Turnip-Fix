package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.AlertDialog;

/**
 * "Restart game?" confirmation for switching Enable 60 FPS Mode off.
 *
 * <p>A 60 FPS pnach is {@code patch=1} — it rewrites EE memory every frame. Turning
 * it off stops the rewriting, but nothing puts the original bytes back, so a running
 * game stays at 60. Measured on Ultimate Spider-Man: OFF alone leaves it at 60.01;
 * OFF plus a game reboot gives G: 29.18.
 *
 * <p>Writing the stock bytes back from a revert pnach was tried and does not work —
 * with the revert body in place and {@code EnableCheats = true} in both the global
 * and per-game ini, USM stayed at 60.01 live and after a full restart.
 *
 * <p>A VM reset does work, and it works for every game: the ELF is reloaded from the
 * ISO, so every patched byte returns to its original value with no per-game
 * knowledge needed. It reboots the game, so it is always confirmed first.
 */
final class ShimRestartPrompt {
    private ShimRestartPrompt() {}

    /** Suppressed during startup sync, where there is no user action to confirm. */
    static volatile boolean suppress;

    /**
     * @param enable which way the switch just moved — both directions need the reset.
     *               Measured: with the switch on, the pnach enabled and
     *               {@code EnableCheats = true} everywhere, a running Ultimate
     *               Spider-Man stayed at 30; with it off it stayed at 60. Only a
     *               game boot changed either (60.01 on / 29.18 off).
     */
    static void askReset(boolean enable) {
        if (suppress) return;
        if (!gameRunning()) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (a == null) return;
        final String msg = enable
                ? "60 FPS is on, but patches are only applied when a game boots — "
                  + "this one is already running.\n\nRestart now to switch to 60 FPS? "
                  + "Progress since your last save state will be lost."
                : "60 FPS is off, but the patch has already been written into memory "
                  + "— the game keeps running at 60 until it restarts.\n\nRestart now "
                  + "to go back to 30 FPS? Progress since your last save state will "
                  + "be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart game?")
                            .setMessage(msg)
                            .setPositiveButton("Restart", (d, w) -> resetVM())
                            .setNegativeButton("Later", null)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    android.util.Log.w("VulkanShim", "restart prompt: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "restart prompt post: " + t.getMessage());
        }
    }

    /**
     * Widescreen patches, like the 60 FPS pnach, are written into EE memory when
     * a game BOOTS. Toggling the switch updates every config layer correctly and
     * still appears to do nothing, because the running game is already patched
     * (or already un-patched) and nothing puts the original bytes back.
     *
     * Reported as "widescreen not disabling with toggle in graphics settings"
     * -- the setting was in fact applied: the inis went to false and AspectRatio
     * was restored, all verified in the log. Only the live VM was stale.
     */
    static void askResetWidescreen(boolean enable) {
        if (suppress) return;
        if (!gameRunning()) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (a == null) return;
        final String msg = enable
                ? "Widescreen is on, but patches are only applied when a game "
                  + "boots — this one is already running.\n\nRestart now to switch "
                  + "to widescreen? Progress since your last save state will be "
                  + "lost."
                : "Widescreen is off, but the patch has already been written into "
                  + "memory — the game keeps rendering widescreen until it "
                  + "restarts.\n\nRestart now to go back to 4:3? Progress since "
                  + "your last save state will be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart game?")
                            .setMessage(msg)
                            .setPositiveButton("Restart", (d, w) -> resetVM())
                            .setNegativeButton("Later", null)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    android.util.Log.w("VulkanShim", "ws restart prompt: " + t.getMessage());
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // GS capture starts/stops live. Only the optional presented-image route
    // needs a new swapchain with TRANSFER_SRC when it is enabled.
    static boolean framegenNeedsRestart(android.content.Context ctx, boolean enable) {
        if (!enable || ctx == null || ctx.getExternalFilesDir(null) == null) return false;
        String source = TurnipConfig.readConfKey(new java.io.File(
                ctx.getExternalFilesDir(null), "turnip.conf"), "fg_capture_src");
        return source != null && !source.equalsIgnoreCase("gs")
                && !source.equalsIgnoreCase("target") && !source.equals("0");
    }

    static void askResetFramegen(boolean enable) {
        if (suppress || !gameRunning()) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (!framegenNeedsRestart(a, enable)) return;
        final String msg = "Frame generation needs a game restart for this capture mode."
                + "\n\nRestart now? Progress since your last save state will be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart game?")
                            .setMessage(msg)
                            .setPositiveButton("Restart", (d, w) -> resetVM())
                            .setNegativeButton("Later", null)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    android.util.Log.w("VulkanShim", "framegen restart prompt: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "framegen restart prompt post: " + t.getMessage());
        }
    }

    static void askResetFsr(boolean enable) {
        if (suppress) return;
        if (!gameRunning()) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (a == null) return;
        final String msg = enable
                ? "FSR changes the swapchain size, so it only starts after the game "
                  + "reboots.\n\nRestart now? Progress since your last save state will "
                  + "be lost."
                : "FSR is off, but this game still has the reduced swapchain. Restart "
                  + "to return to native presentation.\n\nRestart now? Progress since "
                  + "your last save state will be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart game?")
                            .setMessage(msg)
                            .setPositiveButton("Restart", (d, w) -> resetVM())
                            .setNegativeButton("Later", null)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    android.util.Log.w("VulkanShim", "FSR restart prompt: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "FSR restart prompt post: " + t.getMessage());
        }
    }

    static void askResetIr(String ir) {
        if (suppress) return;
        if (!gameRunning()) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (a == null) return;
        final String msg = "The new internal resolution could not be applied "
                + "while this game was running.\n\n"
                + "Restart now for " + ir + "×? Progress since your last save "
                + "state will be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    if (sPromptLive || a.isFinishing() || !gameRunning()) return;
                    sPromptLive = true;
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart game?")
                            .setMessage(msg)
                            .setPositiveButton("Restart", (d, w) -> resetVM())
                            .setNegativeButton("Later", null)
                            .setOnDismissListener(d -> sPromptLive = false)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    sPromptLive = false;
                    android.util.Log.w("VulkanShim", "IR restart prompt: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "IR restart prompt post: " + t.getMessage());
        }
    }

    /** Guards against stacking framegen prompts — the IR watchdog can fire twice. */
    private static volatile boolean sPromptLive;

    /**
     * IR changed while frame generation was live.
     *
     * <p>Hot-reloading framegen on an IR change was tried and removed — the LSFG
     * layer builds its AHB images once per swapchain and its re-create path
     * fails outright, so the result was a black screen or a zeroed instrument.
     * A game restart is the only reliable recovery, so it is asked for.
     */
    static void askResetIrFramegen() {
        if (!FramegenBuild.AVAILABLE) return;
        if (suppress) return;
        if (!gameRunning()) return;
        if (sPromptLive) return;
        final Activity a = ToggleWatchdog.foregroundActivity();
        if (a == null) return;
        final String msg = "The new internal resolution could not be applied "
                + "while this game was running. Restart to restore frame generation.\n\n"
                + "Progress since your last save state will be lost.";
        try {
            a.runOnUiThread(() -> {
                try {
                    if (sPromptLive || a.isFinishing() || !gameRunning()) return;
                    sPromptLive = true;
                    AlertDialog dialog = new AlertDialog.Builder(a)
                            .setTitle("Restart required")
                            .setMessage(msg)
                            .setPositiveButton("Restart now", (d, w) -> {
                                ShimFrameGen.onRestartConfirmed(a);
                                resetVM();
                            })
                            .setNegativeButton("Continue without framegen",
                                    (d, w) -> ShimFrameGen.disableForSession(a))
                            .setOnDismissListener(d -> sPromptLive = false)
                            .show();
                    ShimAccent.styleDialog(dialog);
                } catch (Throwable t) {
                    sPromptLive = false;
                    android.util.Log.w("VulkanShim", "IR framegen prompt: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "IR framegen prompt post: " + t.getMessage());
        }
    }

    /** Resolved once — ShimFrameGen's OSD tick asks this twice a second. */
    private static volatile java.lang.reflect.Method hasEmuThread;

    /** True while a VM exists. The predicate for "this setting cannot apply yet". */
    static boolean gameRunning() {
        try {
            java.lang.reflect.Method m = hasEmuThread;
            if (m == null) {
                m = Class.forName("xyz.aethersx2.android.NativeLibrary")
                        .getMethod("hasEmulationThread");
                hasEmuThread = m;
            }
            return Boolean.TRUE.equals(m.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    static void resetVM() {
        /* The game is about to reboot, so the settings that only take effect at
         * boot become true NOW: land the staged AspectRatio follow and re-read
         * the widescreen value the new VM will come up with, before the reset. */
        try {
            Activity a = ToggleWatchdog.foregroundActivity();
            if (a != null) {
                TurnipConfig.flushPendingAspectFollow(a.getApplicationContext());
                ShimFrameGen.latchBootedWide(a.getApplicationContext());
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "pre-reset sync: " + t.getMessage());
        }
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            java.lang.reflect.Method reset = nl.getMethod("resetVM");
            ShimFrameGen.onVmReset(ToggleWatchdog.foregroundActivity());
            reset.invoke(null);
            android.util.Log.i("VulkanShim", "NativeLibrary.resetVM() (patch restart)");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "resetVM failed: " + t.getMessage());
        }
    }
}
