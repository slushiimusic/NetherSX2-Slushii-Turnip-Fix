package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import com.lsfg.android.session.NativeBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * In-process frame generation using liblsfg-android.so, the pipeline from the
 * LSFG-Android app, driven from inside NetherSX2 instead of from a separate app.
 *
 * <p>Why in-process. That app is not a Vulkan layer: it screen-captures with
 * MediaProjection and composites into a SYSTEM_ALERT_WINDOW overlay, which is
 * where its documented 50-80ms of added latency comes from. Its native entry
 * point {@code pushFrame} takes an AHardwareBuffer and is indifferent to the
 * source, and libvulkad.so already intercepts vkQueuePresentKHR here — so the
 * swapchain image can be handed over directly, with no capture, no
 * VirtualDisplay, and no consent prompt.
 *
 * <p>This is deliberately NOT the liblsfg-vk.so route. That one wraps the
 * emulator's own swapchain, so its cost comes straight out of the emulator's
 * frame budget (measured: SOTC 60 -> 23 fps) and a failure inside it black-
 * screens the game. Here the pipeline owns a separate output surface and the
 * emulator's present path is only read from, never blocked.
 *
 * <p>Gated on turnip.conf {@code lsfg_overlay=on}, separate from the {@code lsfg}
 * key that arms the Vulkan layer. The two must never be on together: they would
 * both try to drive frame generation for the same swapchain.
 */
public final class ShimFrameGen {
    private ShimFrameGen() {}

    private static final String TAG = "VulkanShim";
    private static boolean tried = false;
    private static boolean shadersReady = false;
    /** Why prepare() returned false — shown on OSD when fg_osd=on. */
    private static String prepareBlockReason = "";

    /** Native fg_set_suppress_reason codes (fg_source.h). */
    private static final int SUPPRESS_NONE = 0;
    private static final int SUPPRESS_60FPS = 1;

    /** turnip.conf key. Off unless explicitly set. */
    static final String CONF_KEY = "lsfg_overlay";

  private static boolean vulkadLoaded = false;

