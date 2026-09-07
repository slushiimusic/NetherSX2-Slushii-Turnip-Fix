package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.util.Log;

/** Runs early at app start — wires VulkanShim settings without replacing Application. */
public class ShimInitProvider extends ContentProvider {
    private static final java.util.Set<Integer> layoutHooked = new java.util.HashSet<>();

    @Override
    public boolean onCreate() {
        Log.i("VulkanShim", "ShimInitProvider onCreate");
        if (getContext() != null) {
            // Explicitly staged migration only; originals stay in the backup.
            if (!StorageOwnershipRepair.restore(getContext().getExternalFilesDir(null)))
                throw new IllegalStateException("Pink data import incomplete; backup retained");
            HandheldTier.ensureTurnipConfBeforeNativeLoad(getContext());
            /* libvulkad.so must load after turnip.conf is patched on SD865 — fg_osd=on
             * installs Vulkan present hooks even with lsfg_overlay=off. */
            try {
                System.loadLibrary("vulkad");
                Log.i("VulkanShim", "Preloaded libvulkad.so (shim_init should run)");
            } catch (Throwable t) {
                Log.w("VulkanShim", "libvulkad preload failed: " + t.getMessage());
            }
            /* BEFORE RendererPrefs/OsdPrefs/any fragment: leftover Float/Integer
             * in ListPreference keys ClassCast-crash Graphics inflate. */
            TurnipConfig.forceGraphicsListPrefsAsStrings(getContext());
            RendererPrefs.ensureVulkanRenderer(getContext());
            /* applyPolicy first: it repairs a stock OSD whose SharedPreferences
             * have been left all-false (the core stomps them), reseeding from
             * PCSX2.ini. It MUST run before syncPrefsToIniLayers, which pushes
             * SP -> ini and would otherwise overwrite the ini's good values with
             * the all-false state — destroying the only recovery source. */
            OsdPrefs.applyPolicy(getContext());
            OsdPrefs.syncPrefsToIniLayers(getContext());
            TurnipConfig.syncAtStartup(getContext());
            TurnipConfig.forceGraphicsListPrefsAsStrings(getContext());
            TurnipConfig.logToggleState(getContext());
            ShimSettingsBridge.registerGlobalPrefListener(getContext());
            ToggleWatchdog.start(getContext());
            Application app = (Application) getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                private boolean isEmu(Activity a) {
                    return a != null && "xyz.aethersx2.android.EmulationActivity"
                            .equals(a.getClass().getName());
                }
                /**
                 * Drop the native layer's cached render surface before a fresh
                 * VM starts.
                 *
                 * Starting a game a SECOND time inside one process bounced the
                 * user straight back to the library about 2 times in 3. Not a
                 * crash — the emulation thread simply ends, and q3.s1 finishes
                 * EmulationActivity when it does, so nothing reaches dropbox:
                 *
                 *   Starting emulation thread (...)
                 *   SurfaceView[...EmulationActivity]#20 connect:
                 *       BufferQueue has been abandoned
                 *   surface create returned -1000000001   (NATIVE_WINDOW_IN_USE)
                 *   Emulation thread exiting.             (45 ms later)
                 *
                 * The GS opens its Vulkan surface on the PREVIOUS activity's
                 * dead ANativeWindow. The app means to prevent this — but its
                 * surfaceDestroyed() guards changeSurface(null) on
                 * hasEmulationThread(), and both teardown paths call
                 * stopEmulationThread() first, which nulls that thread. So the
                 * clear is dead code and the stale window survives the activity.
                 *
                 * EmulationActivity.onCreate ends in startEmulationThread() +
                 * wait(), so the new SurfaceView cannot reach the native side
                 * before the GS is already opening — this callback is the last
                 * point that still runs first.
                 *
                 * Measured: same-process relaunch 2/5 bounced before, and a
                 * force-stop between launches was 0/13 — killing the process is
                 * what made it invisible under automation for so long.
                 */
                private void clearStaleRenderSurface() {
                    try {
                        Class<?> nl = Class.forName(
                                "xyz.aethersx2.android.NativeLibrary");
                        Object live = nl.getMethod("hasEmulationThread")
                                .invoke(null);
                        /* An activity RECREATE (rotation, theme) keeps the VM
                         * alive and it still owns that window — clearing it
                         * there would blank a running game. */
                        if (live instanceof Boolean && (Boolean) live) return;
                        nl.getMethod("changeSurface", android.view.Surface.class,
                                     int.class, int.class, float.class)
                          .invoke(null, null, 0, 0, 0.0f);
                        Log.i("VulkanShim",
                              "cleared stale native render surface before VM start");
                    } catch (Throwable t) {
                        Log.w("VulkanShim", "clearStaleRenderSurface: " + t);
                    }
                }
                private boolean isSetup(Activity a) {
                    return a != null && "xyz.aethersx2.android.SetupWizardActivity"
                            .equals(a.getClass().getName());
                }
                @Override public void onActivityPreCreated(Activity activity, android.os.Bundle b) {
                    if (isEmu(activity)) clearStaleRenderSurface();
                    TurnipConfig.forceGraphicsListPrefsAsStrings(activity);
                    ShimTheme.prepareActivityPreCreate(activity);
                    // Settings' stock ActionBar is constructed between this
                    // callback and onActivityCreated. Keep it invisible until
                    // ShimAccent applies the packaged gradient.
                    ShimAccent.hideSettingsUntilBranded(activity);
                }
                @Override public void onActivityCreated(Activity activity, android.os.Bundle b) {
                    ShimTheme.registerActivity(activity);
                    TurnipConfig.forceGraphicsListPrefsAsStrings(activity);
                    // Settings creates its toolbar during Activity.onCreate.
                    // Apply here, before onStart/onResume can draw the stock
                    // teal header for a frame.
                    ShimTheme.applyAccentAfterCreate(activity);
                    /* Earliest point the emulator SurfaceView can exist. The
                     * size must be set BEFORE its surfaceCreated starts the
                     * renderer: LSFG builds its AHB images once per swapchain
                     * and its re-create path fails outright, so resizing later
                     * disables frame generation silently. */
                    if (isEmu(activity) && FramegenBuild.AVAILABLE) {
                        ShimSurfaceScale.applyEarly(activity);
                        ShimFrameGen.attachOsdIfEnabled(activity);
                        if (ShimFrameGen.prepare(activity))
                            ShimFrameGen.attachOverlay(activity);
                    }
                }
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {
                    ShimTheme.syncActivity(activity);
                    if (isEmu(activity)) {
                        TurnipConfig.onEmulationResumed(activity);
                        if (FramegenBuild.AVAILABLE)
                            ShimFrameGen.onEmulationResumed(activity);
                    }
                    TurnipConfig.forceGraphicsListPrefsAsStrings(activity);
                    ShimSettingsBridge.hook(activity);
                    ToggleWatchdog.pollActivity(activity);
                    android.view.View decor = activity.getWindow().getDecorView();
                    decor.post(() -> {
                        ShimSettingsBridge.hook(activity);
                        ToggleWatchdog.pollActivity(activity);
                    });
                    decor.postDelayed(() -> {
                        ShimSettingsBridge.hook(activity);
                        ToggleWatchdog.pollActivity(activity);
                    }, 300);
                    decor.postDelayed(() -> {
                        ShimSettingsBridge.hook(activity);
                        ToggleWatchdog.pollActivity(activity);
                    }, 1000);
                    int id = System.identityHashCode(activity);
                    if (layoutHooked.add(id)) {
                        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                            ShimSettingsBridge.hook(activity);
                            ToggleWatchdog.pollActivity(activity);
                        });
                    }
                }
                @Override public void onActivityPaused(Activity activity) {
                    if (isEmu(activity)) {
                        TurnipConfig.onEmulationPaused(activity);
                        if (FramegenBuild.AVAILABLE)
                            ShimFrameGen.onEmulationPaused(activity);
                    }
                }
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle out) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    ShimTheme.unregisterActivity(activity);
                    if (isSetup(activity))
                        ShimAccent.stopSetupMusic();
                    if (isEmu(activity)) {
                        ShimTheme.onEmulationEnded();
                        if (FramegenBuild.AVAILABLE)
                            ShimFrameGen.onEmulationDestroyed(activity);
                    }
                    layoutHooked.remove(System.identityHashCode(activity));
                }
            });
        }
        return true;
    }

    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        android.content.Context ctx = getContext();
        if (ctx == null) return null;
        boolean on = arg != null && (arg.equals("1") || arg.equalsIgnoreCase("true")
                || arg.equalsIgnoreCase("on"));
        android.os.Bundle out = new android.os.Bundle();
        try {
            if ("set60".equals(method) || "set60fps".equals(method)) {
                TurnipConfig.apply60FpsMode(ctx, on);
                out.putBoolean("enable_60fps", on);
                Log.i("VulkanShim", "provider call set60=" + on);
            } else if ("setFsr".equals(method) || "setFsr4x".equals(method)) {
                if (ShimBuildConfig.FSR)
                    TurnipConfig.applyFsrMode(ctx, on);
                out.putBoolean("fsr", ShimBuildConfig.FSR && on);
                Log.i("VulkanShim", "provider call setFsr=" + on
                        + (ShimBuildConfig.FSR ? "" : " ignored (FSR compiled out)"));
            } else if ("setIr".equals(method) || "setUpscale".equals(method)) {
                String ir = TurnipConfig.applyUserInternalResolution(ctx, arg);
                out.putString("ir", ir);
                Log.i("VulkanShim", "provider call setIr=" + ir);
            } else if ("setFg".equals(method) || "setFramegen".equals(method)) {
                TurnipConfig.stripFramegenConf(ctx);
                out.putBoolean("ok", true);
                Log.i("VulkanShim", "provider call setFg ignored (no-op)");
            } else if ("setTheme".equals(method)) {
                ShimTheme.onThemeSpChanged(ctx, arg);
                out.putString("theme", arg);
                Log.i("VulkanShim", "provider call setTheme=" + arg);
            } else if ("status".equals(method)) {
                java.io.File files = ctx.getExternalFilesDir(null);
                if (files != null) {
                    java.io.File conf = new java.io.File(files, "turnip.conf");
                    out.putString("enable_60fps",
                            TurnipConfig.readConfKeyPublic(conf, "enable_60fps"));
                    out.putString("upscaler",
                            TurnipConfig.readConfKeyPublic(conf, "upscaler"));
                }
            } else {
                out.putString("error", "unknown method");
            }
        } catch (Throwable t) {
            out.putString("error", t.getMessage());
        }
        return out;
    }

    @Override public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(android.net.Uri uri) { return null; }
    @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
    @Override public int delete(android.net.Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(android.net.Uri uri, android.content.ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
