package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Wires Graphics prefs using PrefCompat (handles R8-obfuscated androidx.preference). */
public final class ShimSettingsBridge {
    private ShimSettingsBridge() {}

    private static boolean mutating = false;
    private static boolean globalSpWired = false;
    /* STRONG REFERENCES, AND THAT IS THE WHOLE POINT.
     *
     * SharedPreferencesImpl keeps listeners in a WeakHashMap. This lambda used
     * to be registered and dropped on the floor, so it was collected long
     * before the user ever reached a game -- which is why an in-game OSD toggle
     * wrote SharedPreferences correctly and then went nowhere, while App
     * Settings still worked through the watchdog poll. */
    private static SharedPreferences.OnSharedPreferenceChangeListener globalSpListener;
    private static SharedPreferences globalSp;
    private static final Set<Integer> prefListenerActivities = new HashSet<>();

    private static final String[] GRAPHICS_SYNC_KEYS = {
        "VulkanShim/UpscalerFsr1",
        "VulkanShim/FsrQuality4x",
        "VulkanShim/Lsfg",
        "VulkanShim/LowEndPerf",
        "EmuCore/EnableCheats",
        "EmuCore/EnableWideScreenPatches",
        "EmuCore/GS/upscale_multiplier",
        "EmuCore/GS/OsdShowFPS",
        "EmuCore/GS/OsdShowSpeed",
        "EmuCore/GS/OsdShowCPU",
        "EmuCore/GS/OsdShowGPU",
        "EmuCore/GS/OsdShowFrameTimes",
        "EmuCore/GS/OsdShowResolution",
        "EmuCore/GS/OsdPerformancePos",
    };