    /** ShimFrameGen natives live in libvulkad.so — load before any native call. */
    private static void ensureVulkadLoaded() {
        if (vulkadLoaded) return;
        try {
            System.loadLibrary("vulkad");
            vulkadLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "ensureVulkadLoaded failed: " + t.getMessage());
        }
    }

    private static boolean failPrepare() {
        tried = false;
        return false;
    }

    /**
     * Loads the pipeline and unpacks its shaders. Safe to call repeatedly; the
     * work happens once.
     *
     * @return true when the shaders are extracted and the pipeline is usable.
     */
    public static synchronized boolean prepare(Context ctx) {
        ensureVulkadLoaded();
        if (!FramegenBuild.AVAILABLE) {
            prepareBlockReason = "not available";
            return false;
        }
        noteDisplayRefresh(ctx);
        String displayReason = HandheldTier.framegenDisplayBlockReason(ctx);
        if (displayReason != null) {
            prepareBlockReason = displayReason;
            Log.i(TAG, "framegen unavailable: " + displayReason);
            return false;
        }
        if (sessionDisabled) {
            prepareBlockReason = "disabled for session";
            return false;
        }
        if (shadersReady) return true;
        if (tried) return false;
        tried = true;

        File files = ctx.getExternalFilesDir(null);
        if (files == null) return failPrepare();

        String on = TurnipConfig.readConfKey(new File(files, "turnip.conf"), CONF_KEY);
        if (on == null || !(on.equalsIgnoreCase("on") || on.equals("1")
                || on.equalsIgnoreCase("true"))) {
            prepareBlockReason = "lsfg_overlay off";
            tried = false;
            return false;
        }

        File conf = new File(files, "turnip.conf");
        /* Both routes driving the same swapchain would fight over it. */
        String vkLayer = TurnipConfig.readConfKey(new File(files, "turnip.conf"), "lsfg");
        if (vkLayer != null && (vkLayer.equalsIgnoreCase("on") || vkLayer.equals("1")
                || vkLayer.equalsIgnoreCase("true"))) {
            prepareBlockReason = "lsfg=on conflict";
            note(files, "lsfg_overlay=on but lsfg=on too - refusing to run both "
                    + "frame-generation routes; turn lsfg off");
            return failPrepare();
        }

        noteDisplayRefresh(ctx);
        noteIrCaptureDefault(ctx);
        loadFramegenConfig(files);

        String loadErr = NativeBridge.load();
        if (loadErr != null) {
            /* The likeliest cause is libc++_shared.so: liblsfg-android.so is
             * built against NDK 29's, while the apk ships AetherSX2's older
             * copy, and dlopen names the missing symbol when they disagree. */
            note(files, "liblsfg-android.so did not load: " + loadErr);
            return failPrepare();
        }

        String ver;
        try {
            ver = NativeBridge.INSTANCE.nativeVersion();
        } catch (Throwable t) {
            note(files, "liblsfg-android.so loaded but nativeVersion() failed: " + t);
            return failPrepare();
        }
        note(files, "liblsfg-android.so loaded, version " + ver);

        /* In-process capture already runs at the game's present rate. The
         * pipeline's duplicate-content filter is for MediaProjection 120Hz
         * dupes and freezes static scenes / starves sub-60 games when left on. */
        try {
            NativeBridge.INSTANCE.setSkipDuplicateCapture(true);
        } catch (Throwable t) {
            note(files, "setSkipDuplicateCapture failed: " + t);
        }

        File cacheDir = shaderCacheDir(files);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            note(files, "could not create " + cacheDir.getAbsolutePath());
            return failPrepare();
        }

        /* THE DLL IS THE SOURCE; THE SHADER CACHE IS THE ARTIFACT.
         *
         * initContext() takes only the cache directory — nothing at run time
         * reads Lossless.dll. It exists to be translated once. Requiring it on
         * every prepare() meant a user who had already supplied their dll was
         * asked for it again the moment the file went away, and
         * Android/data is not a place the app controls: a reinstall, a storage
         * cleaner or a restore can take it while the app-owned shaders/
         * directory survives untouched. That is exactly the state this device
         * was found in — 48 valid .spv files and no dll — and the prompt then
         * could not be got rid of.
         *
         * So: extract when there is no stamped cache to reuse, and treat a
         * stamped, complete, driver-validated cache as proof the dll was
         * supplied. Skipping a matching re-extraction also takes the several
         * seconds it costs off every launch. */
        File dll = ShimDllImport.locateDll(ctx);
        String sha = dll != null ? sha256(dll) : null;
        String stamped = readStamp(cacheDir);
        boolean stampMatches = stamped != null && cacheLooksComplete(cacheDir)
                && (sha == null || stamped.equals(sha));
        /* A cache written before the stamp existed still deserves a try when the
         * dll is gone — probeShaders is the authority, and one driver load is a
         * cheap question. With the dll in hand, re-extracting answers it more
         * definitively than guessing from file names. */
        boolean adoptUnstamped = !stampMatches && dll == null
                && new File(cacheDir, "255.spv").isFile();
        boolean reusable = stampMatches || adoptUnstamped;

        boolean extracted = false;
        if (!reusable) {
            if (dll == null) {
                note(files, "no Lossless.dll and no reusable shader cache in "
                        + cacheDir.getAbsolutePath() + " - frame generation needs "
                        + "a user-supplied Lossless Scaling dll");
                /* Single source of truth for "the dll is missing": this is the
                 * code that actually looks for it. ShimDllImport had its own
                 * copy of the check and it silently disagreed. */
                ShimDllImport.noteMissing();
                return failPrepare();
            }
            if (sha == null) {
                note(files, "could not hash " + dll.getAbsolutePath());
                return failPrepare();
            }
            if (!extractInto(files, dll, sha, cacheDir)) return failPrepare();
            extracted = true;
        } else {
            note(files, "reusing extracted shaders in " + cacheDir.getAbsolutePath()
                    + (dll == null ? " (Lossless.dll no longer present; not needed)"
                                   : " (dll unchanged)")
                    + (adoptUnstamped ? " [unstamped cache, probe decides]" : ""));
        }

        /* The app's own second check, worth keeping: it has the driver load
         * every SPIR-V that was just written. Drivers reject bad modules at
         * creation time, which is far cheaper to find here than deep inside the
         * pipeline. It also proves the extraction actually produced files —
         * the directory is app-owned and 0770, so adb cannot inspect it. */
        int probe;
        try {
            probe = NativeBridge.INSTANCE.probeShaders(cacheDir.getAbsolutePath());
        } catch (Throwable t) {
            note(files, "probeShaders threw: " + t);
            return failPrepare();
        }
        /* A reused cache that the driver will not load is not a reason to give
         * up while the dll is still here — rebuild it once and probe again. */
        if (probe != 0 && !extracted && dll != null && sha != null) {
            note(files, "cached shaders failed the driver probe (rc=" + probe
                    + "); re-extracting from " + dll.getAbsolutePath());
            if (!extractInto(files, dll, sha, cacheDir)) return failPrepare();
            extracted = true;
            try {
                probe = NativeBridge.INSTANCE.probeShaders(cacheDir.getAbsolutePath());
            } catch (Throwable t) {
                note(files, "probeShaders threw: " + t);
                return failPrepare();
            }
        }
        if (probe != 0) {
            note(files, "probeShaders rejected the extracted SPIR-V, rc=" + probe);
            /* The cache is unusable and cannot be rebuilt without the file. */
            if (dll == null) {
                clearStamp(cacheDir);
                ShimDllImport.noteMissing();
            }
            return failPrepare();
        }
        /* Stamped only now, with the driver's acceptance behind it: the stamp is
         * the promise that this cache alone is enough to run frame generation,
         * so nothing may write it that has not been probed. "unknown" records a
         * cache adopted without its source dll — a later import will not match
         * it and will rebuild once, which is the right answer for a new dll. */
        if (extracted || !stampMatches) writeStamp(cacheDir, sha == null ? "unknown" : sha);

        shadersReady = true;
        note(files, "shaders extracted AND driver-validated in "
                + cacheDir.getAbsolutePath() + " - in-process frame generation is available");
        /* Hand the bridge to native from a Java thread so fg_source.cpp never
         * has to FindClass on the render thread (system class loader trap). */
        try {
            nativeSetBridge(NativeBridge.INSTANCE);
            nativeBindCallbacks();
            note(files, "native bridge bound for frame handoff");
        } catch (Throwable t) {
            note(files, "nativeSetBridge failed: " + t);
            shadersReady = false;
            return failPrepare();
        }
        return true;
    }

    /** True once the pipeline is loaded and its shaders unpacked. */
    public static synchronized boolean ready() {
        return shadersReady && !displaySuspended;
    }

    /** Temporary display suspension never rewrites the user's framegen choice. */
    private static volatile boolean displaySuspended;

    static boolean refreshDisplayEligibility(Context ctx) {
        String reason = HandheldTier.framegenDisplayBlockReason(ctx);
        if (reason != null) {
            prepareBlockReason = reason;
            if (!displaySuspended || overlayRuntimeEnabled || contextUp || overlayAdded) {
                displaySuspended = true;
                disableOverlay(ctx);
                Log.i(TAG, "framegen suspended: " + reason);
            }
            return false;
        }
        boolean recovering = displaySuspended;
        displaySuspended = false;
        if (recovering && !sessionDisabled && confSaysOverlayOn(ctx)) {
            Log.i(TAG, "framegen display restored to 120 Hz");
            enableOverlay(ctx);
        }
        return true;
    }

    /** Capture / render size — default 480x360, tunable via turnip.conf. */
    private static int captureW = 480;
    private static int captureH = 360;
    private static int fgMultiplier = 2;
    /** No explicit fg_multiplier in turnip.conf: pick one to fill the panel. */
    private static boolean fgMultiplierAuto = true;
    private static int autoMultTicks;
    /** When the multiplier last changed — a rebuild is a visible hitch, so rate-limit it. */
    private static long lastMultChangeMs;
    private static final long MULT_COOLDOWN_MS = 60000L;
    private static float fgFlowScale = 0.25f;
    private static int fgQueueDepth = 1;
    private static int fgTargetFpsCap = 120;
    private static boolean fgPerformance = false;

    private static boolean overlayAdded = false;
    private static boolean contextUp = false;

    /** turnip.conf key for the display alone, independent of {@link #CONF_KEY}. */
    static final String OSD_KEY = "fg_osd";

    /**
     * True only for {@code fg_osd=force} in turnip.conf.
     *
     * NOT for {@code fg_osd=on}. That value means "the instrument is available"
     * and is what the framegen builds have always written, so treating it as
     * "always visible" made it beat the stock OSD switches: the user turned all
     * six off, correctly got `anyStatOn=false`, and the overlay stayed on screen
     * anyway because of a config line they never set. The instrument mirrors
     * those switches, so the switches decide. `force` remains for debugging a
     * framegen problem with the stock rows deliberately off.
     */
    private static boolean confForcesOsd(Context ctx) {
        try {
            java.io.File files = ctx.getExternalFilesDir(null);
            if (files == null) return false;
            String v = TurnipConfig.readConfKey(
                    new java.io.File(files, "turnip.conf"), OSD_KEY);
            return v != null && (v.equalsIgnoreCase("force")
                    || v.equalsIgnoreCase("always"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean osdAdded = false;

    /**
     * Shows the shim's on-screen display whether or not frame generation is on.
     *
     * <p>Deliberately independent. The emulator's own OSD is drawn after the GS
     * target this captures, so it is never in a generated frame and is covered
     * by the output surface — and more practically, the display is the only way
     * to see WHY frame generation is not running. Tying it to the feature meant
     * switching the feature off to stop a black screen also removed the
     * instrument needed to diagnose it.
     *
     * <p>Needs nothing loaded: the counters come from libvulkad.so, which is
     * always present, so this works with the pipeline absent entirely.
     */
    public static void attachOsdIfEnabled(final Activity activity) {
        if (activity == null) return;
        if (osdIsWindowHealthy()) return;
        if (!shouldShowInstrument(activity)) return;
        attachOsd(activity, null);
    }

    /**
     * Instrument follows {@code fg_osd}; an explicit <em>off</em> always wins.
     *
     * <p>The "stays up once shown" fallback at the bottom is only a default for
     * when nothing has an opinion. It used to be reached even with
     * {@code fg_osd=off} in turnip.conf, which made the TextView permanent for
     * the life of the process — a zombie all-zeros box parked on the game's own
     * HUD that no setting could remove.
     */
    private static boolean shouldShowInstrument(Context ctx) {
        /* In-game only. The instrument is a readout of a pipeline that only runs
         * during emulation, so showing it over the game list or Settings is just
         * a green box on top of the UI. */
        if (!emuForeground || displaySuspended) return false;
        /* Framegen-on only. The instrument sits top-right, exactly where the
         * stock PCSX2 OSD draws. While framegen is LIVE that is free real
         * estate — the output panel is a window above the emulator surface, so
         * the stock OSD is hidden anyway and the instrument stands in for it.
         * But with framegen OFF the stock OSD is visible again, and the
         * instrument was covering it with "FRAMEGEN off ... 0 0 0 0". Nothing
         * the stock OSD shows should be lost to a readout that has nothing to
         * report. Diagnostics for a pipeline that never started live in
         * shim_java.log. */
        if (!overlayRuntimeEnabled && !confSaysOverlayOn(ctx)) return false;
        if (ctx == null) return osd != null;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return osd != null;
        File conf = new File(files, "turnip.conf");
        String fgOsd = TurnipConfig.readConfKey(conf, OSD_KEY);
        return OsdPrefs.instrumentVisible(fgOsd,
                OsdPrefs.anyStatOn(ctx), OsdPrefs.userWantsNoStats(ctx));
    }

    private static volatile String framegenOffReason;

    static void reportFramegenOff(Context ctx, String reason) {
        if (reason == null || reason.equals(framegenOffReason)) return;
        framegenOffReason = reason;
        note(ctx == null ? null : ctx.getExternalFilesDir(null),
                "frame generation off — " + reason);
        if (ctx != null) PrefCompat.toast(ctx, "Frame generation off — " + reason);
    }

    private static void reportIrCeiling(Context ctx) {
        if (!overlayRuntimeEnabled && !contextUp) return;
        reportFramegenOff(ctx, String.format(java.util.Locale.US,
                "internal resolution exceeds %.2f×; lower it to resume", fgMaxIr));
    }

    /** turnip.conf lsfg_overlay — asked directly, since the runtime flag lags a relaunch. */
    static boolean confSaysOverlayOn(Context ctx) {
        if (ctx == null) return false;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return false;
        String v = TurnipConfig.readConfKey(new File(files, "turnip.conf"), CONF_KEY);
        return v != null && (v.equalsIgnoreCase("on") || v.equals("1")
                || v.equalsIgnoreCase("true"));
    }

    /**
     * Take the instrument down for good. The TextView is dropped, not merely
     * hidden, so {@link #shouldShowInstrument}'s {@code osd != null} fallback
     * cannot resurrect it on the next tick.
     */
    private static void hideInstrument() {
        try { detachOsdView(); } catch (Throwable ignored) { }
        osd = null;
        osdLp = null;
        osdAdded = false;
        osdWindowAttached = false;
        osdAttachScheduled = false;
        Log.i(TAG, "framegen instrument OSD removed (fg_osd=off)");
    }

    /** Attach or re-attach the framegen instrument (independent of pipeline). */
    public static void ensureInstrumentOsd(Activity activity) {
        if (activity == null) return;
        if (!emuForeground || !isEmulationActivity(activity)) return;
        attachOsd(activity, null);
        ensureOsdTicking();
    }

    /**
     * Graphics toggle ON — load pipeline if needed and start framegen overlay.
     */
    public static void enableOverlay(Context ctx) {
        if (!FramegenBuild.AVAILABLE || ctx == null) return;
        ensureVulkadLoaded();
        // Recheck before the cached-shader path can arm native capture.
        String displayReason = HandheldTier.framegenDisplayBlockReason(ctx);
        if (displayReason != null) {
            prepareBlockReason = displayReason;
            refreshDisplayEligibility(ctx);
            return;
        }
        displaySuspended = false;
        /* RE-READ turnip.conf ON EVERY ENABLE.
         *
         * loadFramegenConfig() used to run only inside prepare(), which is
         * skipped once shadersReady -- so the FIRST enable of a process read the
         * conf and every later toggle silently reused whatever was parsed then.
         * Every tuning key was therefore dead after startup: fg_capture_w/h,
         * fg_present_mode, fg_multiplier, fg_flow_scale and fg_osd could all be
         * edited, the pipeline would visibly stop and restart, and none of it
         * took effect. Measured 2026-09-02: capture pinned to 640x448 in the
         * conf, framegen toggled off and on, and the log still read
         * "LIVE at 1280x896" with the last "framegen config:" line minutes old
         * from the previous cold start.
         *
         * The toggle is the natural place to pick up an edited conf, and it
         * costs one small file read. */
        try {
            File cf = ctx.getExternalFilesDir(null);
            if (cf != null) loadFramegenConfig(cf);
        } catch (Throwable ignored) {
        }
        sessionDisabled = false;
        overlayRuntimeEnabled = true;
        irRestartPromptShown = false;
        resetCostGuard();
        /* Re-arm capture; the stop path gates it off. */
        try {
            nativeSetCaptureEnabled(true);
        } catch (Throwable ignored) {
        }
        if (!shadersReady) {
            /* PREPARE OFF THE UI THREAD.
             *
             * prepare() calls probeShaders(), which has the driver load all 50
             * cached SPIR-V modules to validate them, and then builds the LSFG
             * pipelines. Run inline it froze the app for 10 seconds before
             * framegen appeared, and again for 10-15 on every IR change, because
             * the size rebuild re-prepares. Reported 2026-08-17. The shader
             * cache already skips re-extraction; this is the driver work, and it
             * does not belong on the thread drawing the UI.
             *
             * Re-enters enableOverlay on the main thread once ready, so the
             * overlay attach below still happens with the UI in a known state
             * rather than from a worker. */
            if (!preparing) {
                preparing = true;
                tried = false;
                final Context prepCtx = ctx;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            prepare(prepCtx.getApplicationContext());
                        } catch (Throwable t) {
                            android.util.Log.w("VulkanShim", "framegen prepare: " + t);
                        } finally {
                            preparing = false;
                        }
                        if (!shadersReady) {
                            if (prepareBlockReason != null)
                                android.util.Log.w("VulkanShim",
                                        "framegen prepare failed: " + prepareBlockReason);
                            return;
                        }
                        MAIN_FG.post(new Runnable() {
                            @Override public void run() { enableOverlay(prepCtx); }
                        });
                    }
                }, "shim-fg-prepare").start();
            }
            return;
        }
        Activity act = hostActivity;
        if (act == null && ctx instanceof Activity) act = (Activity) ctx;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        if (act == null) return;
        hostActivity = act;
        ensureInstrumentOsd(act);
        OsdPrefs.updateStockOsdPolicy(ctx);
        if (!ready()) return;
        if (!overlayAdded)
            attachOverlay(act);
        else if (!contextUp)
            ensurePipelineStarted(act);
    }

    /**
     * Graphics toggle OFF — stop pipeline but keep the instrument OSD visible.
     */
    public static void disableOverlay(Context ctx) {
        if (ctx == null) return;
        ensureVulkadLoaded();
        overlayRuntimeEnabled = false;
        try { nativeSetCaptureEnabled(false); } catch (Throwable ignored) { }
        clearIrRecoveryState();
        Activity act = hostActivity;
        if (act == null && ctx instanceof Activity) act = (Activity) ctx;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        /* PULL THE PANEL UNCONDITIONALLY.
         *
         * This used to sit inside `if (act != null)`, so when no Activity could be
         * resolved -- toggling from Settings, or mid-transition -- the opaque output
         * panel was never removed, while `overlayAdded = false` below recorded it as
         * gone. The result was framegen "off" with its panel still covering the game
         * and no state saying so. Detaching needs the WindowManager we already hold,
         * not an Activity, exactly like the menu-open path. */
        detachOnScreenOutputOnly();
        closeOffscreenReader();
        if (act != null) {
            hostActivity = act;
            if (contextUp) stopContext(act);
            ensureInstrumentOsd(act);
            OsdPrefs.updateStockOsdPolicy(ctx);
        }
        overlayAdded = false;
        note(ctx.getExternalFilesDir(null), "framegen disabled (instrument OSD kept)");
    }

    /** User chose restart after IR change broke framegen. */
    public static void onRestartConfirmed(Context ctx) {
        sessionDisabled = false;
        irRestartPromptShown = false;
        overlayRuntimeEnabled = true;
        resetCostGuard();
        clearIrRecoveryState();
        resetOsdForIrChange();
        Activity act = ctx instanceof Activity ? (Activity) ctx
                : ToggleWatchdog.foregroundActivity();
        if (act != null) {
            hostActivity = act;
            if (contextUp) stopContext(act);
            detachOnScreenOutputOnly();
            closeOffscreenReader();
            overlayAdded = ready();
            recordOutputBaselines();
        }
    }

    /** User chose to continue without framegen after IR change. */
    public static void disableForSession(Context ctx) {
        if (ctx == null) return;
        sessionDisabled = true;
        overlayRuntimeEnabled = false;
        irRestartPromptShown = true;
        clearIrRecoveryState();
        Activity act = ctx instanceof Activity ? (Activity) ctx
                : ToggleWatchdog.foregroundActivity();
        if (act != null) {
            hostActivity = act;
            if (contextUp) stopContext(act);
            detachOnScreenOutputOnly();
            closeOffscreenReader();
            resetOsdForIrChange();
            ensureInstrumentOsd(act);
            OsdPrefs.updateStockOsdPolicy(ctx);
        }
        overlayAdded = false;
        note(ctx.getExternalFilesDir(null), "framegen disabled for session (IR)");
    }

    /** Detach OSD view for IR; keep TextView for reattach. */
    public static void resetOsdForIrChange() {
        detachOsdView();
        osdAdded = false;
        osdAttachScheduled = false;
        osdRaisePending = false;
        ensureOsdTicking();
    }

    static void clearIrRecoveryState() { clearIrRecoveryState(true); }

    private static void clearIrRecoveryState(boolean cancelPause) {
        irPausePending = false;
        irRecovering = false;
        cancelGsSettleTimeout();
        cancelIrRestartPromptCheck();
        irExpectedGsW = irExpectedGsH = 0;
        gsSettleMismatches = 0;
        if (cancelPause) cancelIrPause();
    }

    private static void scheduleIrRestartPromptCheck(final Context ctx) {
        if (ctx == null || sessionDisabled || irRestartPromptShown) return;
        cancelIrRestartPromptCheck();
        final Context app = ctx.getApplicationContext();
        irRestartPromptHandler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        irRestartPromptCheck = new Runnable() {
            private int waits;
            @Override public void run() {
            if (irRestartPromptShown || sessionDisabled) return;
            if (!overlayRuntimeEnabled || !overlayAdded) return;
            if (irHotReloadSucceeded()) {
                note(app.getExternalFilesDir(null), "IR hot reload OK");
                return;
            }
            /* Do NOT prompt while the rebuild is still running. LSFG init scales
             * with GS size — at IR 3x (1536x1344) it outlasts the fixed timeout,
             * so the dialog fired on a recovery that was about to succeed. Worse,
             * showing it resizes the panel window (view 1280x912 instead of 960)
             * and the picture ends up in the corner. Wait for the worker. */
            /* Keep waiting whenever the context is coming up OR is already up but
             * has not posted yet. The old gate was `contextStarting` alone, which
             * is false in exactly the window that matters. Measured 2026-08-16,
             * in-game menu, IR 2.0 -> 1.25:
             *
             *   20:03:10.195  frame generation LIVE at 640x560
             *   20:03:10.516  IR hot reload failed -> restart prompt
             *
             * 321 ms. outputHasLiveFrames() counts frames posted SINCE the
             * baselines the rebuild itself had just reset, so a healthy pipeline
             * reads "failed" until it has posted OUTPUT_LIVE_FRAME_MIN of them. */
            if ((contextStarting || contextUp) && ++waits <= IR_HOT_RELOAD_MAX_WAITS) {
                irRestartPromptHandler.postDelayed(this, IR_HOT_RELOAD_CHECK_MS);
                return;
            }
            /* AND THEN DO NOTHING BUT SAY SO.
             *
             * This used to set irRestartPromptShown and open a dialog. Both are
             * wrong. The latch is checked by pauseForIrChange and
             * kickPipelineAfterIr, so one false alarm left every LATER IR change
             * in the session unguarded — which is what "changing IR breaks
             * framegen, and then it stays broken" actually was. And the dialog
             * changes the window insets (view 1280x912 instead of 1280x960),
             * putting the game in the corner: it manufactures the fault it asks
             * about. The instrument OSD is the honest signal, and it now shows a
             * CAPTURE STALE marker when the context really is wrong. */
            note(app.getExternalFilesDir(null),
                    "IR hot reload unconfirmed after "
                            + (waits * IR_HOT_RELOAD_CHECK_MS / 1000) + "s"
                            + " (contextUp=" + contextUp + ") — no prompt, "
                            + "watchdog owns it from here");
            }
        };
        irRestartPromptHandler.postDelayed(irRestartPromptCheck, IR_HOT_RELOAD_CHECK_MS);
    }

    private static void cancelIrRestartPromptCheck() {
        if (irRestartPromptHandler != null && irRestartPromptCheck != null)
            irRestartPromptHandler.removeCallbacks(irRestartPromptCheck);
    }

    private static boolean irHotReloadSucceeded() {
        if (!contextUp || !overlayRuntimeEnabled) return false;
        return outputHasLiveFrames();
    }

    /**
     * Place the instrument top-RIGHT, where stock PCSX2 stats render
     * ({@code OsdPerformancePos = 2} is TopRight).
     *
     * <p>It used to sit top-left, which is where Ultimate Spider-Man draws its
     * health bar — the instrument covered the game's own HUD. Top-right is free
     * while framegen is live because the stock OSD is drawn into the emulator
     * surface, which the output panel covers.
     */
    private static void applyStockOsdLayout(Activity activity,
            android.view.ViewGroup.MarginLayoutParams lp) {
        lp.leftMargin = 0;
        lp.rightMargin = 8;
        int top = 8;
        try {
            int id = activity.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0)
                top += activity.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        lp.topMargin = top;
        /* Both hosts (decor and the output panel container) are FrameLayouts,
         * so gravity is what actually moves it; margins alone would leave it
         * pinned left. */
        if (lp instanceof android.widget.FrameLayout.LayoutParams)
            ((android.widget.FrameLayout.LayoutParams) lp).gravity =
                    android.view.Gravity.TOP | android.view.Gravity.END;
    }

    private static void detachOsdView() {
        if (osd == null) return;
        try {
            android.view.ViewParent parent = osd.getParent();
            if (parent instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) parent).removeView(osd);
        } catch (Throwable ignored) { }
        try {
            if (osdWm != null) osdWm.removeView(osd);
        } catch (Throwable ignored) { }
        osdWindowAttached = false;
    }

    /** Framegen TextView instrument is configured in turnip.conf. */
    public static boolean isFramegenInstrumentEnabled(Context ctx) {
        return OsdPrefs.isFramegenInstrumentEnabled(ctx);
    }

    /** EmulationActivity destroyed — static flags must not block the next session. */
    public static void onEmulationDestroyed(Activity activity) {
        if (activity == null) return;
        emuForeground = false;
        try {
            if (contextUp && !irPausePending && !irRecovering)
                stopContext(activity);
        } catch (Throwable ignored) { }
        /* applySettings during IR recreates the activity — keep the TextView
         * and tick loop; only the window token goes stale.
         *
         * GATED ON isFinishing() since 2026-08-17. overlayAdded is true whenever
         * framegen is running, so a real Exit Game took this path too and the
         * offscreen ImageReader was never closed. Starting a second game in the
         * same process then inherited its dead BufferQueue:
         *   [ImageReader-1024x896f1m3-...] dequeueBuffer: BufferQueue has been
         *   abandoned
         * and the OSD read "no source ... pushed 1287 skipped 10382 posted 0" —
         * capture submitting into a queue nobody owns. A recreate does not set
         * isFinishing(); a genuine teardown does, and must free everything. */
        if (!activity.isFinishing()
                && (irPausePending || irRecovering || overlayAdded)) {
            osdWindowAttached = false;
            hostActivity = null;
            ensureOsdTicking();
            return;
        }
        try {
            detachOsdView();
        } catch (Throwable ignored) { }
        try {
            if (outputContainer != null && outputWm != null)
                outputWm.removeView(outputContainer);
            else if (outputView != null && outputWm != null)
                outputWm.removeView(outputView);
        } catch (Throwable ignored) { }
        outputContainer = null;
        closeOffscreenReader();
        /* A failed startContext can leave LSFG initialized while contextUp stays
         * false (rc=1 / rc=-40 before the lsfgInitLive fix). Tear it down on a
         * real exit or the next game hits rc=-40 forever. */
        queueContextDestroy();
        osdAdded = false;
        overlayAdded = false;
        osdWindowAttached = false;
        osdAttachScheduled = false;
        osdRaisePending = false;
        /* Keep TextView across short activity hops — reattach on resume. */
        outputView = null;
        outputLp = null;
        outputWm = null;
        outputPromoted = false;
        hostActivity = null;
        contextUp = false;
        lastHotReloadScheduleIr = null;
        /* Keep irPausePending / irRecovering / gs-settle timeout across activity
         * recreate — applySettings often destroys EmulationActivity mid-IR. */
        if (!irPausePending)
            cancelGsSettleTimeout();
    }

    /** True when in-process framegen overlay is attached and pipeline loaded. */
    public static boolean isActive() {
        return ready() && overlayAdded;
    }

    /** True when LSFG context is up (capture → framegen → output). */
    public static boolean isContextUp() {
        return contextUp;
    }

    /** True when framegen overlay attach has run for this session. */
    public static boolean isOverlayAttached() {
        return overlayAdded;
    }

    static void prepareForIrChange(Context ctx, String ir, String appliedIr) {
        if (ctx == null || !ready() || sessionDisabled || !overlayRuntimeEnabled
                || (!contextUp && !overlayAdded)) return;
        // The optional present-source route has a fixed capture extent; only
        // GS-source capture scales with the emulator's internal resolution.
        String source = TurnipConfig.readConfKey(
                new File(ctx.getExternalFilesDir(null), "turnip.conf"), "fg_capture_src");
        if ("swapchain".equalsIgnoreCase(source)) return;
        // Native can settle before the UI callback tears down the old context.
        // Its allocated capture size still records the pre-change game target.
        int[] gs = !captureExplicit && (contextUp || contextStarting)
                && contextCaptureW > 0 && contextCaptureH > 0
                ? new int[] { contextCaptureW, contextCaptureH } : currentGsExtent();
        irRebuildGate.request(ir, appliedIr, gs != null ? gs[0] : 0, gs != null ? gs[1] : 0);
    }

    /**
     * IR changed while framegen is live. GS is updated by {@link TurnipConfig};
     * pause capture and rebuild after the observed GS extent settles.
     */
    public static void pauseForIrChange(final Context ctx) {
        if (ctx == null || !ready() || sessionDisabled || irRestartPromptShown) return;
        if (!overlayRuntimeEnabled || (!contextUp && !overlayAdded)) return;

        final String ir = TurnipConfig.peekLastPushedIr();
        final Context app = ctx.getApplicationContext();
        note(app.getExternalFilesDir(null),
                "IR change -> framegen paused for GS settle"
                        + (ir != null ? " ir=" + ir : ""));

        clearIrRecoveryState(false);
        holdIrPause(ir);
        resetOsdForIrChange();

        Activity act = hostActivity;
        if (act == null && ctx instanceof Activity) act = (Activity) ctx;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        if (act != null) {
            hostActivity = act;
            detachOnScreenOutputOnly();
            if (contextUp) stopContext(act);
            closeOffscreenReader();
            /* overlayAdded=false gates onGsSettledOutsideIr(), which is what
             * stops the pipeline rebuilding itself after an IR change.
             *
             * It stayed false through two earlier attempts for two reasons, both
             * now fixed: the rebuild bound the panel at CAPTURE size (fixed in
             * startContext's panel re-bind), and startContext/destroyContext ran
             * on the MAIN THREAD, so a rebuild meant
             *   "ANR ... Waited 5000ms for MotionEvent"
             * and Android killed the app. Both context calls are on the serial
             * ctx worker now, so the rebuild no longer blocks the UI thread and
             * framegen can come back by itself. */
            ensureInstrumentOsd(act);
            OsdPrefs.updateStockOsdPolicy(app);
        }
        lastLiveOutputMs = 0;

        /* THE REBUILD CANNOT ASSUME ANOTHER GS SETTLE IS COMING.
         *
         * Everything above hands the restart to onGsSettledOutsideIr, which only
         * runs when the native side reports the next settle. Measured 2026-08-16
         * driving the IN-GAME menu (pause -> Settings -> Graphics), the settle
         * arrives FIRST:
         *
         *   19:44:43.805  capture follows GS: 1024x896 -> 640x560
         *   19:44:43.806  GS settled 640x560 -> ... reloading context
         *   19:44:44.175  live IR 1.250000 stock reload+applySettings
         *   19:44:44.176  IR change -> framegen paused, restart required
         *   19:44:44.682  frame generation stopped
         *                 ... and nothing, for three minutes, until the next IR
         *                     change produced another settle.
         *
         * The ContentProvider path always ran the other way round, which is why
         * every scripted IR walk recovered and the in-game menu never did. So
         * poll for an ALREADY-settled GS rather than waiting on an event that has
         * been and gone. */
        if (act != null) scheduleSettleRearm(act, 0);

        /* NO RESTART PROMPT. The pipeline rebuilds itself now (verified at
         * Native, 2x and 3x: panel back at 120 fps filling 1280x960), so the
         * dialog is a fallback for a failure mode that no longer happens — and
         * it actively causes one. It fired while the OSD behind it already read
         * "FRAMEGEN live ... real 60 gen 60 out 120", because
         * irHotReloadSucceeded() checks frame counters against baselines that
         * the recovery had only just reset. Worse, showing a dialog changes the
         * window insets (view 1280x912 instead of 1280x960) and the game ends up
         * rendering into the corner.
         *
         * If a rebuild ever genuinely fails the instrument OSD reports it, which
         * is the honest signal — no modal needed. */
    }

    /**
     * Called after applySettings — activity recreate often cancels the settle
     * timeout while irPausePending stays true, so attachOverlay never starts
     * the offscreen pipeline and cap/gen/out sit at zero.
     */
    public static void kickPipelineAfterIr(final Context ctx) {
        if (ctx == null || !ready() || sessionDisabled || irRestartPromptShown) return;
        Activity act = hostActivity;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        if (act == null && ctx instanceof Activity) act = (Activity) ctx;
        if (act == null) return;
        final Context app = act.getApplicationContext();
        hostActivity = act;
        ensureOsdTicking();
        scheduleOsdIrRecovery(act);
        if (irPausePending) {
            long stall = android.os.SystemClock.uptimeMillis() - lastHotReloadScheduleMs;
            if (gsHasSettledForIr()) {
                note(app.getExternalFilesDir(null),
                        "IR kick: GS settled -> finishing recovery");
                finishIrRecovery(app, true);
            } else if (stall > 12000L) {
                note(app.getExternalFilesDir(null),
                        "IR kick: forced recovery (stalled " + stall + "ms)");
                finishIrRecovery(app, false);
            } else {
                note(app.getExternalFilesDir(null),
                        "IR kick: waiting for GS settle (stall " + stall + "ms)");
                scheduleIrRecoveryFallback(app, 1500);
            }
            return;
        }
        ensurePipelineStarted(act);
    }

    /** @deprecated use {@link #pauseForIrChange} */
    public static void scheduleHotReloadAfterIrChange(final Context ctx) {
        pauseForIrChange(ctx);
    }

    private static volatile int gsSettleMismatches = 0;

    /** Called from fg_source.cpp once GS resize debounce commits. */
    static void onGsResizeSettledFromNative(final int w, final int h) {
        lastGsSettleMs = android.os.SystemClock.uptimeMillis();
        Activity act = hostActivity;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        if (act == null) return;
        final Activity hostAct = act;
        final Context app = hostAct.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                /* The GS has reopened, so let TurnipConfig record the IR it is
                 * rendering at if nothing has yet. Without this the FIRST IR
                 * change of a session that booted straight into its saved IR is
                 * unguarded — pauseForIrChange is gated on a recorded value, so
                 * the pipeline is never torn down and LSFG keeps its old capture
                 * images while the native source rebuilds at the new GS size. */
                try { TurnipConfig.noteGsReopened(); } catch (Throwable ignored) { }
                /* Above the ceiling: stop, and stay stopped until the GS comes
                 * back down. Done here rather than in either branch below because
                 * both of them can (re)start the pipeline. */
                if (gsOverIrCap(w, h)) {
                    overIrCap = true;
                    reportIrCeiling(hostAct);
                    File f = hostAct.getExternalFilesDir(null);
                    note(f, String.format(java.util.Locale.US,
                            "GS %dx%d is ~%.2fx native — above the %.2fx framegen "
                            + "ceiling; %s", w, h, irOfGs(w, h), fgMaxIr,
                            contextUp ? "tearing the pipeline down"
                                      : "staying off"));
                    if (contextUp || outputView != null) {
                        detachOnScreenOutputOnly();
                        stopContext(hostAct);
                        closeOffscreenReader();
                        ensureInstrumentOsd(hostAct);
                    }
                    return;
                }
                overIrCap = false;
                /* Size the capture to the GS HERE, before either branch decides
                 * what to do, because both of them can end up starting the
                 * pipeline and LSFG bakes the capture size in at that moment.
                 * Doing it inside one branch only is why the capture sat at
                 * 1024x896 through GS 1536x1344, 512x448, 1280x1120 and 640x560
                 * — measured, four IR steps in a row, every counter healthy and
                 * the picture quietly wrong. */
                trackCaptureToGs(app, w, h);
                if (irPausePending) {
                    /* IR hot-reload removed — pauseForIrChange shows restart dialog. */
                    return;
                }
                if (overlayAdded && contextUp)
                    onGsSettledWhileLive(hostAct, w, h);
                else
                    onGsSettledOutsideIr(hostAct, w, h);
            }
        });
    }

    /** ~18 s of 1.5 s polls — long enough for a GS reopen, short enough to stop. */
    private static final int REARM_TRIES = 12;

    /**
     * Restart the pipeline once the GS is settled, for the case where the settle
     * fired BEFORE the teardown and no further one is coming.
     *
     * <p>Gated on {@link #gsHasSettledForIr()} rather than a plain delay: LSFG
     * bakes its capture images at context-build time, so starting while the GS is
     * still reopening is exactly how the capture size ends up stale.
     */
    private static void scheduleSettleRearm(final Activity act, final int attempt) {
        if (act == null || attempt >= REARM_TRIES) return;
        if (osdTickHandler == null)
            osdTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        osdTickHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ready() || sessionDisabled || !overlayRuntimeEnabled) return;
                /* A real settle beat us to it, or the overlay is gone — done. */
                if (contextUp || !overlayAdded || irPausePending) return;
                Activity a = hostActivity != null
                        ? hostActivity : ToggleWatchdog.foregroundActivity();
                if (a == null || !gsHasSettledForIr()) {
                    scheduleSettleRearm(a != null ? a : act, attempt + 1);
                    return;
                }
                int[] gs = currentGsExtent();
                if (gs != null)
                    trackCaptureToGs(a.getApplicationContext(), gs[0], gs[1]);
                note(a.getExternalFilesDir(null), "re-arm: GS already settled"
                        + (gs != null ? " at " + gs[0] + "x" + gs[1] : "")
                        + " -> pipeline start (attempt " + (attempt + 1) + ")");
                releaseIrPause();
                startPipelineOffscreen(a);
                schedulePromotionRetries(a);
                scheduleOsdIrRecovery(a);
            }
        }, 1500L);
    }

    /** Committed GS extent, or null if native has none. */
    private static int[] currentGsExtent() {
        try {
            int n = nativeStats(statsBuf);
            if (n < 4) return null;
            int w = (int) statsBuf[2], h = (int) statsBuf[3];
            return (w > 0 && h > 0) ? new int[] { w, h } : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean recentIrReloadRequest() {
        return android.os.SystemClock.uptimeMillis() - lastHotReloadScheduleMs
                < IR_REQUEST_WINDOW_MS;
    }

    /**
     * GS settled while the pipeline is live.
     *
     * <p>A rebind alone is NOT enough when the GS changed SIZE. LSFG bakes its
     * capture images once per context, so after an Internal Resolution change
     * the OSD reads {@code GS 640x560 -> capture 1024x896}: the new, smaller GS
     * is blown up into the old buffer before interpolation. Everything reports
     * healthy — live, 117 fps, uniq climbing — and the picture is simply soft,
     * which is the "changing IR breaks framegen" nobody could point at. Lowering
     * IR looked blurry; raising it threw the extra pixels away.
     *
     * <p>So a size change takes the same full teardown-and-restart the IR
     * recovery path uses. It costs a couple of seconds of paused capture, which
     * is what a resolution change costs anyway.
     */
    private static void onGsSettledWhileLive(Activity act, int w, int h) {
        if (act == null || !ready() || !overlayAdded || !contextUp) return;
        hostActivity = act;
        File files = act.getExternalFilesDir(null);
        /* trackCaptureToGs already ran and set the wanted size — compare against
         * THAT, not against the GS, or the cost cap gets recomputed differently
         * here and the context reloads on every settle. */
        if (!captureExplicit
                && (captureW != contextCaptureW || captureH != contextCaptureH)) {
            note(files, "GS settled " + w + "x" + h + " -> context was built at "
                    + contextCaptureW + "x" + contextCaptureH
                    + ", reloading context at " + captureW + "x" + captureH);
            finishIrRecovery(act, true);
            return;
        }
        if (needsOutputRebindAfterGsSettle()) {
            /* Keep capture off until bindOutputSurfaceSafely finishes on fg-ctx.
             * Unpausing first let pushFrame race setOutputSurface → SIGSEGV in
             * present() on USM 768x672 (measured 2026-08-21). */
            releaseIrPause();
            note(files, "GS settled " + w + "x" + h + " -> rebind (live)");
            rebindOutputSurface(act);
        } else {
            note(files, "GS settled " + w + "x" + h + " -> capture refreshed (live, output unchanged)");
            releaseIrPause();
        }
        schedulePromotionRetries(act);
        scheduleOsdIrRecovery(act);
    }

    /** True when GS settle requires a new LSFG output bind (size or sink changed). */
    private static boolean needsOutputRebindAfterGsSettle() {
        if (outputView != null) {
            int vw = outputView.getWidth();
            int vh = outputView.getHeight();
            return vw > 0 && vh > 0
                    && (outputSurfaceW != vw || outputSurfaceH != vh);
        }
        if (offscreenReader == null) return false;
        return outputSurfaceW != captureW || outputSurfaceH != captureH;
    }

    /** Prefer the live ImageReader surface — the one captured at post time may be stale. */
    private static android.view.Surface resolveOffscreenSurface(
            android.view.Surface fallback) {
        try {
            if (offscreenReader != null) {
                android.view.Surface s = offscreenReader.getSurface();
                if (s != null && s.isValid()) return s;
            }
        } catch (Throwable ignored) { }
        return (fallback != null && fallback.isValid()) ? fallback : null;
    }

    private static int clampCapture(int v, int lo, int hi) {
        if (v <= 0) return 0;
        return Math.max(lo, Math.min(hi, v & ~1));
    }

    /**
     * Keep the capture buffer the same size as the GS target.
     *
     * <p>Any mismatch is paid for in sharpness: a smaller GS is blown up into an
     * oversized buffer before interpolation, and a larger one is thrown away.
     * Skipped when the user pinned {@code fg_capture_w/h} in turnip.conf — that
     * is a deliberate cost ceiling, and cost scales with capture AREA.
     *
     * @return true when the size changed
     */
    private static boolean trackCaptureToGs(Context ctx, int w, int h) {
        /* AN IR CHANGE IS A SECOND CHANCE.
         *
         * The cost guard turning framegen off is correct when the setting is
         * genuinely too expensive, but it used disableForSession, which is
         * final — and that stops the context, so onContextReady never runs
         * again and nothing ever clears the trip. Measured: at IR 2.5x the
         * guard fired ("44 fps now, 60 fps normally"), the user dropped back to
         * 2x where framegen had been running at a locked 120, and it stayed off
         * until the game was restarted.
         *
         * The user changing internal resolution is exactly the signal that the
         * conditions the guard judged have changed. Re-arm and let it prove
         * itself again; if the new setting is also too expensive it will simply
         * trip a second time. */
        if (costGuardDisabled && (w != captureW || h != captureH)) {
            costGuardDisabled = false;
            sessionDisabled = false;
            overlayRuntimeEnabled = true;
            resetCostGuard();
            Log.i(TAG, "framegen re-armed after IR change (was off by the cost guard)");
            File f = ctx != null ? ctx.getExternalFilesDir(null) : null;
            if (f != null)
                note(f, "internal resolution changed — giving frame generation "
                        + "another try at the new setting");
            /* CLEARING THE FLAGS IS NOT ENOUGH — RE-ATTACH THE OVERLAY.
             *
             * disableForSession() also sets overlayAdded = false, and every
             * start path is gated on it: onGsSettledOutsideIr() returns
             * immediately while (!ready() || !overlayAdded || contextUp). So the
             * re-arm above announced "giving frame generation another try",
             * updated the capture size, and then nothing ever started the
             * pipeline again -- framegen sat at "waiting for GS target"
             * indefinitely. Measured: guard tripped at IR 1.5, user went back to
             * IR 2.0, log stopped dead after "capture follows GS: 768x672 ->
             * 1024x896" with zero panel layers for over a minute.
             *
             * attachOverlay() is the correct entry point: it is itself guarded
             * on sessionDisabled / overlayRuntimeEnabled, which the three lines
             * above have just cleared, and it defers ensurePipelineStarted() by
             * 400 ms -- comfortably after this method finishes setting the new
             * capture size below, so the pipeline builds at the NEW size. */
            Activity reArm = hostActivity != null
                    ? hostActivity : ToggleWatchdog.foregroundActivity();
            if (reArm != null) attachOverlay(reArm);
        }
        if (captureExplicit) return false;
        int cw = clampCapture(w, 256, 2048);
        int ch = clampCapture(h, 224, 1792);
        if (cw <= 0 || ch <= 0) return false;
        /* Follow the GS, but not past the cost ceiling — keep the aspect and
         * shrink both sides together, or interpolation starts stretching. */
        double maxArea = fgCaptureMaxMp > 0f ? fgCaptureMaxMp * 1000000.0
                                             : Double.MAX_VALUE;
        boolean capped = false;
        if ((double) cw * ch > maxArea) {
            double k = Math.sqrt(maxArea / ((double) cw * ch));
            cw = clampCapture((int) Math.round(cw * k), 256, 2048);
            ch = clampCapture((int) Math.round(ch * k), 224, 1792);
            capped = true;
        }
        if (cw == captureW && ch == captureH) return false;
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        note(files, "capture follows GS: " + captureW + "x" + captureH
                + " -> " + cw + "x" + ch
                + (capped ? " (capped at " + fgCaptureMaxMp + " MP; GS is "
                            + w + "x" + h + ")" : ""));
        captureW = cw;
        captureH = ch;
        try {
            nativeSetCaptureSize(captureW, captureH);
        } catch (Throwable t) {
            note(files, "setCaptureSize failed: " + t);
        }
        return true;
    }

    /**
     * With {@code fg_capture=gs}, size the capture to the GS target so Internal
     * Resolution actually reaches the screen. Only safe here — before the
     * pipeline is up — because LSFG builds its images once per context.
     */
    private static void applyGsCaptureSize(File files, int w, int h) {
        if (!captureFollowsGs || w <= 0 || h <= 0) return;
        int cw = Math.max(256, Math.min(2048, w & ~1));
        int ch = Math.max(224, Math.min(1792, h & ~1));
        if (cw == captureW && ch == captureH) return;
        captureW = cw;
        captureH = ch;
        try {
            nativeSetCaptureSize(captureW, captureH);
            note(files, "fg_capture=gs — capture now " + captureW + "x" + captureH
                    + " (matches GS; IR no longer discarded)");
        } catch (Throwable t) {
            note(files, "fg_capture=gs setCaptureSize failed: " + t);
        }
    }

    /** GS settled before framegen pipeline is up — start only, never reset capture. */
    private static void onGsSettledOutsideIr(Activity act, int w, int h) {
        if (act == null || !ready() || !overlayAdded || contextUp) return;
        hostActivity = act;
        File files = act.getExternalFilesDir(null);
        applyGsCaptureSize(files, w, h);
        note(files, "GS settled " + w + "x" + h + " -> pipeline start");
        releaseIrPause();
        startPipelineOffscreen(act);
        schedulePromotionRetries(act);
        scheduleOsdIrRecovery(act);
    }

    /** Core setting and observed GS extent agree, and native debounce is done. */
    private static boolean gsHasSettledForIr() {
        try {
            long[] gsStats = new long[statsBuf.length];
            int n = nativeStats(gsStats);
            if (n < 4) return false;
            long gsw = gsStats[2];
            long gsh = gsStats[3];
            long pendingW = n >= 9 ? gsStats[8] : 0;
            long pendingH = n >= 10 ? gsStats[9] : 0;
            if (pendingW > 0 || pendingH > 0) return false;
            if (gsw <= 0 || gsh <= 0) return false;
            if (!irRebuildGate.settled(TurnipConfig.confirmedIr(), (int) gsw, (int) gsh,
                    (int) pendingW, (int) pendingH)) return false;
            if (irPauseTarget != null && !TurnipConfig.isIrConfirmed(irPauseTarget)) return false;
            if (irExpectedGsW > 0) {
                int dw = Math.abs((int) gsw - irExpectedGsW);
                int dh = Math.abs((int) gsh - irExpectedGsH);
                return dw <= 16 && dh <= 16;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recordOutputBaselines() {
        try {
            outputLivePostedBaseline = NativeBridge.INSTANCE.getPostedFrameCount();
            outputLiveGenBaseline = NativeBridge.INSTANCE.getGeneratedFrameCount();
        } catch (Throwable ignored) {
            outputLivePostedBaseline = 0;
            outputLiveGenBaseline = 0;
        }
        lastLiveOutputMs = 0;
    }

    private static synchronized void finishIrRecovery(Context ctx) {
        finishIrRecovery(ctx, false);
    }

    private static synchronized void finishIrRecovery(Context ctx, boolean fromGsSettle) {
        /* fromGsSettle bypasses irPausePending DELIBERATELY, and this is the only
         * thing that covers an IR change made from the IN-GAME menu.
         *
         * irPausePending is never set true anywhere any more — it went with the
         * restart dialog — so this method was unreachable, and the mismatch branch
         * in onGsSettledWhileLive logged "reloading context at NxM" and returned.
         * That is the corner-crop: the native source rebuilds its AHBs at the new
         * GS size while LSFG's images stay baked at the old one.
         *
         * It cannot be fixed in the notification path instead. Measured
         * 2026-08-16 from pause menu -> Settings -> Graphics -> Upscale
         * Multiplier: the core applies the setting ITSELF, so there is no
         * "live IR ... stock reload" and neither pauseForIrChange site fires. The
         * GS settle is the ONLY signal the shim gets on that path. Same at boot,
         * where the context is built from the last-known IR pref and the GS then
         * opens at a different size — measured content 1024x768 of a 1280x960
         * panel (0.8 = 1024/1280) with framegen reporting live and 126 presents. */
        if (!irPausePending && !fromGsSettle) return;
        if (contextStarting) {
            scheduleIrRecoveryFallback(ctx, 500);
            return;
        }
        if (!fromGsSettle && !gsHasSettledForIr()) {
            long stall = android.os.SystemClock.uptimeMillis() - lastHotReloadScheduleMs;
            if (stall < 12000L) {
                note(ctx.getExternalFilesDir(null),
                        "IR recovery deferred (waiting GS settle)");
                scheduleIrRecoveryFallback(ctx, 1500);
                return;
            }
        }
        irPausePending = false;
        cancelGsSettleTimeout();

        Activity act = hostActivity;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        if (act == null) {
            irRecovering = false;
            releaseIrPause();
            return;
        }

        irRecovering = true;
        outputResizing = true;
        try {
            tearDownContextForIr(ctx, fromGsSettle);

            outputPromoted = false;
            lastSampleNs = 0;
            // Keep the latest requested extent until its IR owner observes it.

            hostActivity = act;
            detachOnScreenOutputOnly();
            closeOffscreenReader();
            recordOutputBaselines();
            final Activity recoveryAct = act;
            final long pipelineDelayMs = fromGsSettle ? 200L : 0L;
            recoveryAct.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    if (!ready()) return;
                    startPipelineOffscreen(recoveryAct);
                    if (!contextUp)
                        recoveryAct.getWindow().getDecorView().postDelayed(
                                () -> ensurePipelineStarted(recoveryAct), 500);
                }
            }, pipelineDelayMs);

            schedulePromotionRetries(act);
            scheduleOsdIrRecovery(act);
            OsdPrefs.updateStockOsdPolicy(ctx);
            note(ctx.getExternalFilesDir(null), "framegen hot reload"
                    + (contextUp ? " LIVE (offscreen)" : " waiting for pipeline"));
            scheduleIrRestartPromptCheck(ctx);
        } finally {
            outputResizing = false;
            irRecovering = false;
            releaseIrPause();
        }
    }

    private static void tearDownContextForIr(Context ctx, boolean skipCaptureReset) {
        /* ORDER IS LOAD-BEARING. This used to null the output surface FIRST and
         * destroy the context second, which raced LSFG's render thread into
         *
         *   SIGSEGV fault addr 0x98  ANativeWindow_lock+20 <- liblsfg-android.so
         *
         * every time IR was lowered. destroyContext() stops that thread, so it
         * has to come first; after it there is no consumer left and the window
         * never needs nulling at all. Pause the capture ahead of both so no new
         * frame enters the pipeline while it is being dismantled. */
        if (contextUp) {
            contextUp = false;
            /* Off the main thread — this is the call that ANR'd the app on every
             * IR change. Serialized with init on the same worker. */
            queueContextDestroy();
        } else if (contextStarting) {
            /* fg-ctx is inside initContext with a live setOutputSurface bind.
             * Nulling here was measured on USM: "Output surface detached" at
             * init complete, then SIGSEGV in LSFG_3_1::Context::present (pc=0)
             * on the first pushFrame. Queue destroy after init; never detach. */
            queueContextDestroy();
            contextStarting = false;
        } else {
            /* No context: nothing is holding the window, so clearing it is safe
             * and keeps a stale Surface from being reused after the IR change. */
            try { NativeBridge.INSTANCE.setOutputSurface(null, 0, 0); }
            catch (Throwable ignored) { }
        }
    }

    /** Drop the on-screen panel only — gameplay must stay visible. */
    private static void detachOnScreenOutputOnly() {
        outputPromoted = false;
        promoteRetryGen++;
        if (contextUp) {
            /* NEVER hand LSFG a null output window while its render thread is
             * live. setOutputSurface(null) raced that thread straight into
             *
             *   SIGSEGV, fault addr 0x98, null pointer dereference
             *     #00 ANativeWindow_lock+20   libandroid.so
             *     #01..#06                    liblsfg-android.so
             *
             * on every IR change that had no offscreen reader to fall back to —
             * i.e. lowering IR back to Native. Pause the capture first so the
             * loop stops pulling frames, then either retarget it at the
             * offscreen reader or tear the context down entirely. Handing over
             * null is never an option. */
            try {
                if (offscreenReader != null) {
                    bindOutputSurfaceSafely(
                            offscreenReader.getSurface(), captureW, captureH);
                } else {
                    contextUp = false;
                    queueContextDestroy();
                }
            } catch (Throwable ignored) { }
        }
        /* The view teardown below MUST NOT run inline.
         *
         * This is reached from SurfaceHolder.surfaceDestroyed, which Android
         * calls from inside ViewRootImpl.performTraversals. Removing a child
         * from a ViewGroup mid-traversal leaves a null hole in mChildren, and
         * the next dispatch walks straight into it:
         *
         *   NullPointerException: 'void View.dispatchWindowVisibilityChanged(int)'
         *     on a null object reference
         *       at ViewGroup.dispatchWindowVisibilityChanged
         *       at ViewRootImpl.performTraversals
         *
         * That killed the process on every live IR change — which is what
         * "changing IR breaks framegen" actually was. The window/view work is
         * therefore posted, while the state fields are cleared synchronously so
         * callers still observe the panel as gone immediately. */
        final android.widget.FrameLayout container = outputContainer;
        final android.view.SurfaceView view = outputView;
        final android.view.WindowManager wm = outputWm;
        final android.widget.TextView osdView = osd;
        outputView = null;
        outputContainer = null;
        outputLp = null;
        outputWm = null;
        osdWindowAttached = false;

        Runnable teardown = new Runnable() {
            @Override public void run() {
                try {
                    if (osdView != null && osdView.getParent() == container
                            && container != null)
                        container.removeView(osdView);
                } catch (Throwable ignored) { }
                try {
                    if (container != null && wm != null) wm.removeView(container);
                    else if (view != null && wm != null) wm.removeView(view);
                } catch (Throwable ignored) { }
                Activity a = hostActivity;
                /* The panel is gone, so the emulator surface is what shows now —
                 * give it its full-size buffer back or the user sees a
                 * quarter-resolution image until the panel returns. */
                if (a != null) ShimSurfaceScale.restoreFullSize(a);
                if (a != null && !a.isFinishing()) attachOsdIfEnabled(a);
            }
        };
        if (osdTickHandler == null)
            osdTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        osdTickHandler.post(teardown);
    }

    private static boolean mayShowOutputPanel() {
        return !irPausePending && !irRecovering;
    }

    private static void closeOffscreenReader() {
        if (offscreenReader != null) {
            try { offscreenReader.close(); } catch (Throwable ignored) { }
            offscreenReader = null;
        }
    }

    /** LSFG init on an ImageReader — zero WM overlay until frames post. */
    private static synchronized void startPipelineOffscreen(Context ctx) {
        if (contextUp || contextStarting || ctx == null || !ready() || irPausePending
                || !overlayRuntimeEnabled || sessionDisabled) return;
        // Every entry point must wait, including activity resume and watchdog
        // retries. An unchanged committed size is not proof an IR click landed.
        if ((irRebuildGate.pending() || irPause != null) && !gsHasSettledForIr()) return;
        /* Never build above the ceiling — this is where the IR-3.0 driver crash
         * happens, and a pipeline that cannot post is worse than none. */
        int[] gsNow = currentGsExtent();
        if (gsNow != null && gsOverIrCap(gsNow[0], gsNow[1])) {
            reportIrCeiling(ctx);
            if (!overIrCap) {
                overIrCap = true;
                note(ctx instanceof Activity
                                ? ((Activity) ctx).getExternalFilesDir(null)
                                : ctx.getExternalFilesDir(null),
                        String.format(java.util.Locale.US,
                                "framegen held off: GS %dx%d is ~%.2fx native, "
                                + "above the %.2fx ceiling (fg_max_ir)",
                                gsNow[0], gsNow[1], irOfGs(gsNow[0], gsNow[1]),
                                fgMaxIr));
            }
            return;
        }
        overIrCap = false;
        try (CapturePauses.Lease setup = pauseCapture(
                CapturePauses.Reason.PIPELINE_START, "pipeline setup")) {
        /* fg_capture=gs has to be resolved HERE, not on the GS-settle callback:
         * LSFG builds its images once per context, so by the time GS settles the
         * capture size is already baked in. Ask the native side for the current
         * GS extent instead — it is known well before the pipeline starts. */
        /* Gated on captureExplicit, NOT on captureFollowsGs.
         *
         * This block used to sit inside `if (captureFollowsGs)`, which is false
         * unless turnip.conf sets fg_capture=gs — and it is not set — so the
         * pre-build sizing never ran, while trackCaptureToGs (guarded only by
         * captureExplicit) happily followed the GS on the settle AFTERWARDS.
         * Result: the context is always built at the default first and rebuilt
         * second. */
        if (!captureExplicit) {
            int nativeGw = 0, nativeGh = 0;
            try {
                int n = nativeStats(statsBuf);
                if (n >= 4) {
                    nativeGw = (int) statsBuf[2];
                    nativeGh = (int) statsBuf[3];
                }
            } catch (Throwable ignored) { }
            /* Do not init LSFG while the native tracker has never seen a GS
             * target. Measured on USM IR 1.5: context went LIVE at 768x672 from
             * the IR pref, GS then settled 0x0->768x672 (fg_reset_capture), and
             * the first pushFrame SIGSEGV'd in LSFG_3_1::Context::present (pc=0)
             * even with the ImageReader output bound. onGsSettledOutsideIr starts
             * the pipeline once native reports a real extent. */
            if (nativeGw <= 0 || nativeGh <= 0) {
                if (lastExpectedGs != null) {
                    nativeGw = lastExpectedGs[0];
                    nativeGh = lastExpectedGs[1];
                    trackCaptureToGs(ctx, nativeGw, nativeGh);
                }
                note(ctx.getExternalFilesDir(null),
                        "framegen pipeline waiting for GS target (native "
                                + (int) statsBuf[2] + "x" + (int) statsBuf[3]
                                + (lastExpectedGs != null
                                        ? ", sized from IR " + nativeGw + "x" + nativeGh
                                        : "")
                                + ")");
                return;
            }
            trackCaptureToGs(ctx, nativeGw, nativeGh);
        }
        closeOffscreenReader();
        outputSurfaceW = captureW;
        outputSurfaceH = captureH;
        try {
            offscreenReader = android.media.ImageReader.newInstance(
                    captureW, captureH, android.graphics.PixelFormat.RGBA_8888, 3);
            startContext(ctx, offscreenReader.getSurface());
        } catch (Throwable t) {
            note(ctx.getExternalFilesDir(null), "offscreen pipeline start failed: " + t);
            closeOffscreenReader();
        }
        } // setup lease; startContext owns the queued initialization
    }

    /** Tear down LSFG + remove any on-screen output. Game must show through. */
    private static void detachOutputOverlayForIr(Context ctx) {
        detachOnScreenOutputOnly();
        tearDownContextForIr(ctx, false);
        closeOffscreenReader();
        lastLiveOutputMs = 0;
    }

    /** Add WM output surface at full panel size once LSFG is posting frames. */
    private static void attachOnScreenOutput(final Activity activity) {
        if (activity == null || outputView != null || !contextUp || !mayShowOutputPanel())
            return;
        /* DO NOT call ShimSurfaceScale.applyEarly() here. It was, and it is what
         * quartered the picture after an IR change: applyEarly does
         * setFixedSize(640,480) on the emulator SurfaceView, which requests a
         * relayout, and getPanelSize() immediately below reads that same view's
         * width/height — so the panel got created at 640x480 inside a 1280x960
         * window and the game rendered into the top-left corner. The scale is
         * re-applied after the panel is attached instead (see below). */
        int[] panel = getPanelSize(activity);
        if (panel == null) {
            activity.getWindow().getDecorView().postDelayed(
                    new Runnable() {
                        @Override public void run() { attachOnScreenOutput(activity); }
                    }, 200);
            return;
        }
        final int pw = panel[0];
        final int ph = panel[1];
        try {
            final android.view.SurfaceView sv = new android.view.SurfaceView(activity);
            sv.setZOrderMediaOverlay(true);
            sv.setClickable(false);
            sv.setFocusable(false);
            sv.setFocusableInTouchMode(false);
            sv.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
            sv.getHolder().setFixedSize(pw, ph);
            sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                    if (!contextUp) return;
                    outputResizing = true;
                    try {
                        outputSurfaceW = pw;
                        outputSurfaceH = ph;
                        /* MUST precede the bind: LSFG converts this Surface to an
                         * ANativeWindow internally, and the transform has to be set
                         * on the window before it starts producing buffers. */
                        bindOutputSurfaceSafely(h.getSurface(), pw, ph);
                        /* AFTER the bind, not before. Setting it first succeeded
                         * (rc=0) and did nothing: LSFG calls setBuffersGeometry when
                         * it binds, which resets the window's transform. Re-applying
                         * afterwards is what survives to the producer. */
                        /* The native compute pass (rotate90.comp) owns rotation
                         * now. Applying it here TOO rotated every frame twice --
                         * 2x90 and 2x270 both land on 180, which is why both
                         * settings looked identical and upside down. This legacy
                         * surface-transform route also forces SurfaceFlinger to
                         * rotate each frame off the cheap overlay path, so it stays
                         * off unless fg_rotate_via_surface=on asks for it. */
                        /* Native compute pass follows the live display. */
                        lastPanelRotateDeg = -1;
                        syncPanelRotation(activity);
                        lastOutputAspect = -1f;
                        syncOutputAspect(activity);
                        final int deg = rotateViaSurface(activity)
                                ? panelRotateDegrees(activity) : 0;
                        if (deg != 0) {
                            final android.view.Surface osurf = h.getSurface();
                            Runnable applyXform = () -> {
                                try {
                                    int rc = nativeSetSurfaceTransform(
                                            osurf, transformForDegrees(deg));
                                    Log.i(TAG, "framegen output surface rotated " + deg
                                            + " deg (rc=" + rc + ")");
                                } catch (Throwable t) {
                                    Log.w(TAG, "surface transform failed: " + t);
                                }
                            };
                            applyXform.run();
                            /* LSFG may re-set geometry on its first frames, so land it
                             * again shortly after the pipeline starts producing. */
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(applyXform, 1200L);
                        }
                        outputPromoted = true;
                        note(activity.getExternalFilesDir(null),
                                "output on-screen " + pw + "x" + ph
                                        + " (capture " + captureW + "x" + captureH + ")");
                    } catch (Throwable t) {
                        note(activity.getExternalFilesDir(null),
                                "on-screen setOutputSurface failed: " + t);
                    } finally {
                        outputResizing = false;
                    }
                }
                /* The ONLY source of truth for the output buffer's size.
                 *
                 * surfaceCreated binds LSFG to pw x ph — the size we REQUESTED
                 * through setFixedSize, which is asynchronous. If the buffer is
                 * still the old size at bind time, or is resized afterwards,
                 * LSFG renders at one size into a buffer of another and the
                 * panel shows the result scaled by buffer/told in each axis
                 * independently. Measured after a live IR change: 1.250
                 * horizontal and 1.079 vertical, while GS, capture, "ctx built"
                 * and "out told" ALL read 384x336 — the wrong number was the
                 * one nothing printed. Intermittent, because it depends on
                 * whether the resize landed before the bind.
                 *
                 * Leaving this callback empty is what made it possible; the
                 * real dimensions arrive here and were being discarded. */
                /* Deliberately EMPTY — do not rebind LSFG from these dimensions.
                 *
                 * Tried 2026-08-16 and REVERTED: binding setOutputSurface to the
                 * width/height reported here made a live change to IR 1.25
                 * corner-box at 0.6 scale (384/640) on 3 of 3 attempts, where it
                 * had been correct before. This callback fires with transient
                 * sizes during the panel's layout, and binding LSFG to one of
                 * those sticks. Both bind sites use the PANEL size on purpose.
                 *
                 * The open bug this was aimed at is real but is NOT here: after
                 * a live IR change the picture is sometimes scaled by
                 * buffer/told independently per axis (measured 1.250 x 1.079)
                 * while GS, capture, ctx-built and out-told all agree. Whatever
                 * carries the wrong size, it is not this callback. */
                @Override public void surfaceChanged(android.view.SurfaceHolder h,
                        int f, int w, int hh) { }
                @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
                    if (outputResizing || irRecovering) return;
                    detachOnScreenOutputOnly();
                    if (contextUp && offscreenReader != null) {
                        bindOutputSurfaceSafely(
                                offscreenReader.getSurface(), captureW, captureH);
                    }
                }
            });
            outputView = sv;
            /* Container is the panel window's view root: the SurfaceView is a
             * media overlay, so it composites BELOW the container's own view
             * content — any view added to the container (the OSD) draws above
             * the generated frames, inside the same window. */
            final android.widget.FrameLayout container =
                    new android.widget.FrameLayout(activity);
            /* fg_panel_rotate — turn the output layer by N degrees.
             *
             * On a portrait-native panel (AYN Thor: physical 1080x1920 driven in
             * landscape) the generated frames arrive 90 degrees off while the
             * emulator's own picture and the Android OSD stay upright. It is NOT a
             * Vulkan transform: the emulator's swapchain reports IDENTITY, and LSFG
             * never creates a swapchain of its own -- it renders into this Surface,
             * whose buffers SurfaceFlinger then rotates. So the correction belongs
             * to this view, not to preTransform. Default 0 = no change. */
            container.addView(sv, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            outputContainer = container;
            final android.view.WindowManager.LayoutParams olp =
                    new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                      | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                      | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.OPAQUE);
            olp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            outputLp = olp;
            outputWm = activity.getWindowManager();
            final android.view.View decor = activity.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override public void run() {
                    try {
                        olp.token = decor.getWindowToken();
                        if (olp.token == null) { decor.postDelayed(this, 200); return; }
                        activity.getWindowManager().addView(container, olp);
                        schedulePromotionRetries(activity);
                        moveOsdIntoOutputPanel(activity);
                        /* Panel is up and its size is already fixed at pw x ph,
                         * so shrinking the emulator buffer now cannot affect the
                         * panel's geometry. Paired with restoreFullSize() on
                         * teardown so the user never sees the scaled buffer. */
                        ShimSurfaceScale.applyEarly(activity);
                    } catch (Throwable t) {
                        note(activity.getExternalFilesDir(null),
                                "on-screen output addView failed: " + t);
                        outputView = null;
                        outputContainer = null;
                        outputPromoted = false;
                    }
                }
            });
        } catch (Throwable t) {
            note(activity.getExternalFilesDir(null), "attachOnScreenOutput failed: " + t);
            outputPromoted = false;
        }
    }

    private static int[] getPanelSize(Activity activity) {
        if (activity == null) return null;
        android.view.View decor = activity.getWindow().getDecorView();
        android.view.SurfaceView emu = findEmulatorSurfaceView(decor);
        int pw = emu != null && emu.getWidth() > 0 ? emu.getWidth() : decor.getWidth();
        int ph = emu != null && emu.getHeight() > 0 ? emu.getHeight() : decor.getHeight();
        if (pw <= 0 || ph <= 0) return null;
        return new int[] { pw, ph };
    }

    /** Auto-fallback: strip on-screen overlay when framegen stops posting. */
    /** Last drift state reported, so the line is logged on change only. */
    private static String lastBufferDrift;

    /**
     * INSTRUMENT ONLY — never acts, only reports.
     *
     * <p>The open bug: the panel intermittently shows the frame scaled by 1.252
     * horizontally and 1.061 vertically about the top-left, while every size the
     * code logs reads correct. 1280/1.252 and 960/1.061 land near 1024x896, so
     * the suspicion is that LSFG renders for the size it was TOLD while the
     * window's buffer is a different one. getSurfaceFrame() is the buffer's real
     * size and is the one quantity nothing has ever printed.
     *
     * <p>Deliberately not a fix: re-binding from observed buffer sizes was tried
     * and made things worse (see the surfaceChanged comment). Measure first.
     */
    /** Last AHB-vs-context state reported, so the line prints on change only. */
    private static String lastAhbState;

    /**
     * Report when the capture AHB is not the size LSFG's input slot expects.
     *
     * <p>THE failure that produced every "crop" in this file: LSFG copies the
     * AHB into its slot 1:1 from the top-left, clamped to the min of the two
     * extents, so an AHB larger than the context is silently cropped and then
     * blown up to fill the panel. Nothing compared the two — every logged size
     * was Java's intent, and after a context rebuild those all agree.
     */
    private static void reportAhbMismatch() {
        try {
            int n = nativeStats(statsBuf);
            if (n < 14) return;
            int aw = (int) statsBuf[12], ah = (int) statsBuf[13];
            if (aw <= 0 || ah <= 0) { lastAhbState = null; return; }
            String state = aw + "x" + ah + "|" + contextCaptureW + "x" + contextCaptureH;
            if (state.equals(lastAhbState)) return;
            lastAhbState = state;
            if (aw == contextCaptureW && ah == contextCaptureH) return;
            Activity a = hostActivity;
            if (a == null) a = ToggleWatchdog.foregroundActivity();
            note(a != null ? a.getExternalFilesDir(null) : null,
                    String.format(java.util.Locale.US,
                            "AHB MISMATCH: capture buffers are %dx%d but LSFG's"
                            + " context is %dx%d — picture would be %s x%.3f y%.3f",
                            aw, ah, contextCaptureW, contextCaptureH,
                            aw > contextCaptureW ? "CROPPED" : "corner-boxed",
                            contextCaptureW > 0 ? (double) aw / contextCaptureW : 0.0,
                            contextCaptureH > 0 ? (double) ah / contextCaptureH : 0.0));
        } catch (Throwable ignored) { }
    }

    private static void reportOutputBufferDrift() {
        try {
            if (outputView == null) { lastBufferDrift = null; return; }
            android.graphics.Rect sf = outputView.getHolder().getSurfaceFrame();
            int bw = sf.width(), bh = sf.height();
            if (bw <= 0 || bh <= 0) return;
            int vw = outputView.getWidth(), vh = outputView.getHeight();
            String state = bw + "x" + bh + "|" + outputSurfaceW + "x" + outputSurfaceH
                    + "|" + vw + "x" + vh;
            if (state.equals(lastBufferDrift)) return;
            lastBufferDrift = state;
            boolean drift = (bw != outputSurfaceW || bh != outputSurfaceH);
            Activity a = hostActivity;
            if (a == null) a = ToggleWatchdog.foregroundActivity();
            note(a != null ? a.getExternalFilesDir(null) : null,
                    (drift ? "OUTPUT BUFFER DRIFT: " : "output buffer ok: ")
                    + "buffer " + bw + "x" + bh
                    + ", LSFG told " + outputSurfaceW + "x" + outputSurfaceH
                    + ", view " + vw + "x" + vh
                    + (drift ? String.format(java.util.Locale.US,
                            "  -> would scale x%.3f y%.3f",
                            (double) outputSurfaceW / bw, (double) outputSurfaceH / bh)
                       : ""));
        } catch (Throwable ignored) { }
    }

    /** Consecutive OSD ticks with capture pushing but nothing changing. */
    private static int frozenTicks;
    /** uptimeMillis of the last latch drop, so a false positive cannot loop. */
    private static long lastFrozenDropMs;

    /** Reset timing when patch changes reboot the VM without recreating its activity. */
    static void onVmReset(Context ctx) {
        resetCostGuard();
        lastSampleNs = 0;
        heldUniqueFps = heldCapFps = heldGenFps = heldPostFps = 0;
        frozenTicks = frozenDrops = 0;
        try { nativeDropLatch(); } catch (Throwable ignored) { }
        // A fresh boot may recover a previous automatic shutdown; an explicit
        // user-off remains off. The context lifecycle still owns capture pauses.
        if (costGuardDisabled && overlayRuntimeEnabled && ctx != null) {
            costGuardDisabled = false;
            enableOverlay(ctx);
        }
        Log.i(TAG, "framegen timing reset for VM restart");
    }

    /** A new game, or the user turning framegen back on, deserves a fresh baseline. */
    private static void resetCostGuard() {
        captureFallbackDone = false;
        pushedEver = 0;
        neverPushedTicks = 0;
        costTripped = false;
        losingTicks = 0;
        costWindowAt = 0;
        costWindowFill = 0;
        java.util.Arrays.fill(costWindow, 0.0);
    }

    /** Clear the samples but keep the trip state — used after a multiplier step-down. */
    private static void resetCostGuardWindowOnly() {
        costWindowAt = 0;
        costWindowFill = 0;
        java.util.Arrays.fill(costWindow, 0.0);
    }

    /** Recent median source cadence, used only to select the automatic multiplier. */
    private static double costBaseline() {
        if (costWindowFill <= 0) return 0.0;
        double[] sorted = java.util.Arrays.copyOf(costWindow, costWindowFill);
        java.util.Arrays.sort(sorted);
        return Math.min(sorted[costWindowFill / 2], COST_MAX_BASELINE);
    }

    /**
     * Choose the LSFG multiplier so the output fills the panel.
     *
     * <p>PS2 titles are natively 60, 50 or 30, and the old fixed 2 could only
     * ever double whatever it was given: a 30 fps game on a 120 Hz panel
     * produced 60 and left half the refresh idle. Measured on Ultimate
     * Spider-Man, 2 gives `out 59` and 4 gives `out 119` from the same
     * unchanged `real 30`.
     *
     * <p>Reads the same median window the cost guard maintains, so it inherits
     * all of that machinery's hard-won robustness: a full 48 s of samples before
     * it is believed, immune to the 60 fps boot logos that sit in front of a
     * 30 fps game, and reset per game. Rounds DOWN, so the output never asks
     * for more than the panel can scan out.
     */
    private static void autoMultiplier(long capturePaused) {
        if (!fgMultiplierAuto || costTripped || sessionDisabled || !contextUp
                || capturePaused != 0 || irRecovering || irPausePending) {
            autoMultTicks = 0;
            return;
        }
        final double src = costBaseline();
        if (costWindowFill < COST_WINDOW || src < 20.0) {
            autoMultTicks = 0;
            return;
        }
        /* ROUND, then check the product — do not floor.
         *
         * Flooring is brittle exactly where it matters: a 30 fps title whose
         * median lands at 31 gives floor(120/31) = 3, so the panel gets 93
         * instead of 120 because the counter scattered by one. Rounding picks 4
         * and the check below is what keeps it honest, allowing a little over
         * the refresh (the pacing cap absorbs it) but never a whole step. */
        int want = (int) Math.round(displayRefreshHz / src);
        if (want < 2) want = 2;
        if (want > 4) want = 4;
        while (want > 2 && src * want > displayRefreshHz * 1.05) want--;
        if (want == fgMultiplier) {
            autoMultTicks = 0;
            return;
        }
        /* COOLDOWN. A source rate that does not divide the panel cleanly makes
         * this oscillate: USM with the 60 fps patch lands near 52, where
         * 120/52 sits between 2 and 3, and the multiplier changed three times
         * in 70 seconds — each one a context rebuild, each one a visible hitch,
         * and the churn is what left the pipeline wedged. One change a minute
         * is plenty for a cadence that only moves when the scene does. */
        if (lastMultChangeMs != 0
                && android.os.SystemClock.uptimeMillis() - lastMultChangeMs < MULT_COOLDOWN_MS)
            return;
        /* Rebuilding the context is a visible hitch, so only for a rate that
         * has been stable for a few seconds on top of the window's own 48. */
        if (++autoMultTicks < 4) return;
        autoMultTicks = 0;
        Log.i(TAG, "framegen multiplier " + fgMultiplier + " -> " + want
                + " (source " + Math.round(src) + " fps, panel "
                + displayRefreshHz + " Hz)");
        setMultiplierAndRebuild(want, "targeting the panel");
    }

    /**
     * Change the multiplier and rebuild the context around it.
     *
     * <p>LSFG bakes the multiplier in at initContext, so there is no way to
     * change it in place. Stopping the context is NOT enough on its own — the
     * first version of this did exactly that and left the OSD reading
     * "FRAMEGEN waiting, gen 0 out 0" forever, because the only thing that
     * starts a context is a GS settle and the source extent had not changed, so
     * no settle was ever going to arrive. Start it again explicitly.
     */
    /** Last ctx/capture-size rebuild, so a stuck mismatch cannot rebuild on a loop. */
    private static long lastMismatchRebuildMs;

    /**
     * Rebuild the LSFG context when it was built for a different size than
     * capture is pushing.
     *
     * <p>A CONTEXT BUILT FOR THE WRONG SIZE IS A BROKEN PICTURE, NOT A WARNING.
     * The context is built once from the GS extent expected at startup. Change
     * Internal Resolution afterwards and the expectation updates, capture
     * follows it, but the context keeps its original size: measured 2026-08-17
     * as "CAPTURE STALE: ctx 1024x896, pushing 896x784", which renders the game
     * into the top-left of the panel with black bands down the right and along
     * the bottom while every rate counter reads perfect (out 121, skipped 0).
     * Only restarting the game cleared it, and the whole IR-change path looked
     * broken because of it.
     *
     * <p>The detector for this already existed and only printed a warning. It
     * reuses the multiplier-change teardown, which is the one restart sequence
     * in this file that is known not to trip the crash it carries a tombstone
     * for: stop the context, then rebuild one tick later, never in the same
     * pass.
     */
    private static void rebuildForCaptureSize(long capturePaused) {
        if (capturePaused != 0 || irPausePending || irRecovering) {
            /* These three reset the counter, so a flag stuck on does not merely
             * delay the rebuild -- it disables it for the life of the process
             * and the size mismatch never heals. Name the one responsible. */
            if (++mismatchBlockedLogs <= 6) {
                Activity a0 = hostActivity != null
                        ? hostActivity : ToggleWatchdog.foregroundActivity();
                if (a0 != null)
                    note(a0.getExternalFilesDir(null),
                            "capture-size rebuild BLOCKED (capturePaused="
                            + capturePaused + " irPausePending=" + irPausePending
                            + " irRecovering=" + irRecovering + ") — ctx "
                            + contextCaptureW + "x" + contextCaptureH
                            + " vs capture " + captureW + "x" + captureH);
            }
            captureMismatchTicks = 0;
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMismatchRebuildMs < 10000L) return;
        lastMismatchRebuildMs = now;
        captureMismatchTicks = 0;
        final Activity act = hostActivity != null
                ? hostActivity : ToggleWatchdog.foregroundActivity();
        if (act == null) return;
        java.io.File files = act.getExternalFilesDir(null);
        if (files != null)
            note(files, "context was built for " + contextCaptureW + "x" + contextCaptureH
                    + " but capture is pushing " + captureW + "x" + captureH
                    + " - rebuilding at the new size");
        if (contextUp) stopContext(act);
        scheduleMultiplierRestart(act, 0);
    }

    private static void setMultiplierAndRebuild(int mult, final String why) {
        fgMultiplier = mult;
        final Activity act = hostActivity != null
                ? hostActivity : ToggleWatchdog.foregroundActivity();
        if (act == null) return;
        java.io.File files = act.getExternalFilesDir(null);
        if (files != null)
            note(files, "framegen multiplier -> " + mult + "x (" + why + ")");
        if (contextUp) stopContext(act);
        /* Off the current tick: stopContext tears down the offscreen reader and
         * the native context, and rebuilding on top of that teardown in the
         * same pass is the shape of the crash this file already carries a
         * tombstone for. One frame of daylight is enough. */
        lastMultChangeMs = android.os.SystemClock.uptimeMillis();
        scheduleMultiplierRestart(act, 0);
    }

    /**
     * Bring the pipeline back after a multiplier change, and KEEP TRYING.
     *
     * <p>A single delayed start is not enough. startContext refuses while
     * {@code contextStarting} is still set from a previous attempt, so one
     * missed window leaves framegen down permanently with no retry — measured
     * on USM with the 60 fps patch, where three multiplier changes landed in
     * 70 s and the pipeline ended stuck at "waiting, out 0" while the counters
     * showed it had been generating happily moments before.
     */
    private static void scheduleMultiplierRestart(final Activity act, final int attempt) {
        if (attempt >= 6) {
            java.io.File f = act.getExternalFilesDir(null);
            if (f != null)
                note(f, "framegen could not restart after the multiplier change");
            return;
        }
        if (osdTickHandler == null)
            osdTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        osdTickHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (sessionDisabled || !overlayRuntimeEnabled) return;
                if (contextUp) return;                  /* it came back */
                if (contextStarting) {                  /* in flight — wait it out */
                    scheduleMultiplierRestart(act, attempt + 1);
                    return;
                }
                try {
                    startPipelineOffscreen(act);
                } catch (Throwable t) {
                    java.io.File f = act.getExternalFilesDir(null);
                    if (f != null) note(f, "multiplier rebuild failed: " + t);
                }
                scheduleMultiplierRestart(act, attempt + 1);
            }
        }, attempt == 0 ? 250L : 1500L);
    }

    /**
     * False whenever a rate reading cannot be trusted as GAMEPLAY.
     *
     * Both watchdogs below latch something off permanently, and both have now
     * fired on a transient rather than a fault: the cost guard read "22 fps"
     * while the pause menu was open and disabled framegen for the session, and
     * the frozen-source guard dropped the GS latch every 30 s against a game
     * that was simply sitting still with the app not resumed. A paused emulator
     * keeps handing over the same frame at 60/s, which is indistinguishable
     * from a dead source unless you first ask whether it is running at all.
     */
    private static boolean gameplaySampleTrustworthy() {
        if (!emuForeground) return false;
        Activity a = hostActivity != null
                ? hostActivity : ToggleWatchdog.foregroundActivity();
        if (a == null || !isEmulationActivity(a)) return false;
        try {
            if (dialogWindowPresent(a)) return false;
        } catch (Throwable ignored) {
        }
        return true;
    }

    static boolean outputAddsFrames(double sourceFps, double outputFps) {
        return sourceFps > 5.0 && outputFps >= sourceFps * 1.15;
    }

    private static void frameCostGuard(double realFps, double postFps, long capturePaused) {
        /* Never trip on a menu/background transient — see helper above. */
        if (!gameplaySampleTrustworthy()) {
            losingTicks = 0;
            resetCostGuardWindowOnly();
            return;
        }
        if (costTripped || sessionDisabled || !contextUp || capturePaused != 0
                || irRecovering || irPausePending || realFps < 1.0) {
            losingTicks = 0;
            resetCostGuardWindowOnly();
            return;
        }
        // Judge whether output adds frames using the current source. Boot logos,
        // video and gameplay can change cadence while the VM stays full speed;
        // their source rates alone cannot establish a framegen performance cost.
        // Require a full settled window before stepping down or switching off.
        if (losingTicks >= COST_LOSING_TICKS && costWindowFill >= COST_WINDOW) {
            if (fgMultiplier > 2) {
                int lower = fgMultiplier - 1;
                if (lower < 2) lower = 2;
                fgMultiplierAuto = false;
                losingTicks = 0;
                Log.i(TAG, "framegen losing: stepping " + fgMultiplier + "x -> " + lower + "x");
                setMultiplierAndRebuild(lower, "output was not adding frames");
                resetCostGuardWindowOnly();
                return;
            }
            costTripped = true;
            costGuardDisabled = true;
            final String lose = String.format(java.util.Locale.US,
                    "it was not adding frames to the game "
                    + "(%.0f out vs %.0f real) — try a lower internal resolution",
                    postFps, realFps);
            Log.i(TAG, "framegen cost guard tripped: " + lose);
            Activity la = hostActivity != null
                    ? hostActivity : ToggleWatchdog.foregroundActivity();
            if (la != null) {
                java.io.File lf = la.getExternalFilesDir(null);
                if (lf != null)
                    note(lf, "frame generation switched OFF automatically — " + lose);
                try {
                    reportFramegenOff(la, lose);
                } catch (Throwable ignored) {
                }
                disableForSession(la);
            }
            return;
        }
        costWindow[costWindowAt] = realFps;
        costWindowAt = (costWindowAt + 1) % COST_WINDOW;
        if (costWindowFill < COST_WINDOW) costWindowFill++;

    }

    private static final CapturePauses capturePauses = new CapturePauses(
            mask -> nativeSetCapturePauseReasons(mask),
            () -> android.os.SystemClock.elapsedRealtime());
    private static CapturePauses.Lease irPause;
    private static final IrRebuildGate irRebuildGate = new IrRebuildGate();
    private static String irPauseTarget;
    private static long lastPauseDiagnosticMs;

    private static CapturePauses.Lease pauseCapture(CapturePauses.Reason reason, String owner) {
        return capturePauses.acquire(reason, owner, 5000L);
    }

    private static synchronized void holdIrPause(String ir) {
        irPauseTarget = ir;
        if (irPause == null) irPause = pauseCapture(CapturePauses.Reason.IR_CHANGE, "GS resolution");
    }

    private static synchronized void releaseIrPause() {
        // Check the latest request, so an older GS callback cannot resume a
        // newer IR change. Use the core's applied setting, not a guessed base
        // size: PS2 games do not all render at 512x448.
        if (irPause != null && gsHasSettledForIr()
                && (irPauseTarget == null || TurnipConfig.isIrConfirmed(irPauseTarget))) {
            irPause.close();
            irPause = null;
            irPauseTarget = null;
            // Keep the stable size basis until the replacement context is ready.
            // Another click during its asynchronous init still needs that basis.
        }
    }

    private static synchronized void cancelIrPause() {
        if (irPause != null) { irPause.close(); irPause = null; }
        irPauseTarget = null;
        irRebuildGate.clear();
        irExpectedGsW = irExpectedGsH = 0;
    }

    private static void queueContextDestroy() {
        final CapturePauses.Lease destroying = pauseCapture(
                CapturePauses.Reason.CONTEXT_REBUILD, "context destroy");
        boolean queued = ctxWorker().post(() -> {
            try { NativeBridge.INSTANCE.destroyContext(); }
            catch (Throwable t) { Log.w(TAG, "framegen destroy failed", t); }
            finally { destroying.close(); }
        });
        if (!queued) destroying.close();
    }

    private static void watchdogStuckPause(long capturePaused, long gsLatched) {
        String overdue = capturePauses.overdue();
        if (overdue.isEmpty()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPauseDiagnosticMs >= 5000L) {
            lastPauseDiagnosticMs = now;
            Log.i(TAG, "framegen pause deadline: " + overdue);
        }
        // Only the IR owner can recover its lease, after an observed GS settle.
        // A slow init/destroy/bind must keep capture stopped until its finally block.
        if (!irRecovering && !irPausePending && gsLatched != 0 && gsHasSettledForIr())
            releaseIrPause();
    }

    private static void watchdogFrozenSource(double uniqueFps, double capFps,
                                             long capturePaused, long posted,
                                             long generated) {
        /* A paused or backgrounded emulator is legitimately 0-unique. Dropping
         * the latch there is a visible hitch that fixes nothing. */
        if (!gameplaySampleTrustworthy()) return;
        /* OUTPUT IS THE TEST, NOT UNIQUENESS (corrected 2026-08-17).
         *
         * `uniq` counts frames whose content differs from the previous one, so
         * it goes to zero on any genuinely still scene — and a PS2 game left
         * standing on a rooftop is still. Measured directly: "live (panel),
         * real 60 gen 60 out 120 fps, posted 14022 gen 7017, uniq 0". That is a
         * flawless pipeline, and the old test called it frozen: the watchdog
         * fired every 30 s and the escalation below then rejected the GS target
         * and rebuilt capture, twice, against a pipeline that was working.
         *
         * A pipeline that is genuinely dead cannot post: the real incident read
         * "pushed 11906 skipped 3994 posted 0 gen 0". So if posted or generated
         * moved since the last tick, there is nothing wrong here whatever `uniq`
         * says. */
        boolean producing = posted > lastFrozenPosted || generated > lastFrozenGen;
        lastFrozenPosted = posted;
        lastFrozenGen = generated;
        /* `producing` gates the ESCALATION, not the detection.
         *
         * setSkipDuplicateCapture(true) means LSFG keeps posting whatever it is
         * handed, so `producing` stays true even when the source is a target
         * nobody renders into — the very failure this watchdog exists for. Using
         * it as an early return therefore made the wrong-latch case invisible to
         * every guard in the system, native and Java.
         *
         * So: still note it (the log is the only way that class of fault is
         * currently observable at all), but never tear anything down while
         * frames are being produced. That is what caused the previous revert. */
        if (!contextUp || capturePaused != 0 || capFps < 20.0 || uniqueFps > 0.5) {
            frozenTicks = 0;
            if (uniqueFps > 0.5) frozenDrops = 0;
            return;
        }
        if (++frozenTicks < 8) return;          /* ~8 s of pushing, zero change */

        /* DEAD LATCH: FALL BACK TO SWAPCHAIN CAPTURE.
         *
         * The gs route picks a render target by extent, and it can settle on one
         * that PCSX2 stops drawing into. Measured 2026-08-17: every counter
         * healthy (real 59, gen 61, out 123 fps, 99% speed) with the panel PURE
         * BLACK, luma 0 and max 0, uniq pinned at 0. Rate counters cannot see
         * this because the rates are all correct; only the content is wrong.
         *
         * uniq==0 alone cannot tell a dead latch from a parked game, which is
         * why every previous attempt here tore down working pipelines and was
         * reverted. This one does not need to tell them apart: the swapchain
         * holds whatever PCSX2 actually presented, so switching to it is
         * CORRECT IN BOTH CASES. A genuinely still scene looks identical
         * through either route and loses nothing; a dead latch is repaired.
         * That is why this is safe where a teardown was not.
         *
         * Written to turnip.conf rather than called directly: fg_capture_src is
         * hot-reloaded by the shim, so the conf is already the supported channel
         * and no new JNI entry point is needed. */
        if (!captureFallbackDone) {
            captureFallbackDone = true;
            Activity fa = hostActivity != null
                    ? hostActivity : ToggleWatchdog.foregroundActivity();
            java.io.File ffiles = fa != null ? fa.getExternalFilesDir(null) : null;
            if (ffiles != null) {
                java.io.File fconf = new java.io.File(ffiles, "turnip.conf");
                String src = TurnipConfig.readConfKey(fconf, "fg_capture_src");
                boolean onGs = src == null
                        || !src.trim().equalsIgnoreCase("swapchain");
                if (onGs) {
                    /* DO NOT SWITCH TO SWAPCHAIN CAPTURE AT RUNTIME. It cannot work.
                     *
                     * That route needs two things decided BEFORE the swapchain is
                     * created: the image table is filled at vkGetSwapchainImagesKHR
                     * time, and TRANSFER_SRC usage has to be forced at creation.
                     * Flipping the key mid-session satisfies neither, so capture
                     * lands on a source nothing fills and wedges permanently at
                     * "capture idle - src=present image=0", with no way back.
                     * Measured 2026-08-22: framegen generated 866 frames, this
                     * fallback fired during a black fade-in, and the panel froze on
                     * a stale frame for the rest of the session.
                     *
                     * The trigger is unreliable too: it fires on "0 unique", which a
                     * genuinely static scene produces as well -- and a fade-in is
                     * exactly that. Record the preference for NEXT launch instead,
                     * and let the GS latch recovery below do its job now. */
                    /* DO NOT RECORD A CAPTURE-SOURCE PREFERENCE HERE EITHER.
                     *
                     * Writing fg_capture_src_next=swapchain into the user's conf
                     * only deferred the damage by one launch. On the next start it
                     * promotes to swapchain capture, which wedges exactly as the
                     * comment above predicts: `pushed` climbs with posted 0 gen 0
                     * and the game sits at ~23 fps FROM BOOT, surviving
                     * force-stop, reinstall and cache clears. Measured
                     * 2026-08-27 -- it reads as failing hardware or thermal
                     * throttling (it was neither), and the cost guard then trips
                     * on the 23 fps this caused and blames the game. Deleting the
                     * key restored 58.9 fps instantly.
                     *
                     * The trigger is a known false positive on a still scene, so
                     * it must not leave PERSISTENT state behind. Log and drop the
                     * latch; that is all this is allowed to do. */
                    note(ffiles, String.format(java.util.Locale.US,
                            "capture source static for 8 s at %.0f fps. NOT"
                            + " recording a swapchain-capture preference: that"
                            + " key wedges the next launch at ~23 fps. Dropping"
                            + " the GS latch instead.",
                            capFps));
                    frozenTicks = 0;
                    /* fall through to the latch drop */
                }
            }
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFrozenDropMs < 30000L) return;
        lastFrozenDropMs = now;
        frozenTicks = 0;
        Activity a = hostActivity;
        if (a == null) a = ToggleWatchdog.foregroundActivity();
        note(a != null ? a.getExternalFilesDir(null) : null,
                String.format(java.util.Locale.US,
                        "frozen source: %.0f captures/s but 0 unique for 8 ticks"
                        + " — dropping the GS latch to re-take it%s",
                        capFps, producing
                                ? " (output still flowing, but that proves"
                                  + " nothing: LSFG posts black frames too)"
                                : ""));
        /* ESCALATE if a plain drop already failed. fg_note_current_gs re-takes
         * the same handle on the very next frame, so against a target that is
         * reported but never drawn into, dropping alone is an infinite loop —
         * measured as seven identical fires 30 s apart. The second and later
         * fires refuse that handle and rebuild capture instead.
         *
         * The first fire stays cheap on purpose: this watchdog's known false
         * positive is a genuinely static scene, and there a drop-and-retake is
         * invisible while a reject-and-rebuild would not be. */
        /* Not gated on `producing` either — see the drop below for why it cannot
         * distinguish a dead source. The guard against the still-scene false
         * positive is TIME: frozenDrops >= 2 means the condition survived a drop
         * and ~60 s of continuous zero-unique. A genuinely parked game paying a
         * one-off ~5 s rebuild after a minute of stillness is a far better trade
         * than a black screen nothing can clear. */
        /* NO AUTOMATIC REJECTION. Tried on 2026-08-17 and reverted the same
         * night: it never once cleared a frozen source, and it is actively
         * dangerous. Measured while black — drop, reject, and even a full
         * capture rebuild all fired on a 30 s cycle and the content stayed
         * static, because the render-pass hook was faithfully reporting two live
         * handles at the right extent and PCSX2 was presenting from NEITHER of
         * them. Nothing at the latch layer can fix that.
         *
         * Meanwhile the reject list refuses a handle for 15 s, so on a parked
         * game (uniq 0 is normal there) it blacklists the GOOD target and the
         * screen goes black the moment the camera moves again — a black screen
         * manufactured by the thing meant to prevent one.
         *
         * The drop below stays: it is free, it re-latches next frame, and it is
         * the only step that has ever helped. */
        try { nativeDropLatch(); } catch (Throwable ignored) { }
    }

    /** Consecutive frozen-source fires; reset whenever the source recovers. */
    private static int frozenDrops;
    /** Output counters at the previous tick — "is the pipeline producing?". */
    private static long lastFrozenPosted, lastFrozenGen;
    /** Consecutive ticks spent re-asserting an output surface LSFG dropped. */
    private static int outputReassertTicks;
    /** Last anyStatOn result — log the gate only when it flips. */
    private static boolean lastAnyStatOn = true;
    /** Cached OSD-gate decision; recomputed at most every 2 s. */

    /**
     * Re-assert the output surface while the pipeline is up but producing
     * nothing.
     *
     * LSFG's render loop initialises ASYNCHRONOUSLY and takes about 4.5 s
     * (measured: init 03:18:49, "Render loop initialised" 03:18:53.9).
     * startContext calls setOutputSurface long before that, and a call made
     * before the loop exists is DROPPED — silently, with no return code. LSFG's
     * own log then shows no "Output surface attached" line at all until
     * teardown, and the OSD reads "no source ... posted 0 gen 0".
     *
     * The FIRST game of a session survives this by accident: the panel attach
     * calls setOutputSurface a second time, after the loop is ready. Every
     * later game in the same process deadlocks instead, because attaching the
     * panel requires live frames (watchdogOutputOverlay) and frames require the
     * output surface — so neither can go first. That is the "framegen dies on
     * the second launch" bug.
     *
     * Bounded and idempotent: only while nothing has been produced, at most
     * once per tick, and it stops as soon as a frame lands.
     */
    private static void watchdogOutputSurfaceLost(long posted, long generated) {
        if (!contextUp || posted > 0 || generated > 0 || outputView != null) {
            outputReassertTicks = 0;
            return;
        }
        if (++outputReassertTicks > 30) return;   /* ~30 s, then give up */
        /* DO NOT ATTACH THE PANEL AS A RESCUE HERE — TRIED, AND IT IS WORSE.
         *
         * The reasoning looked sound: our ImageReader is a CPU-consumer queue,
         * so LSFG refuses a swapchain on it ("window in use by CPU producer
         * (rc=-1000000001) — WSI disabled ... using CPU blit"), while the panel
         * is a SurfaceView and takes the WSI path. But attaching the panel did
         * NOT make LSFG produce: it stayed at posted=0 and the panel then
         * covered the running game with pure black, which is strictly worse than
         * the half-resolution-but-visible picture you get by leaving it alone.
         *
         * So the output surface is NOT what is wrong on a second launch. LSFG
         * accepts frames (pushed 2615) and emits nothing regardless of where it
         * is asked to present. Whatever is broken is inside its render loop
         * after a context rebuild in the same process. */
        android.view.Surface s = null;
        int w = captureW, h = captureH;
        try {
            if (offscreenReader != null) s = offscreenReader.getSurface();
        } catch (Throwable ignored) { }
        if (s == null || !s.isValid()) return;
        bindOutputSurfaceSafely(s, w, h);
        if (outputReassertTicks == 1 || outputReassertTicks == 6)
            note(hostActivity != null
                         ? hostActivity.getExternalFilesDir(null) : null,
                    "output produced nothing yet — re-asserting the offscreen "
                    + "surface " + w + "x" + h + " (attempt "
                    + outputReassertTicks + ")");
    }

    /** Promote/maintain the output panel when the instrument is switched off. */
    private static void watchdogOutputOverlay(long posted, long generated) {
        long postedDelta = posted - outputLivePostedBaseline;
        long genDelta = generated - outputLiveGenBaseline;
        long now = android.os.SystemClock.uptimeMillis();
        boolean live = postedDelta >= OUTPUT_LIVE_FRAME_MIN
                || genDelta >= OUTPUT_LIVE_FRAME_MIN;
        if (live) {
            lastLiveOutputMs = now;
            if (mayShowOutputPanel()) {
                Activity act = hostActivity;
                if (act == null) act = ToggleWatchdog.foregroundActivity();
                if (outputView == null && act != null && contextUp && outputHasLiveFrames())
                    attachOnScreenOutput(act);
                else if (!outputPromoted && outputView != null)
                    tryPromoteOutput();
            }
            return;
        }
        if (!mayShowOutputPanel() || outputView == null) return;
        if (lastLiveOutputMs == 0) lastLiveOutputMs = now;
        if (now - lastLiveOutputMs < OUTPUT_STALE_MS) return;
        File files = hostActivity != null
                ? hostActivity.getExternalFilesDir(null) : null;
        note(files, "output overlay removed (no live frames) — game visible");
        detachOnScreenOutputOnly();
        if (contextUp && offscreenReader != null) {
            bindOutputSurfaceSafely(
                    offscreenReader.getSurface(), captureW, captureH);
        }
        lastLiveOutputMs = 0;
    }

    /** True once LSFG has posted enough frames since the last IR recovery baseline. */
    private static boolean outputHasLiveFrames() {
        if (!contextUp || !mayShowOutputPanel()) return false;
        try {
            long posted = NativeBridge.INSTANCE.getPostedFrameCount();
            long generated = NativeBridge.INSTANCE.getGeneratedFrameCount();
            long postedDelta = posted - outputLivePostedBaseline;
            long genDelta = generated - outputLiveGenBaseline;
            return postedDelta >= OUTPUT_LIVE_FRAME_MIN
                    || genDelta >= OUTPUT_LIVE_FRAME_MIN;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cancelGsSettleTimeout() {
        if (gsSettleHandler != null && gsSettleTimeout != null)
            gsSettleHandler.removeCallbacks(gsSettleTimeout);
    }

    private static void scheduleIrRecoveryFallback(final Context ctx, long delayMs) {
        if (ctx == null || !irPausePending) return;
        cancelGsSettleTimeout();
        final Context app = ctx.getApplicationContext();
        final android.os.Handler h = new android.os.Handler(
                android.os.Looper.getMainLooper());
        gsSettleHandler = h;
        gsSettleTimeout = () -> {
            if (!irPausePending) return;
            if (gsHasSettledForIr()) {
                note(app.getExternalFilesDir(null),
                        "IR recovery: GS settled via stats");
                finishIrRecovery(app, true);
            } else {
                note(app.getExternalFilesDir(null),
                        "IR recovery fallback (" + delayMs + "ms)");
                finishIrRecovery(app, false);
            }
        };
        h.postDelayed(gsSettleTimeout, delayMs);
    }

    private static void ensurePipelineStarted(final Activity activity) {
        if (activity == null || !ready() || !overlayAdded
                || !overlayRuntimeEnabled || sessionDisabled) return;
        if (irPausePending) {
            scheduleIrRecoveryFallback(activity, 2500);
            return;
        }
        if (!contextUp)
            startPipelineOffscreen(activity);
    }

    /**
     * Rebind output surface after GS resize without resetting capture or context.
     */
    private static void rebindOutputSurface(Activity act) {
        if (act == null || !ready() || !overlayAdded || !contextUp) return;
        hostActivity = act;
        try {
            android.view.Surface out = null;
            int outW = captureW;
            int outH = captureH;
            if (outputView != null) {
                android.view.Surface panel = outputView.getHolder().getSurface();
                if (panel != null && panel.isValid()) {
                    out = panel;
                    /* The on-screen panel must be told the PANEL size.
                     *
                     * startPipelineOffscreen() sets outputSurfaceW/H to the
                     * CAPTURE size for its ImageReader, and nothing restores
                     * them when the panel comes back — so after a live IR change
                     * this handed LSFG 640x480 for a 1280x960 window and the
                     * game rendered into the top-left quarter with black around
                     * it. That is the "downsizes to 1/4" report.
                     *
                     * Take the size from the SurfaceView itself, which is the
                     * window it actually occupies. NOTE FOR FUTURE ME: frame-rate
                     * measurements stay a perfect 119.9/60.0 while the screen is
                     * quartered — this bug is invisible to present-rate metrics.
                     * Verify with a SCREENSHOT, never with fps alone. */
                    int vw = outputView.getWidth();
                    int vh = outputView.getHeight();
                    if (vw <= 0 || vh <= 0) {
                        /* Panel is attached but not laid out yet. Falling back to
                         * captureW/H HERE is the bug: it binds the on-screen panel
                         * at capture size and that sticks, so after an IR change
                         * the game renders into a 480x360 corner of a 1280x960
                         * window. Whatever surfaceCreated() already bound is
                         * correct — leave it alone and let the next tick rebind
                         * once the view has a size. */
                        return;
                    }
                    outW = vw;
                    outH = vh;
                    outputSurfaceW = vw;
                    outputSurfaceH = vh;
                }
            } else if (offscreenReader != null) {
                out = offscreenReader.getSurface();
            }
            if (out != null && out.isValid())
                bindOutputSurfaceSafely(out, outW, outH);
        } catch (Throwable t) {
            note(act.getExternalFilesDir(null), "rebindOutputSurface failed: " + t);
        }
    }

    /**
     * Full IR recovery: reset capture latch after hot reload destroyed context.
     */
    private static void reconcileAfterGsResize(Activity act) {
        if (act == null || !ready() || !overlayAdded) return;
        if (irPausePending || irRecovering) return;
        hostActivity = act;
        File files = act.getExternalFilesDir(null);
        note(files, "framegen reconcile after GS resize");
        releaseIrPause();
        if (!contextUp) {
            startPipelineOffscreen(act);
            schedulePromotionRetries(act);
            return;
        }
        rebindOutputSurface(act);
    }

    private static synchronized void hotReloadAfterIrChange(Context ctx) {
        /* Legacy entry — redirect to settle-driven recovery. */
        finishIrRecovery(ctx);
    }

    /** Re-stack OSD above the promoted framegen output after resume. */
    public static void onEmulationResumed(Activity activity) {
        if (activity == null) return;
        emuForeground = true;
        hostActivity = activity;
        if (refreshDisplayEligibility(activity)
                && confSaysOverlayOn(activity) && !sessionDisabled) {
            overlayRuntimeEnabled = true;
            if (ready() && !overlayAdded)
                attachOverlay(activity);
            else if (!ready())
                enableOverlay(activity);
        }
        // Resume does not own a pending context or output-bind pause.
        attachOsdIfEnabled(activity);
        ensureOsdTicking();
        scheduleOsdIrRecovery(activity);
        OsdPrefs.updateStockOsdPolicy(activity);
        if (irPausePending)
            scheduleIrRecoveryFallback(activity, 1200L);
        else if (!osdIsWindowHealthy())
            raiseOsdAboveOutput(activity, 200);
        if (!irPausePending && overlayAdded && !contextUp)
            activity.getWindow().getDecorView().postDelayed(
                    () -> ensurePipelineStarted(activity), 400);
    }

    /**
     * Adds the frame generator's output SurfaceView on top of the emulator's
     * own, inside EmulationActivity.
     *
     * <p>This is the part that makes the in-process route worth having. The
     * standalone app has to host its output in a SYSTEM_ALERT_WINDOW or an
     * accessibility overlay, which is what OEM game modes veto — on this
     * device the overlay lived 83ms before being torn down, taking the render
     * loop with it. A SurfaceView owned by the foreground activity is just
     * part of the app's own view hierarchy: nothing to grant and nothing to
     * revoke.
     *
     * <p>setZOrderMediaOverlay puts it above the emulator's SurfaceView but
     * still below the window's normal children, so the existing on-screen
     * controls keep drawing over it.
     */
    public static void attachOverlay(final Activity activity) {
        if (overlayAdded || activity == null || !ready()
                || sessionDisabled || !overlayRuntimeEnabled) return;
        overlayAdded = true;
        hostActivity = activity;
        outputPromoted = false;
        lastLiveOutputMs = 0;
        if (!osdIsWindowHealthy()) attachOsd(activity, null);
        OsdPrefs.updateStockOsdPolicy(activity);
        /* No WM SurfaceView here — LSFG runs offscreen until posted>0.
         * Adding a panel window at attach time covered the game with black
         * when the pipeline was not yet posting (context down / IR reload). */
        final android.view.View decor = activity.getWindow().getDecorView();
        decor.postDelayed(new Runnable() {
            @Override public void run() {
                ensurePipelineStarted(activity);
            }
        }, 400);
        decor.postDelayed(new Runnable() {
            @Override public void run() {
                ensurePipelineStarted(activity);
            }
        }, 2000);
    }

    private static android.view.SurfaceView outputView = null;
    /** Panel-window root: holds outputView (media overlay) below the OSD.
     *  The OSD must live INSIDE the output panel window — it is a separate
     *  window above the decor, so no view-level bringToFront/setZ on a
     *  decor-attached OSD can ever rise above it. */
    private static android.widget.FrameLayout outputContainer = null;
    private static android.view.WindowManager.LayoutParams outputLp = null;
    private static android.view.WindowManager outputWm = null;
    private static boolean outputPromoted = false;
    private static boolean outputResizing = false;
    /** GS reopen must not permanently stopContext via surfaceDestroyed. */
    private static volatile boolean irRecovering = false;
    private static volatile boolean irPausePending = false;
    private static volatile boolean sessionDisabled = false;
    private static volatile boolean overlayRuntimeEnabled = true;
    private static volatile boolean irRestartPromptShown = false;
    private static android.os.Handler irRestartPromptHandler = null;
    private static Runnable irRestartPromptCheck = null;
    private static final long IR_HOT_RELOAD_CHECK_MS = 5000L;
    /** ~60s: LSFG init scales with capture area and is slow at high IR. */
    private static final int IR_HOT_RELOAD_MAX_WAITS = 12;
    private static volatile int irExpectedGsW = 0;
    private static volatile int irExpectedGsH = 0;
    private static volatile String lastHotReloadScheduleIr = null;
    private static volatile long lastHotReloadScheduleMs = 0;
    private static final long IR_REQUEST_WINDOW_MS = 15000L;
    private static android.os.Handler gsSettleHandler = null;
    private static Runnable gsSettleTimeout = null;
    private static int promoteRetryGen = 0;
    private static Activity hostActivity = null;
    /** Dimensions passed to setOutputSurface — panel size after promotion. */
    private static int outputSurfaceW = 480;
    private static int outputSurfaceH = 360;
    private static android.widget.TextView osd = null;
    private static android.view.ViewGroup.MarginLayoutParams osdLp = null;
    private static android.view.WindowManager osdWm = null;
    private static boolean osdWindowAttached = false;
    private static android.os.Handler osdTickHandler = null;
    private static Runnable osdTickRunnable = null;
    private static boolean osdTickScheduled = false;
    /* 14: [12]/[13] are the size the capture AHBs were actually BUILT at, which
     * is what LSFG's input slot gets compared against. Everything else here is
     * Java's own intent, which is why a stale AHB could crop the picture with
     * every figure agreeing. */
    private static final long[] statsBuf = new long[14];
    private static long lastPosted = 0, lastGenerated = 0, lastPushed = 0, lastUnique = 0;
    /** Hold last non-zero counters when LSFG context briefly tears down during IR. */
    private static long lastDispPosted = 0, lastDispGenerated = 0, lastDispPushed = 0;
    private static long lastSampleNs = 0;
    private static int osdWarmupTicks = 0;
    /** Offscreen drain target — LSFG runs here until posted>0, then migrates on-screen. */
    /** Last computed rates, held across a baseline reset so the OSD never blanks. */
    private static double heldUniqueFps, heldCapFps, heldGenFps, heldPostFps;
    /** Consecutive "instrument should be hidden" votes; needs 2 to act. */
    private static int osdHideVotes;
    /** Stall watchdog: when the GS last changed size, and the recovery state. */
    private static long lastGsSettleMs;
    private static int stallTicks;
    private static long lastRelatchMs;
    /** turnip.conf fg_capture=gs — capture tracks the GS target, not a fixed size. */
    private static boolean captureFollowsGs;
    /** fg_capture_w/h was set by hand — never resize the capture out from under it. */
    private static boolean captureExplicit;
    /** Capture size the LIVE LSFG context was built with (vs the wanted one). */
    private static volatile int contextCaptureW, contextCaptureH;
    /** turnip.conf fg_capture_max_mp — cost ceiling for the follow-the-GS path. */
    private static float fgCaptureMaxMp = 0f;
    /** turnip.conf fg_max_ir — GS above this many x native disables framegen. */
    private static float fgMaxIr = 2.5f;
    /** Set while framegen is held off because the GS is above {@link #fgMaxIr}. */
    private static volatile boolean overIrCap;

    /**
     * True when a GS of {@code w}x{@code h} is above the framegen ceiling.
     *
     * <p>Area, not width: 512x448 x IR is the PS2 GS, so the budget scales with
     * IR squared. 2% slack because a game's native height is not always 448.
     */
    private static boolean gsOverIrCap(int w, int h) {
        if (fgMaxIr <= 0f || w <= 0 || h <= 0) return false;
        double cap = 512.0 * 448.0 * fgMaxIr * fgMaxIr;
        return (double) w * h > cap * 1.02;
    }

    /** Approximate IR implied by a GS extent, for messages only. */
    private static double irOfGs(int w, int h) {
        return Math.sqrt(((double) w * h) / (512.0 * 448.0));
    }
    /** Consecutive ticks where framegen output trailed the emulator's own rate. */
    private static int losingTicks;
    private static long lastSuppressedPairs;
    /** prepare() is running on a worker; see enableOverlay. */
    private static volatile boolean preparing;
    private static final android.os.Handler MAIN_FG =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** One swapchain fallback per context; see watchdogFrozenSource(). */
    private static boolean captureFallbackDone;
    /** Ticks with nothing measured and no push history; see the OSD status row. */
    private static int neverPushedTicks;
    /** Highest push count seen for this context, so a rebuild is distinguishable from never arming. */
    private static long pushedEver;
    /* Cost guard. See frameCostGuard(). */
    private static boolean costTripped;
    /** True when the guard, not the user, is the reason framegen is off. */
    private static boolean costGuardDisabled;
    // Cadence history selects the automatic multiplier. It never establishes
    // that framegen caused a slowdown: USM's reboot legitimately changes cadence.
    private static final int COST_WINDOW = 48;
    private static final double[] costWindow = new double[COST_WINDOW];
    private static int costWindowAt;
    private static int costWindowFill;
    private static final double COST_MAX_BASELINE = 60.0;
    /** Consecutive settled samples where output does not add frames. */
    private static final int COST_LOSING_TICKS = 15;
    /** Consecutive ticks where the live context's capture size was the wrong one. */
    private static int captureMismatchTicks;
    private static int mismatchBlockedLogs;

    /**
     * The live LSFG context was built for a different capture size than the one
     * now being pushed into it.
     *
     * <p>LSFG bakes its images at context-build time, so this is not cosmetic: the
     * smaller frame lands in the top-left of the old images and the game ends up in
     * the corner of the panel. {@link #captureW}/{@link #captureH} is only the
     * WANTED size — it changes the instant the GS settles — which is why the OSD
     * read "GS 1024x896 -> capture 1024x896" while the context still held
     * 1536x1344 and the picture was visibly broken.
     */
    private static boolean captureMismatched() {
        return contextUp && contextCaptureW > 0 && contextCaptureH > 0
                && (contextCaptureW != captureW || contextCaptureH != captureH);
    }

    /* Default capture size, derived from the user's Internal Resolution.
     *
     * The old fixed 480x360 default is a RESOLUTION BOTTLENECK: the GS is copied
     * down into the capture buffer, LSFG interpolates at that size, and the
     * result is scaled up to the panel. At IR 3x the GS is 1536x1344 and the
     * user was watching 480x360 blown up to 1280x960 — reported as "resolution
     * is VERY low", and rightly so; that is below even native PS2 (512x448).
     *
     * Deriving it from IR rather than from the live GS extent is deliberate: the
     * IR is known at prepare() time, whereas the GS is not, and LSFG bakes the
     * capture size in once per context. Measured cost at IR 3x (1536x1344):
     * emulator 60.0 / panel 120.0 — no cost at all. */
    private static int defaultCaptureW = 480, defaultCaptureH = 360;

    private static void noteIrCaptureDefault(Context ctx) {
        try {
            String ir = TurnipConfig.readUserIrFile(ctx);
            if (ir == null) return;
            float f = Float.parseFloat(ir.trim());
            if (f < 1f || f > 8f) return;
            int w = ((int) (512f * f)) & ~1;
            int h = ((int) (448f * f)) & ~1;
            defaultCaptureW = Math.max(256, Math.min(2048, w));
            defaultCaptureH = Math.max(224, Math.min(1792, h));
            Log.i(TAG, "framegen: IR " + ir + " -> default capture "
                    + defaultCaptureW + "x" + defaultCaptureH
                    + " (was a fixed 480x360, which threw the IR away)");
        } catch (Throwable ignored) { }
    }
    /** Init is in flight on the ctx worker; stops a second startContext racing it. */
    private static volatile boolean contextStarting;
    /** Producer-side buffer rotation on the output Surface; see the C side. */
    private static native int nativeSetSurfaceTransform(android.view.Surface s, int t);

    private static native void nativeSetPanelRotate(int deg);

    private static native void nativeSetCaptureEnabled(boolean on);

    private static native void nativeSetOutputAspect(float aspect);

    /** Degrees -> NATIVE_WINDOW_TRANSFORM bits. ROT_90=0x04, ROT_180=0x03, ROT_270=0x07. */
    private static int transformForDegrees(int deg) {
        switch (deg) {
            case 90:  return 0x04;
            case 180: return 0x03;
            case 270: return 0x07;
            default:  return 0;
        }
    }

    /** turnip.conf fg_panel_rotate: 0 (default), 90, 180 or 270. */
    /** Legacy route: rotate the output Surface via setBuffersTransform instead of
     *  the native compute pass. Off by default -- with both on, the frame is
     *  rotated twice. Kept only so the old path can be re-tested deliberately. */
    private static boolean rotateViaSurface(Context ctx) {
        try {
            File files = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (files == null) return false;
            String v = TurnipConfig.readConfKey(new File(files, "turnip.conf"),
                                                "fg_rotate_via_surface");
            if (v == null) return false;
            v = v.trim();
            return v.equalsIgnoreCase("on") || v.equals("1")
                    || v.equalsIgnoreCase("true");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int panelRotateDegrees(Context ctx) {
        /* An explicit fg_panel_rotate still wins, for deliberate testing. */
        try {
            File files = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (files != null) {
                String v = TurnipConfig.readConfKey(new File(files, "turnip.conf"),
                                                    "fg_panel_rotate");
                if (v != null && !v.trim().equalsIgnoreCase("auto")) {
                    int d = Integer.parseInt(v.trim());
                    d = ((d % 360) + 360) % 360;
                    return (d == 90 || d == 180 || d == 270) ? d : 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return displayRotateDegrees(ctx);
    }

    /**
     * Rotation the framegen output must apply, derived from the LIVE display.
     *
     * The output swapchain keeps the surface's NATIVE (unrotated) orientation so
     * the compositor stays on its cheap overlay path, which makes pre-rotating
     * the content our job -- and the amount is exactly the display's current
     * rotation. This self-configures per device: on a portrait-native panel
     * (AYN Thor) landscape play is ROTATION_90 so it returns 90, while on a
     * landscape-native panel the same play is ROTATION_0 and it returns 0.
     *
     * It MUST be read live. A fixed value stayed at 90 after the panel returned
     * to portrait, which both mis-rotated the picture and left the output buffer
     * mismatched against its view -- a per-frame rescale that took the emulator
     * from 60 fps to about 20.
     */
    private static int displayRotateDegrees(Context ctx) {
        try {
            if (ctx == null) return 0;
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null || wm.getDefaultDisplay() == null) return 0;
            switch (wm.getDefaultDisplay().getRotation()) {
                case android.view.Surface.ROTATION_90:  return 90;
                case android.view.Surface.ROTATION_180: return 180;
                case android.view.Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Display aspect the framegen output must letterbox to, from the emulator's
     * OWN settings so the picture matches what PCSX2 would have drawn.
     *
     * The output blit used to fill the swapchain, which silently forced every
     * game to widescreen and made the Widescreen toggle and Aspect Ratio setting
     * do nothing while framegen was on.
     *
     * Mirrors EmuCore/GS/AspectRatio, and folds in EnableWideScreenPatches for
     * the Auto case: patches change what the game renders, so Auto + patches is
     * 16:9. An explicit 4:3 or 16:9 is respected as-is, and Stretch returns 0
     * (fill), which is the old behaviour on purpose.
     */
    /** Widescreen setting the RUNNING game booted with; null = nothing latched. */
    private static volatile Boolean bootedWide;

    /**
     * Latch the widescreen setting a game is booting with.
     *
     * <p>The patches are written into EE memory at boot, so the shape of the
     * render is fixed for the life of the VM. computeOutputAspect read the LIVE
     * pref instead, so flipping Widescreen mid-game and answering "Later" to the
     * restart prompt still widened the destination rect on the next 500 ms OSD
     * tick -- a 4:3 render stretched across a 16:9 output, and the reverse
     * squashed. The output has to follow what the game actually booted with.
     */
    static void latchBootedWide(Context ctx) {
        try {
            Object w = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx).getAll()
                    .get("EmuCore/EnableWideScreenPatches");
            bootedWide = Boolean.valueOf(prefTruthy(w));
            Log.i(TAG, "framegen latched booted widescreen=" + bootedWide);
        } catch (Throwable ignored) {
            bootedWide = null;
        }
    }

    /** Cleared when no game is running, so the next boot re-latches. */
    static void clearBootedWide() {
        if (bootedWide != null) {
            bootedWide = null;
            Log.i(TAG, "framegen booted-widescreen latch cleared (no VM)");
        }
    }

    /**
     * Boolean.parseBoolean only accepts "true", so a pref stored as the String
     * "1" -- which is how several keys in this fork arrive -- read as false and
     * silently gave a 4:3 output while the emulator rendered 16:9.
     */
    private static boolean prefTruthy(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        String s = String.valueOf(raw).trim();
        return s.equalsIgnoreCase("true") || s.equals("1")
                || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
    }

    static float computeOutputAspect(Context ctx) {
        /* An explicit fg_aspect in turnip.conf still wins, for testing. */
        try {
            File files = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (files != null) {
                String v = TurnipConfig.readConfKey(
                        new File(files, "turnip.conf"), "fg_aspect");
                if (v != null && !v.trim().equalsIgnoreCase("auto")) {
                    v = v.trim();
                    if (v.equalsIgnoreCase("fill") || v.equalsIgnoreCase("stretch"))
                        return 0f;
                    int c = v.indexOf(':');
                    if (c > 0) {
                        float w = Float.parseFloat(v.substring(0, c));
                        float h = Float.parseFloat(v.substring(c + 1));
                        if (h > 0) return w / h;
                    } else {
                        return Float.parseFloat(v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String ar = null;
        boolean wide = false;
        try {
            java.util.Map<String, ?> all = TurnipConfig.defaultPrefs(ctx).getAll();
            Object a = all.get("EmuCore/GS/AspectRatio");
            if (a != null) ar = String.valueOf(a);
            wide = prefTruthy(all.get("EmuCore/EnableWideScreenPatches"));
        } catch (Throwable ignored) {
        }
        /* A running game keeps the shape it booted with, whatever the pref says
         * now -- see latchBootedWide. Absent a VM the live pref is correct,
         * because the next boot is the one that will use it. */
        Boolean booted = bootedWide;
        if (booted != null) wide = booted.booleanValue();
        // A per-game aspect overrides the global preference. Read the core's
        // effective setting without reopening GS or changing frame timing.
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            if (Boolean.TRUE.equals(nl.getMethod("hasEmulationThread").invoke(null))) {
                Object effective = nl.getMethod("getStringSettingValue", String.class,
                        String.class, String.class).invoke(null,
                        "EmuCore/GS", "AspectRatio", "");
                if (effective instanceof String && !((String) effective).trim().isEmpty())
                    ar = (String) effective;
            }
        } catch (Throwable ignored) { }
        return displayAspectForSetting(ar, wide);
    }

    static float displayAspectForSetting(String setting, boolean wide) {
        String a = setting == null ? "" : setting.trim().toLowerCase(java.util.Locale.US);
        if (a.equals("stretch")) return 0f;
        if (a.equals("4:3")) return 4f / 3f;
        if (a.equals("16:9")) return 16f / 9f;
        // Auto is the only mode that follows the booted widescreen setting.
        // Explicit choices must match the original emulator's display setting.
        return wide ? 16f / 9f : 4f / 3f;
    }

    /** Last aspect handed to the native blit; <0 = never. */
    private static volatile float lastOutputAspect = -1f;

    /** Re-apply the output aspect if the emulator's settings changed. */
    static void syncOutputAspect(Context ctx) {
        try {
            float a = computeOutputAspect(ctx);
            if (Math.abs(a - lastOutputAspect) < 0.0005f) return;
            lastOutputAspect = a;
            nativeSetOutputAspect(a);
            Log.i(TAG, "framegen output aspect -> "
                    + (a == 0f ? "fill/stretch" : String.format(
                            java.util.Locale.US, "%.4f", a)));
        } catch (Throwable ignored) {
        }
    }

    /** Last rotation handed to the native pass; -1 = never. */
    private static volatile int lastPanelRotateDeg = -1;

    /** Re-apply the rotation if the display has turned. Cheap; call often. */
    static void syncPanelRotation(Context ctx) {
        try {
            int deg = panelRotateDegrees(ctx);
            if (deg == lastPanelRotateDeg) return;
            lastPanelRotateDeg = deg;
            nativeSetPanelRotate(deg);
            Log.i(TAG, "framegen panel rotation -> " + deg + " deg (display follow)");
        } catch (Throwable ignored) {
        }
    }

    /** True only while EmulationActivity is resumed — gates the instrument. */
    private static volatile boolean emuForeground;

    /** The activity that hosts a game. Every other screen stays clean. */
    private static boolean isEmulationActivity(Activity a) {
        return a != null && "xyz.aethersx2.android.EmulationActivity"
                .equals(a.getClass().getName());
    }

    /**
     * This display's refresh rate, rounded. Defaults the framegen output cap so
     * the feature behaves on a 60/90/144 Hz device and not just a 120 Hz one.
     */
    private static int displayRefreshHz = 120;

    /** turnip.conf fg_force=on — deliberate override of the refresh-rate refusal. */
    private static boolean forceEnabled(Context ctx) {
        try {
            java.io.File f = ctx == null ? null : ctx.getExternalFilesDir(null);
            String v = f == null ? null
                    : TurnipConfig.readConfKey(new java.io.File(f, "turnip.conf"), "fg_force");
            return v != null && (v.equalsIgnoreCase("on") || v.equals("1")
                    || v.equalsIgnoreCase("true"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void noteDisplayRefresh(Context ctx) {
        try {
            android.view.Display d = null;
            if (ctx instanceof Activity)
                d = ((Activity) ctx).getWindowManager().getDefaultDisplay();
            if (d == null) {
                android.view.WindowManager wm = (android.view.WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) d = wm.getDefaultDisplay();
            }
            if (d == null) return;
            float hz = d.getRefreshRate();
            /* Take the highest mode the display can actually run: Android may
             * report the current (possibly throttled) mode, and the compositor
             * can switch up once we start presenting. */
            try {
                for (android.view.Display.Mode m : d.getSupportedModes())
                    if (m.getRefreshRate() > hz) hz = m.getRefreshRate();
            } catch (Throwable ignored) { }
            if (hz >= 30f && hz <= 240f) {
                displayRefreshHz = Math.round(hz);
                Log.i(TAG, "framegen: display refresh " + displayRefreshHz
                        + " Hz — default output cap follows it");
            }
        } catch (Throwable ignored) { }
    }

    /** EmulationActivity paused — instrument off until it resumes. */
    public static void onEmulationPaused(Activity activity) {
        emuForeground = false;
    }

    private static android.media.ImageReader offscreenReader = null;
    private static long lastLiveOutputMs = 0;
    private static final long OUTPUT_STALE_MS = 2500L;
    /** On-screen panel only after this many posted frames since recovery. */
    private static final long OUTPUT_LIVE_FRAME_MIN = 8L;
    /** Block on-screen panel during / after IR — prevents ghost strobe. */
    private static volatile long outputLivePostedBaseline = 0;
    private static volatile long outputLiveGenBaseline = 0;

    /** Counters from fg_source.cpp; see nativeStats there for the layout. */
    private static native int nativeStats(long[] out);

    /** Passes NativeBridge.INSTANCE to fg_source.cpp from a Java thread. */
    private static native void nativeSetBridge(Object bridge);

    /** Must match fg_set_capture_size in fg_source.cpp before the first capture. */
    private static native void nativeSetCaptureSize(int w, int h);

    /** Drop GS latch + AHB slots after IR change (fg_source.cpp). */
    private static native void nativeResetCapture();

    /** Let go of the GS target; re-latched on the next report, costing a frame. */
    private static native void nativeDropLatch();
    private static native void nativeRejectSourceAndReset();
    /** Refuse the frozen handle without rebuilding capture — the cheap step. */
    private static native void nativeRejectSourceOnly();
    private static native void nativeSetExpectedGs(int w, int h);

    private static native void nativeBindCallbacks();

    private static native void nativeSetCapturePauseReasons(int reasons);

    /**
     * The shim's own on-screen display.
     *
     * <p>Necessary rather than a nicety: the frame source copies the GS colour
     * target, but PCSX2 draws its stats at present time — after that target —
     * so they are never in a captured frame, and the output surface then covers
     * the real ones. Without this there is no way to see whether anything is
     * being generated at all.
     *
     * <p>Cheap on purpose. A TextView is composited by SurfaceFlinger along with
     * every other window; it adds no work to the emulator's render pipeline and
     * is repainted twice a second, not per frame. That matters here — the whole
     * problem with frame generation on this device has been GPU cost, and an
     * instrument that distorts what it measures is worse than none.
     *
     * <p>Added AFTER the SurfaceView in the same parent, so it draws above it.
     */
    private static volatile boolean osdAttachScheduled = false;
    private static volatile boolean osdRaisePending = false;
    private static int osdIrRecoveryGen = 0;
    private static long lastOsdRaiseMs = 0;
    private static long lastOsdAttachAttemptMs = 0;
    private static final long OSD_ATTACH_BACKOFF_MS = 400L;
    private static final long OSD_RAISE_DEBOUNCE_MS = 600L;

    private static boolean osdIsWindowHealthy() {
        return osd != null && osd.isAttachedToWindow()
                && osd.getVisibility() == android.view.View.VISIBLE;
    }

    /** Reparent the OSD into the output panel window so it draws above the
     *  generated frames — a decor-attached view can never cross the window
     *  boundary. Safe to call repeatedly; no-op when already there. */
    private static void moveOsdIntoOutputPanel(final Activity activity) {
        final android.widget.FrameLayout container = outputContainer;
        if (container == null || !container.isAttachedToWindow()) return;
        if (osd == null) { attachOsdIfEnabled(activity); return; }
        if (osd.getParent() == container) return;
        container.post(new Runnable() {
            @Override public void run() {
                try {
                    if (outputContainer != container
                            || !container.isAttachedToWindow()) return;
                    detachOsdView();
                    android.view.ViewGroup.MarginLayoutParams lp = osdLp;
                    if (lp == null) {
                        lp = new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                        osdLp = lp;
                    }
                    applyStockOsdLayout(activity, lp);
                    container.addView(osd, lp);
                    osd.setVisibility(android.view.View.VISIBLE);
                    osdWindowAttached = true;
                    osdAdded = true;
                    ensureOsdTicking();
                    Log.i(TAG, "framegen instrument OSD moved into output panel");
                } catch (Throwable t) {
                    note(activity.getExternalFilesDir(null),
                            "OSD move into panel failed: " + t);
                }
            }
        });
    }

    private static void attachOsd(final Activity activity, android.view.ViewGroup unusedRoot) {
        if (activity == null) return;
        /* IN-GAME ONLY. shouldShowInstrument() already refuses outside emulation,
         * but three callers bypass it and land here directly. syncAtStartup ->
         * applyLsfgMode -> enableOverlay runs at ContentProvider time with no
         * Activity, defers to the prepare worker, and re-enters seconds later when
         * foregroundActivity() is MainActivity -- so the instrument attached to the
         * LIBRARY screen and took two 500 ms ticks to come off. That is the flash. */
        if (!emuForeground || !isEmulationActivity(activity)) return;
        if (!shouldShowInstrument(activity)) return;
        /* Output panel up? The OSD must live inside it (separate window above
         * the decor) or it is covered no matter what Z we set. */
        if (outputContainer != null && outputContainer.isAttachedToWindow()
                && osd != null && osd.getParent() != outputContainer) {
            moveOsdIntoOutputPanel(activity);
            return;
        }
        if (osdIsWindowHealthy()) return;
        synchronized (ShimFrameGen.class) {
            if (osdIsWindowHealthy() || osdAttachScheduled) return;
            osdAttachScheduled = true;
        }
        try {
            final android.widget.TextView tv;
            final android.view.ViewGroup.MarginLayoutParams lp;
            if (osd != null && osdLp != null) {
                tv = osd;
                lp = osdLp;
                applyStockOsdLayout(activity, lp);
            } else {
                tv = new android.widget.TextView(activity);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
                tv.setTextColor(0xFF00FF88);
                tv.setShadowLayer(3f, 0f, 0f, 0xFF000000);
                tv.setBackgroundColor(0xAA000000);
                tv.setPadding(14, 10, 14, 10);
                tv.setText("framegen: starting");
                lp = new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                applyStockOsdLayout(activity, lp);
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    tv.setElevation(10000f);
                    tv.setZ(10000f);
                }
                osd = tv;
                osdLp = lp;
            }
            /* Attach to the decor so we draw above the emulator SurfaceView.
             * TYPE_APPLICATION_PANEL via WindowManager often lands behind it. */
            final android.view.View decor = activity.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (osdIsWindowHealthy()) return;
                        if (!(decor instanceof android.view.ViewGroup)) {
                            note(activity.getExternalFilesDir(null),
                                    "OSD: decor is not a ViewGroup");
                            return;
                        }
                        android.view.ViewGroup root = (android.view.ViewGroup) decor;
                        if (tv.getParent() != null && tv.getParent() != root)
                            detachOsdView();
                        if (tv.getParent() == null)
                            root.addView(tv, lp);
                        else
                            tv.bringToFront();
                        osdLp = lp;
                        osdWm = null;
                        osdWindowAttached = true;
                        osdAdded = true;
                        hostActivity = activity;
                        tv.setVisibility(android.view.View.VISIBLE);
                        ensureOsdTicking();
                        Log.i(TAG, "framegen instrument OSD attached (decor, fg_osd="
                                + shouldShowInstrument(activity) + ")");
                    } catch (Throwable t) {
                        Log.w(TAG, "OSD decor attach failed: " + t);
                        note(activity.getExternalFilesDir(null),
                                "OSD decor attach failed: " + t);
                        osdWindowAttached = false;
                    } finally {
                        osdAttachScheduled = false;
                    }
                }
            });
        } catch (Throwable t) {
            osdAttachScheduled = false;
            note(activity.getExternalFilesDir(null), "OSD attach failed: " + t);
        }
    }

    /** Keep the tick loop alive; self-heals detached OSD with backoff. */
    private static void ensureOsdTicking() {
        if (osdTickHandler == null)
            osdTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        if (osdTickRunnable == null) {
            osdTickRunnable = new Runnable() {
                @Override public void run() {
                    tickOsd();
                    if (osdTickHandler != null)
                        osdTickHandler.postDelayed(this, 500);
                }
            };
        }
        if (!osdTickScheduled) {
            osdTickScheduled = true;
            osdTickHandler.postDelayed(osdTickRunnable, 500);
        }
    }

    /**
     * IR recreates EmulationActivity and invalidates the OSD window token.
     * Retry attach across the settle window — a single delayed raise is not enough.
     */
    private static void scheduleOsdIrRecovery(final Activity activity) {
        if (activity == null) return;
        hostActivity = activity;
        final int gen = ++osdIrRecoveryGen;
        final android.os.Handler h = new android.os.Handler(activity.getMainLooper());
        for (long delay : new long[] { 0, 200, 600, 1200, 2000, 4000 }) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gen != osdIrRecoveryGen) return;
                    Activity act = hostActivity;
                    if (act == null) act = ToggleWatchdog.foregroundActivity();
                    if (act == null || act.isFinishing()) return;
                    if (!osdIsWindowHealthy()) {
                        if (osd == null || osdLp == null)
                            attachOsdIfEnabled(act);
                        else
                            reattachOsdToken(act, 0);
                    } else if (outputView != null && outputView.isAttachedToWindow()) {
                        raiseOsdAboveOutput(act, 0);
                    }
                }
            }, delay);
        }
    }

    /**
     * Bring the instrument OSD above the framegen output panel / SurfaceView.
     */
    private static void raiseOsdAboveOutput(final Activity activity, long delayMs) {
        if (activity == null) return;
        hostActivity = activity;
        if (osdRaisePending) return;
        osdRaisePending = true;
        final android.view.View decor = activity.getWindow().getDecorView();
        decor.postDelayed(new Runnable() {
            @Override public void run() {
                osdRaisePending = false;
                if (activity.isFinishing()) return;
                hostActivity = activity;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastOsdRaiseMs < OSD_RAISE_DEBOUNCE_MS) {
                    decor.postDelayed(this, OSD_RAISE_DEBOUNCE_MS);
                    osdRaisePending = true;
                    return;
                }
                lastOsdRaiseMs = now;
                if (osd == null || osdLp == null) {
                    attachOsdIfEnabled(activity);
                    return;
                }
                /* A decor-attached OSD sits in a window BELOW the output
                 * panel — reparent it into the panel instead of z-shuffling. */
                if (outputContainer != null && outputContainer.isAttachedToWindow()
                        && osd.getParent() != outputContainer) {
                    moveOsdIntoOutputPanel(activity);
                    return;
                }
                if (!osd.isAttachedToWindow()) {
                    attachOsdIfEnabled(activity);
                    return;
                }
                try {
                    osd.bringToFront();
                    if (android.os.Build.VERSION.SDK_INT >= 21)
                        osd.setZ(10000f);
                    osd.setVisibility(android.view.View.VISIBLE);
                    osdWindowAttached = true;
                    ensureOsdTicking();
                } catch (Throwable t) {
                    osdWindowAttached = false;
                    note(activity.getExternalFilesDir(null),
                            "OSD raise above output failed: " + t);
                }
            }
        }, delayMs);
    }

    /** Re-attach OSD on the decor after activity recreate — never discard TextView. */
    private static void reattachOsdToken(final Activity activity, long delayMs) {
        if (activity == null) return;
        raiseOsdAboveOutput(activity, delayMs);
    }

    /** Retry promotion until panel dimensions are known or frames flow. */
    private static void schedulePromotionRetries(final Activity activity) {
        if (activity == null) return;
        final int gen = ++promoteRetryGen;
        final android.os.Handler h = new android.os.Handler(activity.getMainLooper());
        for (long delay : new long[] { 0, 300, 800, 1500, 3000, 5000 }) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gen != promoteRetryGen || outputPromoted) return;
                    tryPromoteOutput();
                }
            }, delay);
        }
    }

    private static void tryPromoteOutput() {
        if (outputPromoted || !mayShowOutputPanel() || !outputHasLiveFrames()) return;
        promoteOutputToPanel();
    }

    /**
     * Tell native which GS extent the user's Internal Resolution implies.
     *
     * <p>Without it the native tracker cannot tell the display target from the
     * render targets PCSX2 keeps alive at every OTHER resolution the session has
     * used — they all pass the 512:448 shape test, and the resulting flip-flop
     * pins capture behind a resize that never settles ("FRAMEGEN paused").
     * Polled rather than pushed on change: the core rewrites this preference
     * itself, so a listener misses it (the same reason ToggleWatchdog polls).
     */
    /**
     * Push the expected GS extent for an IR the shim is applying RIGHT NOW.
     *
     * <p>{@link #pushExpectedGs} reads the pref on the OSD tick, which is up to
     * a second late and was measured at FOUR seconds late. PCSX2 recreates its
     * render targets the instant the setting is applied, so on a live IR change
     * the new target is created while native still expects the OLD extent — it
     * is rejected, never tracked, and never reported, so capture stays on the
     * previous resolution's buffer. That is invisible at boot, where the
     * expectation is set before any target exists, which is why sub-native IR
     * worked from a cold start and broke as a live change.
     *
     * <p>Called from the apply path so the hint always precedes the recreate.
     */
    public static void pushExpectedGsForIr(String ir) {
        int[] gs = expectedGsFromIr(ir);
        if (gs == null) return;
        if (lastExpectedGs != null
                && lastExpectedGs[0] == gs[0] && lastExpectedGs[1] == gs[1])
            return;
        lastExpectedGs = gs;
        try {
            nativeSetExpectedGs(gs[0], gs[1]);
            note(null, "expected GS " + gs[0] + "x" + gs[1] + " (IR " + ir
                    + ", pushed on apply)");
        } catch (Throwable t) {
            note(null, "nativeSetExpectedGs failed: " + t);
        }
    }

    private static void pushExpectedGs(Context ctx) {
        if (ctx == null) return;
        String ir = null;
        try {
            Object raw = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .getAll().get("EmuCore/GS/upscale_multiplier");
            if (raw != null)
                ir = TurnipConfig.formatUpscaleMultiplier(String.valueOf(raw));
        } catch (Throwable ignored) { }
        int[] gs = expectedGsFromIr(ir);
        if (gs == null) return;
        if (lastExpectedGs != null
                && lastExpectedGs[0] == gs[0] && lastExpectedGs[1] == gs[1])
            return;
        lastExpectedGs = gs;
        try {
            nativeSetExpectedGs(gs[0], gs[1]);
            note(null, "expected GS " + gs[0] + "x" + gs[1] + " (IR " + ir + ")");
        } catch (Throwable t) {
            note(null, "nativeSetExpectedGs failed: " + t);
        }
    }

    private static int[] lastExpectedGs;

    /** GS size for a Graphics IR upscale_multiplier string (512×448 base). */
    private static int[] expectedGsFromIr(String ir) {
        if (ir == null || ir.isEmpty()) return null;
        double mult;
        if (TurnipConfig.IR_NATIVE.equals(ir) || "native".equalsIgnoreCase(ir))
            mult = 1.0;
        else {
            try {
                mult = Double.parseDouble(ir.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (mult < 0.5 || mult > 8.5) return null;
        int w = (int) Math.round(512.0 * mult);
        int h = (int) Math.round(448.0 * mult);
        if ((w & 1) != 0) w++;
        if ((h & 1) != 0) h++;
        return new int[] { w, h };
    }

    private static void scheduleOsdToFront(android.os.Handler h) {
        Activity act = hostActivity;
        if (act != null)
            raiseOsdAboveOutput(act, 150);
    }

    /** Poll native + LSFG counters and refresh the on-screen display. */
    /**
     * True when a dialog window is on screen for this activity — the in-game
     * pause/settings menu.
     *
     * <p>The instrument sits inside the framegen output panel, which is a window
     * ABOVE the emulator surface, so it draws on top of that menu and makes it
     * hard to read. Detected by window, not by fragment: the menu is a
     * DialogFragment R8-renamed to {@code b} and is not reachable from the
     * activity's FragmentManagers at all.
     */
    private static boolean dialogWindowPresent(Activity activity) {
        if (activity == null || activity.getWindow() == null) return false;
        try {
            android.view.View decor = activity.getWindow().getDecorView();
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object inst = cls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = cls.getDeclaredField("mViews");
            f.setAccessible(true);
            java.util.List<?> views = (java.util.List<?>) f.get(inst);
            if (views == null) return false;
            for (Object o : views) {
                if (!(o instanceof android.view.View)) continue;
                android.view.View v = (android.view.View) o;
                if (v == decor || v == outputContainer || v == outputView) continue;
                if (v == osd || (osd != null && osd.getRootView() == v)) continue;
                if (v.getWindowToken() != null && v.isShown()) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void tickOsd() {
        Activity act = hostActivity;
        if (act == null) act = ToggleWatchdog.foregroundActivity();
        /* Follow device rotation even when the output surface is NOT recreated.
         * No-ops unless the display actually turned. */
        if (act != null && contextUp) syncPanelRotation(act);
        /* Latch the widescreen setting on the transition into a running game,
         * and drop it when the VM goes away so the next boot re-reads it. A
         * mid-game context rebuild (IR or capture-size change) never clears it,
         * which is the point: the render's shape did not change. */
        if (act != null) {
            if (!ShimRestartPrompt.gameRunning()) clearBootedWide();
            else if (bootedWide == null) latchBootedWide(act);
        }
        /* Follow the Widescreen toggle / Aspect Ratio setting live. */
        if (act != null && contextUp) syncOutputAspect(act);
        /* Get out of the way of the pause/settings menu. */
        if (osd != null && act != null) {
            boolean menuOpen = dialogWindowPresent(act);
            /* ALL STOCK OSD TOGGLES OFF MEANS "SHOW ME NOTHING".
             *
             * This instrument exists to mirror those switches, and every row it
             * draws is gated on one — except the "FRAMEGEN <status>" header and
             * the counters, which were unconditional. So turning everything off
             * still left an overlay on screen, which is the overlay overriding
             * the user. turnip.conf fg_osd=force is the deliberate override for
             * debugging and still wins; absent, the switches decide. */
            boolean anyOn = OsdPrefs.anyStatOn(act) && !OsdPrefs.userWantsNoStats(act);
            if (anyOn != lastAnyStatOn) {
                lastAnyStatOn = anyOn;
                Log.i(TAG, "framegen instrument gate: anyStatOn=" + anyOn
                        + " " + OsdPrefs.statSummary(act));
            }
            boolean hide = menuOpen || !shouldShowInstrument(act);
            int want = hide ? android.view.View.GONE : android.view.View.VISIBLE;
            if (osd.getVisibility() != want) {
                osd.setVisibility(want);
                Log.i(TAG, "framegen instrument " + (hide
                        ? (menuOpen ? "hidden (menu open)"
                                    : "hidden (all stock OSD toggles off)")
                        : "shown"));
            }
            // Visibility controls drawing only; updateOsd still runs recovery.
        }
        /* fg_osd turned off while the instrument was up — take it down rather
         * than leaving a stale box over the game. Nothing below removes it.
         *
         * Requires TWO consecutive false readings. shouldShowInstrument() re-reads
         * turnip.conf from disk every tick, and mergeConf() rewrites that file in
         * place; a read landing mid-rewrite can miss the key transiently. Acting
         * on one sample dropped and recreated the whole TextView, which is a
         * second of the instrument vanishing for no reason. */
        if (act != null && osd != null) {
            if (!shouldShowInstrument(act)) {
                if (++osdHideVotes >= 2) { hideInstrument(); osdHideVotes = 0; }
            } else {
                osdHideVotes = 0;
            }
        }
        if (!osdIsWindowHealthy()) {
            osdWindowAttached = false;
            long now = android.os.SystemClock.uptimeMillis();
            if (act != null && now - lastOsdAttachAttemptMs >= OSD_ATTACH_BACKOFF_MS) {
                lastOsdAttachAttemptMs = now;
                if (osd == null || osdLp == null)
                    attachOsdIfEnabled(act);
                else if (outputView != null && outputView.isAttachedToWindow())
                    raiseOsdAboveOutput(act, 0);
                else
                    reattachOsdToken(act, 0);
            }
        } else if (outputView != null && outputView.isAttachedToWindow()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastOsdRaiseMs >= OSD_RAISE_DEBOUNCE_MS * 4)
                raiseOsdAboveOutput(act, 0);
        }
        updateOsd();
    }

    private static void updateOsd() {
        try {
            long pushed = 0, skipped = 0, gsw = 0, gsh = 0, built = 0;
            long nativeEnabled = 0, throttled = 0, suppressReason = 0;
            long pendingGsW = 0, pendingGsH = 0, gsLatched = 0, capturePaused = 0;
            try {
                int n = nativeStats(statsBuf);
                if (n >= 5) {
                    pushed = statsBuf[0]; skipped = statsBuf[1];
                    if (pushed > pushedEver) pushedEver = pushed;
                    gsw = statsBuf[2]; gsh = statsBuf[3]; built = statsBuf[4];
                }
                if (n >= 8) {
                    nativeEnabled = statsBuf[5]; throttled = statsBuf[6];
                    suppressReason = statsBuf[7];
                }
                if (n >= 12) {
                    pendingGsW = statsBuf[8];
                    pendingGsH = statsBuf[9];
                    gsLatched = statsBuf[10];
                    capturePaused = statsBuf[11];
                }
            } catch (Throwable ignored) { }

            /* Display GS: pending debounce target during IR, else committed size. */
            long dispGsW;
            long dispGsH;
            if (irPausePending && irExpectedGsW > 0) {
                dispGsW = irExpectedGsW;
                dispGsH = irExpectedGsH;
            } else if (pendingGsW > 0) {
                dispGsW = pendingGsW;
                dispGsH = pendingGsH;
            } else {
                dispGsW = gsw;
                dispGsH = gsh;
            }

            long posted = 0, generated = 0, unique = 0;
            try {
                posted = NativeBridge.INSTANCE.getPostedFrameCount();
                generated = NativeBridge.INSTANCE.getGeneratedFrameCount();
                unique = NativeBridge.INSTANCE.getUniqueCaptureCount();
            } catch (Throwable ignored) { }

            /* LSFG destroyContext zeros bridge counters — keep last values on OSD
             * during IR recovery so pushed/posted do not flash to zero. */
            if (pushed > 0) lastDispPushed = pushed;
            else if (irPausePending || irRecovering) pushed = lastDispPushed;
            if (posted > 0 || contextUp) lastDispPosted = posted;
            else if (irPausePending || irRecovering) posted = lastDispPosted;
            if (generated > 0 || contextUp) lastDispGenerated = generated;
            else if (irPausePending || irRecovering) generated = lastDispGenerated;

            long now = System.nanoTime();
            double dt = 0;
            if (lastSampleNs != 0) {
                dt = (now - lastSampleNs) / 1e9;
                double maxDt = (irPausePending || irRecovering) ? 12.0 : 3.0;
                if (dt < 0.05 || dt > maxDt || unique < lastUnique
                        || pushed < lastPushed || generated < lastGenerated || posted < lastPosted) {
                    /* Handler jitter or IR pause — reset rate baseline only. */
                    lastUnique = unique;
                    lastPushed = pushed;
                    lastGenerated = generated;
                    lastPosted = posted;
                    dt = 0;
                }
            }
            lastSampleNs = now;

            /* dt is forced to 0 whenever the rate baseline resets — handler
             * jitter, an IR pause, a pipeline rebuild. Rendering 0 in that tick
             * is why the readout "shows nothing on gen and out" for a second
             * and then comes back. The counters are still perfectly valid, so
             * hold the last computed rates over the gap instead of flashing
             * zeros. */
            double uniqueFps, capFps, genFps, postFps;
            if (dt > 0) {
                uniqueFps = (unique - lastUnique) / dt;
                capFps  = (pushed - lastPushed) / dt;
                genFps  = (generated - lastGenerated) / dt;
                postFps = (posted - lastPosted) / dt;
                heldUniqueFps = uniqueFps; heldCapFps = capFps;
                heldGenFps = genFps;       heldPostFps = postFps;
            } else {
                uniqueFps = heldUniqueFps; capFps = heldCapFps;
                genFps = heldGenFps;       postFps = heldPostFps;
            }
            watchdogStuckPause(capturePaused, gsLatched);
            boolean running = emuForeground && ShimRestartPrompt.gameRunning()
                    && !TurnipConfig.nativeBool("isVMPaused");
            if (running && dt > 0) {
                watchdogFrozenSource(uniqueFps, capFps, capturePaused, posted, generated);
                watchdogOutputSurfaceLost(posted, generated);
            }
            reportOutputBufferDrift();
            reportAhbMismatch();
            lastUnique = unique;
            lastPushed = pushed; lastGenerated = generated; lastPosted = posted;

            /* DO NOT put uniqueFps in a field labelled "game". It is a
             * unique-pixel-HASH rate: on a near-static scene it collapses to
             * 2-12 while the emulator is verifiably at a solid 60 (checked
             * against SurfaceFlinger on the emulator's own layer), and it read
             * "4" during normal play. That misreading was reported as a
             * performance bug twice.
             *
             * capFps is the native push rate — one per present hook — and it
             * matches SurfaceFlinger exactly. That is the honest number, so it
             * is what gets the prominent slot, labelled "real". uniqueFps still
             * has diagnostic value ("is the captured image actually changing?")
             * so it is kept, but demoted and named for what it is. */
            double realFps = capFps;

            if (running) watchdogOutputOverlay(posted, generated);

            int ow = 0, oh = 0;
            String viewTag;
            if (outputPromoted) {
                ow = outputSurfaceW;
                oh = outputSurfaceH;
                viewTag = " panel";
            } else if (outputView != null && outputView.getWidth() > 0) {
                ow = outputView.getWidth();
                oh = outputView.getHeight();
                viewTag = " on-screen";
            } else if (contextUp && offscreenReader != null) {
                ow = outputSurfaceW;
                oh = outputSurfaceH;
                viewTag = " offscreen";
            } else {
                viewTag = "";
            }

            String status = computeOsdStatus(
                    nativeEnabled, throttled, suppressReason, capturePaused,
                    dispGsW, dispGsH, pendingGsW, gsLatched, built,
                    pushed, posted, generated, capFps, postFps);

            /* Say it plainly when framegen is COSTING frames rather than adding
             * them. Above roughly IR 2x on this device the emulator owns the
             * GPU, the panel falls to ~25 fps and the emulator itself drops
             * below full speed — measured, and no capture size rescues it. The
             * numbers were always on screen; nothing said which way they pointed,
             * so "framegen is broken" was the only available reading.
             *
             * Needs three consecutive ticks: a single sample dips during scene
             * loads and every context rebuild. */
            /* "Not meaningfully ABOVE real", not merely "below it".
             *
             * Measured at IR 2.5: real 59, out 61, and SurfaceFlinger confirms
             * the panel at exactly 60.0 fps — the emulator's own rate. Frame
             * generation is contributing nothing there while still paying the
             * GPU for it, and the old strictly-less-than test stayed silent
             * because output was not BELOW real, it was equal. At IR 2.0 the
             * same panel runs 119-121 against real 60, so a 1.15x floor
             * separates the two cases with room to spare. */
            long suppressedPairs = lastSuppressedPairs;
            try { suppressedPairs = NativeBridge.INSTANCE.getSuppressedFrameCount(); }
            catch (Throwable ignored) { }
            boolean qualityFallback = suppressedPairs > lastSuppressedPairs;
            lastSuppressedPairs = suppressedPairs;
            if (!qualityFallback && contextUp && realFps > 5.0 && postFps > 0.0
                    && !outputAddsFrames(realFps, postFps))
                losingTicks++;
            else
                losingTicks = 0;

            if (running && dt > 0) {
                frameCostGuard(realFps, postFps, capturePaused);
                autoMultiplier(capturePaused);
            } else {
                losingTicks = 0;
                resetCostGuardWindowOnly();
            }

            /* Say it on the status line too, because the resolution row is OFF by
             * default and this fault is invisible in every other counter: a live
             * context whose capture size no longer matches the one being pushed
             * puts the game in the top-left corner of the panel while the rate,
             * the push count and uniq all read perfect. Two ticks, so a context
             * rebuild in progress cannot flash it. */
            if (captureMismatched()) captureMismatchTicks++;
            else captureMismatchTicks = 0;
            if (running && captureMismatchTicks >= 3) rebuildForCaptureSize(capturePaused);

            pushExpectedGs(hostActivity);
            // Everything below this line only formats the optional instrument.
            if (osd == null || osd.getVisibility() != android.view.View.VISIBLE) return;

            /* Mirror the user's own stock OSD toggles.
             *
             * While framegen is live the stock OSD is invisible — it draws into
             * the emulator surface and the output panel covers it — so this
             * instrument IS the OSD, and it should show what the user asked for
             * in Settings rather than a fixed set of its own. Each row below is
             * gated on the same preference the core reads. */
            Context pc = hostActivity != null
                    ? hostActivity : ToggleWatchdog.foregroundActivity();
            boolean wantFps   = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_FPS, OsdPrefs.defaultFor(OsdPrefs.KEY_FPS));
            boolean wantSpeed = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_SPEED, OsdPrefs.defaultFor(OsdPrefs.KEY_SPEED));
            boolean wantTimes = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_FRAMETIMES, OsdPrefs.defaultFor(OsdPrefs.KEY_FRAMETIMES));
            boolean wantRes   = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_RESOLUTION, OsdPrefs.defaultFor(OsdPrefs.KEY_RESOLUTION));
            boolean wantCpu   = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_CPU, OsdPrefs.defaultFor(OsdPrefs.KEY_CPU));
            boolean wantGpu   = OsdPrefs.stockOsdShows(pc, OsdPrefs.KEY_GPU, OsdPrefs.defaultFor(OsdPrefs.KEY_GPU));


            StringBuilder sb = new StringBuilder(96);
            sb.append("FRAMEGEN ").append(status);
            if (!contextUp && framegenOffReason != null)
                sb.append(" — ").append(framegenOffReason);
            if (captureMismatchTicks >= 2)
                sb.append(String.format(java.util.Locale.US,
                        "  — CAPTURE STALE: ctx %dx%d, pushing %dx%d",
                        contextCaptureW, contextCaptureH, captureW, captureH));
            if (losingTicks >= 3)
                sb.append(postFps < realFps
                        ? String.format(java.util.Locale.US,
                                "  — LOSING: out %.0f < real %.0f, lower IR",
                                postFps, realFps)
                        : String.format(java.util.Locale.US,
                                "  — NO GAIN: out %.0f = real %.0f, lower IR",
                                postFps, realFps));
            /* DO NOT PRINT ZEROS WE DID NOT MEASURE.
             *
             * realFps is the CAPTURE rate. While the context is rebuilding —
             * every IR change — capture is paused, so it reads 0 and the rows
             * below rendered "real 0 gen 0 out 0 fps" and "0% speed". That looks
             * exactly like the emulator having stalled, and was reported as
             * "changing IR makes the game slow". It had not: the stock OSD
             * behind the panel read 16.68 ms (59.95 fps) with GPU at 57% for the
             * whole rebuild. The game is fine; these counters simply have
             * nothing to describe yet. Say that instead of implying a stall. */
            boolean measuring = realFps > 0.5 || postFps > 0.5;
            if (!measuring) {
                /* "REBUILDING" IS ONLY HONEST IF SOMETHING IS BEING REBUILT.
                 *
                 * A rebuild is transient and ends. Native never arming is
                 * permanent, and it produced exactly the same zeros, so this row
                 * sat on "rebuilding — emulator unaffected" forever and read as
                 * a broken OSD (reported 2026-08-17). Tell the two apart by
                 * whether native has EVER pushed a frame for this context: a
                 * real rebuild has a push history behind it, an unarmed native
                 * has none. */
                if (pushedEver <= 0 && ++neverPushedTicks > 6)
                    sb.append("\nnot capturing — restart the game to enable it");
                else
                    sb.append("\nrebuilding — emulator unaffected");
            } else {
            neverPushedTicks = 0;
            if (wantFps)
                sb.append(String.format(java.util.Locale.US,
                        "\nSource %.0f  |  Generated %.0f  |  Output %.0f fps",
                        realFps, genFps, postFps));
            if (wantFps && qualityFallback) sb.append("\nArtifact protection active");
            if (wantSpeed || wantTimes) {
                sb.append('\n');
                if (wantSpeed) {
                    /* NTSC full speed is 59.94 vsyncs/s; PAL is 50. Percentage
                     * of the real rate, which is what the stock row shows. */
                    double pct = realFps / 59.94 * 100.0;
                    sb.append(String.format(java.util.Locale.US, "%.0f%% speed", pct));
                }
                if (wantTimes) {
                    if (wantSpeed) sb.append("  ");
                    double ms = realFps > 0.5 ? 1000.0 / realFps : 0.0;
                    sb.append(String.format(java.util.Locale.US, "%.1f ms", ms));
                }
            }
            }   /* measuring */
            if (wantCpu || wantGpu) {
                sb.append('\n');
                if (wantCpu) {
                    double c = readCpuPercent();
                    sb.append(c < 0 ? "CPU --" : String.format(
                            java.util.Locale.US, "CPU %.0f%%", c));
                }
                if (wantGpu) {
                    if (wantCpu) sb.append("  ");
                    double g = readGpuPercent();
                    sb.append(g < 0 ? "GPU --" : String.format(
                            java.util.Locale.US, "GPU %.0f%%", g));
                }
            }
            if (wantRes)
                sb.append(String.format(java.util.Locale.US,
                        "\nGS %dx%d -> capture %dx%d%s -> view %dx%d%s",
                        (int) dispGsW, (int) dispGsH, captureW, captureH,
                        captureMismatched()
                                ? String.format(java.util.Locale.US, " (ctx %dx%d)",
                                        contextCaptureW, contextCaptureH)
                                : "",
                        ow, oh, viewTag));
            // Internal cumulative counters are useful only in diagnostic mode.
            if (confForcesOsd(pc))
                sb.append(String.format(java.util.Locale.US,
                        "\npushed %d  skipped %d  posted %d  gen %d  uniq %.0f",
                        pushed, skipped, posted, generated, uniqueFps));
            osd.setText(sb.toString());
        } catch (Throwable ignored) { }
    }

    private static String computeOsdStatus(
            long nativeEnabled, long throttled, long suppressReason,
            long capturePaused, long dispGsW, long dispGsH, long pendingGsW,
            long gsLatched, long built, long pushed, long posted, long generated,
            double capFps, double postFps) {
        if (!shadersReady) {
            return prepareBlockReason != null && !prepareBlockReason.isEmpty()
                    ? "off (" + prepareBlockReason + ")" : "off";
        }
        if (!overlayRuntimeEnabled || sessionDisabled) {
            Activity act = hostActivity;
            if (act == null) act = ToggleWatchdog.foregroundActivity();
            if (act != null) {
                File files = act.getExternalFilesDir(null);
                if (files != null) {
                    String ov = TurnipConfig.readConfKey(
                            new File(files, "turnip.conf"), CONF_KEY);
                    if (ov == null || ov.equalsIgnoreCase("off") || ov.equals("0"))
                        return "off (lsfg_overlay off)";
                }
            }
            return "off";
        }
        if (overIrCap)
            return String.format(java.util.Locale.US,
                    "off (IR ~%.2fx > %.2fx cap — lower IR or raise fg_max_ir)",
                    irOfGs((int) dispGsW, (int) dispGsH), fgMaxIr);
        /* "throttled" and "suppressed (60fps)" USED TO BE REPORTED HERE AND ARE
         * NOT REACHABLE. Both read native counters that no longer have a writer:
         * fg_update_present_rate (the only code that sets throttle_active) has
         * no callers, and fg_set_suppress_reason is never called, so v[6]/v[7]
         * are permanently 0. A status the OSD can never show is worse than no
         * status — it invites reading the absence as evidence. Capture therefore
         * runs unthrottled, which measures fine: 120.0 fps panel, skipped 0.
         * If auto-throttling is ever wanted back, wire those two up first and
         * restore the branches with them. */
        if (irRecovering)
            return "recovering (IR)";
        if (irPausePending)
            return "paused (IR)";
        if (capturePaused != 0)
            return "paused (capture)";
        if (!contextUp)
            return "waiting";
        if (pendingGsW > 0)
            return "warming (GS debounce)";
        if (capFps >= 5.0 || postFps >= 5.0
                || pushed > 0 && (posted > 0 || generated > 0)) {
            osdWarmupTicks = 0;
            if (outputPromoted && outputView != null)
                return "live (panel)";
            if (outputView != null)
                return "live (on-screen)";
            if (offscreenReader != null && (posted > 0 || generated > 0))
                return "live (offscreen)";
            return "live";
        }
        if (dispGsW == 0 && dispGsH == 0) {
            osdWarmupTicks++;
            return "warming (GS settle)";
        }
        if (gsLatched == 0 || built == 0) {
            osdWarmupTicks++;
            return "warming (capture)";
        }
        osdWarmupTicks++;
        if (osdWarmupTicks <= 6)
            return "warming";
        return nativeEnabled == 0 ? "off (native)" : "no source";
    }

    /**
     * Serial worker for LSFG context lifecycle.
     *
     * <p>{@code initContextDefaults} (shader extract + driver validate) and
     * {@code destroyContext} are native calls that take SECONDS. Running them on
     * the main thread is what froze the app on every Internal Resolution change:
     * Android killed it with {@code ANR ... Waited 5000ms for MotionEvent}.
     *
     * <p>One thread, so init and teardown stay strictly ordered with respect to
     * each other — a destroy queued before an init still completes first, which
     * is the invariant LSFG needs. Java state and every View touch stay on the
     * main thread; only the native call crosses over.
     */
    private static android.os.Handler ctxHandler;

    private static synchronized android.os.Handler ctxWorker() {
        if (ctxHandler == null) {
            android.os.HandlerThread t =
                    new android.os.HandlerThread("fg-ctx", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            ctxHandler = new android.os.Handler(t.getLooper());
        }
        return ctxHandler;
    }

    /**
     * Bind LSFG's output on the serial fg-ctx worker, pausing capture around the
     * native call. setOutputSurface destroys/recreates the WSI swapchain while
     * fg-ctx may be inside presentContext — measured at 1.5 IR boot as SIGSEGV
     * (pc=0) on the first pushFrame when GS settle rebind raced the worker.
     */
    private static void bindOutputSurfaceSafely(final android.view.Surface surface,
            final int w, final int h) {
        if (surface == null || !surface.isValid() || w <= 0 || h <= 0) return;
        final CapturePauses.Lease binding = pauseCapture(
                CapturePauses.Reason.OUTPUT_BIND, "output surface");
        boolean queued = ctxWorker().post(new Runnable() {
            @Override public void run() {
                try {
                    NativeBridge.INSTANCE.setOutputSurface(surface, w, h);
                } catch (Throwable ignored) {
                } finally {
                    binding.close();
                }
            }
        });
        if (!queued) binding.close();
    }

    private static android.os.Handler mainWorker() {
        if (osdTickHandler == null)
            osdTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        return osdTickHandler;
    }

    /** lsfg_render_loop.hpp — positive rc=1 is success (mirror/passthrough). */
    private static final int RC_LSFG_OK = 0;
    private static final int RC_LSFG_MIRROR = 1;
    private static final int RC_LSFG_ALREADY_INIT = -40;

    private static boolean lsfgInitLive(int rc) {
        return rc == RC_LSFG_OK || rc == RC_LSFG_MIRROR || rc == RC_LSFG_ALREADY_INIT;
    }

    private static synchronized void startContext(Context ctx, android.view.Surface surface) {
        final File files = ctx.getExternalFilesDir(null);
        if (contextUp || contextStarting || surface == null) {
            /* A SILENT EARLY RETURN HERE LOOKS EXACTLY LIKE A REBUILT CONTEXT.
             * contextCaptureW/H keep their previous values, so the next "LIVE at
             * WxH (ctx built AxB)" line reports a stale build size and the
             * picture is corner-boxed while every counter reads healthy. */
            note(files, "startContext skipped (contextUp=" + contextUp
                    + " contextStarting=" + contextStarting
                    + " surface=" + (surface != null)
                    + ") — ctx stays " + contextCaptureW + "x" + contextCaptureH
                    + " while capture wants " + captureW + "x" + captureH);
            return;
        }
        contextStarting = true;
        final CapturePauses.Lease starting = pauseCapture(
                CapturePauses.Reason.PIPELINE_START, "context init");
        final int outW = outputSurfaceW, outH = outputSurfaceH;
        final int capW = captureW, capH = captureH;
        final String cache = new File(new File(files, "lsfg"), "shaders").getAbsolutePath();
        boolean queued = ctxWorker().post(new Runnable() {
            @Override public void run() {
                int rc;
                try {
                    android.view.Surface liveSurf = resolveOffscreenSurface(surface);
                    if (liveSurf == null) {
                        note(files, "initContext output bind skipped — offscreen surface invalid");
                        rc = -1;
                    } else {
                        /* Bind the ImageReader sink before initContext starts the
                         * LSFG worker — otherwise the first pushFrame after g.initialized
                         * presents to a null output (pc=0 SIGSEGV on USM). */
                        NativeBridge.INSTANCE.setOutputSurface(liveSurf, outW, outH);
                        rc = initContextDefaults(cache, capW, capH);
                        if (rc == RC_LSFG_MIRROR) {
                            note(files, "initContext rc=1 (mirror mode — passthrough blit, no interpolation)");
                        } else if (rc == RC_LSFG_ALREADY_INIT) {
                            note(files, "initContext rc=-40 (native loop already up)");
                        }
                        /* ALWAYS re-bind after initContext returns. Shader compile
                         * takes seconds; tearDownContextForIr or finishIrRecovery
                         * can null the surface while fg-ctx is still inside init.
                         * Measured on USM: present() SIGSEGV pc=0 when the first
                         * pushFrame ran with "Output surface detached" in log. */
                        if (lsfgInitLive(rc)) {
                            NativeBridge.INSTANCE.setOutputSurface(liveSurf, outW, outH);
                        }
                    }
                } catch (Throwable t) {
                    note(files, "initContext threw: " + t);
                    rc = -1;
                }
                if (!lsfgInitLive(rc)) {
                    note(files, "initContext failed rc=" + rc + " - no frame generation");
                    try { NativeBridge.INSTANCE.setOutputSurface(null, 0, 0); } catch (Throwable ignored) { }
                    contextStarting = false;
                    starting.close();
                    return;
                }
                /* LSFG builds its images once per context — remember the size it
                 * actually got, so a later GS change can tell whether the live
                 * context still matches or has to be rebuilt. captureW/H alone
                 * cannot answer that: it is the WANTED size, updated the moment
                 * the GS settles, while the context still holds the old one. */
                contextCaptureW = capW;
                contextCaptureH = capH;
                boolean published = mainWorker().post(new Runnable() {
                    @Override public void run() {
                        try { onContextReady(files); } finally { starting.close(); }
                    }
                });
                if (!published) { contextStarting = false; starting.close(); }
            }
        });
        if (!queued) { contextStarting = false; starting.close(); }
    }

    /** Main thread: context is live — publish state and bind the on-screen panel. */
    private static void onContextReady(File files) {
        contextStarting = false;
        try {
            contextUp = true;
            try {
                long[] gs = new long[statsBuf.length];
                if (irRebuildGate.pending() && nativeStats(gs) >= 10)
                    irRebuildGate.contextReady(TurnipConfig.confirmedIr(),
                            (int) gs[2], (int) gs[3], (int) gs[8], (int) gs[9],
                            captureExplicit || (contextCaptureW == gs[2] && contextCaptureH == gs[3]));
            } catch (Throwable ignored) { }
            framegenOffReason = null;
            osdWarmupTicks = 0;
            lastSampleNs = 0;
            /* THE BASELINE IS PER GAME, NOT PER PROCESS.
             *
             * bestRealFps is the rate this title has been seen to hold, and PS2
             * titles are natively 60, 50 or 30. Carrying it across a game change
             * condemns the next one: measured exactly that way — Champions of
             * Norrath left a 60 fps baseline behind, Ultimate Spider-Man was
             * launched in the same process, and its perfectly healthy native 30
             * tripped the guard within seconds ("31 fps now, 60 fps at its
             * best"). A new context is a new game or a new internal resolution;
             * either way the old baseline describes something that is no longer
             * on screen. */
            resetCostGuard();
            recordOutputBaselines();
            /* Print every size that has to agree, not just the wanted capture.
             * A live IR change to a sub-native IR produced a picture zoomed by
             * 1.250 horizontally and 1.079 vertically with all three of GS,
             * capture and "LIVE at" reading 384x336 — so the number that was
             * wrong had to be one nothing printed. getSurfaceFrame() is the
             * BUFFER the panel actually has, as opposed to the size requested
             * via setFixedSize and handed to setOutputSurface. */
            String outReal = "?";
            try {
                if (outputView != null) {
                    android.graphics.Rect sf = outputView.getHolder().getSurfaceFrame();
                    outReal = sf.width() + "x" + sf.height()
                            + " view " + outputView.getWidth() + "x" + outputView.getHeight();
                }
            } catch (Throwable ignored) { }
            /* RE-ARM CAPTURE ON EVERY START, not just enableOverlay().
             *
             * The stop path gates native capture off, and a GS-size change goes
             * through that same stop path on its way to rebuilding the context.
             * Only enableOverlay() re-armed it, and a rebuild does not call that
             * -- so any game whose GS changes after boot (i.e. most of them, as
             * the logo screen and gameplay differ) came back "LIVE" with capture
             * still disabled: no frames pushed, so outputHasLiveFrames() never
             * became true and the output panel was never re-promoted. That is
             * the "rebuilding - emulator unaffected" state, and it never
             * recovered. Measured on Bionicle Heroes, GS 1024x896 -> 1280x896. */
            try {
                nativeSetCaptureEnabled(true);
            } catch (Throwable ignored) {
            }
            note(files, "frame generation LIVE at " + captureW + "x" + captureH
                    + " (ctx built " + contextCaptureW + "x" + contextCaptureH
                    + ", out told " + outputSurfaceW + "x" + outputSurfaceH
                    + ", out buffer " + outReal + ")");
            /* If an on-screen panel already exists, re-bind it HERE at panel
             * size. Its SurfaceHolder.surfaceCreated callback bails out with
             * `if (!contextUp) return;`, and after an IR change the panel's
             * surface is created BEFORE the context comes back — so the binding
             * was dropped and the setOutputSurface above (which uses
             * outputSurfaceW/H, left at the CAPTURE size by
             * startPipelineOffscreen) was the last word. That is what rendered
             * the game into a 480x360 corner of a 1280x960 panel. Binding last,
             * here, makes ordering irrelevant. */
            if (outputView != null) {
                try {
                    int vw = outputView.getWidth();
                    int vh = outputView.getHeight();
                    android.view.Surface ps = outputView.getHolder().getSurface();
                    if (ps != null && ps.isValid() && vw > 0 && vh > 0) {
                        outputSurfaceW = vw;
                        outputSurfaceH = vh;
                        bindOutputSurfaceSafely(ps, vw, vh);
                        outputPromoted = true;
                        note(files, "output re-bound on-screen " + vw + "x" + vh
                                + " (capture " + captureW + "x" + captureH + ")");
                    }
                } catch (Throwable t) {
                    note(files, "startContext panel rebind failed: " + t);
                }
            }
            Activity act = hostActivity;
            if (act != null)
                schedulePromotionRetries(act);
            /* Re-assert offscreen sink before unpausing capture — startContext
             * re-binds on fg-ctx, but the main thread may have raced ahead. */
            if (outputView == null && offscreenReader != null) {
                bindOutputSurfaceSafely(
                        offscreenReader.getSurface(), captureW, captureH);
            }
        } catch (Throwable t) {
            note(files, "startContext threw: " + t);
        }
    }

    private static synchronized void stopContext(Context ctx) {
        if (!contextUp) return;
        /* Same ordering rule as tearDownContextForIr: pause, destroy, and never
         * hand LSFG a null window while its render thread is alive.
         *
         * This path used to clear contextUp FIRST, which silently disabled the
         * safe teardown inside detachOnScreenOutputOnly() (it is guarded on
         * contextUp), and then nulled the surface itself — reintroducing the
         * ANativeWindow_lock race through the back door. Symptom was framegen
         * cycling "LIVE -> stopped -> LIVE" with a black or absent panel. */
        detachOnScreenOutputOnly();
        contextUp = false;
        /* destroyContext() is seconds of native work — off the main thread, or
         * every IR change ANRs. Queued on the same serial worker as init, so a
         * later startContext cannot overtake this teardown. */
        queueContextDestroy();
        closeOffscreenReader();
        lastLiveOutputMs = 0;
        /* Stop CAPTURING too, not just generating. Left on, the per-frame copy
         * kept costing 8-16 ms for output nobody consumed. */
        try {
            nativeSetCaptureEnabled(false);
        } catch (Throwable ignored) {
        }
        note(ctx.getExternalFilesDir(null), "frame generation stopped");
    }

    /**
     * Expand the output window to cover the panel and recreate the native
     * swapchain at the emulator SurfaceView's layout size. The capture path
     * stays at the capture size; only the presentation surface grows.
     */
    private static void promoteOutputToPanel() {
        if (outputPromoted || hostActivity == null || outputView == null
                || outputLp == null || outputWm == null) {
            return;
        }
        int[] panel = getPanelSize(hostActivity);
        if (panel == null) {
            schedulePromotionRetries(hostActivity);
            return;
        }
        final int pw = panel[0];
        final int ph = panel[1];

        outputPromoted = true;
        outputSurfaceW = pw;
        outputSurfaceH = ph;

        outputLp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        outputLp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        outputLp.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        outputLp.format = android.graphics.PixelFormat.OPAQUE;
        outputView.setClickable(false);
        outputView.setFocusable(false);
        final android.view.View target =
                outputContainer != null ? outputContainer : outputView;
        try {
            /* THE WINDOW MANAGER MAY NO LONGER HOLD THIS VIEW.
             *
             * updateViewLayout throws IllegalArgumentException("not attached to
             * window manager") whenever the overlay was torn down between being
             * added and being promoted, which any activity recreate does. The
             * throw left outputPromoted false with nothing scheduled to retry,
             * so output stayed on the offscreen surface permanently: capture and
             * generation both ran (measured 2026-08-17: real 60, gen 101/s,
             * posted 5601) while `out` read 0 and the status sat on "waiting"
             * with no frame ever reaching the panel. Re-add it instead of
             * giving up, and if even that fails, schedule another attempt. */
            if (target.isAttachedToWindow()) {
                outputWm.updateViewLayout(target, outputLp);
            } else {
                android.view.ViewParent vp = target.getParent();
                if (vp instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) vp).removeView(target);
                outputWm.addView(target, outputLp);
                note(hostActivity.getExternalFilesDir(null),
                     "output promote: view was detached, re-added at panel size");
            }
        } catch (Throwable t) {
            note(hostActivity.getExternalFilesDir(null),
                 "output promote failed: " + t + " — retrying");
            outputPromoted = false;
            schedulePromotionRetries(hostActivity);
            return;
        }

        outputResizing = true;
        outputView.getHolder().setFixedSize(pw, ph);
        outputView.post(new Runnable() {
            @Override public void run() {
                try {
                    android.view.Surface s = outputView.getHolder().getSurface();
                    if (s != null && s.isValid() && contextUp) {
                        bindOutputSurfaceSafely(s, pw, ph);
                        note(hostActivity.getExternalFilesDir(null),
                             "output promoted to panel " + pw + "x" + ph
                             + " (capture stays " + captureW + "x" + captureH + ")");
                        scheduleOsdToFront(new android.os.Handler(hostActivity.getMainLooper()));
                        raiseOsdAboveOutput(hostActivity, 300);
                    }
                } catch (Throwable t) {
                    note(hostActivity.getExternalFilesDir(null),
                         "output promote setOutputSurface failed: " + t);
                } finally {
                    outputResizing = false;
                }
            }
        });
    }

    private static android.view.SurfaceView findEmulatorSurfaceView(android.view.View v) {
        if (v instanceof android.view.SurfaceView) {
            if (v == outputView) return null;
            return (android.view.SurfaceView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.SurfaceView found = findEmulatorSurfaceView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** @deprecated use {@link #findEmulatorSurfaceView} */
    private static android.view.SurfaceView findSurfaceView(android.view.View v) {
        return findEmulatorSurfaceView(v);
    }

    /** Suppress generated frames on high-delta pairs (fast motion / cuts). */
    private static boolean fgAntiArtifacts = true;

    private static int initContextDefaults(String cacheDir, int w, int h) {
        return NativeBridge.INSTANCE.initContext(
                cacheDir, w, h,
                fgMultiplier,
                fgFlowScale,
                /* performance  */ fgPerformance,
                /* hdr          */ false,
                /* antiArtifacts*/ fgAntiArtifacts,
                /* framegenFp16 */ false,
                /* npu...       */ false, 0, 1, 0f, 0f, 0f, false,
                /* cpu...       */ false, 0, 0f, 0f, 0f, 0f,
                /* gpu...       */ false, 0, 0, 1f, 0f, 0f,
                /* targetFpsCap */ fgTargetFpsCap,
                EMA_ALPHA, OUTLIER_RATIO, VSYNC_SLACK_MS, fgQueueDepth);
    }

    /**
     * Reads in-process framegen tunables from turnip.conf. Keys are ignored when
     * absent; invalid values are clamped.
     *
     * <ul>
     *   <li>{@code fg_capture_w} / {@code fg_capture_h} — capture buffer (default 480x360)</li>
     *   <li>{@code fg_multiplier} — LSFG multiplier, 2–4 (default 2)</li>
     *   <li>{@code fg_flow_scale} — optical-flow scale, 0.25–1.0 (default 0.25)</li>
     *   <li>{@code fg_queue_depth} — pipeline backlog, 1–8 (default 1)</li>
     *   <li>{@code fg_target_fps_cap} — output pacing cap, 0 or 60–240 (default 120)</li>
     * </ul>
     */
    private static void loadFramegenConfig(File files) {
        ensureVulkadLoaded();
        File conf = new File(files, "turnip.conf");
        /* The capture buffer is the RESOLUTION BOTTLENECK of the whole feature:
         * the GS is copied down into it, LSFG interpolates at that size, and the
         * result is scaled up to the panel. At the old 640x480 ceiling an IR of
         * 2x (GS 1024x896) was rendered at full cost and then thrown away — the
         * picture looked like native or worse, which is exactly the "2x that
         * looks like 512" report.
         *
         * Measured on Ultimate Spider-Man, IR 2x: 480x360 and 640x480 both held
         * emulator 60.0 / panel 120.0, i.e. no cost at all. So the ceiling was
         * arbitrary; it is raised to full 4x GS here. Cost DOES scale with
         * capture area (the layer was ~25ms/frame at 1.23MP on the panel-sized
         * path), so this is a ceiling, not a recommendation — if `real` drops
         * below the game's rate, lower it. */
        captureW = evenDim(clampInt(conf, "fg_capture_w", 256, 2048, defaultCaptureW));
        captureH = evenDim(clampInt(conf, "fg_capture_h", 224, 1792, defaultCaptureH));
        /* An explicit fg_capture_w/h is the user pinning cost, and must survive
         * an Internal Resolution change. Without a flag for it the follow-the-GS
         * code below cannot tell a pinned size from the default one. */
        captureExplicit = TurnipConfig.readConfKey(conf, "fg_capture_w") != null
                       || TurnipConfig.readConfKey(conf, "fg_capture_h") != null;
        /* fg_capture=gs makes the capture track the GS target, so Internal
         * Resolution actually reaches the screen instead of being discarded. */
        String mode = TurnipConfig.readConfKey(conf, "fg_capture");
        captureFollowsGs = mode != null && (mode.equalsIgnoreCase("gs")
                || mode.equalsIgnoreCase("auto") || mode.equalsIgnoreCase("ir"));
        /* Ceiling on capture AREA, in megapixels, for the follow-the-GS path.
         *
         * Matching the GS 1:1 is right for sharpness and wrong for cost past a
         * point. Measured on this device, same scene, same save state:
         *   capture 1024x896 (0.92 MP), IR 2.0 -> out 120 fps, 0 stutter
         *   capture 1536x1344 (2.06 MP), IR 3.0 -> out 26 fps, GPU pegged at 99%
         * DEFAULT OFF (0), deliberately. Capping did NOT rescue high IR — with
         * the GS at 1536x1344 capped to 1042x912 the panel still ran 24 fps and
         * the emulator still fell to 89% speed, because at that resolution the
         * emulator owns the GPU and capture area has stopped being the lever.
         * All a default cap would buy is a quietly softer picture, which is the
         * bug that started this. Set it by hand if you want the trade. */
        fgCaptureMaxMp = clampFloat(conf, "fg_capture_max_mp", 0f, 4.0f, 0f);
        /* HARD CEILING, and it is a crash guard rather than a quality knob.
         * At IR 3.0 the rebuild at capture 1536x1344 never posts a frame and the
         * process dies about a minute later:
         *   SIGSEGV ... libvulkan_freedreno_a8xx-turnip-gen8-V31.so
         * (measured 2026-08-16). Even when it survives, 2.75 lands at 31.7 fps
         * on a 120 Hz panel — WORSE than framegen off. Compared against the GS
         * EXTENT, not against the IR pref: the in-game menu changes IR inside
         * PCSX2 without telling the shim, so the pref is not trustworthy here. */
        fgMaxIr = clampFloat(conf, "fg_max_ir", 1.0f, 8.0f, 2.5f);
        /* AUTO BY DEFAULT, because a fixed 2 cannot reach a 120 Hz panel from a
         * 30 fps game — and most of the PS2 library is 30 fps.
         *
         * Measured on Ultimate Spider-Man (native 30): at the old hardcoded
         * mult=2 the pipeline produced a flawless "real 30 gen 30 out 59" and
         * left half the panel's refresh unused. At mult=4 the same scene reads
         * "real 30 gen 90 out 119", emulator still 29.98, skipped 0. Nothing
         * was broken; the multiplier simply never asked for 120.
         *
         * An explicit fg_multiplier still pins it. */
        fgMultiplier = clampInt(conf, "fg_multiplier", 2, 4, 2);
        /* AUTO IS OPT-IN NOW. It re-picked the multiplier every cooldown and each
         * change rebuilds the context, so a long session thrashed 2x <-> 4x once a
         * minute. Reported as hallucinated frames and "buggy as hell", and it is:
         * every rebuild drops the latch and restarts generation mid-motion. A
         * pinned 2x measured "real 60 gen 60 out 119 skipped 0" on the same scene.
         * Set fg_multiplier=auto to get the old behaviour back. */
        String multRaw = TurnipConfig.readConfKey(conf, "fg_multiplier");
        fgMultiplierAuto = multRaw != null && "auto".equalsIgnoreCase(multRaw.trim());
        fgFlowScale = clampFloat(conf, "fg_flow_scale", 0.25f, 1.0f, 0.25f);
        // Reject cuts and adjacent regions with large high-contrast changes.
        // Keep optical flow at the requested scale; only unsafe pairs use a real frame.
        fgAntiArtifacts = readConfBool(conf, "fg_anti_artifacts", true);
        fgQueueDepth = clampInt(conf, "fg_queue_depth", 1, 8, 1);
        /* Default the output cap to THIS DISPLAY's refresh rate, not 120.
         * Hardcoding 120 was tuned for one 120 Hz handheld; on a 60 Hz phone it
         * asks the pipeline to generate two frames per vblank, which is pure
         * wasted GPU and shows up as judder rather than smoothness. */
        fgTargetFpsCap = clampInt(conf, "fg_target_fps_cap", 0, 240, displayRefreshHz);
        fgPerformance = readConfBool(conf, "fg_performance",
                readConfBool(conf, "lsfg_performance_mode", false));
        try {
            nativeSetCaptureSize(captureW, captureH);
        } catch (Throwable t) {
            note(files, "nativeSetCaptureSize failed: " + t);
        }
        note(files, String.format(java.util.Locale.US,
                "framegen config: capture=%dx%d mult=%d flow=%.2f queue=%d cap=%d"
                        + " perf=%s antiart=%s",
                captureW, captureH, fgMultiplier, fgFlowScale, fgQueueDepth,
                fgTargetFpsCap, fgPerformance ? "on" : "off",
                fgAntiArtifacts ? "on" : "off"));
    }

    private static boolean readConfBool(File conf, String key, boolean def) {
        String raw = TurnipConfig.readConfKey(conf, key);
        if (raw == null || raw.isEmpty()) return def;
        return raw.equals("1") || raw.equalsIgnoreCase("on")
                || raw.equalsIgnoreCase("true");
    }

    private static int evenDim(int v) { return v & ~1; }

    private static int clampInt(File conf, String key, int min, int max, int def) {
        String raw = TurnipConfig.readConfKey(conf, key);
        if (raw == null || raw.isEmpty()) return def;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float clampFloat(File conf, String key, float min, float max, float def) {
        String raw = TurnipConfig.readConfKey(conf, key);
        if (raw == null || raw.isEmpty()) return def;
        try {
            float v = Float.parseFloat(raw.trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* Pacing values are the app's own defaults (PacingDefaults in
     * LsfgPreferences.kt) rather than invented ones — this pipeline was tuned
     * against them. */
    private static final float EMA_ALPHA = 0.125f;
    private static final float OUTLIER_RATIO = 4.0f;
    private static final float VSYNC_SLACK_MS = 3.0f;

    /**
     * Headless proof that the pipeline runs on this device: renders into an
     * off-screen ImageReader instead of a visible surface, pushes a run of
     * frames, and reports what the pipeline says it did.
     *
     * <p>Off-screen on purpose. The question here is only "does the render loop
     * accept frames and produce generated ones on an Adreno 740", and asking it
     * with a real overlay would put untested output on top of a running game.
     * The frame counters answer it without drawing anything the user can see.
     *
     * <p>Runs on its own thread — it deliberately paces pushes at ~60Hz.
     */
    public static void selfTestAsync(final Context ctx) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        String on = TurnipConfig.readConfKey(new File(files, "turnip.conf"), "lsfg_overlay_selftest");
        if (on == null || !(on.equalsIgnoreCase("on") || on.equals("1")
                || on.equalsIgnoreCase("true"))) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    selfTest(app);
                } catch (Throwable t) {
                    note(app.getExternalFilesDir(null), "selftest threw: " + t);
                }
            }
        }, "lsfg-selftest").start();
    }

    private static void selfTest(Context ctx) {
        File files = ctx.getExternalFilesDir(null);
        if (!ready()) {
            note(files, "selftest skipped - pipeline not ready");
            return;
        }
        final int w = captureW, h = captureH;
        final int frames = 120;

        android.media.ImageReader reader = null;
        android.hardware.HardwareBuffer hb = null;
        try {
            reader = android.media.ImageReader.newInstance(
                    w, h, android.graphics.PixelFormat.RGBA_8888, 4);
            File cacheDir = new File(new File(files, "lsfg"), "shaders");

            int rc = initContextDefaults(cacheDir.getAbsolutePath(), w, h);
            if (!lsfgInitLive(rc)) {
                note(files, "selftest initContext FAILED rc=" + rc);
                return;
            }
            NativeBridge.INSTANCE.setOutputSurface(reader.getSurface(), w, h);
            note(files, "selftest initContext ok at " + w + "x" + h + " (rc=" + rc + ")");

            hb = android.hardware.HardwareBuffer.create(w, h,
                    android.hardware.HardwareBuffer.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);

            long t = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                NativeBridge.INSTANCE.pushFrame(hb, t);
                t += 16_666_667L;               /* pretend 60Hz */
                try { Thread.sleep(16); } catch (InterruptedException ie) { break; }
            }
            /* Let the worker drain before reading its counters. */
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }

            long posted = NativeBridge.INSTANCE.getPostedFrameCount();
            long generated = NativeBridge.INSTANCE.getGeneratedFrameCount();
            note(files, "SELFTEST pushed=" + frames + " posted=" + posted
                    + " generated=" + generated
                    + (generated > 0
                        ? "  => PIPELINE PRODUCES FRAMES"
                        : "  => no generated frames"));
        } finally {
            try { NativeBridge.INSTANCE.setOutputSurface(null, 0, 0); } catch (Throwable ignored) { }
            try { NativeBridge.INSTANCE.destroyContext(); } catch (Throwable ignored) { }
            if (hb != null) try { hb.close(); } catch (Throwable ignored) { }
            if (reader != null) try { reader.close(); } catch (Throwable ignored) { }
        }
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---- the extracted shader cache, and the stamp that vouches for it ---- */

    /** Where {@code extractShaders} writes, and the only input {@code initContext} needs. */
    static File shaderCacheDir(File files) {
        return new File(new File(files, "lsfg"), "shaders");
    }

    /**
     * Written only after an extraction has ALSO passed the driver probe, so its
     * presence means "these shaders were built from a real Lossless.dll and this
     * device could load them". Two lines: the dll's sha256, then the number of
     * top-level .spv files that extraction produced.
     */
    private static File stampFile(File cacheDir) {
        return new File(cacheDir, ".source");
    }

    private static String[] readStampLines(File cacheDir) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(stampFile(cacheDir)))) {
            String sha = r.readLine();
            String count = r.readLine();
            if (sha == null || sha.trim().isEmpty()) return null;
            return new String[]{sha.trim(), count == null ? "0" : count.trim()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readStamp(File cacheDir) {
        String[] s = readStampLines(cacheDir);
        return s == null ? null : s[0];
    }

    private static void writeStamp(File cacheDir, String sha) {
        if (sha == null) return;
        try (java.io.FileOutputStream out =
                     new java.io.FileOutputStream(stampFile(cacheDir))) {
            out.write((sha + "\n" + countSpv(cacheDir) + "\n").getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "could not write shader stamp: " + t);
        }
    }

    private static void clearStamp(File cacheDir) {
        try {
            stampFile(cacheDir).delete();
        } catch (Throwable ignored) {
        }
    }

    private static int countSpv(File cacheDir) {
        String[] names = cacheDir.list();
        if (names == null) return 0;
        int n = 0;
        for (String s : names) if (s.endsWith(".spv")) n++;
        return n;
    }

    /** Cheap, file-level: as many .spv as the stamp recorded, and the first one there. */
    private static boolean cacheLooksComplete(File cacheDir) {
        String[] s = readStampLines(cacheDir);
        if (s == null) return false;
        if (!new File(cacheDir, "255.spv").isFile()) return false;
        try {
            return countSpv(cacheDir) >= Integer.parseInt(s[1]);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * True when a previously extracted, driver-validated shader cache is still
     * on disk. This — not the presence of the dll file — is what "the user has
     * supplied their Lossless.dll" means once framegen has run: nothing at run
     * time reads the dll. Cheap enough for the UI thread; the authoritative
     * check is still {@code probeShaders} inside {@link #prepare}.
     */
    static boolean shaderCacheUsable(File files) {
        if (files == null) return false;
        return cacheLooksComplete(shaderCacheDir(files));
    }

    private static boolean extractInto(File files, File dll, String sha, File cacheDir) {
        int rc;
        try {
            rc = NativeBridge.INSTANCE.extractShaders(
                    dll.getAbsolutePath(), sha, cacheDir.getAbsolutePath());
        } catch (Throwable t) {
            note(files, "extractShaders threw: " + t);
            return false;
        }
        if (rc != 0) {
            note(files, "extractShaders failed rc=" + rc + " (dll sha256=" + sha + ")");
            return false;
        }
        return true;
    }

    /**
     * Process CPU as a share of the whole device, from {@code /proc/self/stat}.
     *
     * <p>This is NOT the number PCSX2's own row shows — that one comes from the
     * core's per-thread accounting, and {@code NativeLibrary} exposes no getter
     * for it (checked by dumping every method). utime+stime over wall time is
     * the honest substitute, and it is the app's own file, so no permission is
     * involved. Normalised by core count so it reads 0-100, not 0-800.
     */
    private static double readCpuPercent() {
        try {
            byte[] buf = new byte[512];
            int n;
            try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/stat")) {
                n = in.read(buf);
            }
            if (n <= 0) return -1;
            String s = new String(buf, 0, n);
            /* comm can contain spaces and parens — fields are counted after the
             * LAST ')', which is why this cannot just be split(" "). */
            int close = s.lastIndexOf(')');
            if (close < 0) return -1;
            String[] f = s.substring(close + 2).trim().split("\\s+");
            /* after comm+state the array is 0-based: utime is field 11, stime 12 */
            if (f.length < 13) return -1;
            long jiffies = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            long now = System.nanoTime();
            double pct = -1;
            if (lastCpuJiffies > 0 && now > lastCpuNs) {
                double dtMs = (now - lastCpuNs) / 1e6;
                int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
                /* USER_HZ is 100 on Android: one jiffy is 10 ms of one core. */
                pct = (jiffies - lastCpuJiffies) * 10.0 / (dtMs * cores) * 100.0;
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
            }
            lastCpuJiffies = jiffies;
            lastCpuNs = now;
            return pct;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Adreno GPU busy, from kgsl sysfs.
     *
     * <p>Prefer {@code gpu_busy_percentage} — it is a plain reading. The older
     * {@code gpubusy} pair RESETS when read, so any other reader (a system
     * daemon, an adb shell) silently steals part of the interval and both get
     * an under-count. Both files are 0444 root:root, so the app can read them.
     */
    private static double readGpuPercent() {
        double v = readSysfsNumber("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (v >= 0) return v;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String[] p = r.readLine().trim().split("\\s+");
            long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
            return total > 0 ? Math.min(100.0, busy * 100.0 / total) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static double readSysfsNumber(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String line = r.readLine();
            if (line == null) return -1;
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\d+").matcher(line);
            return m.find() ? Double.parseDouble(m.group()) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long lastCpuJiffies, lastCpuNs;

    private static void note(File files, String msg) {
        Log.i(TAG, "framegen: " + msg);
        try {
            TurnipConfig.note(files, "framegen: " + msg);
        } catch (Throwable ignored) { }
    }
}
