package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Master path: poll default SharedPreferences for Graphics toggles and sync
 * turnip.conf / pnaches / FSR. App Settings and in-game Settings both write SP
 * (not game-ini DataStore). Preference listeners are unreliable (PCSX2 replaces them).
 */
public final class ToggleWatchdog {
    private ToggleWatchdog() {}

    private static boolean started;
    private static WeakReference<Activity> currentActivity;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Boolean lastWidescreen;
    private static int diag;
    /** Last IR seen in the visible Graphics row — edge detection, see pollOnce. */
    private static String lastSeenUiIr;
    private static String lastSeenUiTheme;
    private static final Map<String, Boolean> lastOsdToggles = new HashMap<>();
    private static boolean osdPollInitialized;
    /** Last observed Frame Generation switch state; null until first seen. */
    private static Boolean lastLsfg;
    /** Last observed Low-End Performance switch; null until first seen. */
    private static Boolean lastLowEndPerf;
    private static boolean lsfgPrefAligned;
    private static long lastAccentMs;
    private static long lastIniScanMs;
    private static long lastBindMs;
    private static long lastInGameThemeMs;
    private static Boolean cachedPrefsUi;
    private static long cachedPrefsUiMs;

    /** Cached — hasVisiblePreferenceUi walks the whole decor tree. */
    private static boolean prefsUiVisible(Activity activity) {
        if (activity == null) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (cachedPrefsUi != null && (now - cachedPrefsUiMs) < 500L)
            return cachedPrefsUi;
        cachedPrefsUi = ShimTheme.isSettingsActivity(activity)
                || PrefCompat.hasVisiblePreferenceUi(activity);
        cachedPrefsUiMs = now;
        return cachedPrefsUi;
    }

    public static void start(Context ctx) {
        if (started || ctx == null) return;
        started = true;
        Context app = ctx.getApplicationContext();
        H.postDelayed(() -> tick(app), 600);
        android.util.Log.i("VulkanShim", "ToggleWatchdog started (SP poll)");
    }

    public static void pollActivity(Activity activity) {
        if (activity == null) return;
        currentActivity = new WeakReference<>(activity);
        H.post(() -> syncFromSharedPrefs(activity.getApplicationContext(), activity));
    }

    /** Foreground Activity, for the restart prompt. Null when nothing is on screen. */
    static Activity foregroundActivity() {
        Activity a = currentActivity != null ? currentActivity.get() : null;
        if (a == null) return null;
        return (a.isFinishing() || a.isDestroyed()) ? null : a;
    }