    public static void registerGlobalPrefListener(Context ctx) {
        if (globalSpWired || ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, app);
            SharedPreferences sp = (SharedPreferences) pm;
            globalSp = sp;
            OsdPrefs.seedExternalOsdWatch(app);
            globalSpListener = (sharedPreferences, key) -> {
                if (key == null) return;
                if (OsdPrefs.isStockOsdPrefKey(key)) {
                    /* Provenance-gated in OsdPrefs. Deliberately NOT behind
                     * `mutating`: the gate must see EVERY write to these keys,
                     * including the core's mirror write that lands inline while
                     * we are still applying an accepted tap -- otherwise its
                     * per-key baseline drifts and the next real tap counts as
                     * "2 stats moved" and is thrown away. The gate carries its
                     * own re-entrancy guard, so this cannot loop. */
                    OsdPrefs.onExternalOsdWrite(app, key);
                    return;
                }
                if (mutating) return;
                if (!key.equals("EmuCore/EnableCheats")
                        && !key.equals("VulkanShim/FsrQuality4x")
                        && !key.equals("VulkanShim/UpscalerFsr1")
                        && !key.equals("VulkanShim/LowEndPerf")
                        && !key.equals("EmuCore/GS/upscale_multiplier")
                        && !key.equals("EmuCore/EnableWideScreenPatches")
                        && !key.equals("UI/Theme"))
                    return;
                mutating = true;
                try {
                    handlePrefKey(app, sharedPreferences, key, null, true);
                } finally {
                    mutating = false;
                }
            };
            sp.registerOnSharedPreferenceChangeListener(globalSpListener);
            globalSpWired = true;
            TurnipConfig.note(app.getExternalFilesDir(null), "global SP listener registered");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "global SP listener failed: " + t.getMessage());
            try {
                TurnipConfig.note(ctx.getApplicationContext().getExternalFilesDir(null),
                        "global SP listener FAILED: " + t);
            } catch (Throwable ignored) { }
        }
    }

    private static void handlePrefKey(Context ctx, SharedPreferences sp, String key,
            Object rawOverride, boolean toast) {
        if (key.equals("EmuCore/EnableCheats")) {
            boolean on = resolveBool(sp, key, rawOverride);
            if (TurnipConfig.apply60FpsMode(ctx, on) && toast)
                toast(ctx, on ? "60 FPS ON" : "60 FPS OFF");
            return;
        }
        if (key.equals("EmuCore/EnableWideScreenPatches")) {
            Object raw = rawOverride != null ? rawOverride
                    : (sp != null ? sp.getAll().get(key) : null);
            // Core mirrors use Integer 0/1; only UI Boolean writes ask to restart.
            TurnipConfig.syncWidescreenLive(ctx,
                    raw instanceof Boolean && !TurnipConfig.inBootGrace());
            return;
        }
        if (key.equals("EmuCore/GS/upscale_multiplier")) {
            Object raw = rawOverride != null ? rawOverride
                    : (sp != null ? sp.getAll().get(key) : null);
            syncUpscaleFromRaw(ctx instanceof Activity ? (Activity) ctx : null, ctx, raw);
            return;
        }
        if ("VulkanShim/Lsfg".equals(key)) {
            /* In-process frame generation. Gated on FramegenBuild, not
             * ShimBuildConfig.FSR — the FSR flag is off in this build and has
             * nothing to do with this feature. */
            if (!FramegenBuild.AVAILABLE)
                return;
            TurnipConfig.applyLsfgMode(ctx, resolveBool(sp, key, rawOverride));
            return;
        }
        if (HandheldTier.PREF_KEY.equals(key)) {
            boolean on = resolveBool(sp, key, rawOverride);
            if (TurnipConfig.applyLowEndPerfMode(ctx, on) && toast)
                toast(ctx, on ? "Low-end perf ON" : "Quality restored");
            return;
        }
        if ("VulkanShim/FsrQuality4x".equals(key) || "VulkanShim/UpscalerFsr1".equals(key)) {
            if (!ShimBuildConfig.FSR)
                return;
            boolean on = resolveBool(sp, key, rawOverride);
            if (TurnipConfig.applyFsrMode(ctx, on) && toast)
                return;
            return;
        }
        if (OsdPrefs.isStockOsdPrefKey(key)) {
            /* Do NOT go through commitStockOsdToggle here. On a listener path
             * SP already holds the new value, so it returns false at its own
             * no-change guard and the old code then applied unconditionally --
             * which turned every core stomp into an applySettings() during
             * gameplay. onExternalOsdWrite decides provenance first, and
             * ignores OsdPerformancePos (an int, never a tap). */
            OsdPrefs.onExternalOsdWrite(ctx, key);
            return;
        }
        if ("UI/Theme".equals(key)) {
            Object raw = rawOverride != null ? rawOverride
                    : (sp != null ? sp.getAll().get(key) : null);
            if (ctx instanceof Activity) {
                Activity activity = (Activity) ctx;
                if (ShimTheme.isSettingsActivity(activity)
                        || ShimTheme.hasInGameSettingsOverlay(activity))
                    ctx = activity;
            }
            ShimTheme.onThemeSpChanged(ctx, raw);
        }
    }

    private static boolean resolveBool(SharedPreferences sp, String key, Object rawOverride) {
        if (rawOverride != null) {
            return Boolean.TRUE.equals(rawOverride)
                    || "true".equalsIgnoreCase(String.valueOf(rawOverride));
        }
        if (sp == null) return false;
        try {
            return sp.getBoolean(key, false);
        } catch (ClassCastException e) {
            try {
                String s = sp.getString(key, null);
                return s != null && (s.equalsIgnoreCase("true") || s.equals("1"));
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static void toast(Context ctx, String msg) {
        PrefCompat.toast(ctx, msg);
    }

    public static void hook(Activity activity) {
        PrefCompat.resolve();
        try {
            Class<?> fragAct = Class.forName("androidx.fragment.app.FragmentActivity");
            /* Pink's SettingsActivity is not a FragmentActivity.  Do not return
             * here: PrefCompat.augmentThemeInActivity() also scans the platform
             * fragment manager, which is where its General screen lives. */
            if (fragAct.isInstance(activity)) {
                Object fm = fragAct.getMethod("getSupportFragmentManager").invoke(activity);
                wireTree(fm, activity);
            }
        } catch (Throwable ignored) {
            // Pink's SettingsActivity may not bundle FragmentActivity. Its
            // platform PreferenceActivity path is handled below.
        }
        try {
            PrefCompat.augmentThemeInActivity(activity);
            PrefCompat.bindVisibleSwitches(activity);
            ToggleWatchdog.pollActivity(activity);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "settings hook: " + t.getMessage());
        }
    }

    private static void wireTree(Object fragmentManager, Activity activity) {
        try {
            List<Object> frags = PrefCompat.fragmentsOf(fragmentManager);
            if (frags == null) return;
            for (Object frag : frags) {
                if (frag == null) continue;
                if (PrefCompat.isPreferenceFragment(frag)) {
                    wirePreferenceFragment(frag, activity);
                }
                try {
                    Object childFm = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    wireTree(childFm, activity);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static void wirePreferenceFragment(Object frag, Activity activity) {
        try {
            int bound = 0;
            for (String key : GRAPHICS_SYNC_KEYS) {
                Object pref = PrefCompat.findPreference(frag, key);
                if (pref == null) continue;
                PrefCompat.setChangeListener(pref, key, activity);
                bound++;
            }
            Object themePref = PrefCompat.findPreference(frag, "UI/Theme");
            if (themePref != null) {
                PrefCompat.augmentThemePreference(themePref);
                PrefCompat.armThemePreferenceDialog(themePref);
                PrefCompat.setChangeListener(themePref, "UI/Theme", activity);
                bound++;
            }
            if (bound > 0)
                android.util.Log.i("VulkanShim", "bound " + bound + " graphics prefs (obfuscated API)");

            int actId = System.identityHashCode(activity);
            if (prefListenerActivities.add(actId)) {
                Object prefs = Class.forName("androidx.preference.PreferenceManager")
                        .getMethod("getDefaultSharedPreferences", Context.class)
                        .invoke(null, activity);
                SharedPreferences sp = (SharedPreferences) prefs;
                sp.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                    if (key == null || mutating) return;
                    if (OsdPrefs.isStockOsdPrefKey(key)) {
                        /* Weak on purpose (captures the Activity), so normally
                         * dead -- but if it ever fires it must not apply ungated. */
                        OsdPrefs.onExternalOsdWrite(activity, key);
                        return;
                    }
                    if (!key.startsWith("VulkanShim/")
                            && !key.equals("EmuCore/EnableWideScreenPatches")
                            && !key.equals("EmuCore/EnableCheats")
                            && !key.equals("EmuCore/GS/upscale_multiplier")
                            && !key.equals("UI/Theme")) return;
                    if ("UI/Theme".equals(key)) {
                        ShimTheme.onThemeSpChanged(activity, sharedPreferences.getAll().get(key));
                        return;
                    }
                    mutating = true;
                    try {
                        handlePrefKey(activity, sharedPreferences, key, null, true);
                    } finally {
                        mutating = false;
                    }
                });
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "wirePreferenceFragment: " + t.getMessage());
        }
    }

    private static void syncUpscaleFromRaw(Activity activity, Context ctx, Object raw) {
        Context c = activity != null ? activity : ctx;
        if (c == null) return;
        TurnipConfig.applyUserInternalResolution(c, raw, true);
    }
}
