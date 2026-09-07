package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;

/**
 * PCSX2 Android reads OSD toggles from SharedPreferences. Ini layers must match SP
 * so {@code reloadGameSettings} does not snap overlays back off.
 */
public final class OsdPrefs {
    private static final String POS_KEY = "EmuCore/GS/OsdPerformancePos";
    private static final int POS_NONE = 0;
    private static final int POS_TOP_RIGHT = 2;
    private static final String GS_PREFIX = "EmuCore/GS/";
    /** Persisted "the user switched every stat off" — see noteUserToggle. */
    private static final String NONE_KEY = "VulkanShim/OsdUserWantsNone";

    private static final String[] STAT_KEYS = {
        "EmuCore/GS/OsdShowFPS",
        "EmuCore/GS/OsdShowSpeed",
        "EmuCore/GS/OsdShowCPU",
        "EmuCore/GS/OsdShowGPU",
        "EmuCore/GS/OsdShowFrameTimes",
        "EmuCore/GS/OsdShowResolution",
    };

    /** Last known user OSD intent — survives native reloadGameSettings stomping SP. */
    private static volatile boolean userIntentKnown;
    private static volatile boolean userWantsAnyStat;
    private static volatile int userIntentPos = POS_TOP_RIGHT;
    private static final boolean[] userIntentStats = new boolean[STAT_KEYS.length];

    private OsdPrefs() {}

    /**
     * Deliberately always false — framegen does NOT suppress the stock OSD here.
     *
     * <p>The framegen tree hid it whenever {@code lsfg_overlay=on}, keeping the
     * pre-suppression backup in static fields. Those do not survive a
     * force-stop, so the restore never ran and every {@code OsdShow*} was left
     * false on disk permanently. Suppression buys nothing anyway: the framegen
     * output panel is a window above the emulator surface, and the stock OSD is
     * drawn <em>into</em> that surface, so it is already invisible while
     * framegen is live. Leaving this false keeps the shipped OSD behaviour —
     * {@link #rememberUserIntent}/{@link #restoreIfStomped} — exactly as is.
     */
    public static boolean shouldSuppressStockOsd(Context ctx) {
        return false;
    }

    /**
     * True when the framegen instrument TextView should be shown
     * ({@code fg_osd=on} or {@code lsfg_overlay=on} in turnip.conf).
     */
    public static boolean isFramegenInstrumentEnabled(Context ctx) {
        if (!FramegenBuild.AVAILABLE) return false;
        if (ctx == null) return false;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return false;
        File conf = new File(files, "turnip.conf");
        if (confBool(conf, ShimFrameGen.OSD_KEY)) return true;
        return confBool(conf, ShimFrameGen.CONF_KEY);
    }

    private static boolean confBool(File conf, String key) {
        String on = TurnipConfig.readConfKey(conf, key);
        return on != null && (on.equalsIgnoreCase("on") || on.equals("1")
                || on.equalsIgnoreCase("true"));
    }

    /** Startup / resume entry point kept name-compatible with the framegen tree. */
    public static void applyPolicy(Context ctx) {
        if (ctx == null) return;
        repairOrphanedStockOsd(ctx);
        ensureStatsOverlay(ctx);
    }