    private static void tick(Context app) {
        Activity activity = currentActivity != null ? currentActivity.get() : null;
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
            activity = null;
            currentActivity = null;
        }
        try {
            syncFromSharedPrefs(app, activity);
            TurnipConfig.pollForceFile(app);
            TurnipConfig.flushPendingGsIr(app);
            TurnipConfig.flushPendingFsr(app);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "ToggleWatchdog: " + t.getMessage());
        }
        H.postDelayed(() -> tick(app), tickIntervalMs(activity));
    }

    private static long tickIntervalMs(Activity activity) {
        if (activity == null) return 3000L;
        if (prefsUiVisible(activity)) return 750L;
        if ("xyz.aethersx2.android.EmulationActivity".equals(activity.getClass().getName()))
            return 8000L;
        return 3000L;
    }

    private static boolean inEmulation(Activity activity) {
        return activity != null
                && "xyz.aethersx2.android.EmulationActivity"
                        .equals(activity.getClass().getName());
    }

    private static void syncFromSharedPrefs(Context app, Activity activity) {
        SharedPreferences sp;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, app);
            sp = (SharedPreferences) pm;
        } catch (Throwable t) {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        boolean prefsUi = prefsUiVisible(activity);
        if (FramegenBuild.AVAILABLE)
            ShimFrameGen.refreshDisplayEligibility(activity != null ? activity : app);

        boolean sp60 = readSpBool(sp, "EmuCore/EnableCheats", false);
        boolean spFsr = ShimBuildConfig.FSR && (readSpBool(sp, "VulkanShim/FsrQuality4x", false)
                || readSpBool(sp, "VulkanShim/UpscalerFsr1", false));

        /*
         * NOT reading gamesettings/*.ini back here on purpose. The in-game Game
         * Properties → Graphics tab persists through an INI PreferenceDataStore
         * rather than SharedPreferences, so its switches are dropped — but an
         * ini read-back made the toggles flip themselves on and off during
         * startup (measured: 60 FPS false→true→false inside 4 s, re-renaming
         * every pnach each time). SharedPreferences stays the only authority
         * until that path is properly worked out. TurnipConfig.detectGameIniEdit
         * is kept but unused.
         */

        /*
         * Prefer SharedPreferences. When Graphics is open and isChecked differs
         * (obfuscated Preference field R via PrefCompat), take that as user intent.
         */
        File files = app.getExternalFilesDir(null);
        boolean confOwns = TurnipConfig.confOwnsToggles(files);
        boolean want60 = sp60;
        boolean wantFsr = spFsr;
        /* Bench deploy pins 60 FPS / FSR in turnip.conf — SP must not re-arm those. */
        if (confOwns) {
            want60 = readConf60(app);
            if (ShimBuildConfig.FSR)
                wantFsr = readConfFsr(app);
        }
        Boolean ui60 = prefsUi
                ? PrefCompat.findCheckedInActivity(activity, "EmuCore/EnableCheats") : null;
        Boolean uiFsr = null;
        if (prefsUi && ShimBuildConfig.FSR && activity != null) {
            uiFsr = PrefCompat.findCheckedInActivity(activity, "VulkanShim/FsrQuality4x");
            if (uiFsr == null)
                uiFsr = PrefCompat.findCheckedInActivity(activity, "VulkanShim/UpscalerFsr1");
        }
        /* Stale back-stack checkbox state during Emulation boot caused false toggles. */
        if (!TurnipConfig.inBootGrace()) {
            if (ui60 != null && ui60 != sp60)
                want60 = ui60;
            if (uiFsr != null && uiFsr)
                wantFsr = true;
        }

        /* Frame Generation switch. Polled rather than driven by a listener for
         * the same reason as everything else here: PCSX2 replaces the
         * SharedPreferences change listener, so a listener alone misses changes.
         * Edge-triggered on lastLsfg so applyLsfgMode is not called every tick —
         * it rewrites turnip.conf and starts/stops the pipeline. */
        /* ALIGN THE STORED ROW VALUE WITH THE CONF ONCE, BEFORE ANY ROW EXISTS.
         *
         * turnip.conf ships lsfg_overlay=on; the row's stored value can be
         * anything, so the switch could read "off" over a running pipeline —
         * the exact lie the row's own comment in add-vulkan-shim-prefs.py warns
         * about. It has to happen here, with no Settings screen on screen: an
         * inflated row does NOT repaint from a SharedPreferences write, so
         * correcting the value under a visible switch leaves the widget showing
         * the old state, and the very next poll reads that as a user click and
         * writes it straight back. Measured, when this ran a few lines lower:
         * "corrected to the conf (true)" at 09:32:03.435 and
         * "framegen switch -> false" 35 ms later, with lsfg_overlay=off as the
         * result. */
        if (FramegenBuild.AVAILABLE && !lsfgPrefAligned && sp != null
                && (activity == null || !activity.getClass().getName().endsWith("SettingsActivity"))) {
            lsfgPrefAligned = true;
            try {
                String confVal = TurnipConfig.readConfKey(
                        new java.io.File(files, "turnip.conf"),
                        ShimFrameGen.CONF_KEY);
                boolean confOn = confVal != null && (confVal.equalsIgnoreCase("on")
                        || confVal.equals("1") || confVal.equalsIgnoreCase("true"));
                boolean prefOn = sp.getBoolean("VulkanShim/Lsfg", false);
                if (TurnipConfig.confOwnsToggles(files)) {
                    Object stored = sp.getAll().get("VulkanShim/Lsfg");
                    if (!(stored instanceof Boolean) || ((Boolean) stored) != confOn) {
                        sp.edit().putBoolean("VulkanShim/Lsfg", confOn).apply();
                        android.util.Log.i("VulkanShim", "framegen row value aligned to conf "
                                + "lsfg_overlay=" + confVal + " (was " + stored + ")");
                    }
                    lastLsfg = confOn;
                } else if (prefOn != confOn) {
                    TurnipConfig.applyLsfgMode(app, prefOn);
                    lastLsfg = prefOn;
                    android.util.Log.i("VulkanShim", "framegen conf aligned to pref Lsfg="
                            + prefOn + " (was lsfg_overlay=" + confVal + ")");
                } else {
                    lastLsfg = prefOn;
                }
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", "framegen row align: " + t);
            }
        }

        if (FramegenBuild.AVAILABLE && sp != null && !TurnipConfig.inBootGrace()) {
            boolean prefLsfg = sp.getBoolean("VulkanShim/Lsfg", false);
            if (lastLsfg == null) {
                lastLsfg = prefLsfg;
                android.util.Log.i("VulkanShim", "framegen switch adopted as " + prefLsfg);
            } else if (!lastLsfg.equals(prefLsfg)) {
                lastLsfg = prefLsfg;
                android.util.Log.i("VulkanShim", "framegen switch -> " + prefLsfg);
                TurnipConfig.applyLsfgMode(app, prefLsfg);
            }
        }

        if (FramegenBuild.AVAILABLE && prefsUi && activity != null
                && HandheldTier.framegenBlockReason(activity) == null
                && !TurnipConfig.inBootGrace()) {
            Boolean uiLsfg = PrefCompat.findCheckedInActivity(activity, "VulkanShim/Lsfg");
            /* The preference-object read returns null on this obfuscated APK —
             * see PrefCompat.findCheckedByTitle. Read the row off the screen. */
            if (uiLsfg == null)
                uiLsfg = PrefCompat.findCheckedByTitle(activity, "Frame Generation");
            if (uiLsfg != null && lastLsfg != null && !lastLsfg.equals(uiLsfg)) {
                lastLsfg = uiLsfg;
                android.util.Log.i("VulkanShim", "framegen UI switch -> " + uiLsfg);
                TurnipConfig.applyLsfgMode(app, uiLsfg);
                try {
                    sp.edit().putBoolean("VulkanShim/Lsfg", uiLsfg).apply();
                } catch (Throwable ignored) { }
            }

            /* Turnip Driver + Bug Report: wired in PrefCompat.bindActionSwitch.
             * Polling here double-fired the picker and missed taps when
             * findCheckedInActivity returned null on obfuscated builds. */
        }

        if (prefsUi && activity != null && !TurnipConfig.inBootGrace()) {
            Boolean uiLowEnd = PrefCompat.findCheckedInActivity(activity, HandheldTier.PREF_KEY);
            if (uiLowEnd == null)
                uiLowEnd = PrefCompat.findCheckedByTitle(activity, "Low-End Performance");
            if (uiLowEnd != null) {
                if (lastLowEndPerf == null) {
                    lastLowEndPerf = uiLowEnd;
                    android.util.Log.i("VulkanShim", "LowEndPerf switch adopted as " + uiLowEnd);
                } else if (!lastLowEndPerf.equals(uiLowEnd)) {
                    lastLowEndPerf = uiLowEnd;
                    android.util.Log.i("VulkanShim", "LowEndPerf switch -> " + uiLowEnd);
                    TurnipConfig.applyLowEndPerfMode(app, uiLowEnd);
                }
            }
        }

        /* Bind switch listeners at most once per 1.5 s while settings is open. */
        if (prefsUi && activity != null && (now - lastBindMs) >= 1500L) {
            lastBindMs = now;
            PrefCompat.bindVisibleSwitches(activity);
        }

        ShimTheme.pollTheme(app);
        if (!inEmulation(activity) && (now - lastInGameThemeMs) >= 2000L) {
            lastInGameThemeMs = now;
            ShimTheme.pollInGameTheme(app);
        }
        /* EDGE-TRIGGERED, for the same reason the IR poll below is, and it cost
         * the user their theme until it was.
         *
         * findVisibleThemeChoice reads a ListPreference's in-memory value, and
         * nothing in the shim writes back into that widget. Applying a theme
         * RESTARTS SettingsActivity, so for a moment the widget being read still
         * holds the value from BEFORE the change while SharedPreferences already
         * holds the new one. Level-triggered, this site saw "widget != SP",
         * decided the widget was right, and handed the stale value to
         * onThemeSpChanged — which commits it. Measured: picking Dark wrote
         * dark at 08:40:53.707 and this backstop wrote follow_system back 19 ms
         * later, so the library stayed dark on its already-themed instance while
         * every Settings screen opened afterwards came up light. That is the
         * "dark mode resets when I re-enter Settings" report.
         *
         * A real click already arrives via PrefCompat's change listener and the
         * SharedPreferences listener, so this is only a backstop for a write
         * those miss: act when the widget's value CHANGES, never while a theme
         * change is still settling, and never off a dead activity. */
        if (prefsUi && activity != null && !activity.isFinishing()
                && !activity.isDestroyed() && !ShimTheme.themeSettling()) {
            String uiTheme = PrefCompat.findVisibleThemeChoice(activity);
            if (uiTheme == null) {
                lastSeenUiTheme = null;
            } else if (lastSeenUiTheme == null) {
                lastSeenUiTheme = uiTheme;
            } else if (!uiTheme.equals(lastSeenUiTheme)) {
                lastSeenUiTheme = uiTheme;
                try {
                    Object raw = sp.getAll().get("UI/Theme");
                    String spTheme = ShimTheme.coerce(raw != null ? raw : "follow_system");
                    if (!uiTheme.equals(spTheme))
                        ShimTheme.onThemeSpChanged(activity, uiTheme);
                } catch (Throwable ignored) { }
            }
        } else {
            lastSeenUiTheme = null;
        }

        if (prefsUi) {
            pollStockOsdToggles(app, activity, sp);
        } else {
            /* RESEED THE BASELINE ON EVERY VISIT, OR THE STOMP IS CHARGED TO THE USER.
             *
             * lastOsdToggles is written only inside pollStockOsdToggles, which
             * runs only while a preference screen is visible, and nothing else
             * clears it -- the one clear at :436 sits under
             * shouldSuppressStockOsd(), which is hardcoded `return false`
             * (OsdPrefs.java:49-51). So `changed` means "moved since the last
             * VISIT", which can span a whole gameplay session.
             *
             * That breaks the single-key discriminator added above. The core
             * stomps every OsdShow* to false on reloadGameSettings (a save-state
             * load or an IR change), and it happens while NO prefs screen is up,
             * so the poll does not see it happen -- it sees the delta later, on
             * the first tick after the user opens Settings. On the shipped
             * default profile OsdShowFPS is the ONLY default-true stat
             * (OsdPrefs.defaultFor:559-561), so that stomp moves exactly ONE
             * observed key and reads as a deliberate tap.
             *
             * The consequence is the unrecoverable state the whole guard exists
             * to prevent: noteUserToggle latches ALL STATS OFF and persists
             * NONE_KEY, which bypasses the all-false guard in
             * syncPrefsToIniLayers and writes OsdShow*=false into PCSX2.ini --
             * destroying the only source repairOrphanedStockOsd can rebuild
             * from -- and then makes that repair return early forever.
             *
             * Clearing here makes the first tick of each visit seed-only
             * (last == null), so intent is recorded only for a change observed
             * across consecutive ticks of an OPEN screen. */
            lastOsdToggles.clear();
        }

        if ((now - lastAccentMs) >= 5000L) {
            lastAccentMs = now;
            ShimAccent.recolorStockAccent(activity);
        }
        /* Offers the Lossless.dll import once per process when framegen is
         * armed but the file is absent — Android 11+ blocks the user from
         * putting it in Android/data themselves. */
        ShimDllImport.maybeOffer(activity);
        /* The automatic offer only fires when framegen is already on AND the dll
         * is already missing, which on a fresh install is never (no turnip.conf
         * yet, so the overlay reads as off). The settings row is the entry point
         * the user can actually find. */
        if (activity != null
                && activity.getClass().getName().endsWith("SettingsActivity"))
            ShimDllImport.onSettingsOpened(activity);

        /* IR from Graphics list — always honor user selection, even during boot grace. */
        /* EDGE-TRIGGERED, and that matters.
         *
         * findVisibleIrChoice reads a ListPreference's in-memory value, and
         * nothing in the shim ever writes back into that widget — the one
         * function that would, PrefCompat.setVisibleIrChoice, has zero callers.
         * So after ANY shim-side IR write (the provider's setIr, a live apply)
         * the on-screen row still holds the old value indefinitely.
         *
         * Level-triggered, this site then replayed that stale value with
         * userClick=true, which bypasses the irWriteGuard re-entry check and
         * arms the framegen teardown — silently reverting the user's choice and
         * rewriting SP, PCSX2.ini and every gamesettings ini to match. Observed
         * as an IR that changed itself back minutes later, always to a value
         * written much earlier in the session.
         *
         * A real click already arrives via PrefCompat's change listener, so
         * this is only a backstop: act when the widget's value CHANGES. */
        if (prefsUi) {
            String uiIr = PrefCompat.findVisibleIrChoice(activity);
            if (uiIr == null) {
                lastSeenUiIr = null;
            } else if (lastSeenUiIr == null) {
                lastSeenUiIr = uiIr;
            } else if (!uiIr.equals(lastSeenUiIr)) {
                lastSeenUiIr = uiIr;
                if (TurnipConfig.irNeedsApply(uiIr))
                    TurnipConfig.applyUserInternalResolution(app, uiIr, true);
            }
        } else {
            lastSeenUiIr = null;
        }

        if (TurnipConfig.inBootGrace())
            return;

        boolean conf60 = readConf60(app);
        boolean confFsr = readConfFsr(app);

        if ((++diag % 25) == 1) {
            android.util.Log.i("VulkanShim", "poll sp60=" + sp60
                    + " spFsr=" + spFsr
                    + " ui60=" + ui60 + " uiFsr=" + uiFsr
                    + " conf60=" + conf60 + " confFsr=" + confFsr
                    + " act=" + (activity != null ? activity.getClass().getSimpleName() : "-"));
        }

        if (want60 != conf60) {
            if (TurnipConfig.apply60FpsMode(app, want60)) {
                android.util.Log.i("VulkanShim", "SP/UI→60fps " + want60);
                toast(app, want60 ? "60 FPS ON" : "60 FPS OFF");
            }
        }
        /* WIDESCREEN. The pref alone changes nothing -- the core reads
         * EnableWideScreenPatches from the game ini, and only syncGameIniEmuCore
         * writes it. Nothing polled this key, and the SP listener is unreliable
         * here, so re-enabling Widescreen after something cleared it did
         * literally nothing. Same poll-and-apply shape as the toggles above. */
        boolean spWs = readSpBool(sp, "EmuCore/EnableWideScreenPatches", false);
        if (lastWidescreen == null || lastWidescreen != spWs) {
            boolean seeding = (lastWidescreen == null);
            lastWidescreen = spWs;
            TurnipConfig.syncWidescreenLive(app, !seeding);
            android.util.Log.i("VulkanShim", "SP→widescreen " + spWs
                    + (seeding ? " (seed)" : " (user)"));
        }
        /* An AspectRatio follow staged under a running game lands when the VM is
         * gone. ShimRestartPrompt.resetVM covers the "Restart" answer; this
         * covers leaving the game by any other route. No-op when none is staged. */
        if (!ShimRestartPrompt.gameRunning())
            TurnipConfig.flushPendingAspectFollow(app);
        if (ShimBuildConfig.FSR && wantFsr != confFsr) {
            if (TurnipConfig.applyFsrMode(app, wantFsr)) {
                android.util.Log.i("VulkanShim", "SP/UI→FSR " + wantFsr);
                toast(app, wantFsr ? "FSR ON" : "FSR OFF");
            }
        }
        /* Graphics list/SP is IR. In-game Game Properties writes gamesettings INI
         * via PreferenceDataStore — detect that edit and apply live. */
        /* Scan gamesettings/ at most every 3 s — each file is read from disk. */
        TurnipConfig.IniEdit iniEd = new TurnipConfig.IniEdit();
        if (!inEmulation(activity) && (now - lastIniScanMs) >= 3000L) {
            lastIniScanMs = now;
            iniEd = TurnipConfig.detectGameIniEdit(files);
        }
        if (iniEd.wantIr != null) {
            TurnipConfig.applyUserInternalResolution(app, iniEd.wantIr, true);
        } else if (prefsUi) {
            String ui = PrefCompat.findVisibleIrChoice(activity);
            String ir = ui != null ? ui : TurnipConfig.resolveEffectiveIr(app, sp);
            if (ir != null && TurnipConfig.irNeedsApply(ir))
                TurnipConfig.applyUserInternalResolution(app, ir);
        }
        if (wantFsr && activity != null
                && activity.getClass().getSimpleName().contains("Emulation")) {
            TurnipConfig.refreshIrOsd(app);
        }
    }

    private static void pollStockOsdToggles(Context app, Activity activity, SharedPreferences sp) {
        if (OsdPrefs.shouldSuppressStockOsd(app)) {
            lastOsdToggles.clear();
            osdPollInitialized = false;
            return;
        }
        boolean apply = false;
        boolean userFlipped = false;
        boolean osdUiOnScreen = false;
        int changed = 0;
        for (String key : OsdPrefs.stockOsdPollKeys()) {
            /* Same default the OSD draws with — see OsdPrefs.defaultFor. When
             * these disagreed, materialising the keys here silently switched the
             * fps row off the first time any OSD toggle was touched. */
            boolean spOn = OsdPrefs.readBool(sp, key, OsdPrefs.defaultFor(key));
            Boolean ui = activity != null ? PrefCompat.findCheckedInActivity(activity, key) : null;
            if (ui != null) osdUiOnScreen = true;
            if (ui != null && ui != spOn) {
                if (OsdPrefs.commitStockOsdToggle(app, key, ui)) {
                    apply = true;
                    userFlipped = true;   /* came from the UI, not a core stomp */
                }
                spOn = ui;
            }
            Boolean last = lastOsdToggles.get(key);
            if (last == null) {
                lastOsdToggles.put(key, spOn);
            } else if (last != spOn) {
                lastOsdToggles.put(key, spOn);
                apply = true;
                changed++;
            }
        }
        /* ESTABLISH INTENT, INCLUDING "ALL OFF".
         *
         * Intent used to need findCheckedInActivity to catch the flip, but it
         * returns null on the App Settings screen -- the widgets are not
         * reachable from the activity the watchdog holds -- so switching the
         * last stat off left intent unknown. That is a closed loop that always
         * resurrects the OSD: syncPrefsToIniLayers refuses to write an all-false
         * state without intent, the ini keeps OsdShowFPS=true, and on the next
         * launch repairOrphanedStockOsd calls that orphaned and switches a row
         * back on. Measured: "OSD toggle poll -> applySettings" immediately
         * followed by "skipped - all stats off", ini still true.
         *
         * The discriminator is HOW MANY keys moved in one tick. A tap moves
         * exactly one switch; the core's reloadGameSettings stomp knocks every
         * live stat down together. So a single-key transition is the user, and
         * a multi-key one is the stomp the guard exists to survive. */
        if (osdUiOnScreen) {
            OsdPrefs.adoptUiAsIntent(app);
        } else if (changed == 1 && osdPollInitialized) {
            OsdPrefs.noteUserToggle(app);
            OsdPrefs.syncPrefsToIniLayers(app);
            android.util.Log.i("VulkanShim",
                    "OSD intent from single-key change (user tap)");
        }
        if (!osdPollInitialized) {
            osdPollInitialized = true;
        } else if (apply) {
            /* Only a UI flip establishes intent — see OsdPrefs.noteUserToggle. */
            if (userFlipped) OsdPrefs.noteUserToggle(app);
            android.util.Log.i("VulkanShim", "OSD toggle poll -> applySettings");
            TurnipConfig.applyOsdSettingsLive(app);
        }
    }

    /** SharedPreferences may hold String for some keys — getBoolean then throws and aborted the poll. */
    private static boolean readSpBool(SharedPreferences sp, String key, boolean def) {
        try {
            return sp.getBoolean(key, def);
        } catch (ClassCastException e) {
            try {
                String s = sp.getString(key, null);
                if (s == null) return def;
                return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
            } catch (Throwable t) {
                return def;
            }
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean readConf60(Context app) {
        File files = app.getExternalFilesDir(null);
        if (files == null) return false;
        String e = TurnipConfig.readConfKeyPublic(new File(files, "turnip.conf"), "enable_60fps");
        return e != null && (e.equalsIgnoreCase("on") || e.equals("1") || e.equalsIgnoreCase("true"));
    }

    private static boolean readConfFsr(Context app) {
        File files = app.getExternalFilesDir(null);
        if (files == null) return false;
        String u = TurnipConfig.readConfKeyPublic(new File(files, "turnip.conf"), "upscaler");
        if (u == null) return false;
        return u.equalsIgnoreCase("fsr") || u.equalsIgnoreCase("fsr1") || u.equalsIgnoreCase("on");
    }

    private static void toast(Context ctx, String msg) {
        try {
            H.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) { }
    }
}