    /**
     * Bring back a stock OSD that the core has stomped to all-false.
     *
     * <p>{@code reloadGameSettings} clears every {@code OsdShow*} in
     * SharedPreferences — which is what the core actually reads — and
     * {@link #ensureStatsOverlay} then sets {@code OsdPerformancePos} to None
     * because nothing is on. The result is an OSD that cannot appear at all,
     * observed live as "Reset OsdPerformancePos -> None (all stats off)".
     *
     * <p>{@code PCSX2.ini} still holds what the user asked for, so use it as the
     * recovery source. Paired with the all-false guard in
     * {@link #syncPrefsToIniLayers}, without which that ini gets overwritten
     * with the stomped state and the recovery source is lost too.
     *
     * <p>Deliberately narrow: it only acts when EVERY stat is off, so it can
     * never override someone who has simply switched most rows off.
     */
    private static void repairOrphanedStockOsd(Context ctx) {
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            coerceOsdBoolPrefs(ctx, sp);
            if (anyStatOn(sp)) return;
            /* The user asked for none. That is a choice, not a stomp — and it
             * is read from SharedPreferences so it survives a restart. */
            if (userWantsNoStats(ctx)) return;

            File files = ctx.getExternalFilesDir(null);
            if (files == null) return;
            File ini = new File(files, "PCSX2.ini");
            if (!ini.isFile()) return;

            java.util.Set<String> want = new java.util.HashSet<>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(ini));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (!k.startsWith("OsdShow")) continue;
                    if (v.equalsIgnoreCase("true") || v.equals("1"))
                        want.add(GS_PREFIX + k);
                }
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
            if (want.isEmpty()) return;

            SharedPreferences.Editor ed = sp.edit();
            int n = 0;
            for (String key : STAT_KEYS)
                if (want.contains(key)) { putBool(ed, key, true); n++; }
            putInt(ed, POS_KEY, POS_TOP_RIGHT);
            shimCommit(sp, ed);
            if (n > 0) {
                rememberUserIntent(ctx);
                Log.i("VulkanShim", "Stock OSD orphaned (all stats off) — reseeded "
                        + n + " rows from PCSX2.ini");
            }
        } catch (Throwable t) {
            Log.w("VulkanShim", "repairOrphanedStockOsd: " + t.getMessage());
        }
    }

    /**
     * Called by {@link ShimFrameGen} whenever {@code lsfg_overlay} flips.
     *
     * <p>In this tree that is <em>not</em> a hide/restore transition — see
     * {@link #shouldSuppressStockOsd}, which is permanently false. All it does
     * is re-run the position repair, so a framegen toggle still heals an
     * {@code OsdPerformancePos} that the native reload left at None. Kept as a
     * real method rather than deleting the call sites, so re-syncing
     * {@code ShimFrameGen.java} from the framegen tree stays a clean copy.
     */
    public static void updateStockOsdPolicy(Context ctx) {
        if (ctx == null) return;
        ensureStatsOverlay(ctx);
    }

    /**
     * Repair OsdPerformancePos only — never force stat toggles on/off.
     * reloadGameSettings writes Integer 0/1 into SP; coerce before reading.
     */
    public static void ensureStatsOverlay(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            coerceOsdBoolPrefs(ctx, sp);

            boolean anyOn = anyStatOn(sp);
            int pos = prefInt(sp, POS_KEY, POS_TOP_RIGHT);
            SharedPreferences.Editor ed = null;

            if (anyOn && pos == POS_NONE) {
                ed = sp.edit();
                putInt(ed, POS_KEY, POS_TOP_RIGHT);
                Log.i("VulkanShim", "Reset OsdPerformancePos None -> TopRight");
            } else if (!anyOn && pos != POS_NONE) {
                ed = sp.edit();
                putInt(ed, POS_KEY, POS_NONE);
                Log.i("VulkanShim", "Reset OsdPerformancePos -> None (all stats off)");
            }
            if (ed != null) {
                shimCommit(sp, ed);
                syncPrefsToIniLayers(ctx);
            }
        } catch (Throwable t) {
            Log.w("VulkanShim", "ensureStatsOverlay failed: " + t.getMessage());
        }
    }

    /**
     * reloadGameSettings stores OSD toggles as Integer 0/1. prefBool without this
     * misreads them as false and syncPrefsToIniLayers writes "false" to ini —
     * toggles snap off on the next IR reload.
     */
    public static void coerceOsdBoolPrefs(Context ctx, SharedPreferences sp) {
        if (sp == null) return;
        try {
            SharedPreferences.Editor ed = null;
            for (String key : STAT_KEYS) {
                Object val = sp.getAll().get(key);
                if (val instanceof Integer) {
                    if (ed == null) ed = sp.edit();
                    ed.remove(key);
                    putBool(ed, key, (Integer) val != 0);
                }
            }
            Object posRaw = sp.getAll().get(POS_KEY);
            if (posRaw instanceof String) {
                try {
                    int n = Integer.parseInt((String) posRaw);
                    if (ed == null) ed = sp.edit();
                    ed.remove(POS_KEY);
                    putInt(ed, POS_KEY, n);
                } catch (NumberFormatException ignored) { }
            }
            if (ed != null) {
                shimCommit(sp, ed);
                Log.i("VulkanShim", "Coerced OSD prefs Integer->Boolean");
            }
        } catch (Throwable t) {
            Log.w("VulkanShim", "coerceOsdBoolPrefs failed: " + t.getMessage());
        }
    }

    public static void syncPrefsToIniLayers(Context ctx) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            coerceOsdBoolPrefs(ctx, sp);
            /* NEVER push an all-false state into the ini.
             *
             * The core stomps these prefs to false on reloadGameSettings; this
             * method then wrote that straight through, so the ini — the only
             * thing repairOrphanedStockOsd can rebuild from — was destroyed too,
             * and the stock OSD could never come back. Observed live: every
             * OsdShow* false in both stores and OsdPerformancePos forced to
             * None. If nothing is on, leave the ini alone and let the repair
             * path restore from it. */
            if (!anyStatOn(sp) && !userWantsNoStats(ctx)) {
                Log.i("VulkanShim",
                        "syncPrefsToIniLayers skipped — all stats off, keeping ini as recovery source");
                return;
            }
            /* When the user DID ask for none, the ini must record it, or the
             * next boot repairs from stale trues and the choice is undone. */
            for (String key : STAT_KEYS) {
                TurnipConfig.mergeGsKeyEverywhere(files,
                        gsIniKey(key), prefBool(sp, key, defaultFor(key)) ? "true" : "false");
            }
            TurnipConfig.mergeGsKeyEverywhere(files,
                    gsIniKey(POS_KEY), String.valueOf(prefInt(sp, POS_KEY, POS_TOP_RIGHT)));
        } catch (Throwable t) {
            Log.w("VulkanShim", "syncPrefsToIniLayers failed: " + t.getMessage());
        }
    }

    public static boolean commitStockOsdToggle(Context ctx, String key, boolean on) {
        if (ctx == null || !isStatKey(key)) return false;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return false;
            boolean changed = prefBool(sp, key, defaultFor(key)) != on;
            SharedPreferences.Editor ed = sp.edit();
            ed.remove(key);
            putBool(ed, key, on);
            shimCommit(sp, ed);
            // A real click is intent even if androidx already persisted its value.
            // Record NONE before the ini sync's native-stomp recovery guard runs.
            noteUserToggle(ctx);
            ensureStatsOverlay(ctx);
            rememberUserIntent(ctx);
            syncPrefsToIniLayers(ctx);
            Log.i("VulkanShim", "OSD toggle SP " + key + "=" + on);
            return changed;
        } catch (Throwable t) {
            Log.w("VulkanShim", "commitStockOsdToggle failed: " + t.getMessage());
            return false;
        }
    }

    public static String[] stockOsdPollKeys() {
        return STAT_KEYS.clone();
    }

    /** Snapshot SP before reloadGameSettings / save-state resume can stomp toggles. */
    /**
     * Record intent after a change the USER made in the settings UI.
     *
     * Unlike {@link #rememberUserIntent}, this can record "I want NONE of them".
     * That state was previously unrepresentable — rememberUserIntent ends with
     * {@code userIntentKnown = userWantsAnyStat}, so an all-off snapshot was
     * treated as "no intent known" — and two guards then conspired to undo it:
     * repairOrphanedStockOsd reseeded SharedPreferences from PCSX2.ini, and
     * syncPrefsToIniLayers refused to write all-false to that ini, so the stale
     * trues lived forever. Turning every OSD switch off simply did not stick,
     * and the framegen instrument stayed on screen because a stat was always
     * back on.
     *
     * Only a UI-driven flip calls this. A core {@code reloadGameSettings} stomp
     * still leaves intent unknown, so the repair path keeps working for the case
     * it was written for.
     */
    public static void noteUserToggle(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            for (int i = 0; i < STAT_KEYS.length; i++)
                userIntentStats[i] = prefBool(sp, STAT_KEYS[i], defaultFor(STAT_KEYS[i]));
            userIntentPos = prefInt(sp, POS_KEY, POS_TOP_RIGHT);
            userWantsAnyStat = anyStatOn(sp);
            userIntentKnown = true;              /* including "none" */
            /* PERSIST IT. These are statics, so they die with the process — and
             * on the next launch repairOrphanedStockOsd saw "all stats off, no
             * intent known", reseeded from PCSX2.ini and switched a row back on.
             * The choice has to outlive the process that made it. */
            SharedPreferences.Editor ed = sp.edit();
            putBool(ed, NONE_KEY, !userWantsAnyStat);
            ed.commit();
            Log.i("VulkanShim", "OSD user intent recorded: "
                    + (userWantsAnyStat ? "some stats on" : "ALL STATS OFF"));
        } catch (Throwable t) {
            Log.w("VulkanShim", "noteUserToggle: " + t.getMessage());
        }
    }

    /**
     * The OSD settings screen is open, so SharedPreferences now reflect what the
     * user actually chose — adopt that as intent, including "all off".
     *
     * Intent used to be recorded ONLY when the poll caught a change through the
     * UI widget scan. Switching the last stat off any other way left intent
     * unknown, and that is a closed loop that always resurrects the OSD:
     * syncPrefsToIniLayers refuses to write an all-false state without intent,
     * so the ini keeps OsdShowFPS=true; on the next launch repairOrphanedStockOsd
     * sees "all off, no intent known", calls it orphaned, and switches a row back
     * on. Measured: every ini still read true after the user turned it off, and
     * the stock OSD came back on every boot.
     *
     * Widgets are the safe signal. The core stomps PREFS on reloadGameSettings,
     * which is what the guard exists to survive, but it never touches the
     * settings UI — so if those switches are on screen, they are the user.
     * Self-limiting: does nothing unless the recorded intent actually differs.
     */
    public static void adoptUiAsIntent(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            coerceOsdBoolPrefs(ctx, sp);
            boolean anyOn = anyStatOn(sp);
            if (userIntentKnown && userWantsAnyStat == anyOn) return;
            /* NEVER LATCH "ALL STATS OFF" FROM SHAREDPREFERENCES ALONE.
             *
             * This method's premise is "the switches on screen are the user",
             * but it reads SP, which the core stomps. Adopting "some stats on"
             * is harmless -- it can only CLEAR a stale NONE latch, protecting
             * the ini. Adopting "all off" is the destructive direction, and now
             * belongs exclusively to onExternalOsdWrite, which has real
             * evidence of a tap (a Boolean-typed write). */
            if (!anyOn) {
                Log.i("VulkanShim",
                        "OSD intent adopt refused -- all-off is not adoptable from SP");
                return;
            }
            noteUserToggle(ctx);
            /* Push it through: with intent now known the all-false state is
             * allowed into the ini, which is what the core actually renders. */
            syncPrefsToIniLayers(ctx);
            Log.i("VulkanShim", "OSD intent adopted from settings UI (anyOn="
                    + anyOn + ")");
        } catch (Throwable t) {
            Log.w("VulkanShim", "adoptUiAsIntent: " + t.getMessage());
        }
    }

    /** True when the user explicitly switched every stat off (this run). */
    public static boolean userWantsNoStats() {
        return userIntentKnown && !userWantsAnyStat;
    }

    /** As above, but also honours the choice persisted by a previous run. */
    public static boolean userWantsNoStats(Context ctx) {
        if (userIntentKnown) return !userWantsAnyStat;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            return sp != null && prefBool(sp, NONE_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void rememberUserIntent(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            for (int i = 0; i < STAT_KEYS.length; i++) {
                userIntentStats[i] = prefBool(sp, STAT_KEYS[i], defaultFor(STAT_KEYS[i]));
            }
            userIntentPos = prefInt(sp, POS_KEY, POS_TOP_RIGHT);
            userWantsAnyStat = false;
            for (boolean on : userIntentStats) {
                if (on) {
                    userWantsAnyStat = true;
                    break;
                }
            }
            userIntentKnown = userWantsAnyStat || prefBool(sp, NONE_KEY, false);
        } catch (Throwable t) {
            Log.w("VulkanShim", "rememberUserIntent failed: " + t.getMessage());
        }
    }

    /**
     * reloadGameSettings and save-state load often snap every OSD toggle off in SP.
     * Restore from remembered intent when settings UI is not open.
     */
    public static boolean restoreIfStomped(Context ctx) {
        if (ctx == null || !userIntentKnown || !userWantsAnyStat) return false;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            coerceOsdBoolPrefs(ctx, sp);
            if (sp == null) return false;
            boolean anyOnNow = anyStatOn(sp);
            int posNow = prefInt(sp, POS_KEY, POS_NONE);
            if (anyOnNow && posNow != POS_NONE) return false;

            SharedPreferences.Editor ed = sp.edit();
            boolean needRestore = false;
            for (int i = 0; i < STAT_KEYS.length; i++) {
                if (userIntentStats[i] && !prefBool(sp, STAT_KEYS[i], defaultFor(STAT_KEYS[i]))) {
                    putBool(ed, STAT_KEYS[i], true);
                    needRestore = true;
                }
            }
            if (posNow == POS_NONE) {
                putInt(ed, POS_KEY, userIntentPos != POS_NONE ? userIntentPos : POS_TOP_RIGHT);
                needRestore = true;
            }
            if (!needRestore) return false;
            shimCommit(sp, ed);
            syncPrefsToIniLayers(ctx);
            Log.i("VulkanShim", "Restored OSD prefs after native stomp");
            return true;
        } catch (Throwable t) {
            Log.w("VulkanShim", "restoreIfStomped failed: " + t.getMessage());
            return false;
        }
    }

    /* ==================================================================
     * EXTERNAL OSD WRITE GATE — the in-game settings fix.
     *
     * The in-game "Settings" overlay is xyz.aethersx2.android.j, the SAME
     * fragment SettingsActivity uses (EmulationActivity$b.C, i5==2), and its
     * page fragment j$e sets NO PreferenceDataStore -- unlike Game Properties.
     * So its SwitchPreferenceCompat rows already persist to the default
     * SharedPreferences. The toggle was never failing to write; the
     * SP -> ini -> core bridge was simply absent in-game, because the poll is
     * gated on prefsUi and the global SP listener had been garbage collected
     * (ShimSettingsBridge held no strong reference to it -- listeners live in a
     * WeakHashMap).
     *
     * With the listener alive this gate decides, per write, whether a person
     * did it. Three conditions, all required:
     *
     *  1. THE VALUE IS A Boolean. reloadGameSettings writes Integer 0/1 into
     *     SP -- see coerceOsdBoolPrefs, which exists solely to repair that.
     *     androidx TwoStatePreference.persistBoolean writes a Boolean. The core
     *     cannot produce a Boolean here, so it can never be read as a tap. That
     *     is also what makes the accepted path loop-free: our own applySettings
     *     provokes a stomp, the stomp arrives typed Integer, and is discarded.
     *  2. THE SHIM IS NOT THE WRITER. Every shim commit touching a stat key
     *     goes through shimCommit(), which holds a guard across commit() --
     *     and commit() notifies listeners inline.
     *  3. EXACTLY ONE STAT MOVED. A tap moves one switch; a stomp moves every
     *     live stat together.
     *
     * And a fourth, for the ONLY unrecoverable outcome -- recording ALL STATS
     * OFF, which unlocks the all-false ini write in syncPrefsToIniLayers and
     * destroys the source repairOrphanedStockOsd rebuilds from: a settings
     * surface must actually be on screen. Every failure of that check refuses
     * the latch, which is the safe direction. */

    private static final java.util.concurrent.atomic.AtomicInteger SHIM_WRITE =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.HashMap<String, Boolean> lastSeenStat =
            new java.util.HashMap<>();
    /* SharedPreferences listeners always run on the main looper (inline when
     * the commit is on it, posted otherwise), so a plain int is a correct
     * re-entrancy counter here. */
    private static int gateDepth;

    /**
     * Guard inline callbacks and baseline the committed state for callbacks
     * posted to the main looper. Do not reject real taps for a time window after
     * our writes: rapid clicks in the in-game menu must each take effect.
     */
    static void shimCommit(SharedPreferences sp, SharedPreferences.Editor ed) {
        SHIM_WRITE.incrementAndGet();
        try {
            ed.commit();
        } finally {
            try { if (sp != null) reseedStats(sp.getAll()); }
            finally { SHIM_WRITE.decrementAndGet(); }
        }
    }

    /** Baseline the gate. Called once when the listener is registered. */
    public static void seedExternalOsdWatch(Context ctx) {
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp != null) reseedStats(sp.getAll());
        } catch (Throwable ignored) { }
    }

    private static void reseedStats(java.util.Map<String, ?> all) {
        synchronized (lastSeenStat) {
            for (String k : STAT_KEYS)
                lastSeenStat.put(k, rawBool(all.get(k), defaultFor(k)));
        }
    }

    private static boolean rawBool(Object val, boolean def) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Integer) return (Integer) val != 0;
        if (val instanceof String) {
            String v = (String) val;
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        return def;
    }

    static boolean isStatKey(String key) {
        for (String k : STAT_KEYS) if (k.equals(key)) return true;
        return false;
    }

    /**
     * Which preference surface is on screen, or null for none. Any failure
     * returns null, which refuses the all-off latch (the safe direction).
     *
     * The first three detectors walk view trees / fragment managers and are
     * partly dead on this R8 build (Class.forName("androidx.fragment.app.
     * Fragment") throws; the in-game overlay lives in a SEPARATE window that
     * the activity's decor cannot reach, and only a hidden-API
     * WindowManagerGlobal.mViews walk inside ShimTheme.hasSettingsOverlayUi
     * finds it). The last one needs no reflection at all: the in-game Settings
     * is a Dialog -- a separate, FOCUSABLE window above EmulationActivity --
     * so the activity loses window focus while it is up. The framegen output
     * panel is FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE (ShimFrameGen) and
     * never takes focus, so a live, un-focused foreground activity means a
     * dialog is on top. Together with a Boolean-typed single-key write, which
     * in the shipped dex only androidx persist and the (guarded) shim
     * produce, that is a tap.
     */
    private static String settingsSurfaceOnScreen() {
        try {
            android.app.Activity a = ToggleWatchdog.foregroundActivity();
            if (a == null) return null;
            if (ShimTheme.isSettingsActivity(a)) return "SettingsActivity";
            if (ShimTheme.hasInGameSettingsOverlay(a)) return "in-game overlay";
            if (PrefCompat.hasVisiblePreferenceUi(a)) return "preference UI";
            if (!a.isFinishing() && !a.isDestroyed() && !a.hasWindowFocus())
                return "dialog above " + a.getClass().getSimpleName()
                        + " (no window focus)";
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A stock OSD stat key changed in SharedPreferences. Decide who did it. */
    public static void onExternalOsdWrite(Context ctx, String key) {
        if (ctx == null || key == null || !isStatKey(key)) return;
        try {
            if (SHIM_WRITE.get() > 0) {
                seedExternalOsdWatch(ctx);          /* our own write -- re-baseline only */
                return;
            }
            if (gateDepth > 0) {
                /* Re-entered from inside our own accept path below: the
                 * applySettings() we issued provoked the core's mirror write
                 * and it was delivered inline. Never a tap. Keep the baseline
                 * honest and get out. */
                seedExternalOsdWatch(ctx);
                Log.i("VulkanShim", "OSD SP write ignored (re-entrant, our own apply) " + key);
                return;
            }
            gateDepth++;
            try {
                onExternalOsdWriteGated(ctx, key);
            } finally {
                gateDepth--;
            }
        } catch (Throwable t) {
            Log.w("VulkanShim", "onExternalOsdWrite: " + t.getMessage());
        }
    }

    private static void onExternalOsdWriteGated(Context ctx, String key) throws Exception {
        {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return;
            java.util.Map<String, ?> all = sp.getAll();
            Object raw = all.get(key);

            int moved = 0;
            synchronized (lastSeenStat) {
                for (String k : STAT_KEYS) {
                    Boolean was = lastSeenStat.get(k);
                    if (was == null || was.booleanValue() != rawBool(all.get(k), defaultFor(k)))
                        moved++;
                }
            }
            reseedStats(all);                       /* baseline tracks reality either way */

            if (!(raw instanceof Boolean)) {
                Log.i("VulkanShim", "OSD SP write ignored (core stomp, "
                        + (raw == null ? "absent" : raw.getClass().getSimpleName())
                        + ") " + key);
                return;
            }
            if (moved != 1) {
                Log.i("VulkanShim", "OSD SP write ignored (" + moved
                        + " stats moved, not a tap) " + key);
                return;
            }
            boolean anyOn = anyStatOn(sp);
            String surface = anyOn ? null : settingsSurfaceOnScreen();
            if (!anyOn && surface == null) {
                Log.i("VulkanShim",
                        "OSD all-off write ignored -- no settings UI on screen");
                return;
            }
            Log.i("VulkanShim", "OSD UI write accepted " + key + "=" + rawBool(raw, false)
                    + (anyOn ? "" : " (all off; surface: " + surface + ")"));
            noteUserToggle(ctx);
            syncPrefsToIniLayers(ctx);
            TurnipConfig.applyOsdSettingsLive(ctx);
        }
    }

    public static boolean isStockOsdPrefKey(String key) {
        if (key == null) return false;
        if (POS_KEY.equals(key)) return true;
        for (String k : STAT_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /** Stock OSD resolution is GS framebuffer size. Always on. Never hide for FSR. */
    static void showGsResolution(Context ctx, boolean show) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null || prefBool(sp, "EmuCore/GS/OsdShowResolution", !show) == show)
                return;
            SharedPreferences.Editor ed = sp.edit();
            ed.remove("EmuCore/GS/OsdShowResolution");
            putBool(ed, "EmuCore/GS/OsdShowResolution", show);
            shimCommit(sp, ed);
            syncPrefsToIniLayers(ctx);
        } catch (Throwable t) {
            Log.w("VulkanShim", "showGsResolution failed: " + t.getMessage());
        }
    }

    /**
     * True while the user has at least one stock OSD stat switched on.
     *
     * The framegen instrument mirrors those switches, so this is what decides
     * whether it should be on screen at all: with every one of them off the user
     * has asked for no overlay, and showing "FRAMEGEN live (panel)" plus a
     * counters row anyway is the overlay ignoring them.
     */
    /** Each stat key and its effective value — for diagnosing the gate. */
    public static String statSummary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            if (sp == null) return "(no prefs)";
            for (String k : STAT_KEYS) {
                Object raw = sp.getAll().get(k);
                sb.append(k.substring(k.lastIndexOf('/') + 1))
                  .append('=').append(raw == null ? "absent" : raw).append(' ');
            }
        } catch (Throwable t) {
            return "(threw " + t + ")";
        }
        return sb.toString().trim();
    }

    public static boolean anyStatOn(Context ctx) {
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            return sp != null && anyStatOn(sp);
        } catch (Throwable t) {
            return true;    /* unknown: prefer showing over silently hiding */
        }
    }

    private static boolean anyStatOn(SharedPreferences sp) {
        for (String key : STAT_KEYS) {
            if (prefBool(sp, key, defaultFor(key))) return true;
        }
        return false;
    }

    private static String gsIniKey(String prefKey) {
        if (prefKey.startsWith(GS_PREFIX))
            return prefKey.substring(GS_PREFIX.length());
        return prefKey;
    }

    private static SharedPreferences defaultPrefs(Context ctx) throws Exception {
        return (SharedPreferences) Class.forName("androidx.preference.PreferenceManager")
                .getMethod("getDefaultSharedPreferences", Context.class)
                .invoke(null, ctx);
    }

    /** Shared read path — ToggleWatchdog poll must match ini sync. */
    public static boolean readBool(SharedPreferences sp, String key, boolean def) {
        return prefBool(sp, key, def);
    }

    /* Stock OSD rows, so the framegen instrument can mirror the user's own
     * toggles instead of inventing its own. While framegen is live the stock
     * OSD is invisible — it draws into the emulator surface, which the output
     * panel covers — so the instrument has to stand in for it. */
    public static final String KEY_FPS         = "EmuCore/GS/OsdShowFPS";
    public static final String KEY_SPEED       = "EmuCore/GS/OsdShowSpeed";
    public static final String KEY_CPU         = "EmuCore/GS/OsdShowCPU";
    public static final String KEY_GPU         = "EmuCore/GS/OsdShowGPU";
    public static final String KEY_FRAMETIMES  = "EmuCore/GS/OsdShowFrameTimes";
    public static final String KEY_RESOLUTION  = "EmuCore/GS/OsdShowResolution";

    /** Is a stock OSD row switched on? Reads the prefs the core itself reads. */
    /**
     * The value a stock-OSD key takes while it is absent from SharedPreferences.
     *
     * THERE USED TO BE TWO OF THESE AND THEY DISAGREED. The instrument drew the
     * fps row with a default of true, while the toggle poll read the same key
     * with false. So the row showed on a fresh profile — until the user touched
     * ANY OSD switch, at which point the poll materialised every key at its own
     * default, wrote `OsdShowFPS = false`, and the fps readout silently
     * disappeared. From the user's side, flipping "Show CPU Usage" deleted the
     * frame rate, which is exactly the sort of thing that reads as "the OSD
     * toggles are broken".
     *
     * fps defaults ON because this overlay exists to report frame generation and
     * the rate is the whole point; everything else follows the stock app and
     * defaults off.
     */
    public static boolean defaultFor(String key) {
        return KEY_FPS.equals(key);
    }

    /** Shared visibility policy for attachment and live framegen ticks. */
    static boolean instrumentVisible(String mode, boolean anyOn, boolean wantsNone) {
        if ("force".equalsIgnoreCase(mode) || "always".equalsIgnoreCase(mode)) return true;
        if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)
                || "0".equals(mode)) return false;
        return anyOn && !wantsNone;
    }

    public static boolean stockOsdShows(Context ctx, String key, boolean def) {
        if (ctx == null) return def;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            return sp != null ? prefBool(sp, key, def) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean prefBool(SharedPreferences sp, String key, boolean def) {
        Object val = sp.getAll().get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Integer) return (Integer) val != 0;
        if (val instanceof String) {
            String s = (String) val;
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return def;
    }

    private static int prefInt(SharedPreferences sp, String key, int def) {
        Object val = sp.getAll().get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static void putBool(SharedPreferences.Editor ed, String key, boolean v) {
        ed.putBoolean(key, v);
    }

    private static void putInt(SharedPreferences.Editor ed, String key, int v) {
        ed.putInt(key, v);
    }
}
