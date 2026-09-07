package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Writes turnip.conf beside vulkan_shim.log from VulkanShim prefs. */
public final class TurnipConfig {
    private TurnipConfig() {}

    /**
     * True only when turnip.conf explicitly hands the two user-facing Graphics
     * toggles (FSR x4, Enable 60 FPS) to the conf file: {@code toggles_owner=conf}.
     *
     * <p>Bench deploy scripts that want to pin a state add that line. Without it
     * the UI preference owns both toggles, which is what makes OFF stay OFF: the
     * old code adopted turnip.conf into SharedPreferences on every cold start, so
     * whatever the last deploy (or the last session's own writeback) left in the
     * file overwrote the user's choice before anything could act on it.
     */
    /**
     * Append a line to {@code files/shim_java.log}.
     *
     * <p>The Java side only ever logged to logcat, and the native shim writes enough
     * lines to roll the buffer within seconds — so by the time anyone looked, every
     * message about a pref change was gone. This file survives.
     */
    private static volatile File lastFilesDir;
    private static volatile Context lastAppContext;

    static void rememberFiles(Context ctx) {
        if (ctx == null) return;
        lastAppContext = ctx.getApplicationContext();
        File f = ctx.getExternalFilesDir(null);
        if (f != null) lastFilesDir = f;
    }

    static Context appContext() {
        return lastAppContext;
    }

    static void noteApp(String msg) {
        note(lastFilesDir, msg);
    }

    static void note(File filesDir, String msg) {
        android.util.Log.i("VulkanShim", msg);
        if (filesDir == null) filesDir = lastFilesDir;
        if (filesDir == null) return;
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(filesDir, "shim_java.log"), true),
                StandardCharsets.UTF_8)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()));
            w.write("  ");
            w.write(msg);
            w.write('\n');
        } catch (Throwable ignored) { }
    }

    static boolean confOwnsToggles(File filesDir) {
        if (filesDir == null) return false;
        File conf = new File(filesDir, "turnip.conf");
        if (!conf.isFile()) return false;
        String owner = readConfKey(conf, "toggles_owner");
        return owner != null && owner.equalsIgnoreCase("conf");
    }

    /** Delete stale framegen / min_image_count keys — never writes framegen=off. */
    static void stripFramegenConf(Context ctx) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        if (!conf.isFile()) return;
        boolean hasFgKey = readConfKey(conf, "framegen") != null
                || readConfKey(conf, "framegen_mode") != null
                || readConfKey(conf, "framegen_alpha") != null
                || readConfKey(conf, "framegen_multiplier") != null;
        boolean hasMin = readConfKey(conf, "min_image_count") != null;
        boolean spOn = false;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences sp = (SharedPreferences) pm;
            spOn = sp.getBoolean("VulkanShim/FrameGen", false)
                    || sp.getBoolean("VulkanShim/FrameGenFlow", false);
        } catch (Throwable ignored) { }
        if (!hasFgKey && !hasMin && !spOn) return;
        removeConfKeys(conf, "framegen", "framegen_mode", "min_image_count",
                "framegen_multiplier", "framegen_alpha");
        if (spOn) {
            try {
                Object pm = Class.forName("androidx.preference.PreferenceManager")
                        .getMethod("getDefaultSharedPreferences", Context.class)
                        .invoke(null, ctx);
                SharedPreferences sp = (SharedPreferences) pm;
                sp.edit()
                        .putBoolean("VulkanShim/FrameGen", false)
                        .putBoolean("VulkanShim/FrameGenFlow", false)
                        .commit();
            } catch (Throwable ignored) { }
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            conf.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    public static void syncAtStartup(Context ctx) {
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences prefs = (SharedPreferences) pm;
            File files = ctx.getExternalFilesDir(null);
            ShimTheme.fixStoredPref(ctx);
            forceGraphicsListPrefsAsStrings(ctx);
            boolean lowEndFreshSeed = HandheldTier.seedDefaultIfNeeded(ctx);
            if (HandheldTier.isLowEndHandheld())
                HandheldTier.ensureTurnipConfBeforeNativeLoad(ctx);
            /* Presenter FSR is CASMode=2, not shim upscaler/render_scale.
             * Frame Gen keys stay so the Graphics toggle can arm the shim. */
            if (files != null) {
                removeConfKeys(new File(files, "turnip.conf"),
                        "upscaler", "fsr_quality", "fsr_sharpness", "render_scale");
            }
            /* One-shot adb drop: files/shim_force.txt (fsr=off / upscale=4.000000).
             * Must run before applyFsrMode so Native GS cannot stomp a 4× ini. */
            consumeForceFile(ctx, prefs, files);
            /* Undo the stock-revert experiment before anything reads the cheats dir. */
            if (files != null)
                restoreStashed60Pnaches(files);
            installBundledPnaches(ctx, files);
            installBundledSotcProfiles(ctx, files);
            if (HandheldTier.isLowEndHandheld())
                installBundledLowEndGameProfiles(ctx, files, prefs);
            if (files != null)
                seedIniWroteIrFromDisk(files);
            boolean confOwns = confOwnsToggles(files);

            // Game Properties Graphics use INI PreferenceDataStore — restore toggles from
            // gamesettings [VulkanShim] when default prefs are still false (never overwrite ON).
            mergePrefsFromGameIni(ctx, prefs);
            alignPrefsWithDeployedStable(ctx, prefs);
            stripFramegenConf(ctx);
            stripSpatialSubstitute(ctx);
            if (!ShimBuildConfig.FSR) {
                /* Orange / no-FSR builds: never re-arm FSR, framegen, or LSFG
                 * from leftover prefs or turnip.conf. Force stock CAS off so
                 * leftover CASMode=2 cannot run FSR-as-CAS. */
                prefs.edit()
                        .remove("VulkanShim/FsrQuality4x")
                        .remove("VulkanShim/UpscalerFsr1")
                        .remove("VulkanShim/FrameGen")
                        .remove("VulkanShim/FrameGenFlow")
                        .commit();
                if (files != null) {
                    File conf = new File(files, "turnip.conf");
                    removeConfKeys(conf, "upscaler", "fsr_quality", "fsr_sharpness",
                            "render_scale", "framegen", "framegen_mode",
                            "framegen_alpha", "framegen_multiplier",
                            "min_image_count");
                    applyCasModeEverywhere(ctx, files, "0");
                    applyTvShaderEverywhere(ctx, files, "0");
                    forceSotcFrameLimitOn(files);
                    syncGameIniVulkanShim(files, false);
                }
                stripFramegenConf(ctx);
            }
            if (confOwns) {
                // Opt-in bench path: turnip.conf pins FSR / 60 FPS, prefs follow it.
                applyDeployed60Fps(ctx, prefs);
                applyDeployedUpscale(ctx, prefs);
            }
            // Bench-deployed framegen=on/off in turnip.conf is source of truth on cold start.
            // Never let stale OFF prefs rewrite a deployed framegen profile to framegen=off (+FSR).
            boolean preserved = preserveDeployedTurnipConf(ctx, prefs);
            if (!confOwns) {
                // The UI toggles own turnip.conf, not the other way round. Push both
                // states down every cold start so the shim boots already agreeing with
                // the switches the user can see — including OFF.
                applyUserToggles(ctx, prefs);
            }
            if (!preserved)
                syncFromPrefs(ctx, prefs);
            /* AFTER the strip/preserve passes above, so a deployed
             * lsfg_overlay=on is not undone by them. */
            applyLsfgFromConf(ctx);
            if (lowEndFreshSeed && prefs.getBoolean(HandheldTier.PREF_KEY, false))
                applyLowEndPerfMode(ctx, true);
            else {
                HandheldTier.reapplyProfileIfStale(ctx, prefs);
                adoptLowEndPerfPref(prefs);
            }
            forceGraphicsListPrefsAsStrings(ctx);
            if (HandheldTier.isLowEndHandheld())
                repairUsmSd865Profile(ctx, files);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "syncAtStartup failed: " + t.getMessage());
        }
    }

    /**
     * One-shot: FSR Mode OFF + CASMode=0 so Internal Resolution can change OSD.
     * Does not run again after {@code VulkanShim/IrQualityLever} is set, so a
     * later user FSR ON still persists across launches.
     */
    static void forceFsrOffForIrLever(Context ctx, SharedPreferences prefs, File files) {
        /* No-op. IR is Graphics including Native; FSR is CASMode. Never force FSR off. */
    }

    static void setCasMode(SharedPreferences prefs, String mode) {
        try {
            int n = Integer.parseInt(mode.trim());
            SharedPreferences.Editor ed = prefs.edit();
            ed.remove("EmuCore/GS/CASMode");
            /* ListPreference getString-crashes on Integer. Keep a String. */
            ed.putString("EmuCore/GS/CASMode", Integer.toString(n));
            ed.commit();
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "CASMode write failed: " + t.getMessage());
        }
    }

    /** CASMode in SP + PCSX2.ini + every gamesettings ini (same override as IR). */
    static void applyCasModeEverywhere(Context ctx, File filesDir, String mode) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null)
            setCasMode(prefs, mode);
        if (filesDir == null) return;
        String sharp = "2".equals(mode) ? "100" : "50";
        File global = new File(filesDir, "PCSX2.ini");
        if (global.isFile()) {
            try {
                mergeIniGsKey(global, "CASMode", mode);
                mergeIniGsKey(global, "CASSharpness", sharp);
            } catch (IOException e) {
                logIoFailure("PCSX2.ini CASMode", global, e);
            }
        }
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return;
        for (File ini : inis) {
            try {
                mergeIniGsKey(ini, "CASMode", mode);
                mergeIniGsKey(ini, "CASSharpness", sharp);
            } catch (IOException e) {
                logIoFailure("games CASMode", ini, e);
            }
        }
    }

    /** SotC bench profiles left FrameLimitEnable=false (unlimited / >>). That is
     *  not frame gen, but it is what still looked like it — only on SotC. */
    private static final String[] SOTC_CRCS = { "C19A374E", "0F0C4A9C" };

    /**
     * Every per-game ini that could belong to this CRC.
     *
     * This emulator reads <SERIAL>_<CRC>.ini whenever the disc has a serial, and
     * ignores a bare <CRC>.ini in that case -- measured: a bare file was left
     * untouched while the emulator merged its own settings into the qualified one.
     * SOTC_CRCS is bare, so profiles keyed that way could silently never apply.
     * Return both forms so a caller updates whichever the emulator is really using.
     */
    private static java.util.List<File> gameInisFor(File gsDir, String crc) {
        java.util.ArrayList<File> out = new java.util.ArrayList<>();
        if (gsDir == null || crc == null) return out;
        File bare = new File(gsDir, crc + ".ini");
        if (bare.isFile()) out.add(bare);
        File[] all = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (all != null)
            for (File f : all)
                if (!f.equals(bare) && crcFromIniName(f.getName()).equalsIgnoreCase(crc))
                    out.add(f);
        return out;
    }

    static void forceSotcFrameLimitOn(File filesDir) {
        if (filesDir == null) return;
        File gsDir = new File(filesDir, "gamesettings");
        for (String crc : SOTC_CRCS) {
          for (File ini : gameInisFor(gsDir, crc)) {
            if (!ini.isFile()) continue;
            try {
                mergeIniGsKey(ini, "FrameLimitEnable", "true");
            } catch (IOException e) {
                logIoFailure("SotC FrameLimitEnable", ini, e);
            }
          }
        }
    }

    /** ListPreference String in SP + PCSX2.ini + gamesettings. */
    static void applyGsListPrefEverywhere(Context ctx, File filesDir,
            String prefKey, String listValue) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.remove(prefKey);
                ed.putString(prefKey, listValue);
                ed.commit();
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", prefKey + " write failed: "
                        + t.getMessage());
            }
        }
        if (filesDir == null) return;
        String iniKey = prefKey.startsWith("EmuCore/GS/")
                ? prefKey.substring("EmuCore/GS/".length()) : prefKey;
        mergeGsKeyEverywhere(filesDir, iniKey, listValue);
    }

    /** SP + global PCSX2.ini only — avoids rewriting every gamesettings file. */
    static void applyGsListPrefGlobal(Context ctx, File filesDir,
            String prefKey, String listValue) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.remove(prefKey);
                ed.putString(prefKey, listValue);
                ed.commit();
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", prefKey + " write failed: "
                        + t.getMessage());
            }
        }
        if (filesDir == null) return;
        String iniKey = prefKey.startsWith("EmuCore/GS/")
                ? prefKey.substring("EmuCore/GS/".length()) : prefKey;
        try {
            mergeIniGsKey(new File(filesDir, "PCSX2.ini"), iniKey, listValue);
        } catch (IOException e) {
            android.util.Log.w("VulkanShim", "PCSX2.ini " + iniKey + ": " + e.getMessage());
        }
    }

    /** SP + global PCSX2.ini only — avoids rewriting every gamesettings file. */
    static void applyBoolPrefGlobal(Context ctx, File filesDir,
            String prefKey, boolean value) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.remove(prefKey);
                ed.putBoolean(prefKey, value);
                /* HandheldTier writes OsdShow* through here. commit() notifies
                 * listeners inline, and a Boolean write is indistinguishable
                 * from an androidx persist -- hold the shim-write guard across
                 * it or OsdPrefs.onExternalOsdWrite charges the profile to the
                 * user as a tap. */
                OsdPrefs.shimCommit(prefs, ed);
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", prefKey + " write failed: "
                        + t.getMessage());
            }
        }
        if (filesDir == null) return;
        String iniKey = prefKey.startsWith("EmuCore/GS/")
                ? prefKey.substring("EmuCore/GS/".length()) : prefKey;
        try {
            mergeIniGsKey(new File(filesDir, "PCSX2.ini"), iniKey,
                    value ? "true" : "false");
        } catch (IOException e) {
            android.util.Log.w("VulkanShim", "PCSX2.ini " + iniKey + ": " + e.getMessage());
        }
    }

    /** Boolean pref in SP + PCSX2.ini + gamesettings. */
    static void applyBoolPrefEverywhere(Context ctx, File filesDir,
            String prefKey, boolean value) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.remove(prefKey);
                ed.putBoolean(prefKey, value);
                /* HandheldTier writes OsdShow* through here. commit() notifies
                 * listeners inline, and a Boolean write is indistinguishable
                 * from an androidx persist -- hold the shim-write guard across
                 * it or OsdPrefs.onExternalOsdWrite charges the profile to the
                 * user as a tap. */
                OsdPrefs.shimCommit(prefs, ed);
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", prefKey + " write failed: "
                        + t.getMessage());
            }
        }
        if (filesDir == null) return;
        String iniKey = prefKey.startsWith("EmuCore/GS/")
                ? prefKey.substring("EmuCore/GS/".length()) : prefKey;
        mergeGsKeyEverywhere(filesDir, iniKey, value ? "true" : "false");
    }

    /** TVShader String in SP + PCSX2.ini + gamesettings. "4" = FSR present blit. */
    static void applyTvShaderEverywhere(Context ctx, File filesDir, String mode) {
        SharedPreferences prefs = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            prefs = (SharedPreferences) pm;
        } catch (Throwable ignored) { }
        if (prefs != null) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.remove("EmuCore/GS/TVShader");
                ed.putString("EmuCore/GS/TVShader", mode);
                ed.commit();
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", "TVShader write failed: " + t.getMessage());
            }
        }
        if (filesDir == null) return;
        File global = new File(filesDir, "PCSX2.ini");
        if (global.isFile()) {
            try {
                mergeIniGsKey(global, "TVShader", mode);
            } catch (IOException e) {
                logIoFailure("PCSX2.ini TVShader", global, e);
            }
        }
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return;
        for (File ini : inis) {
            try {
                mergeIniGsKey(ini, "TVShader", mode);
            } catch (IOException e) {
                logIoFailure("games TVShader", ini, e);
            }
        }
    }

    static boolean casModeIsResize(SharedPreferences prefs) {
        if (prefs == null) return false;
        try {
            Object raw = prefs.getAll().get("EmuCore/GS/CASMode");
            return raw != null && "2".equals(String.valueOf(raw).trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Graphics Internal Resolution changed. Always apply the Graphics multiplier
     * to SP, PCSX2.ini, and gamesettings. FSR Mode never calls this.
     *
     * @return formatted multiplier shown in Graphics, or null if unusable
     */
    public static String applyUserInternalResolution(Context ctx, Object raw) {
        return applyUserInternalResolution(ctx, raw, false);
    }

    /**
     * @param userClick Graphics list click (including Native). Bypasses the
     *        write-guard that otherwise swallows Native while 4× is flushing.
     */
    public static String applyUserInternalResolution(Context ctx, Object raw,
            boolean userClick) {
        rememberFiles(ctx);
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return null;
        String ir = formatUpscaleMultiplier(raw != null ? String.valueOf(raw) : null);
        if (ir == null)
            return lastPushedIr != null ? lastPushedIr : readUserIr(ctx);
        if (irWriteGuard > 0 && !userClick)
            return ir;
        /* WORKING VERSION (FSR off): Graphics value is GS, including Native.
         * FSR never pins Native. */
        boolean storesDone = ir.equals(lastPushedIr);
        boolean gsDone = ir.equals(lastGsConsumedIr);
        if (storesDone && gsDone)
            return ir;
        // Snapshot the actual old GS before preference callbacks can apply the
        // new multiplier or report its resize ahead of framegen teardown.
        if (FramegenBuild.AVAILABLE && !gsDone)
            ShimFrameGen.prepareForIrChange(ctx, ir, lastGsConsumedIr);
        saveUserIr(ctx, ir);
        note(files, "IR apply raw=" + raw
                + (raw != null ? " [" + raw.getClass().getSimpleName() + "]" : "")
                + " → " + ir
                + (storesDone ? " (stores already " + lastPushedIr + ")"
                        : " (was " + lastPushedIr + ")"));
        if (!storesDone) {
            irWriteGuard++;
            try {
                applyInternalUpscaleMultiplier(ctx, files, ir);
                applyInternalUpscaleMultiplierAllGames(files, ir);
                lastPushedIr = ir;
            } finally {
                irWriteGuard--;
            }
            note(files, "Internal Resolution write " + ir
                    + " [String] (Graphics + PCSX2.ini + gamesettings)");
            /* Put the new value back into the on-screen row.
             *
             * PrefCompat.setVisibleIrChoice existed with ZERO callers, so a
             * shim-side write (the provider's setIr, a live apply) updated every
             * store while the visible Graphics row kept showing the old value.
             * That stale widget is not just cosmetic: ToggleWatchdog reads it as
             * the user's intent, which is how a stale value used to get replayed
             * over a fresh one. The watchdog is edge-triggered now, but a row
             * that disagrees with the emulator is still a lie to the user. */
            try {
                android.app.Activity vis = ToggleWatchdog.foregroundActivity();
                if (vis != null) PrefCompat.setVisibleIrChoice(vis, ir);
            } catch (Throwable ignored) { }
        }
        /* Before anything can recreate the GS: native must be told the extent to
         * expect, or the target PCSX2 is about to create is rejected as scratch
         * and capture never follows it. Must precede the reload posted below —
         * the OSD-tick push was measured arriving 4 s after the recreate. */
        if (FramegenBuild.AVAILABLE) {
            try { ShimFrameGen.pushExpectedGsForIr(ir); } catch (Throwable ignored) { }
        }
        stripSpatialSubstitute(ctx);
        /* User picked a new IR while framegen is live. Pause capture, retire
         * the old context, and rebuild once GS settles at the requested size.
         * Gated on userClick so the startup sync cannot trip it. */
        /* null now means "never confirmed", not "first boot" — the backoff no
         * longer fills it in on faith — so an unknown consumed IR must count as
         * a change and tear the pipeline down. Rebuilding costs a few hundred ms;
         * NOT rebuilding leaves LSFG holding images for the old size. The old
         * `!= null` guard existed to stop a pause at boot, which userClick
         * already prevents on its own. */
        if (userClick && FramegenBuild.AVAILABLE
                && (ShimFrameGen.isContextUp() || ShimFrameGen.isOverlayAttached())
                && !ir.equals(lastGsConsumedIr))
            ShimFrameGen.pauseForIrChange(ctx);
        pendingGsIrApply = true;
        pendingGsIr = ir;
        tryLiveGsApply(ctx, ir, userClick);
        return ir;
    }

    /**
     * FSR must not write upscale_multiplier. No-op kept so old callers compile.
     */
    public static void applyPresenterNativeGs(Context ctx) {
        if (ctx == null) return;
        note(ctx.getExternalFilesDir(null),
                "applyPresenterNativeGs skipped (FSR must not write upscale_multiplier)");
    }

    /**
     * Force the emulator's own AMD FidelityFX pass off.
     *
     * <p>{@code EmuCore/GS/CASMode} is stock AetherSX2: "FidelityFX Contrast
     * Adaptive Sharpening (CAS)", whose second mode is "Sharpen and Resize
     * (Display Resolution)". FSR Mode uses that slot for EASU; when FSR is off
     * the slot must be 0 so stock bilinear present is used.
     */
    static void forceCasOff(SharedPreferences prefs) {
        try {
            setCasMode(prefs, "0");
            android.util.Log.i("VulkanShim",
                    "FidelityFX CAS forced off (EmuCore/GS/CASMode=0)");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "CAS off failed: " + t.getMessage());
        }
    }

    static void applyUserToggles(Context ctx, SharedPreferences prefs) {
        boolean fsr = ShimBuildConfig.FSR && (prefs.getBoolean("VulkanShim/FsrQuality4x", false)
                || prefs.getBoolean("VulkanShim/UpscalerFsr1", false));
        boolean fps60 = prefs.getBoolean(PREF_60FPS, false);
        seedUserIrFromGraphics(ctx, prefs);
        /* Align Graphics label to the game file so leftover SP Native does not
         * apply before the user clicks. After that, Graphics is IR. */
        /* Before the toggles: a first run has no IR on record, and every path
         * below reads it. Seeding here means the very first boot already runs
         * at the shipped default instead of Native. */
        seedDefaultIrIfUnset(ctx);
        syncGraphicsPrefToGameIni(ctx);
        /* lastApplied* are null on a cold start, so neither call short-circuits.
         * Nothing here is a user action, so never prompt to restart. */
        ShimRestartPrompt.suppress = true;
        try {
            if (ShimBuildConfig.FSR)
                applyFsrMode(ctx, fsr);
            apply60FpsMode(ctx, fps60);
            if (FramegenBuild.AVAILABLE) {
                /* THE CONF WINS WHEN THEY DISAGREE.
                 *
                 * This used to apply the pref unconditionally, which re-asserted
                 * lsfg_overlay=on at every launch and made framegen impossible to
                 * turn off: the pref cannot be changed from the UI on this APK (the
                 * view-hierarchy read returns null, see findCheckedInActivity), so a
                 * pref stuck at true outlived every conf edit and every toggle.
                 * turnip.conf is the layer the user and this shim can both actually
                 * write, so an explicit off there now wins and the pref is corrected
                 * to match. */
                boolean pref = prefs.getBoolean("VulkanShim/Lsfg", false);
                File fdir = ctx.getExternalFilesDir(null);
                String confVal = fdir == null ? null
                        : readConfKey(new File(fdir, "turnip.conf"), ShimFrameGen.CONF_KEY);
                Boolean confSays = null;
                if (confVal != null) {
                    if (confVal.equalsIgnoreCase("off") || confVal.equals("0")
                            || confVal.equalsIgnoreCase("false")) confSays = Boolean.FALSE;
                    else if (confVal.equalsIgnoreCase("on") || confVal.equals("1")
                            || confVal.equalsIgnoreCase("true")) confSays = Boolean.TRUE;
                }
                /* SYMMETRIC. A one-way rule just moves the deadlock: correcting the
                 * pref only towards OFF left the pref stuck false, and then startup
                 * wrote lsfg_overlay=off over a conf that said on -- framegen could
                 * no longer be switched back ON either. The pref is unreachable from
                 * the UI on this APK, so whichever way the conf states it explicitly,
                 * that is the user's intent and the pref is aligned to it. */
                if (confSays != null && confSays != pref) {
                    prefs.edit().putBoolean("VulkanShim/Lsfg", confSays).commit();
                    pref = confSays;
                    android.util.Log.i("VulkanShim",
                            "framegen pref corrected to " + (confSays ? "ON" : "OFF")
                            + " from turnip.conf");
                }
                applyLsfgMode(ctx, pref);
            }
        } finally {
            ShimRestartPrompt.suppress = false;
        }
        android.util.Log.i("VulkanShim",
                "startup toggles from prefs: FSR=" + fsr + " 60FPS=" + fps60
                        + " Lsfg=" + prefs.getBoolean("VulkanShim/Lsfg", false)
                        + " LowEndPerf=" + prefs.getBoolean(HandheldTier.PREF_KEY, false)
                        + " (prefs own turnip.conf; set toggles_owner=conf to invert)");
    }

    /** Remember toggle state without rewriting every gamesettings ini on cold start. */
    static void adoptLowEndPerfPref(SharedPreferences prefs) {
        if (prefs == null) return;
        lastAppliedLowEndPerf = prefs.getBoolean(HandheldTier.PREF_KEY, false);
    }

    /**
     * Drop leftover turnip.conf {@code upscale_multiplier} so the shim file
     * cannot re-lock Graphics. Never writes {@code EmuCore/GS/upscale_multiplier}.
     * Never pins Native when FSR is on.
     */
    static void applyDeployedUpscale(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        if (!conf.isFile()) return;
        removeConfKeys(conf, "upscale_multiplier", "pcsx2_upscale");
    }

    static String formatUpscaleMultiplier(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(java.util.Locale.US);
        if (lower.equals("native") || lower.startsWith("native (")
                || lower.equals("1x") || lower.equals("1×")
                || lower.startsWith("1x native") || lower.startsWith("1× native")
                || lower.contains("512x448") || lower.contains("512×448"))
            return IR_NATIVE;
        int xNative = lower.indexOf("x native");
        if (xNative > 0) {
            try {
                float f = Float.parseFloat(lower.substring(0, xNative).trim());
                if (f >= 0.5f && f <= 8.0f)
                    return String.format(java.util.Locale.US, "%.6f", f);
            } catch (NumberFormatException ignored) { }
        }
        try {
            float f = Float.parseFloat(t);
            if (f < 0.5f || f > 8.0f) return null;
            return String.format(java.util.Locale.US, "%.6f", f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * If turnip.conf already has an explicit upscaler deploy, mirror that into
     * prefs and skip syncFromPrefs so we do not clobber the bench profile.
     * @return true if conf was preserved (caller should skip syncFromPrefs)
     */
    private static boolean preserveDeployedTurnipConf(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return false;
        File conf = new File(files, "turnip.conf");
        if (!conf.isFile()) return false;

        String upscaler = readConfKey(conf, "upscaler");
        if (upscaler == null) return false;

        boolean confFsrOn = "fsr1".equalsIgnoreCase(upscaler);
        boolean confFsrOff = upscaler.equalsIgnoreCase("off") || upscaler.equalsIgnoreCase("none");

        SharedPreferences.Editor ed = prefs.edit();
        boolean changed = false;
        /* FSR prefs follow turnip.conf only on the opt-in bench path — otherwise
         * the switch the user sees is the authority (see applyUserToggles). */
        if (confOwnsToggles(files)) {
            if (confFsrOff && (prefs.getBoolean("VulkanShim/UpscalerFsr1", false)
                    || prefs.getBoolean("VulkanShim/FsrQuality4x", false))) {
                ed.putBoolean("VulkanShim/UpscalerFsr1", false);
                ed.putBoolean("VulkanShim/FsrQuality4x", false);
                changed = true;
            } else if (confFsrOn && (!prefs.getBoolean("VulkanShim/UpscalerFsr1", false)
                    || !prefs.getBoolean("VulkanShim/FsrQuality4x", false))) {
                ed.putBoolean("VulkanShim/UpscalerFsr1", true);
                boolean q4 = "4x".equalsIgnoreCase(readConfKey(conf, "fsr_quality"));
                ed.putBoolean("VulkanShim/FsrQuality4x", q4);
                changed = true;
            }
        }
        if (changed)
            ed.commit();

        if (confOwnsToggles(files) && (confFsrOn || confFsrOff)) {
            android.util.Log.i("VulkanShim",
                    "preserveDeployedTurnipConf upscaler="
                            + upscaler + " (skip syncFromPrefs rewrite)");
            SharedPreferences.Editor e2 = prefs.edit();
            e2.commit();
            boolean want60 = prefs.getBoolean(PREF_60FPS, false);
            if (confOwnsToggles(files)) {
                String raw60 = readConfKey(conf, "enable_60fps");
                if (raw60 != null)
                    want60 = raw60.equalsIgnoreCase("on") || raw60.equalsIgnoreCase("true")
                            || raw60.equals("1");
            }
            applyCheatsPnachGate(files, want60);
            return true;
        }
        return false;
    }

    /**
     * turnip.conf {@code enable_60fps=on} restores Enable 60 FPS Mode + pnach
     * (bench deploy for USM without relying on stale SharedPreferences).
     */
    static void applyDeployed60Fps(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        if (!conf.isFile()) return;
        String raw = readConfKey(conf, "enable_60fps");
        if (raw == null) return;
        boolean on = raw.equalsIgnoreCase("on") || raw.equalsIgnoreCase("true")
                || raw.equals("1");
        boolean off = raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("false")
                || raw.equals("0");
        if (!on && !off) return;
        apply60FpsMode(ctx, on);
        android.util.Log.i("VulkanShim", "deployed enable_60fps=" + on
                + " (from turnip.conf → EnableCheats + pnach)");
    }

    /** If Graphics toggles live in game ini, mirror ON into default prefs for sync. */
    private static void mergePrefsFromGameIni(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File gsDir = new File(files, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null || inis.length == 0) return;

        boolean hasVulkanShim = false;
        boolean iniFsr = false;
        for (File ini : inis) {
            IniVulkanShimFlags f = readIniVulkanShimFlags(ini);
            if (f.hasSection) hasVulkanShim = true;
            iniFsr |= f.fsr;
        }

        IniEmuCoreFlags emu = readGameIniEmuCoreFlags(files);

        if (!hasVulkanShim && !emu.hasEmuCore) return;

        /* Nothing is mirrored back from gamesettings any more.
         * Never mirror FSR back: syncGameIniVulkanShim() writes [VulkanShim] into
         * EVERY game ini, so OR-ing them back was a loop that resurrected the
         * last-written value and destroyed the user's switch on every cold start.
         * The FSR switch is owned by prefs — see applyUserToggles.
         * Never overwrite Enable 60 FPS Mode / Widescreen from OR'd gamesettings
         * either — that glued the toggle ON whenever any game ini still had
         * EnableCheats=true. */
        android.util.Log.i("VulkanShim",
                "gamesettings ini -> prefs FSR=" + iniFsr
                        + " (60fps/widescreen left to UI prefs)");
    }

    /**
     * Bench deploy pins FSR in turnip.conf. Stale ON prefs must not re-arm on launch.
     */
    static void alignPrefsWithDeployedStable(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        if (!conf.isFile()) return;

        String upscaler = readConfKey(conf, "upscaler");
        if (upscaler == null) return;

        boolean confFsrOn = "fsr1".equalsIgnoreCase(upscaler);
        boolean confFsrOff = upscaler.equalsIgnoreCase("off") || upscaler.equalsIgnoreCase("none");

        IniVulkanShimFlags ini = readGameIniVulkanShimFlags(files);
        boolean prefFsr = prefs.getBoolean("VulkanShim/UpscalerFsr1", false);
        boolean prefFsr4x = prefs.getBoolean("VulkanShim/FsrQuality4x", false);

        SharedPreferences.Editor ed = null;
        String fsrQuality = readConfKey(conf, "fsr_quality");
        if (confOwnsToggles(files)) {
            if (fsrQuality != null) {
                boolean confFsr4x = fsrQuality.equalsIgnoreCase("4x");
                if (confFsr4x != prefFsr4x) {
                    if (ed == null) ed = prefs.edit();
                    ed.putBoolean("VulkanShim/FsrQuality4x", confFsr4x);
                }
            }
            if (confFsrOff && (prefFsr || prefFsr4x || ini.fsr)) {
                if (ed == null) ed = prefs.edit();
                ed.putBoolean("VulkanShim/UpscalerFsr1", false);
                ed.putBoolean("VulkanShim/FsrQuality4x", false);
                syncGameIniVulkanShim(files, false);
            } else if (confFsrOn && !prefFsr) {
                if (ed == null) ed = prefs.edit();
                ed.putBoolean("VulkanShim/UpscalerFsr1", true);
                if ("4x".equalsIgnoreCase(fsrQuality))
                    ed.putBoolean("VulkanShim/FsrQuality4x", true);
            }
        } else if (!prefFsr && !prefFsr4x && ini.fsr) {
            syncGameIniVulkanShim(files, false);
        }
        if (ed != null) {
            ed.commit();
            android.util.Log.i("VulkanShim",
                    "alignPrefsWithDeployedStable upscaler=" + upscaler
                            + " ini_fsr=" + ini.fsr);
        }
    }

    private static IniVulkanShimFlags readGameIniVulkanShimFlags(File filesDir) {
        IniVulkanShimFlags out = new IniVulkanShimFlags();
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return out;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return out;
        for (File ini : inis) {
            IniVulkanShimFlags f = readIniVulkanShimFlags(ini);
            out.hasSection |= f.hasSection;
            out.fsr |= f.fsr;
        }
        return out;
    }

    private static final String PREF_60FPS = "EmuCore/EnableCheats"; /* UI: Enable 60 FPS Mode */
    private static final String PREF_WIDESCREEN = "EmuCore/EnableWideScreenPatches";
    private static final String AR_KEY = "EmuCore/GS/AspectRatio";
    /** The AspectRatio the user had before widescreen forced 16:9. */
    private static final String AR_SAVED_KEY = "shim_aspect_before_widescreen";
    /** AspectRatio entryValues are Stretch / Auto 4:3-3:2 / 4:3 / 16:9. */
    private static final String AR_WIDE = "16:9";
    /** The ListPreference's own android:defaultValue for AR_KEY. */
    private static final String AR_DEFAULT = "4:3";

    /**
     * FSR Mode drives presenter FSR1 EASU via the core's CAS resize slot
     * ({@code EmuCore/GS/CASMode=2}) at Native internal resolution.
     * Never writes {@code render_scale} — swapchain image substitution is unsound.
     */
    static final String IR_NATIVE = "1.000000";
    /** Stock Graphics ListPreference + JNI GetFloatValue. One key, two types. */
    static final String PREF_GS_UPSCALE = "EmuCore/GS/upscale_multiplier";
    /** User's Graphics IR. Survives FSR forcing Native into GS / gamesettings. */
    private static final String PREF_USER_IR = "VulkanShim/UserUpscaleMultiplier";
    /** True while EmulationActivity is resumed. Not the same as GS/VM running. */
    private static volatile boolean emuResumed;
    /** Skip reloadGameSettings / applySettings / reloadPatches during VM boot. */
    private static final long BOOT_GRACE_MS = 3500L;
    private static volatile long emulationResumeUptimeMs = 0L;
    private static final String USER_IR_FILE = "user_ir.txt";
    private static final String FORCE_FILE = "shim_force.txt";
    /** Last IR written to SP + INI as String. */
    private static volatile String lastPushedIr;

    public static String peekLastPushedIr() {
        return lastPushedIr;
    }

    static boolean isEmuResumed() {
        return emuResumed;
    }

    /** Watchdog steady-state: only when IR value changed from last disk/SP write. */
    public static boolean irNeedsApply(String ir) {
        if (ir == null || lastPushedIr == null) return ir != null;
        return !ir.equals(lastPushedIr);
    }

    public static boolean hasPendingGsIr() {
        return pendingGsIrApply;
    }

    public static boolean confOwnsToggles(Context ctx) {
        if (ctx == null) return false;
        return confOwnsToggles(ctx.getExternalFilesDir(null));
    }
    /** Last IR actually consumed by a live GS reopen (applySettings while VM Running). */
    private static volatile String lastGsConsumedIr;

    static boolean isIrConfirmed(String ir) {
        return ir != null && ir.equals(lastGsConsumedIr);
    }

    static String confirmedIr() { return lastGsConsumedIr; }
    /** Stores written but GS has not reopened yet (boot, paused settings, applySettings no-op). */
    private static volatile boolean pendingGsIrApply;
    private static volatile boolean pendingOsdApply;
    private static volatile String pendingGsIr;
    private static final android.os.Handler LIVE_IR_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** Nested writes to the Graphics IR pref must not look like the user picking Native. */
    private static volatile int irWriteGuard;

    private static final class IniVulkanShimFlags {
        boolean hasSection;
        boolean fsr;
    }

    private static final class IniEmuCoreFlags {
        boolean hasEmuCore;
        boolean enableWidescreen; /* stock 16:9 — separate from 60 FPS */
        boolean enableCheats;     /* 60 FPS pnach in files/cheats/ */
    }

    private static IniEmuCoreFlags readGameIniEmuCoreFlags(File filesDir) {
        IniEmuCoreFlags out = new IniEmuCoreFlags();
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return out;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return out;
        for (File ini : inis) {
            IniEmuCoreFlags f = readIniEmuCoreFlags(ini);
            out.hasEmuCore |= f.hasEmuCore;
            out.enableWidescreen |= f.enableWidescreen;
            out.enableCheats |= f.enableCheats;
        }
        return out;
    }

    private static IniEmuCoreFlags readIniEmuCoreFlags(File ini) {
        IniEmuCoreFlags out = new IniEmuCoreFlags();
        if (!ini.isFile()) return out;
        boolean inSection = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inSection = t.equalsIgnoreCase("[EmuCore]");
                    if (inSection) out.hasEmuCore = true;
                    continue;
                }
                if (!inSection || t.isEmpty() || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = t.substring(0, eq).trim();
                String val = t.substring(eq + 1).trim();
                if ("EnableWideScreenPatches".equalsIgnoreCase(key)) {
                    if (val.equalsIgnoreCase("true") || val.equals("1"))
                        out.enableWidescreen = true;
                } else if ("EnableCheats".equalsIgnoreCase(key)) {
                    if (val.equalsIgnoreCase("true") || val.equals("1"))
                        out.enableCheats = true;
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static IniVulkanShimFlags readIniVulkanShimFlags(File ini) {
        IniVulkanShimFlags out = new IniVulkanShimFlags();
        if (!ini.isFile()) return out;
        boolean inSection = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inSection = t.equalsIgnoreCase("[VulkanShim]");
                    if (inSection) out.hasSection = true;
                    continue;
                }
                if (!inSection || t.isEmpty() || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = t.substring(0, eq).trim();
                String val = t.substring(eq + 1).trim();
                if ("UpscalerFsr1".equalsIgnoreCase(key) || "Upscaler".equalsIgnoreCase(key)) {
                    if (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("fsr1"))
                        out.fsr = true;
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    public static void syncFromPrefs(Context ctx, SharedPreferences prefs) {
        syncFromPrefs(ctx, prefs, null);
    }

    /** @param changedKey VulkanShim pref that changed; null on bulk sync. */
    public static void syncFromPrefs(Context ctx, SharedPreferences prefs, String changedKey) {
        syncFromBooleanSource(ctx, new BooleanSource() {
            @Override public boolean get(String key, boolean def) {
                return prefs.getBoolean(key, def);
            }
        }, changedKey);
    }

    /** Game Settings use a PreferenceDataStore (INI), not default SharedPreferences. */
    public static void syncFromDataStore(Context ctx, Object dataStore, String changedKey) {
        if ("EmuCore/GS/upscale_multiplier".equals(changedKey)) {
            Object raw = dataStoreGetRaw(dataStore, changedKey);
            /* userClick=true. This is the in-game pause menu -> Graphics screen,
             * which is as deliberate a user action as the Graphics list is, and
             * passing false here broke every IR change made that way:
             *   - the framegen teardown at applyUserInternalResolution is gated
             *     on userClick, so the pipeline was never rebuilt;
             *   - vmReadyForGsApply defers non-clicks during gameplay, so the
             *     stock reload was never posted and PCSX2 kept the OLD IR;
             *   - the backoff in tryLiveGsApply then declared the new IR
             *     "consumed" (it only does that for !userClick), moving capture
             *     to a resolution nothing was rendering.
             * Measured 2026-08-16: picking 1.5x in-game left the panel showing
             * the top-left 768x672 of a still-1024x896 frame. */
            applyUserInternalResolution(ctx, raw, true);
            return;
        }
        syncFromBooleanSource(ctx, new BooleanSource() {
            @Override public boolean get(String key, boolean def) {
                return dataStoreGetBoolean(dataStore, key, def);
            }
        }, changedKey);
    }

    private interface BooleanSource {
        boolean get(String key, boolean def);
    }

    /** Handheld default: balanced FSR unless the 4x toggle is on. */
    private static void putFsrQualityKeys(Map<String, String> kv, BooleanSource src) {
        if (src.get("VulkanShim/FsrQuality4x", false)) {
            kv.put("fsr_quality", "4x");
            kv.put("fsr_sharpness", "0.75");
        } else {
            kv.put("fsr_quality", "balanced");
            kv.put("fsr_sharpness", "0.5");
        }
    }

    private static void syncFromBooleanSource(Context ctx, BooleanSource src, String changedKey) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File conf = new File(files, "turnip.conf");

        boolean fsr = src.get("VulkanShim/FsrQuality4x", false)
                || src.get("VulkanShim/UpscalerFsr1", false);

        // Incremental sync: 60 FPS (EnableCheats) and Widescreen are separate gates.
        if (PREF_60FPS.equals(changedKey) || PREF_WIDESCREEN.equals(changedKey)) {
            boolean fps60 = src.get(PREF_60FPS, false);
            boolean widescreen = src.get(PREF_WIDESCREEN, false);
            if (PREF_60FPS.equals(changedKey)) {
                apply60FpsMode(ctx, fps60);
            } else {
                syncGameIniEmuCore(files, widescreen, fps60);
            }
            android.util.Log.i("VulkanShim", "game ini sync key=" + changedKey
                    + " 60fps=" + fps60 + " widescreen=" + widescreen);
            return;
        }
        if ("VulkanShim/InternalUpscale4x".equals(changedKey)) {
            android.util.Log.i("VulkanShim",
                    "InternalUpscale4x ignored (must not write upscale_multiplier)");
            return;
        }
        if ("VulkanShim/UpscalerFsr1".equals(changedKey)
                || "VulkanShim/FsrQuality4x".equals(changedKey)) {
            boolean on = src.get("VulkanShim/FsrQuality4x", false)
                    || src.get("VulkanShim/UpscalerFsr1", false);
            if ("VulkanShim/FsrQuality4x".equals(changedKey))
                on = src.get("VulkanShim/FsrQuality4x", false);
            applyUpscaleMode(ctx, on);
            return;
        }
        if ("VulkanShim/FrameGen".equals(changedKey)
                || "VulkanShim/FrameGenFlow".equals(changedKey)) {
            stripFramegenConf(ctx);
            return;
        }

        Map<String, String> kv = new LinkedHashMap<>();

        /* Presenter FSR is CASMode, not shim render_scale. Never re-arm that path. */
        removeConfKeys(conf, "upscaler", "fsr_quality", "fsr_sharpness", "render_scale");

        if (fsr)
            applyFsrMode(ctx, true);

        mergeConf(conf, kv);
        removeConfKeys(conf, "framegen", "framegen_mode", "min_image_count",
                "framegen_multiplier", "framegen_alpha");
        removeConfKeys(conf, "upscaler", "fsr_quality", "fsr_sharpness", "render_scale");
        syncGameIniVulkanShim(files, fsr);
        if (changedKey == null) {
            syncGameIniEmuCore(files, src.get(PREF_WIDESCREEN, false),
                    src.get(PREF_60FPS, false));
        }
        android.util.Log.i("VulkanShim", "turnip.conf sync"
                + (changedKey != null ? " key=" + changedKey : " startup")
                + " fsr=" + fsr);
    }

    /** Last values written by apply60 / applyFsr — used to skip duplicate applies. */
    private static volatile Boolean lastApplied60;
    private static volatile Boolean lastAppliedLowEndPerf;
    private static volatile Boolean lastAppliedFsr;

    static void maybeAutoFramegenForSotc(Context ctx) { }

    /** applySettings no-op'd (paused / no surface) — retry on resume. */
    private static volatile Boolean pendingFsrApply;

    public static Boolean getLastApplied60() { return lastApplied60; }
    public static Boolean getLastAppliedFsr() { return lastAppliedFsr; }

    /** @return true if state changed and work was done */
    public static boolean wouldChange60(boolean enable) {
        return lastApplied60 == null || lastApplied60 != enable;
    }

    public static boolean wouldChangeFsr(boolean enable) {
        return lastAppliedFsr == null || lastAppliedFsr != enable;
    }

    /** Log Graphics toggle state (call after syncAtStartup). */
    public static void logToggleState(Context ctx) {
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences prefs = (SharedPreferences) pm;
            boolean fsr = prefs.getBoolean("VulkanShim/UpscalerFsr1", false)
                    || prefs.getBoolean("VulkanShim/FsrQuality4x", false);
            boolean fps60 = prefs.getBoolean(PREF_60FPS, false);
            /* Dump the Internal Resolution pref as stored. It is a ListPreference, so
             * it may be a String, and a wrong-typed value would make the panel look
             * dead. Recorded to the file because logcat rolls too fast to catch it. */
            Object ir = null;
            Object userIr = null;
            try { ir = prefs.getAll().get("EmuCore/GS/upscale_multiplier"); }
            catch (Throwable ignored) { }
            Object cas = null;
            try { cas = prefs.getAll().get("EmuCore/GS/CASMode"); }
            catch (Throwable ignored) { }
            try { userIr = prefs.getAll().get(PREF_USER_IR); }
            catch (Throwable ignored) { }
            note(ctx.getExternalFilesDir(null), "startup: 60FPS=" + fps60 + " FSR=" + fsr
                    + " upscale_multiplier pref=" + ir
                    + (ir != null ? " [" + ir.getClass().getSimpleName() + "]" : " [absent]")
                    + " CASMode=" + cas
                    + (cas != null ? " [" + cas.getClass().getSimpleName() + "]" : " [absent]")
                    + " userIR=" + userIr);
            lastApplied60 = fps60;
            lastAppliedFsr = fsr;
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "logToggleState failed: " + t.getMessage());
        }
    }

    /**
     * Keep [EmuCore] EnableCheats / Widescreen aligned for titles that have a 60fps
     * pnach under files/cheats/<CRC>.pnach. Native-30 games (e.g. Mirra) must stay
     * EnableCheats/60FPSMode/NI off — forcing them on causes judder.
     * Global PCSX2.ini still mirrors the master 60 FPS toggle.
     */
    /**
     * Push the CURRENT Widescreen pref into the game inis.
     *
     * The pref alone does nothing: the core reads EnableWideScreenPatches from
     * the ini, and only syncGameIniEmuCore writes it. The SharedPreferences
     * change listener that used to drive that is unreliable in this fork (PCSX2
     * replaces listeners), so re-enabling Widescreen appeared to do nothing at
     * all. ToggleWatchdog polls for the change and calls this, the same pattern
     * the 60 FPS / FSR / OSD toggles already use.
     *
     * @return the widescreen value that was synced
     */
    private static Boolean lastSyncedWidescreen;
    private static boolean writingWidescreen;

    /** Explicit preference clicks arrive before androidx persists the value. */
    public static synchronized void applyWidescreenMode(Context ctx, boolean enable) {
        SharedPreferences sp = ctx == null ? null : defaultPrefsOrNull(ctx);
        if (sp == null) return;
        writingWidescreen = true;
        try { sp.edit().putBoolean(PREF_WIDESCREEN, enable).commit(); }
        finally { writingWidescreen = false; }
        // The SP listener may already have synced this commit. The shared
        // baseline below makes that path, this click and the poll idempotent.
        syncWidescreenLive(ctx, true);
    }

    public static boolean syncWidescreenLive(Context ctx) {
        return syncWidescreenLive(ctx, false);
    }

    /**
     * @param userChange true when this came from the user flipping the switch,
     *                   as opposed to the watchdog's first tick seeding state.
     *                   Only a real change reloads patches and offers a restart.
     */
    public static synchronized boolean syncWidescreenLive(Context ctx, boolean userChange) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return false;
        /* Read through spBool, not getBoolean. Both values are written straight
         * into every game ini below, so a swallowed ClassCastException here would
         * not degrade -- it would wipe Widescreen AND EnableCheats off the disk,
         * and the watchdog reads the same key with its own tolerant helper, so
         * the two would silently disagree. */
        SharedPreferences sp = defaultPrefsOrNull(ctx);
        if (sp == null) return false;
        boolean ws = spBool(sp, PREF_WIDESCREEN, false);
        if (writingWidescreen) return ws;
        if (lastSyncedWidescreen != null && lastSyncedWidescreen == ws) return ws;
        lastSyncedWidescreen = ws;
        boolean cheats = spBool(sp, PREF_60FPS, false);
        syncGameIniEmuCore(files, ws, cheats);
        /* THE DISPLAY ASPECT MUST NOT MOVE UNDER A RUNNING GAME.
         *
         * The patches decide what the game RENDERS and are written into EE
         * memory at boot, so the render's shape cannot change until the VM
         * reboots. The aspect write, however, took effect at once: with framegen
         * off reloadPatchesLive() below publishes it to the live core, and with
         * framegen on ShimFrameGen.syncOutputAspect follows the same pref within
         * one 500 ms OSD tick. So flipping Widescreen mid-game and answering
         * "Later" -- the only answer that keeps your progress -- stretched a
         * still-4:3 render across a 16:9 frame, and the reverse squashed a
         * 16:9 one. Both are the wrong states section 19 of the handoff says
         * must not be reachable.
         *
         * Stage it instead, and apply it when a game is actually about to boot
         * with the new setting in force. Only a real user flip stages: on the
         * watchdog's seeding tick the pref did not move, so whatever it holds IS
         * what the running game booted with and applying it now is correct. */
        if ((userChange || pendingAspectFollow != null) && ShimRestartPrompt.gameRunning()) {
            pendingAspectFollow = Boolean.valueOf(ws);
            android.util.Log.i("VulkanShim",
                    "AspectRatio follow staged for next boot: " + ws);
        } else {
            applyAspectFollow(ctx, ws);
        }
        android.util.Log.i("VulkanShim", "widescreen synced to game inis: " + ws);
        if (userChange) {
            /* The config is right at this point; the RUNNING GAME is not.
             * Widescreen patches are written into EE memory at boot, so neither
             * direction reaches a game that is already running -- same as the
             * 60 FPS pnach, which has offered this prompt for exactly this
             * reason. Without it the switch looks broken. */
            reloadPatchesLive();
            ShimRestartPrompt.askResetWidescreen(ws);
        }
        return ws;
    }

    /** Widescreen value whose AspectRatio follow is waiting for a game boot. */
    private static volatile Boolean pendingAspectFollow;

    /**
     * Apply an AspectRatio follow that was staged while a game was running.
     *
     * <p>Called from the VM reset the restart prompt performs -- the moment the
     * game reboots with the new patch setting actually in force -- and from the
     * watchdog whenever no game is running, so leaving the game by any other
     * route lands it too. No-op when nothing is staged.
     */
    public static void flushPendingAspectFollow(Context ctx) {
        Boolean ws = pendingAspectFollow;
        if (ws == null || ctx == null) return;
        pendingAspectFollow = null;
        android.util.Log.i("VulkanShim", "flushing staged AspectRatio follow: " + ws);
        applyAspectFollow(ctx, ws.booleanValue());
    }

    /**
     * Keep the display aspect with the widescreen patches.
     *
     * <p>The patches change what the game RENDERS -- on means a genuine 16:9
     * image, off means 4:3 -- so AspectRatio has to follow them or the picture
     * is geometrically wrong in one direction or the other. Pinning 16:9 by hand
     * is not the answer either: that leaves a 4:3 game stretched once the
     * patches go off. The previous value is saved and restored verbatim rather
     * than assuming what "Auto" is called.
     */
    private static void applyAspectFollow(Context ctx, boolean ws) {
        try {
            SharedPreferences sp = defaultPrefsOrNull(ctx);
            if (sp == null) return;
            Object cur = sp.getAll().get(AR_KEY);
            String ar = cur == null ? null : String.valueOf(cur);
            String low = ar == null ? "" : ar.toLowerCase(java.util.Locale.US);
            SharedPreferences.Editor ed = sp.edit();
            boolean dirty = false, moved = false;
            if (ws) {
                /* AN EXPLICIT "Stretch" IS THE USER'S OWN "FILL THE PANEL".
                 *
                 * ShimFrameGen.computeOutputAspect deliberately honours it and
                 * returns 0 (fill). Forcing 16:9 over it changed the setting
                 * behind the user's back -- and, because the watchdog re-runs
                 * this block on its seeding tick at every process start, did it
                 * again on every launch, so Stretch could not survive a restart
                 * while widescreen was on. It also made "fill" unreachable in
                 * the framegen path, since the value no longer said stretch by
                 * the time that code read it. */
                if (!low.contains("stretch")) {
                    /* CAPTURE THE PRE-WIDESCREEN VALUE ONCE.
                     *
                     * This write used to be unconditional, so the seeding tick
                     * captured whatever AspectRatio happened to hold at launch
                     * -- usually the 16:9 this very block had forced, or a value
                     * the user picked mid-session -- as if it were the original.
                     * The eventual OFF then restored the wrong thing, and the
                     * genuine value was gone with no record of it. "" is the
                     * sentinel for "there was no explicit value to begin with". */
                    if (!sp.getAll().containsKey(AR_SAVED_KEY)) {
                        ed.putString(AR_SAVED_KEY, ar == null ? "" : ar);
                        dirty = true;
                    }
                    /* ar == null used to skip the pin as well, so on a profile
                     * that had never opened the Aspect Ratio row the patches
                     * went on and the display stayed at the 4:3 default -- a
                     * genuine 16:9 render squashed into 4:3. */
                    if (!low.contains("16:9")) {
                        ed.remove(AR_KEY);
                        ed.putString(AR_KEY, AR_WIDE);
                        dirty = moved = true;
                    }
                }
            } else {
                Object savedRaw = sp.getAll().get(AR_SAVED_KEY);
                String saved = savedRaw == null ? null : String.valueOf(savedRaw);
                if (saved != null) {
                    ed.remove(AR_KEY);
                    /* The sentinel means the key did not exist before widescreen,
                     * so remove it and let the ListPreference default apply again
                     * rather than inventing a value. */
                    if (!saved.isEmpty()) ed.putString(AR_KEY, saved);
                    ed.remove(AR_SAVED_KEY);
                    dirty = moved = true;
                } else if (low.contains("16:9")) {
                    /* NOTHING SAVED AND THE DISPLAY IS STILL PINNED WIDE.
                     *
                     * The OFF branch used to no-op here, which left a 4:3 render
                     * stretched across a 16:9 frame -- row two of the wrong-state
                     * table -- with no widescreen toggle in either direction able
                     * to repair it. Reachable whenever the patches were already
                     * on before this bookkeeping existed, and after upgrading
                     * from the build that pinned 16:9 by hand. */
                    ed.remove(AR_KEY);
                    ed.putString(AR_KEY, AR_DEFAULT);
                    dirty = moved = true;
                }
            }
            if (dirty) ed.commit();
            if (moved)
                android.util.Log.i("VulkanShim", "AspectRatio follows widescreen="
                        + ws + " (was " + ar + ")");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "aspect follow failed: " + t.getMessage());
        }
    }

    static void syncGameIniEmuCore(File filesDir, boolean widescreen60, boolean enableCheats) {
        File gsDir = new File(filesDir, "gamesettings");
        if (gsDir.isDirectory()) {
            File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
            if (inis != null) {
                for (File ini : inis) {
                    try {
                        String name = ini.getName();
                        String crc = crcFromIniName(name);
                        boolean gameCheats = enableCheats && hasCheatsPnach(filesDir, crc);
                        mergeIniEmuCore(ini, widescreen60, gameCheats);
                        /* Record what the file actually ends up containing, not what
                         * we asked for: if a write does not land, an intent-based
                         * baseline reads back as a user edit and flips the master
                         * toggle off by itself. */
                        INI_WROTE_60.put(crc, readIniEnableCheatsPublic(ini));
                    } catch (IOException e) {
                        logIoFailure("ini emu sync", ini, e);
                    }
                }
            }
        }
        File global = new File(filesDir, "PCSX2.ini");
        if (global.isFile()) {
            try {
                mergeIniEmuCore(global, widescreen60, enableCheats);
            } catch (IOException e) {
                logIoFailure("PCSX2.ini emu sync", global, e);
            }
        }
    }

    /** True when files/cheats/<CRC>.pnach exists (USM/MX/SA 60 unlocks). */
    /**
     * The CRC a per-game ini belongs to.
     *
     * Two naming schemes are in play. This fork writes bare {@code <CRC>.ini}
     * (see SOTC_CRCS), but PCSX2 itself uses {@code <SERIAL>_<CRC>.ini} whenever
     * the disc has a serial -- and the emulator creates files in that form. The
     * old code took everything before ".ini", so a real PCSX2 file yielded
     * "SLUS-21413_172417DB" and hasCheatsPnach() could never match it: the game
     * was treated as unpatched no matter what, or patched no matter what,
     * depending on the caller.
     */
    static String crcFromIniName(String name) {
        if (name == null) return "";
        String base = name.endsWith(".ini") ? name.substring(0, name.length() - 4) : name;
        int us = base.lastIndexOf('_');
        if (us >= 0 && base.length() - us - 1 == 8) return base.substring(us + 1);
        return base;
    }

    /**
     * Give every game we can identify an explicit patch profile, so none of them
     * silently inherits the forced-on globals.
     *
     * The policy already documented on syncGameIniEmuCore -- native-30 titles must
     * keep EnableCheats/60FPSMode/NI OFF or they judder -- only ever ran over inis
     * that already existed. A game with no per-game ini (i.e. almost every game)
     * got the global settings instead, which the 60 FPS master toggle forces ON.
     * That is the judder, and it is not specific to one title.
     *
     * Identifiable means: it has a save state (sstates are named
     * "SERIAL (CRC).NN.p2s") or a per-game ini already. A CRC with no pnach under
     * files/cheats gets the safe profile; one with a pnach is left to
     * syncGameIniEmuCore, which is what turns it on with the master toggle.
     */
    /** The user's global EnableWideScreenPatches, so per-game profiles preserve it. */
    private static boolean globalWidescreenOn(File filesDir) {
        File ini = new File(filesDir, "PCSX2.ini");
        if (!ini.isFile()) return false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim().toLowerCase(java.util.Locale.US);
                if (t.startsWith("enablewidescreenpatches"))
                    return t.contains("true") || t.endsWith("= 1") || t.endsWith("=1");
            }
        } catch (Throwable ignored) { }
        return false;
    }

    static int ensureSafeProfilesForKnownGames(File filesDir) {
        if (filesDir == null) return 0;
        File gsDir = new File(filesDir, "gamesettings");
        java.util.LinkedHashSet<String> crcs = new java.util.LinkedHashSet<>();
        /* Save states are "SERIAL (CRC).NN.p2s" -- they carry BOTH halves, and the
         * serial is not optional: this emulator reads <SERIAL>_<CRC>.ini, proven by
         * it merging its own settings into a file of that name. A bare <CRC>.ini
         * (the form SOTC_CRCS uses) is simply ignored while a serial exists. */
        java.util.LinkedHashMap<String, String> serialOf = new java.util.LinkedHashMap<>();
        File[] states = new File(filesDir, "sstates").listFiles();
        if (states != null) {
            java.util.regex.Pattern p = java.util.regex.Pattern
                    .compile("([A-Za-z]{4}-\\d{5})\\s*\\(([0-9A-Fa-f]{8})\\)");
            for (File f : states) {
                java.util.regex.Matcher m = p.matcher(f.getName());
                if (m.find()) {
                    String c = m.group(2).toUpperCase(java.util.Locale.US);
                    crcs.add(c);
                    serialOf.put(c, m.group(1).toUpperCase(java.util.Locale.US));
                }
            }
        }
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis != null)
            for (File f : inis) crcs.add(crcFromIniName(f.getName()).toUpperCase(java.util.Locale.US));
        int written = 0;
        for (String crc : crcs) {
            if (crc.length() != 8) continue;
            if (hasCheatsPnach(filesDir, crc)) continue;   /* patched: master toggle owns it */
            File alt = null;
            if (inis != null)
                for (File f : inis)
                    if (crcFromIniName(f.getName()).equalsIgnoreCase(crc)) { alt = f; break; }
            String serial = serialOf.get(crc);
            File target = alt != null ? alt
                    : new File(gsDir, (serial != null ? serial + "_" : "") + crc + ".ini");
            try {
                if (!gsDir.isDirectory() && !gsDir.mkdirs()) continue;
                /* Widescreen is NOT part of the judder policy and is the user's own
                 * choice -- passing false here pinned EnableWideScreenPatches=false
                 * into every profile this writes, which silently overrode the global
                 * setting and made "Enable widescreen patches" look broken. Only the
                 * three keys that cause native-30 judder get forced. */
                mergeIniEmuCore(target, globalWidescreenOn(filesDir), false);
                written++;
            } catch (IOException e) {
                logIoFailure("safe profile", target, e);
            }
        }
        if (written > 0)
            android.util.Log.i("VulkanShim",
                    "patch profiles: " + written + " unpatched game(s) pinned to "
                    + "EnableCheats/60FPSMode off (native-30 judder guard)");
        return written;
    }

    static boolean hasCheatsPnach(File filesDir, String crc) {
        if (filesDir == null || crc == null || crc.isEmpty()) return false;
        File pnach = new File(new File(filesDir, "cheats"), crc + ".pnach");
        return pnach.isFile();
    }

    /**
     * FSR Mode: CASMode String only. Presenter runs on the user's Upscale
     * Multiplier (EASU if GS &lt; panel, RCAS if not). Never writes
     * upscale_multiplier. Never render_scale.
     *
     * @return true if applied (state changed); false if already at this value
     */
    public static boolean applyFsrMode(Context ctx, boolean enable) {
        if (!ShimBuildConfig.FSR)
            return false;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return false;
        SharedPreferences sp = null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            sp = (SharedPreferences) pm;
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "FSR SP open failed: " + t.getMessage());
        }
        boolean casOn = casModeIsResize(sp);
        boolean jniOn = jniCasModeIsResize();
        if (lastAppliedFsr != null && lastAppliedFsr == enable && casOn == enable
                && (!enable || jniOn))
            return false;
        try {
            if (sp != null) {
                SharedPreferences.Editor ed = sp.edit();
                ed.putBoolean("VulkanShim/FsrQuality4x", enable);
                ed.putBoolean("VulkanShim/UpscalerFsr1", enable);
                ed.commit();
            }
            applyCasModeEverywhere(ctx, files, enable ? "2" : "0");
            /* Present blit filter. TVShader 4 is the Wave slot, overlaid with
             * EASU. Never writes upscale_multiplier. Native stays GS. */
            applyTvShaderEverywhere(ctx, files, enable ? "4" : "0");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "FSR SP write failed: " + t.getMessage());
        }
        File conf = new File(files, "turnip.conf");
        removeConfKeys(conf, "upscaler", "fsr_quality", "fsr_sharpness",
                "render_scale");
        syncGameIniVulkanShim(files, enable);
        stripSpatialSubstitute(ctx);
        try {
            //noinspection ResultOfMethodCallIgnored
            conf.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) { }
        lastAppliedFsr = enable;
        pendingFsrApply = enable;
        /* OSD stays GS size. Never hide OsdShowResolution. Never overlay
         * "FSR 1280×960" as if it were the Internal Resolution. */
        OsdPrefs.showGsResolution(ctx, true);
        applyGsSettingsLive(ctx);
        forceGraphicsListPrefsAsStrings(ctx);
        if (gsCanApplyLive())
            pendingFsrApply = null;
        announceFsr(ctx, enable, false);
        postIrOsd(ctx);
        note(files, enable
                ? "FSR Mode → ON (DoCAS EASU; Graphics IR unchanged)"
                : "FSR Mode → OFF (bilinear present; Graphics IR unchanged)");
        return true;
    }

    /** Native IR = upscale_multiplier 1.000000 (512×448 GS). */
    static boolean isNativeIr(String ir) {
        String f = formatUpscaleMultiplier(ir);
        return f != null && IR_NATIVE.equals(f);
    }

    /** Spatial substitute removed — always force off in turnip.conf. */
    static void stripSpatialSubstitute(Context ctx) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("spatial_substitute_4x", "off");
        kv.put("spatial_substitute_2x", "off");
        mergeConf(conf, kv);
        removeConfKeys(conf, "spatial_substitute_4x", "spatial_substitute_2x");
    }

    /** @deprecated spatial substitute removed */
    static void syncSpatialSubstitute4x(Context ctx, boolean on) {
        stripSpatialSubstitute(ctx);
    }

    /** @deprecated spatial substitute removed */
    static void syncSpatialSubstitute2x(Context ctx, boolean on) {
        stripSpatialSubstitute(ctx);
    }

    /** Toast only. Stock OSD resolution is GS framebuffer size. */
    static void announceFsr(Context ctx, boolean on, boolean withToast) {
        String msg = on ? "FSR ON" : "FSR OFF";
        android.util.Log.i("VulkanShim", msg);
        noteApp(msg);
        if (withToast)
            PrefCompat.toast(ctx, msg);
    }

    /** @deprecated use {@link #applyFsrMode} */
    public static void applyUpscaleMode(Context ctx, boolean enable4x) {
        applyFsrMode(ctx, enable4x);
    }

    public static String readConfKeyPublic(File conf, String key) {
        return readConfKey(conf, key);
    }

    public static String readIniUpscalePublic(File ini) {
        if (ini == null || !ini.isFile()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
            String line;
            boolean inGs = false;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inGs = t.equalsIgnoreCase("[EmuCore/GS]") || t.equalsIgnoreCase("[GSD]");
                    continue;
                }
                if (!inGs || t.isEmpty() || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = t.substring(0, eq).trim();
                if ("upscale_multiplier".equalsIgnoreCase(key)
                        || "UpscaleMultiplier".equalsIgnoreCase(key))
                    return t.substring(eq + 1).trim();
            }
        } catch (IOException ignored) { }
        return null;
    }

    public static boolean readIniEnableCheatsPublic(File ini) {
        if (ini == null || !ini.isFile()) return false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
            String line;
            boolean inEmu = false;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inEmu = t.equalsIgnoreCase("[EmuCore]");
                    continue;
                }
                if (!inEmu || t.isEmpty() || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                if ("EnableCheats".equalsIgnoreCase(t.substring(0, eq).trim())) {
                    String v = t.substring(eq + 1).trim();
                    return v.equalsIgnoreCase("true") || v.equals("1");
                }
            }
        } catch (IOException ignored) { }
        return false;
    }

    /**
     * Apply Enable 60 FPS Mode globally.
     * Uses EnableCheats + files/cheats/*.pnach (same path as USM PeterDelta).
     * Leaves EnableWideScreenPatches ALONE. It used to force it off, which is
     * exactly the coupling this comment warned against: the user's Widescreen
     * setting was destroyed by any 60 FPS toggle and by every cold start.
     */
    /**
     * @return true if applied (state changed); false if already at this value
     */
    /**
     * @return true if applied (state changed); false if already at this value
     */
    private static boolean applying60;
    public static synchronized boolean apply60FpsMode(Context ctx, boolean enable) {
        if (applying60) return false; // SharedPreferences callbacks can re-enter inline.
        applying60 = true;
        try { return apply60FpsModeOnce(ctx, enable); }
        finally { applying60 = false; }
    }

    private static boolean apply60FpsModeOnce(Context ctx, boolean enable) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return false;
        File conf0 = new File(files, "turnip.conf");
        String e60 = readConfKey(conf0, "enable_60fps");
        boolean confOn = e60 != null && (e60.equalsIgnoreCase("on") || e60.equals("1")
                || e60.equalsIgnoreCase("true"));
        if (lastApplied60 != null && lastApplied60 == enable && confOn == enable)
            return false;
        final boolean modeChanged = lastApplied60 == null
                ? confOn != enable : lastApplied60 != enable;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences sp = (SharedPreferences) pm;
            SharedPreferences.Editor ed = sp.edit();
            ed.putBoolean(PREF_60FPS, enable);
            /* DO NOT TOUCH THE WIDESCREEN PREF HERE.
             *
             * This used to force it false "to clear sticky WS-on from older
             * builds", which made the 60 FPS toggle silently destroy the user's
             * Widescreen setting for EVERY game -- and because apply60FpsMode
             * also runs on every cold start (deployConf/applyStartupModes, where
             * lastApplied60 is null so nothing short-circuits), simply restarting
             * after toggling framegen wiped it too. The doc comment above says
             * widescreen "must not be coupled to this toggle"; forcing it off IS
             * coupling it. Decoupled: whatever the user chose is preserved. */
            ed.commit();
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "60fps SP write failed: " + t.getMessage());
        }
        /* Persist so cold start cannot revive enable_60fps=on after user turns OFF. */
        File conf = new File(files, "turnip.conf");
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("enable_60fps", enable ? "on" : "off");
        mergeConf(conf, kv);
        if (!(enable ? "on" : "off").equals(readConfKey(conf, "enable_60fps"))) {
            android.util.Log.w("VulkanShim", "60 FPS settings could not be saved; retry without restart prompt");
            return false;
        }
        if (enable)
            installBundledPnaches(ctx, files);
        /* Gate FIRST — restore *.pnach before hasCheatsPnach() runs in sync. */
        applyCheatsPnachGate(files, enable);
        /* Carry the user's ACTUAL widescreen choice into the inis, rather than
         * hardcoding false and clobbering it on every 60 FPS toggle. */
        boolean wsNow = false;
        try {
            Object pm2 = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            wsNow = ((SharedPreferences) pm2).getBoolean(PREF_WIDESCREEN, false);
        } catch (Throwable ignored) {
        }
        syncGameIniEmuCore(files, /*widescreen60=*/wsNow, /*enableCheats=*/enable);
        repairUsmSd865Profile(ctx, files);
        /* ON applies live: the pnach is patch=1, so it re-writes every frame as soon
         * as the patch list reloads. OFF cannot — the 60 FPS values are already in
         * EE memory and nothing puts the originals back, so the game keeps running
         * at 60 (measured: OFF alone stays 60, OFF + reboot gives G: 29.18). Reset
         * the VM so OFF actually means OFF. */
        reloadPatchesLive();
        /* Neither direction reaches a running game — PCSX2 applies patches at boot,
         * and already-written bytes are never restored. Offer the reset that works. */
        lastApplied60 = enable;
        if (modeChanged) ShimRestartPrompt.askReset(enable);
        android.util.Log.i("VulkanShim", "Enable 60 FPS Mode → " + enable
                + " (EnableCheats + pnach; widescreen preserved; conf enable_60fps="
                + (enable ? "on" : "off") + ")");
        return modeChanged;
    }

    /**
     * Apply or revert the low-end GS performance bundle from Graphics settings.
     *
     * @return true if applied (state changed); false if already at this value
     */
    public static boolean applyLowEndPerfMode(Context ctx, boolean enable) {
        if (ctx == null) return false;
        if (lastAppliedLowEndPerf != null && lastAppliedLowEndPerf == enable)
            return false;
        try {
            SharedPreferences sp = defaultPrefsOrNull(ctx);
            if (sp != null) {
                SharedPreferences.Editor ed = sp.edit();
                ed.putBoolean(HandheldTier.PREF_KEY, enable);
                ed.commit();
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "LowEndPerf SP write failed: " + t.getMessage());
        }
        HandheldTier.applyProfile(ctx, enable);
        if (enable && HandheldTier.isLowEndHandheld()) {
            HandheldTier.ensureTurnipConfBeforeNativeLoad(ctx);
            String ir = resolveEffectiveIr(ctx, defaultPrefsOrNull(ctx));
            if (ir != null) {
                float mult = parseUpscaleFloat(ir, 1.0f);
                if (mult > 1.001f) {
                    android.util.Log.i("VulkanShim",
                            "Low-End perf: IR " + ir + " -> Native (SD865 headroom)");
                    applyUserInternalResolution(ctx, IR_NATIVE);
                }
            }
        }
        applyGsSettingsLive(ctx);
        repairUsmSd865Profile(ctx, ctx.getExternalFilesDir(null));
        lastAppliedLowEndPerf = enable;
        SharedPreferences spVer = defaultPrefsOrNull(ctx);
        if (enable && spVer != null)
            spVer.edit().putInt(HandheldTier.PREF_PROFILE_VER,
                    HandheldTier.LOW_END_PROFILE_VER).commit();
        android.util.Log.i("VulkanShim", "Low-End Performance → " + enable);
        return true;
    }

    /**
     * Stock AetherSX2 live IR path: write gamesettings INI, then
     * {@code reloadGameSettings} (reloads the layered game overlay from disk)
     * then {@code applySettings} (LoadSettings + GSreopen when UpscaleMultiplier
     * changes). JNI GetFloatValue reads that overlay, not host SP — which is why
     * putString alone left GetFloatValue stuck at the boot value.
     *
     * <p>Always reload — FSR is CASMode in the same overlay. Skipping reload
     * when FSR was on left CASMode=2 on disk while the overlay stayed at boot
     * (bilinear). reloadGameSettings may putFloat into SP; coerce ListPreference
     * keys back to String immediately and again after the VM thread runs.
     * Never SwitchRenderer (that reopened GS at Native 512×448).
     */
    static boolean inBootGrace() {
        if (emulationResumeUptimeMs <= 0L) return false;
        return android.os.SystemClock.uptimeMillis() - emulationResumeUptimeMs < BOOT_GRACE_MS;
    }

    /**
     * Native JNI settings calls need a running VM with a render surface — not merely
     * EmulationActivity resumed (measured: reload during boot closes the game).
     */
    /** OSD applySettings is safe during boot grace; GS reload is not. */
    static boolean vmReadyForOsd() {
        return nativeBool("hasEmulationThread");
    }

    static boolean vmReadyForNative() {
        if (inBootGrace()) return false;
        return nativeBool("hasEmulationThread")
                && nativeBool("hasValidRenderSurface")
                && !nativeBool("isVMPaused");
    }

    static void applyGsSettingsLive(Context ctx) {
        if (!vmReadyForNative()) {
            android.util.Log.i("VulkanShim",
                    "applyGsSettingsLive deferred (boot grace or VM not ready)");
            return;
        }
        irWriteGuard++;
        try {
            if (ctx != null) {
                OsdPrefs.rememberUserIntent(ctx);
                OsdPrefs.syncPrefsToIniLayers(ctx);
            }
            try {
                Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
                try {
                    nl.getMethod("reloadGameSettings").invoke(null);
                    android.util.Log.i("VulkanShim",
                            "NativeLibrary.reloadGameSettings() (stock IR/FSR path)");
                    noteApp("reloadGameSettings ir=" + lastPushedIr);
                } catch (NoSuchMethodException ignored) { }
                if (ctx != null) {
                    SharedPreferences sp = defaultPrefsOrNull(ctx);
                    if (sp != null) {
                        OsdPrefs.coerceOsdBoolPrefs(ctx, sp);
                        OsdPrefs.syncPrefsToIniLayers(ctx);
                    }
                }
                nl.getMethod("applySettings").invoke(null);
                android.util.Log.i("VulkanShim", "NativeLibrary.applySettings() (CAS/IR)");
                if (ctx != null) {
                    SharedPreferences sp = defaultPrefsOrNull(ctx);
                    if (sp != null) {
                        OsdPrefs.coerceOsdBoolPrefs(ctx, sp);
                        OsdPrefs.syncPrefsToIniLayers(ctx);
                    }
                }
                probeJniUpscale(nl);
                probeJniCas(nl);
                if (ctx != null) {
                    final Context app = ctx.getApplicationContext();
                    LIVE_IR_HANDLER.postDelayed(() -> coerceAndProbe(app, nl), 400);
                    LIVE_IR_HANDLER.postDelayed(() -> coerceAndProbe(app, nl), 1200);
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                android.util.Log.w("VulkanShim", "applyGsSettingsLive: " + t.getMessage());
            }
            if (ctx != null)
                forceGraphicsListPrefsAsStrings(ctx);
        } finally {
            irWriteGuard--;
        }
    }

    static void coerceAndProbe(Context ctx, Class<?> nl) {
        irWriteGuard++;
        try {
            if (ctx != null)
                forceGraphicsListPrefsAsStrings(ctx);
            probeJniUpscale(nl);
            probeJniCas(nl);
        } finally {
            irWriteGuard--;
        }
    }

    /** Layered GetFloatValue — game overlay, not host SP. */
    static void probeJniUpscale(Class<?> nl) {
        try {
            Object f = nl.getMethod("getFloatSettingValue", String.class, String.class, float.class)
                    .invoke(null, "EmuCore/GS", "upscale_multiplier", -1.0f);
            Object s = null;
            try {
                s = nl.getMethod("getStringSettingValue", String.class, String.class, String.class)
                        .invoke(null, "EmuCore/GS", "upscale_multiplier", "?");
            } catch (NoSuchMethodException ignored) { }
            String msg = "JNI GetFloatValue upscale_multiplier=" + f
                    + (s != null ? " getString=" + s : "")
                    + " lastWrite=" + lastPushedIr;
            android.util.Log.i("VulkanShim", msg);
            noteApp(msg);
            if (lastPushedIr != null && f instanceof Number) {
                float got = ((Number) f).floatValue();
                float want = parseUpscaleFloat(lastPushedIr, -1f);
                if (want > 0f && Math.abs(got - want) < 0.05f) {
                    lastGsConsumedIr = lastPushedIr;
                    pendingGsIrApply = false;
                    noteApp("JNI layered IR " + got + " matches write " + lastPushedIr);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "JNI IR probe: " + t.getMessage());
        }
    }

    /**
     * The GS has just reopened — record what IR it is rendering at, if nothing
     * has recorded one yet this session.
     *
     * <p>{@link #lastGsConsumedIr} was only ever set by {@link #probeJniUpscale},
     * which only runs on the LIVE reload path. A session that boots straight into
     * its saved IR never takes that path, so the field stayed null for the whole
     * session — and BOTH {@code pauseForIrChange} gates (in
     * {@link #applyUserInternalResolution} and {@link #tryLiveGsApply}) require it
     * to be non-null. So the FIRST IR change of such a session skipped the framegen
     * teardown entirely: the native source rebuilt its AHBs at the new GS size
     * while LSFG's images stayed baked at the old capture size, and the game
     * rendered into the top-left corner of the panel. Measured 2026-08-16 —
     * content 854x640 of a 1280x960 panel after 3.0 -> 2.0, which is exactly
     * 1024/1536, with every counter and the OSD reading healthy.
     *
     * <p>Seeding the TRUE current value cannot trip those gates: they fire on a
     * DIFFERENCE, and this only runs while there is no value to differ from. (That
     * distinction is the whole reason null was not simply treated as "changed" —
     * pausing on first boot killed framegen the instant the overlay went live.)
     *
     * <p>GS settle is the right moment to ask: the core has just reopened GS, so
     * its config value is what is actually being rendered.
     */
    public static void noteGsReopened() {
        if (lastGsConsumedIr != null) {
            noteApp("GS reopened -> consumed IR already " + lastGsConsumedIr
                    + " (no seed needed)");
            return;
        }
        if (lastPushedIr == null) {
            noteApp("GS reopened -> NOT seeded: no IR has been written yet");
            return;
        }
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            probeJniUpscale(nl);
            noteApp(lastGsConsumedIr != null
                    ? "GS reopened -> consumed IR seeded " + lastGsConsumedIr
                            + " (first IR change of this session is now guarded)"
                    : "GS reopened -> NOT seeded: core disagrees with write "
                            + lastPushedIr);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "seed consumed IR: " + t.getMessage());
        }
    }

    /** Layered CASMode — String "2" is the EASU/RCAS presenter slot. */
    static void probeJniCas(Class<?> nl) {
        try {
            Object s = null;
            Object i = null;
            try {
                s = nl.getMethod("getStringSettingValue", String.class, String.class, String.class)
                        .invoke(null, "EmuCore/GS", "CASMode", "?");
            } catch (NoSuchMethodException ignored) { }
            try {
                i = nl.getMethod("getIntSettingValue", String.class, String.class, int.class)
                        .invoke(null, "EmuCore/GS", "CASMode", -1);
            } catch (NoSuchMethodException ignored) { }
            String msg = "JNI CASMode getString=" + s + " getInt=" + i
                    + " (2=EASU presenter, 0=off)";
            android.util.Log.i("VulkanShim", msg);
            noteApp(msg);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "JNI CAS probe: " + t.getMessage());
        }
    }

    static boolean jniCasModeIsResize() {
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            try {
                Object i = nl.getMethod("getIntSettingValue", String.class, String.class, int.class)
                        .invoke(null, "EmuCore/GS", "CASMode", -1);
                if (i instanceof Number && ((Number) i).intValue() == 2)
                    return true;
            } catch (NoSuchMethodException ignored) { }
            try {
                Object s = nl.getMethod("getStringSettingValue", String.class, String.class, String.class)
                        .invoke(null, "EmuCore/GS", "CASMode", "?");
                return s != null && "2".equals(String.valueOf(s).trim());
            } catch (NoSuchMethodException ignored) { }
        } catch (Throwable ignored) { }
        return false;
    }

    /** OSD only. Stock GSreopen is reloadGameSettings + applySettings. */
    static void reopenGsForIr(Context ctx, String ir) {
        postIrOsd(ctx, ir);
        noteApp("posted stock reloadGameSettings+applySettings for IR " + ir);
    }

    static void postIrOsd(Context ctx) {
        postIrOsd(ctx, resolveEffectiveIr(ctx, defaultPrefsOrNull(ctx)));
    }

    private static volatile long lastPostedIrOsdMs;
    private static volatile String lastPostedIrOsdLabel;

    /** Re-post keyed IR OSD during gameplay (startup post often runs before VM). */
    static void refreshIrOsd(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = defaultPrefsOrNull(ctx);
        String ir = resolveEffectiveIr(ctx, sp);
        if (ir == null) return;
        String label = irOsdLabel(ctx, ir);
        long now = android.os.SystemClock.uptimeMillis();
        if (label.equals(lastPostedIrOsdLabel) && now - lastPostedIrOsdMs < 8000L)
            return;
        postIrOsd(ctx, ir);
    }

    static void postIrOsd(Context ctx, String ir) {
        if (ir == null) return;
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            String label = irOsdLabel(ctx, ir);
            float duration = 30.0f;
            try {
                nl.getMethod("addKeyedOSDMessage", String.class, String.class, float.class)
                        .invoke(null, "VulkanShimIR", label, duration);
            } catch (NoSuchMethodException e) {
                try {
                    nl.getMethod("addOSDMessage", String.class, float.class)
                            .invoke(null, label, duration);
                } catch (NoSuchMethodException ignored) { }
            }
            lastPostedIrOsdMs = android.os.SystemClock.uptimeMillis();
            lastPostedIrOsdLabel = label;
            android.util.Log.i("VulkanShim", "IR OSD posted: " + label);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "IR OSD failed: " + t.getMessage());
        }
    }

    /**
     * applySettings requires VM Running. hasValidRenderSurface is the GS
     * display pointer. isVMPaused is true only in state 3 — false both when
     * running and when the VM has not started, so it is not enough alone.
     */
    static boolean gsCanApplyLive() {
        return vmReadyForNative();
    }

    static boolean nativeBool(String method) {
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            return Boolean.TRUE.equals(nl.getMethod(method).invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile long lastReloadPostedMs;
    private static volatile String lastReloadPostedIr;
    private static volatile int liveReloadAttempts;
    /** IR whose live reload gave up unconfirmed; keeps that note to one line. */
    private static volatile String lastReloadUnconfirmedIr;

    static void tryLiveGsApply(Context ctx, String ir) {
        tryLiveGsApply(ctx, ir, false);
    }

    static void tryLiveGsApply(Context ctx, String ir, boolean userClick) {
        if (ir == null) return;
        if (ir.equals(lastPushedIr) && ir.equals(lastGsConsumedIr)) {
            pendingGsIrApply = false;
            liveReloadAttempts = 0;
            return;
        }
        rememberFiles(ctx);
        long now = android.os.SystemClock.uptimeMillis();
        if (ir.equals(lastReloadPostedIr)) {
            /* Same IR: backoff hard after a couple of tries — layered JNI often
             * never confirms during gameplay but reload every 800ms kills fps. */
            if (liveReloadAttempts >= 2 && now - lastReloadPostedMs < 60000L) {
                if (now - lastReloadPostedMs < 1500L || !gsCanApplyLive()) return;
                if (!userClick) {
                    /* Back off from RETRYING — but do not claim the reload
                     * landed. This used to set lastGsConsumedIr = ir here, on no
                     * evidence at all, and that lie propagated: the expected-GS
                     * hint moved to a resolution PCSX2 was never rendering, the
                     * native extent committed to it, and the panel showed a
                     * top-left crop of the real frame at every counter green.
                     *
                     * Unconfirmed now stays unconfirmed. lastGsConsumedIr is set
                     * from an OBSERVED GS reopen (noteGsReopened) or from the
                     * JNI probe reading back what PCSX2 actually holds. */
                    if (!ir.equals(lastReloadUnconfirmedIr)) {
                        lastReloadUnconfirmedIr = ir;
                        noteApp("live reload for IR " + ir + " UNCONFIRMED after "
                                + liveReloadAttempts + " attempts — not retrying,"
                                + " and NOT assuming the GS took it");
                        if (ShimFrameGen.isActive() || ShimFrameGen.confSaysOverlayOn(ctx)) {
                            ShimFrameGen.reportFramegenOff(ctx, "resolution change needs a game restart");
                            ShimRestartPrompt.askResetIrFramegen();
                        } else {
                            ShimRestartPrompt.askResetIr(ir);
                        }
                    }
                }
                return;
            }
            if (now - lastReloadPostedMs < 800L)
                return;
        } else {
            liveReloadAttempts = 0;
            lastReloadUnconfirmedIr = null;
        }
        pendingGsIrApply = true;
        pendingGsIr = ir;
        lastReloadPostedIr = ir;
        lastReloadPostedMs = now;
        if (!vmReadyForGsApply(userClick)) {
            android.util.Log.i("VulkanShim",
                    "live IR " + ir + " deferred (boot grace or VM not ready"
                            + (userClick ? ", user click" : "") + ")");
            return;
        }
        liveReloadAttempts++;
        boolean paused = nativeBool("isVMPaused");
        boolean surface = nativeBool("hasValidRenderSurface");
        note(ctx != null ? ctx.getExternalFilesDir(null) : lastFilesDir,
                "live IR " + ir + " stock reload+applySettings (surface=" + surface
                        + " paused=" + paused + " emuResumed=" + emuResumed
                        + " userClick=" + userClick + ")");
        /* THE live-reload path reopens GS underneath framegen. Without this the
         * pipeline is never told, so it keeps capturing a GS that no longer
         * exists: no teardown, no rebuild, no restart prompt — framegen simply
         * stops and the log shows nothing but "IR OSD posted" every 8s. The
         * pause site in applyUserInternalResolution() does NOT cover this,
         * because a live reload also arrives from the settings watchdog with
         * userClick=false.
         *
         * Only pause once GS has confirmed a DIFFERENT IR. prev == null is first
         * boot / JNI not probed yet, and pausing there killed framegen the
         * instant enableOverlay went live (observed: pushed=0, restart prompt). */
        if (FramegenBuild.AVAILABLE
                && ShimFrameGen.ready()
                && (ShimFrameGen.isContextUp() || ShimFrameGen.isOverlayAttached())) {
            String prev = lastGsConsumedIr;
            if (prev != null && !ir.equals(prev)) {
                ShimFrameGen.prepareForIrChange(ctx, ir, prev);
                ShimFrameGen.pauseForIrChange(ctx);
            }
        }
        applyGsSettingsLive(ctx);
        reopenGsForIr(ctx, ir);
    }

    /** GS reload needs a live VM; user IR clicks skip boot grace once surface exists. */
    static boolean vmReadyForGsApply(boolean userClick) {
        if (!nativeBool("hasEmulationThread")
                || !nativeBool("hasValidRenderSurface")
                || nativeBool("isVMPaused"))
            return false;
        return userClick || !inBootGrace();
    }

    static String irOsdLabel(String ir) {
        return irOsdLabel(null, ir);
    }

    static String irOsdLabel(Context ctx, String ir) {
        String base;
        if (IR_NATIVE.equals(ir))
            base = "Internal Resolution Native 512×448";
        else {
            float f = parseUpscaleFloat(ir, 0f);
            if (f >= 1.74f && f <= 1.76f)
                base = "Internal Resolution 896×784 (1.75×)";
            else if (f >= 1.99f && f <= 2.01f)
                base = "Internal Resolution 1024×896 (2×)";
            else if (f >= 3.99f && f <= 4.01f)
                base = "Internal Resolution 2048×1792 (4×)";
            else
                base = "Internal Resolution " + ir + "x";
        }
        return base;
    }

    /** Watchdog / resume: push a queued IR into a now-running GS. */
    public static void flushPendingGsIr(Context ctx) {
        if (ctx == null || !pendingGsIrApply) return;
        rememberFiles(ctx);
        String ir = pendingGsIr != null ? pendingGsIr : lastPushedIr;
        if (ir == null) return;
        if (ir.equals(lastGsConsumedIr)) {
            pendingGsIrApply = false;
            return;
        }
        tryLiveGsApply(ctx, ir);
    }

    public static void onEmulationResumed() {
        onEmulationResumed(null);
    }

    public static void onEmulationResumed(Context ctx) {
        emuResumed = true;
        /* Settings overlay pause/resume must not restart boot grace while VM runs. */
        if (emulationResumeUptimeMs <= 0L || !nativeBool("hasEmulationThread")) {
            emulationResumeUptimeMs = android.os.SystemClock.uptimeMillis();
        }
        if (ctx == null) return;
        rememberFiles(ctx);
        OsdPrefs.rememberUserIntent(ctx);
        final Context app = ctx.getApplicationContext();
        long[] osdDelays = {500L, 1500L, 3000L};
        for (long d : osdDelays) {
            LIVE_IR_HANDLER.postDelayed(() -> reassertOsdAfterResume(app), d);
        }
        long[] delays = {BOOT_GRACE_MS, BOOT_GRACE_MS + 1500L};
        for (long d : delays) {
            LIVE_IR_HANDLER.postDelayed(() -> {
                flushPendingGsIr(app);
                flushPendingFsr(app);
                flushPendingOsdApply(app);
                reassertOsdAfterResume(app);
                refreshIrOsd(app);
            }, d);
        }
    }

    static void reassertOsdAfterResume(Context ctx) {
        if (ctx == null || !emuResumed) return;
        applyOsdSettingsLive(ctx);
    }

    static void flushPendingOsdApply(Context ctx) {
        if (!pendingOsdApply || ctx == null) return;
        applyOsdSettingsLive(ctx);
    }

    public static void applyOsdSettingsLive(Context ctx) {
        if (ctx == null) return;
        OsdPrefs.ensureStatsOverlay(ctx);
        OsdPrefs.syncPrefsToIniLayers(ctx);
        /* Always snapshot SP — stock Preference may persist before our listener runs,
         * and restoreIfStomped must not fight user OFF with stale cold-start intent. */
        OsdPrefs.rememberUserIntent(ctx);
        if (!vmReadyForOsd()) {
            pendingOsdApply = true;
            return;
        }
        pendingOsdApply = false;
        try {
            Class.forName("xyz.aethersx2.android.NativeLibrary")
                    .getMethod("applySettings").invoke(null);
            android.util.Log.i("VulkanShim", "NativeLibrary.applySettings() (OSD toggle)");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "applyOsdSettingsLive: " + t.getMessage());
        }
    }

    static void flushPendingFsr(Context ctx) {
        if (ctx == null || pendingFsrApply == null) return;
        if (!gsCanApplyLive())
            return;
        applyGsSettingsLive(ctx);
        pendingFsrApply = null;
    }

    /** Reload per-game ini from disk while a title is running (USM repair path). */
    static void reloadGameSettingsLive(Context ctx) {
        if (inBootGrace() || !nativeBool("hasEmulationThread")) return;
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            try {
                nl.getMethod("reloadGameSettings").invoke(null);
                android.util.Log.i("VulkanShim",
                        "NativeLibrary.reloadGameSettings() (USM SD865 repair)");
            } catch (NoSuchMethodException ignored) { }
            nl.getMethod("applySettings").invoke(null);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "reloadGameSettingsLive: " + t.getMessage());
        }
    }

    /** Call PCSX2 NativeLibrary.reloadPatches / applySettings if loaded. */
    public static void reloadPatchesLive() {
        if (inBootGrace() || !nativeBool("hasEmulationThread")) {
            android.util.Log.i("VulkanShim",
                    "reloadPatchesLive deferred (boot grace or no VM thread)");
            return;
        }
        try {
            Class<?> nl = Class.forName("xyz.aethersx2.android.NativeLibrary");
            try {
                nl.getMethod("reloadPatches").invoke(null);
                android.util.Log.i("VulkanShim", "NativeLibrary.reloadPatches()");
            } catch (NoSuchMethodException e) {
                android.util.Log.w("VulkanShim", "reloadPatches missing");
            }
            try {
                nl.getMethod("applySettings").invoke(null);
                android.util.Log.i("VulkanShim", "NativeLibrary.applySettings()");
            } catch (NoSuchMethodException ignored) { }
            Context ctx = appContext();
            if (ctx != null) {
                SharedPreferences sp = defaultPrefsOrNull(ctx);
                if (sp != null) {
                    OsdPrefs.coerceOsdBoolPrefs(ctx, sp);
                    OsdPrefs.syncPrefsToIniLayers(ctx);
                    nl.getMethod("applySettings").invoke(null);
                    android.util.Log.i("VulkanShim",
                            "NativeLibrary.applySettings() (OSD after patches)");
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "reloadPatchesLive: " + t.getMessage());
        }
    }

    /**
     * Stock bytes for every address a shipped 60 FPS pnach overwrites.
     *
     * <p>A 60 FPS pnach is {@code patch=1} — it rewrites EE memory every frame.
     * Switching it off only stops the rewriting; the values already in memory stay,
     * so a running game keeps its 60 FPS until it reboots. Writing the original
     * bytes back is the only live revert that does not reset the VM.
     *
     * <p>These values are not inferred. Each was read out of the game's own
     * executable on the ISO: ISO9660 PVD → root directory → {@code SLUS_*.*} →
     * ELF {@code PT_LOAD}, mapping the patch's EE address through
     * {@code vaddr → p_offset} and reading the 4 bytes there.
     *
     * <pre>
     *   FDD12792 Ultimate Spider-Man   SLUS-20870  0x00311F18=4501FFE5 0x0069FE20=00000001
     *   95BB1901 MX vs. ATV Unleashed  SLUS-21104  0x001CC02C=24050001
     *   399A49CA GTA San Andreas v1.03 SLUS-20946  0x006678CC=04000000
     * </pre>
     *
     * The two instruction patches (USM's branch, MX's {@code addiu a1,zero,1}) are
     * code, so the ELF byte is definitively the original. GTA's is initialised data,
     * so it is the executable's start value rather than a proven runtime original.
     */
    private static final String SUFFIX_60 = ".pnach.60";

    /**
     * One-shot repair for devices touched by the stock-revert experiment.
     *
     * <p>That build replaced {@code cheats/<CRC>.pnach} with a patch writing the
     * stock bytes back (read out of each game's ELF) and stashed the real 60 FPS
     * patch as {@code <CRC>.pnach.60}. It did not work: with the revert body in
     * place and {@code EnableCheats = true} in both the global and per-game ini,
     * Ultimate Spider-Man stayed at G: 60.01 — live after {@code reloadPatches()}
     * and again after a full restart. So the real patches go back and the stashes
     * are dropped.
     */
    /**
     * Install 60 FPS pnaches shipped in the APK (assets/pnach60/) into
     * files/cheats/. Skips names that already exist as .pnach or .pnach.off so
     * a user edit is never overwritten. This is how a fresh package gets 60 FPS
     * without copying another app's data.
     */
    static void installBundledPnaches(Context ctx, File filesDir) {
        if (ctx == null || filesDir == null) return;
        File cheats = new File(filesDir, "cheats");
        if (!cheats.isDirectory() && !cheats.mkdirs()) return;
        android.content.res.AssetManager am = ctx.getAssets();
        String[] names;
        try {
            names = am.list("pnach60");
            if (names == null || names.length == 0)
                names = am.list("");
        } catch (IOException e) {
            return;
        }
        if (names == null || names.length == 0) return;
        int copied = 0;
        for (String n : names) {
            if (n == null || !n.endsWith(".pnach")) continue;
            File live = new File(cheats, n);
            File off = new File(cheats, n + ".off");
            if (live.isFile() || off.isFile()) continue;
            String asset = "pnach60/" + n;
            try {
                am.open(asset).close();
            } catch (IOException missing) {
                asset = n;
            }
            try (java.io.InputStream in = am.open(asset);
                 java.io.OutputStream out = new FileOutputStream(live)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                copied++;
            } catch (IOException io) {
                logIoFailure("bundled pnach " + n, live, io);
            }
        }
        if (copied > 0) {
            android.util.Log.i("VulkanShim", "installed " + copied + " bundled 60 FPS pnaches");
            note(filesDir, "installed " + copied + " bundled 60 FPS pnaches");
        }
        /* Patches just changed, so which games count as "patched" changed too. */
        ensureSafeProfilesForKnownGames(filesDir);
    }

    /**
     * Install the two tested Shadow of the Colossus game profiles that accompany
     * the bundled pnach files.  These are per-game only: a user's existing
     * profile always wins, and other titles retain their own settings.
     */
    static void installBundledSotcProfiles(Context ctx, File filesDir) {
        if (ctx == null || filesDir == null) return;
        File gameSettings = new File(filesDir, "gamesettings");
        if (!gameSettings.isDirectory() && !gameSettings.mkdirs()) return;
        android.content.res.AssetManager am = ctx.getAssets();
        int copied = 0;
        for (String crc : SOTC_CRCS) {
            File live = new File(gameSettings, crc + ".ini");
            if (live.isFile()) continue;
            String asset = "gamesettings60/" + crc + ".ini";
            try (java.io.InputStream in = am.open(asset);
                 java.io.OutputStream out = new FileOutputStream(live)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                copied++;
            } catch (IOException missing) {
                // Older base APKs may not provide this optional profile.
            }
        }
        if (copied > 0) {
            android.util.Log.i("VulkanShim", "installed " + copied
                    + " bundled SOTC game profiles");
            note(filesDir, "installed " + copied + " bundled SOTC game profiles");
        }
    }

    private static final String USM_CRC = "FDD12792";
    private static final String PREF_USM_PROFILE_VER = "VulkanShim/UsmLowEndProfileVer";
    private static final int USM_LOW_END_PROFILE_VER = 9;

    /**
     * Undo 60fps / Low-End sync stomps on USM + global PCSX2.ini on SD865.
     *
     * <p>This used to pass a literal {@code false} as mergeIniEmuCore's
     * WIDESCREEN argument while carefully threading the real EnableCheats value
     * through -- so on every low-end handheld each 60 FPS toggle, each Low-End
     * Performance toggle and each cold start silently cleared
     * EnableWideScreenPatches from the USM profile AND from the global
     * PCSX2.ini, which every game without its own ini inherits. apply60FpsMode
     * calls this one line after doing the right thing, so the correct write was
     * undone immediately and then offered to the user as a restart. It is the
     * same hardcoded false that was removed from apply60FpsMode in 26500783 and
     * that the comment in ensureSafeProfilesForKnownGames warns against; it was
     * never removed here. This repair is about SyncToHostRefreshRate /
     * SkipDuplicateFrames / texture_preloading / Speedhacks and has no business
     * having an opinion on widescreen.
     *
     * @param ctx used only to read the real Widescreen pref; null falls back to
     *            whatever the global ini already holds, which is still better
     *            than assuming off.
     */
    static void repairUsmSd865Profile(File filesDir) {
        repairUsmSd865Profile(null, filesDir);
    }

    static void repairUsmSd865Profile(Context ctx, File filesDir) {
        if (filesDir == null || !HandheldTier.isLowEndHandheld()) return;
        boolean ws = globalWidescreenOn(filesDir);
        SharedPreferences spWs = ctx == null ? null : defaultPrefsOrNull(ctx);
        if (spWs != null) ws = spBool(spWs, PREF_WIDESCREEN, ws);
        File ini = new File(new File(filesDir, "gamesettings"), USM_CRC + ".ini");
        try {
            if (ini.isFile()) {
                mergeIniEmuCore(ini, ws, readIniEnableCheatsPublic(ini));
                mergeIniGsKey(ini, "SyncToHostRefreshRate", "false");
                mergeIniGsKey(ini, "SkipDuplicateFrames", "true");
                mergeIniGsKey(ini, "texture_preloading", "0");
                mergeIniSectionKey(ini, "[EmuCore/Speedhacks]", "vuThread", "true");
                mergeIniSectionKey(ini, "[EmuCore/Speedhacks]", "EECycleRate", "1");
                mergeIniSectionKey(ini, "[EmuCore/Speedhacks]", "EECycleSkip", "0");
            }
            File global = new File(filesDir, "PCSX2.ini");
            if (global.isFile())
                mergeIniEmuCore(global, ws, readIniEnableCheatsPublic(global));
        } catch (IOException e) {
            logIoFailure("USM SD865 repair", ini.isFile() ? ini : filesDir, e);
        }
    }

    static void mergeIniSectionKey(File ini, String section, String key, String value)
            throws IOException {
        List<String> lines = new ArrayList<>();
        if (ini.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
        }
        boolean inSection = false;
        boolean saw = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                inSection = t.equalsIgnoreCase(section);
                continue;
            }
            if (!inSection || t.isEmpty() || t.startsWith(";")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            if (key.equalsIgnoreCase(t.substring(0, eq).trim())) {
                lines.set(i, key + " = " + value);
                saw = true;
            }
        }
        if (!saw) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase(section)) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add(section);
                insertAt = lines.size();
            }
            lines.add(insertAt, key + " = " + value);
        }
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(ini), StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                w.write(lines.get(i));
                w.write('\n');
            }
        }
    }

    /**
     * SD865-class handhelds: ship Native + PeterDelta 60fps speedhacks for USM.
     * Skips if the user already has a profile unless we bump USM_LOW_END_PROFILE_VER.
     */
    static void installBundledLowEndGameProfiles(Context ctx, File filesDir,
            SharedPreferences prefs) {
        if (ctx == null || filesDir == null || prefs == null) return;
        int ver = prefs.getInt(PREF_USM_PROFILE_VER, 0);
        File gameSettings = new File(filesDir, "gamesettings");
        if (!gameSettings.isDirectory() && !gameSettings.mkdirs()) return;
        File live = new File(gameSettings, USM_CRC + ".ini");
        if (live.isFile() && ver >= USM_LOW_END_PROFILE_VER) return;
        android.content.res.AssetManager am = ctx.getAssets();
        String asset = "gamesettings-perf/" + USM_CRC + ".ini";
        try (java.io.InputStream in = am.open(asset);
             java.io.OutputStream out = new FileOutputStream(live)) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            prefs.edit().putInt(PREF_USM_PROFILE_VER, USM_LOW_END_PROFILE_VER).commit();
            android.util.Log.i("VulkanShim",
                    "installed bundled USM SD865 profile gamesettings/" + USM_CRC + ".ini");
            note(filesDir, "installed bundled USM SD865 profile (Native 60fps)");
        } catch (IOException missing) {
            android.util.Log.w("VulkanShim",
                    "gamesettings-perf/" + USM_CRC + ".ini not in APK — skip USM profile");
        }
    }

    private static void restoreStashed60Pnaches(File filesDir) {
        File cheats = new File(filesDir, "cheats");
        if (!cheats.isDirectory()) return;
        File[] stashes = cheats.listFiles((d, n) -> n.endsWith(SUFFIX_60));
        if (stashes == null) return;
        for (File kept : stashes) {
            String n = kept.getName();
            File live = new File(cheats, n.substring(0, n.length() - 3)); /* drop ".60" */
            try {
                writeFile(live, readFile(kept));
                //noinspection ResultOfMethodCallIgnored
                kept.delete();
                android.util.Log.i("VulkanShim",
                        "60 FPS pnach restored from stash: cheats/" + live.getName());
            } catch (IOException io) {
                logIoFailure("restore stashed pnach", kept, io);
            }
        }
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void writeFile(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }

    /**
     * When 60 FPS is OFF, rename pnach files to *.pnach.off under cheats,
     * cheats_ws, and cheats_ni so patches cannot stay active even if a stale
     * game ini still has EnableCheats=true.
     *
     * <p>Files that have a stock-revert body available are left in place instead —
     * they have to stay loaded to write the original bytes back.
     */
    static void applyCheatsPnachGate(File filesDir, boolean enable60) {
        String[] dirs = {"cheats", "cheats_ws", "cheats_ni"};
        for (String dirName : dirs) {
            File cheats = new File(filesDir, dirName);
            if (!cheats.isDirectory()) continue;
            File[] files = cheats.listFiles();
            if (files == null) continue;
            /* Hundreds of pnaches ship here now, so count instead of logging each
             * one — a line per file buried every other message on a toggle. */
            int moved = 0;
            for (File f : files) {
                String n = f.getName();
                if (enable60) {
                    if (n.endsWith(".pnach.off")) {
                        File on = new File(cheats, n.substring(0, n.length() - 4));
                        if (!on.exists() && f.renameTo(on)) moved++;
                    }
                } else {
                    if (n.endsWith(".pnach") && !n.contains(".bak")) {
                        File off = new File(cheats, n + ".off");
                        if (!off.exists() && f.renameTo(off)) moved++;
                    }
                }
            }
            if (moved > 0)
                android.util.Log.i("VulkanShim", "pnach " + (enable60 ? "enabled" : "disabled")
                        + ": " + moved + " in " + dirName);
        }
    }

    /** Drop keys from turnip.conf (empty values are not enough — key must disappear). */
    static void removeConfKeys(File conf, String... keys) {
        if (conf == null || !conf.isFile() || keys == null || keys.length == 0) return;
        Set<String> drop = new HashSet<>();
        for (String k : keys) if (k != null) drop.add(k);
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String raw = line.trim();
                if (!raw.startsWith("#") && !raw.isEmpty()) {
                    int eq = raw.indexOf('=');
                    if (eq > 0 && drop.contains(raw.substring(0, eq).trim()))
                        continue;
                }
                lines.add(line);
            }
        } catch (IOException e) {
            logIoFailure("turnip.conf read", conf, e);
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(conf), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException e) {
            logIoFailure("turnip.conf removeKeys", conf, e);
        }
    }

    /**
     * Global Internal Resolution (SharedPreferences + PCSX2.ini).
     * Callers that need every game to match Graphics also use
     * {@link #applyInternalUpscaleMultiplierAllGames}.
     */
    /** Shipped default Internal Resolution when the user has never picked one. */
    private static final String DEFAULT_IR = "1.500000";

    /**
     * Seed Internal Resolution to 1.5x on a device that has never chosen one.
     *
     * Native (1x) is soft on a 1080p panel and 2x is the top of what framegen
     * can carry on this SoC -- at IR 2.5 the cost guard trips and turns frame
     * generation off outright. 1.5x is the setting that looks sharp and still
     * leaves the interpolator its budget, so it is the better out-of-the-box
     * value than either end.
     *
     * ONLY seeds when nothing was ever saved. readUserIr() returns null in
     * exactly that case -- "the user has no opinion yet" -- and is documented
     * not to treat a Native GS reading as a choice. An existing pick, including
     * a deliberate 1x, is left alone: this is a default, not a policy.
     */
    public static void seedDefaultIrIfUnset(Context ctx) {
        if (ctx == null) return;
        try {
            if (readUserIr(ctx) != null) return;
            saveUserIr(ctx, DEFAULT_IR);
            applyUserInternalResolution(ctx, DEFAULT_IR);
            android.util.Log.i("VulkanShim",
                    "seeded default Internal Resolution " + DEFAULT_IR
                    + " (no user choice on record)");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "IR seed failed: " + t.getMessage());
        }
    }

    public static void saveUserIr(Context ctx, String mult) {
        if (ctx == null || mult == null) return;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences sp = (SharedPreferences) pm;
            SharedPreferences.Editor ed = sp.edit();
            ed.remove(PREF_USER_IR);
            ed.putString(PREF_USER_IR, mult);
            ed.commit();
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "user IR save failed: " + t.getMessage());
        }
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(files, USER_IR_FILE)), StandardCharsets.UTF_8)) {
            w.write(mult);
            w.write('\n');
        } catch (IOException e) {
            logIoFailure("user_ir.txt", new File(files, USER_IR_FILE), e);
        }
    }

    static String readUserIrFile(Context ctx) {
        if (ctx == null) return null;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return null;
        File f = new File(files, USER_IR_FILE);
        if (!f.isFile()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            return line != null ? formatUpscaleMultiplier(line.trim()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Graphics IR the user chose. Null if never saved (do not treat Native GS as this). */
    public static String readUserIr(SharedPreferences prefs) {
        if (prefs == null) return null;
        try {
            Object raw = prefs.getAll().get(PREF_USER_IR);
            if (raw == null) return null;
            return formatUpscaleMultiplier(String.valueOf(raw));
        } catch (Throwable t) {
            return null;
        }
    }

    public static String readUserIr(Context ctx) {
        if (ctx == null) return null;
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            String fromPref = readUserIr((SharedPreferences) pm);
            if (fromPref != null) return fromPref;
        } catch (Throwable ignored) { }
        return readUserIrFile(ctx);
    }

    /**
     * Graphics ListPreference as-is. Null if the key is absent — Native is only
     * valid when the user (or stock) actually stored {@code 1.000000}.
     */
    public static String resolveEffectiveIr(Context ctx, SharedPreferences prefs) {
        if (prefs != null) {
            try {
                Object raw = prefs.getAll().get("EmuCore/GS/upscale_multiplier");
                if (raw != null) {
                    String graphics = formatUpscaleMultiplier(String.valueOf(raw));
                    if (graphics != null)
                        return graphics;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** @deprecated use {@link #resolveEffectiveIr} */
    public static Object resolveGraphicsIr(SharedPreferences prefs) {
        String saved = readUserIr(prefs);
        if (saved != null) return saved;
        if (prefs == null) return null;
        try {
            return prefs.getAll().get("EmuCore/GS/upscale_multiplier");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * SharedPreferences leftover (Native, 2.25×) must not become live IR.
     * Copy the game's {@code upscale_multiplier} into the Graphics pref only.
     * Does not rewrite gamesettings.
     */
    static void syncGraphicsPrefToGameIni(Context ctx) {
        if (ctx == null) return;
        String gameIr = readGameIniIr(ctx);
        if (gameIr == null) return;
        writeUpscalePref(ctx, gameIr);
        lastPushedIr = gameIr;
        saveUserIr(ctx, gameIr);
        note(ctx.getExternalFilesDir(null),
                "Graphics pref aligned to game IR " + gameIr + " (SP leftover ignored)");
    }

    /** Per-game ini first (USM FDD12792), then PCSX2.ini. */
    static String readGameIniIr(Context ctx) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : lastFilesDir;
        if (files == null) return null;
        String usm = formatUpscaleMultiplier(
                readIniUpscalePublic(new File(new File(files, "gamesettings"), "FDD12792.ini")));
        if (usm != null) return usm;
        String global = formatUpscaleMultiplier(
                readIniUpscalePublic(new File(files, "PCSX2.ini")));
        if (global != null) return global;
        return harvestNonNativeGameIr(ctx);
    }

    /** Non-Native upscale from gamesettings. Prefers FDD12792.ini (USM). */
    public static String harvestNonNativeGameIr(Context ctx) {
        if (ctx == null) return null;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return null;
        File gsDir = new File(files, "gamesettings");
        if (!gsDir.isDirectory()) return null;
        File usm = new File(gsDir, "FDD12792.ini");
        String prefer = formatUpscaleMultiplier(readIniUpscalePublic(usm));
        if (prefer != null && !IR_NATIVE.equals(prefer)) return prefer;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return null;
        for (File ini : inis) {
            String v = formatUpscaleMultiplier(readIniUpscalePublic(ini));
            if (v != null && !IR_NATIVE.equals(v)) return v;
        }
        return null;
    }

    /** Watchdog: adb-drop {@code files/shim_force.txt} applies IR live. */
    public static void pollForceFile(Context ctx) {
        if (ctx == null) return;
        try {
            consumeForceFile(ctx, defaultPrefs(ctx), ctx.getExternalFilesDir(null));
        } catch (Throwable ignored) { }
    }

    /**
     * One-shot {@code files/shim_force.txt} (adb-writable). Example:
     * <pre>
     * fsr=off
     * upscale=4.000000
     * </pre>
     * Applied to SharedPreferences before applyFsrMode so a 4× ini survives boot.
     */
    static void consumeForceFile(Context ctx, SharedPreferences prefs, File files) {
        if (files == null) return;
        File f = new File(files, FORCE_FILE);
        if (!f.isFile()) return;
        String wantFsr = null;
        String wantIr = null;
        String wantWs = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String k = t.substring(0, eq).trim().toLowerCase(java.util.Locale.US);
                String v = t.substring(eq + 1).trim();
                if ("fsr".equals(k) || "fsr_mode".equals(k)) wantFsr = v;
                else if ("upscale".equals(k) || "ir".equals(k) || "upscale_multiplier".equals(k))
                    wantIr = v;
                else if ("widescreen".equals(k) || "ws".equals(k)) wantWs = v;
            }
        } catch (IOException e) {
            logIoFailure("shim_force.txt", f, e);
            return;
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) { }
        if (wantIr != null) {
            String ir = formatUpscaleMultiplier(wantIr);
            if (ir != null) {
                saveUserIr(ctx, ir);
                writeUpscalePref(ctx, ir);
            }
        }
        /* WIDESCREEN, so the toggle can be driven without the UI.
         *
         * Write the pref and nothing else: that is exactly what the switch does,
         * so the watchdog poll, syncWidescreenLive, the ini writes and the
         * AspectRatio follow all run through their real paths. Verifying this
         * toggle otherwise needs a human tapping a switch, and the package is
         * not debuggable, so its SharedPreferences cannot be read back either. */
        if (wantWs != null && prefs != null) {
            boolean on = wantWs.equalsIgnoreCase("on") || wantWs.equals("1")
                    || wantWs.equalsIgnoreCase("true");
            prefs.edit().putBoolean(PREF_WIDESCREEN, on).commit();
            android.util.Log.i("VulkanShim", "shim_force.txt widescreen -> " + on);
        }
        if (wantFsr != null && prefs != null && ShimBuildConfig.FSR) {
            boolean on = wantFsr.equalsIgnoreCase("on") || wantFsr.equals("1")
                    || wantFsr.equalsIgnoreCase("true");
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean("VulkanShim/FsrQuality4x", on);
            ed.putBoolean("VulkanShim/UpscalerFsr1", on);
            ed.commit();
        }
        note(files, "consumed shim_force.txt fsr=" + wantFsr + " ir=" + wantIr
                + " widescreen=" + wantWs);
        if (wantIr != null) {
            String ir = formatUpscaleMultiplier(wantIr);
            if (ir != null)
                applyUserInternalResolution(ctx, ir);
        }
    }

    /** Sidecar only. Native is a real IR — never treat 1.000000 as leftover. */
    static void seedUserIrFromGraphics(Context ctx, SharedPreferences prefs) {
        String saved = readUserIr(ctx);
        if (saved != null)
            return;
        String graphics = resolveEffectiveIr(ctx, prefs);
        if (graphics != null)
            saveUserIr(ctx, graphics);
    }

    /**
     * Read a boolean pref that may be stored as a String.
     *
     * <p>Some keys in this fork arrive as Strings rather than Booleans --
     * {@link ToggleWatchdog#readSpBool} and {@code ShimSettingsBridge.resolveBool}
     * both exist for that reason, the former's comment recording it in the past
     * tense ("getBoolean then throws and aborted the poll"). A bare
     * {@code getBoolean} throws {@link ClassCastException} on those, and every
     * caller here swallowed it into {@code false} -- which is the value that
     * WIPES the setting, because false is what then gets written into every game
     * ini. Reading through {@code getAll()} cannot throw at all.
     */
    static boolean spBool(SharedPreferences sp, String key, boolean def) {
        if (sp == null) return def;
        try {
            Object raw = sp.getAll().get(key);
            if (raw == null) return def;
            if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
            String s = String.valueOf(raw).trim();
            if (s.equalsIgnoreCase("true") || s.equals("1")
                    || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on"))
                return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")
                    || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off"))
                return false;
            return def;
        } catch (Throwable t) {
            return def;
        }
    }

    static SharedPreferences defaultPrefsOrNull(Context ctx) {
        try {
            return defaultPrefs(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    static SharedPreferences defaultPrefs(Context ctx) throws Exception {
        Object pm = Class.forName("androidx.preference.PreferenceManager")
                .getMethod("getDefaultSharedPreferences", Context.class)
                .invoke(null, ctx);
        return (SharedPreferences) pm;
    }

    static boolean prefsFsrOn(SharedPreferences sp) {
        if (!ShimBuildConfig.FSR || sp == null) return false;
        try {
            /* CASMode=2 is the presenter slot, not the FSR switch. Leftover
             * resize mode must not pin GS at Native after FSR is turned off. */
            return sp.getBoolean("VulkanShim/FsrQuality4x", false)
                    || sp.getBoolean("VulkanShim/UpscalerFsr1", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ListPreference persist/read is {@code getString}. JNI GetFloatValue
     * parses this via getString + from_chars. Always {@code %.6f}.
     */
    static String upscaleUiString(float f) {
        return String.format(java.util.Locale.US, "%.6f", f);
    }

    static float parseUpscaleFloat(Object raw, float fallback) {
        if (raw instanceof Number)
            return ((Number) raw).floatValue();
        if (raw == null) return fallback;
        try {
            return Float.parseFloat(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Stock Graphics ListPreferences all {@code getString} on inflate.
     * A leftover Float/Integer in any of these keys ClassCast-crashes the
     * Graphics tab. {@code remove} then {@code putString} so the type actually
     * changes (Android SP will not replace Float with String in place).
     */
    public static void forceGraphicsListPrefsAsStrings(Context ctx) {
        if (ctx == null) return;
        irWriteGuard++;
        try {
            SharedPreferences sp = defaultPrefs(ctx);
            SharedPreferences.Editor ed = sp.edit();
            boolean fsrOn = ShimBuildConfig.FSR && prefsFsrOn(sp);
            Object irRaw = sp.getAll().get(PREF_GS_UPSCALE);
            String graphicsIr = irRaw != null
                    ? formatUpscaleMultiplier(String.valueOf(irRaw)) : null;
            String saved = readUserIr(ctx);
            String gameIr = readGameIniIr(ctx);
            /* Label must match live GS. Leftover SP Native must not show
             * "Native" while gamesettings is 2× (OSD 1024×896). This writes
             * the Graphics String only — never gamesettings. */
            String ir;
            if (lastPushedIr != null)
                ir = lastPushedIr;
            else if (gameIr != null)
                ir = gameIr;
            else if (saved != null)
                ir = saved;
            else
                ir = graphicsIr;
            if (ir != null && (!(irRaw instanceof String) || !ir.equals(String.valueOf(irRaw)))) {
                ed.remove(PREF_GS_UPSCALE);
                ed.putString(PREF_GS_UPSCALE, ir);
            }
            final boolean syncUserIr = ir != null
                    && (saved == null || !ir.equals(saved));

            Object casRaw = sp.getAll().get("EmuCore/GS/CASMode");
            String cas = fsrOn ? "2" : "0";
            if (casRaw instanceof String) {
                String t = ((String) casRaw).trim();
                if (fsrOn && "2".equals(t))
                    cas = "2";
                else if (!fsrOn && "0".equals(t))
                    cas = "0";
            }
            if (!(casRaw instanceof String) || !cas.equals(String.valueOf(casRaw).trim())) {
                ed.remove("EmuCore/GS/CASMode");
                ed.putString("EmuCore/GS/CASMode", cas);
            }

            String tv = fsrOn ? "4" : "0";
            Object tvRaw = sp.getAll().get("EmuCore/GS/TVShader");
            if (!(tvRaw instanceof String) || !tv.equals(String.valueOf(tvRaw).trim())) {
                ed.remove("EmuCore/GS/TVShader");
                ed.putString("EmuCore/GS/TVShader", tv);
            }

            final String[] listKeys = {
                "EmuCore/GS/Renderer",
                "EmuCore/GS/filter",
                "EmuCore/GS/mipmap_hw",
                "EmuCore/GS/UserHacks_TriFilter",
                "EmuCore/GS/MaxAnisotropy",
                "EmuCore/GS/accurate_blending_unit",
                "EmuCore/GS/texture_preloading",
                "EmuCore/GS/HWDownloadMode",
                "EmuCore/GS/AspectRatio",
                "EmuCore/GS/FMVAspectRatioSwitch",
                "EmuCore/GS/linear_present_mode",
                "EmuCore/GS/TVShader",
            };
            for (String key : listKeys) {
                Object v = sp.getAll().get(key);
                if (v != null && !(v instanceof String)) {
                    ed.remove(key);
                    ed.putString(key, String.valueOf(v));
                }
            }
            Object themeRaw = sp.getAll().get("UI/Theme");
            if (themeRaw instanceof Integer) {
                ed.remove("UI/Theme");
                ed.putString("UI/Theme", ShimTheme.coerce(themeRaw));
            } else if (themeRaw instanceof String
                    && !ShimTheme.coerce(themeRaw).equals(themeRaw)) {
                ed.remove("UI/Theme");
                ed.putString("UI/Theme", ShimTheme.coerce(themeRaw));
            }
            ed.commit();
            if (lastPushedIr == null && ir != null)
                lastPushedIr = ir;
            if (syncUserIr)
                saveUserIr(ctx, ir);
            note(ctx.getExternalFilesDir(null),
                    "graphics ListPrefs as String ir=" + ir
                            + " cas=" + cas
                            + " (was ir="
                            + (irRaw != null ? irRaw.getClass().getSimpleName() : "absent")
                            + " cas="
                            + (casRaw != null ? casRaw.getClass().getSimpleName() : "absent")
                            + ")");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "forceGraphicsListPrefsAsStrings: " + t.getMessage());
        } finally {
            irWriteGuard--;
        }
    }

    /**
     * JNI {@code GetFloatValue} tries {@code getString} + from_chars, then
     * {@code getFloat}. Never {@code putFloat} this ListPreference key.
     */
    static void bindUpscaleFloatForJni(Context ctx) {
        bindUpscaleStringForUi(ctx);
    }

    /**
     * Stock Graphics ListPreference {@code getString}s this key on inflate.
     * Never leave a Float here while Settings can open.
     */
    static void bindUpscaleStringForUi(Context ctx) {
        if (ctx == null) return;
        forceGraphicsListPrefsAsStrings(ctx);
    }

    /** Always String. GetFloatValue parses it; putFloat crashes Graphics. */
    public static void bindUpscaleForHost(Context ctx, boolean emulation) {
        emuResumed = emulation;
        bindUpscaleStringForUi(ctx);
    }

    public static void onEmulationPaused() {
        onEmulationPaused(null);
    }

    public static void onEmulationPaused(Context ctx) {
        if (ctx != null) OsdPrefs.rememberUserIntent(ctx);
        emuResumed = false;
        if (!nativeBool("hasEmulationThread"))
            emulationResumeUptimeMs = 0L;
    }

    static void writeUpscalePref(Context ctx, String mult) {
        try {
            String s = formatUpscaleMultiplier(mult);
            if (s == null) return;
            SharedPreferences sp = defaultPrefs(ctx);
            SharedPreferences.Editor ed = sp.edit();
            ed.remove(PREF_GS_UPSCALE);
            ed.putString(PREF_GS_UPSCALE, s);
            ed.commit();
            android.util.Log.i("VulkanShim", "upscale SP putString=" + s);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "upscale SP write failed: " + t.getMessage());
        }
    }

    /** PCSX2.ini + every gamesettings ini. Does not touch the Graphics ListPreference. */
    static void applyGsUpscaleIni(File filesDir, String mult) {
        File global = new File(filesDir, "PCSX2.ini");
        if (global.isFile()) {
            try {
                mergeIniUpscaleMultiplier(global, mult);
            } catch (IOException e) {
                logIoFailure("PCSX2.ini upscale sync", global, e);
            }
        }
        applyInternalUpscaleMultiplierAllGames(filesDir, mult);
    }

    static void applyInternalUpscaleMultiplier(Context ctx, File filesDir, String mult) {
        writeUpscalePref(ctx, mult);
        File global = new File(filesDir, "PCSX2.ini");
        if (global.isFile()) {
            try {
                mergeIniUpscaleMultiplier(global, mult);
            } catch (IOException e) {
                logIoFailure("PCSX2.ini upscale sync", global, e);
            }
        }
    }

    /** Force Internal Resolution in every gamesettings/*.ini. */
    static void applyInternalUpscaleMultiplierAllGames(File filesDir, String mult) {
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return;
        for (File ini : inis) {
            try {
                mergeIniUpscaleMultiplier(ini, mult);
                /* Record what landed so in-game INI edits are not mistaken for our write. */
                String landed = formatUpscaleMultiplier(readIniUpscalePublic(ini));
                if (landed != null)
                    INI_WROTE_IR.put(crcOfIni(ini), landed);
            } catch (IOException e) {
                logIoFailure("games upscale sync", ini, e);
            }
        }
        android.util.Log.i("VulkanShim", "all gamesettings upscale_multiplier=" + mult);
    }

    /**
     * Push the Graphics Internal Resolution pref into PCSX2.ini and every
     * gamesettings ini. User Internal Resolution path only — never FSR, Frame
     * Gen, watchdog, or cold start. Never pin Native.
     */
    static void applyGraphicsInternalResolution(Context ctx, SharedPreferences prefs) {
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        String ir = resolveEffectiveIr(ctx, prefs);
        if (ir == null) return;
        irWriteGuard++;
        try {
            applyInternalUpscaleMultiplier(ctx, files, ir);
            applyInternalUpscaleMultiplierAllGames(files, ir);
            lastPushedIr = ir;
        } finally {
            irWriteGuard--;
        }
        pendingGsIrApply = true;
        pendingGsIr = ir;
        note(files, "Internal Resolution write " + ir
                + " [String] (Graphics → all games)");
        tryLiveGsApply(ctx, ir);
    }

    static void mergeIniUpscaleMultiplier(File ini, String mult) throws IOException {
        List<String> lines = new ArrayList<>();
        if (ini.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
        }
        boolean inGs = false;
        boolean inGsd = false;
        boolean sawGs = false;
        boolean sawGsd = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                inGs = t.equalsIgnoreCase("[EmuCore/GS]");
                inGsd = t.equalsIgnoreCase("[GSD]");
                continue;
            }
            if ((!inGs && !inGsd) || t.isEmpty() || t.startsWith(";")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            if ("upscale_multiplier".equalsIgnoreCase(key)
                    || "UpscaleMultiplier".equalsIgnoreCase(key)) {
                lines.set(i, "upscale_multiplier = " + mult);
                if (inGs) sawGs = true;
                if (inGsd) sawGsd = true;
            }
        }
        if (!sawGs) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[EmuCore/GS]")) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add("[EmuCore/GS]");
                insertAt = lines.size();
            }
            lines.add(insertAt, "upscale_multiplier = " + mult);
        }
        if (!sawGsd) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[GSD]")) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add("[GSD]");
                insertAt = lines.size();
            }
            lines.add(insertAt, "upscale_multiplier = " + mult);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ini), StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                w.write(lines.get(i));
                w.write('\n');
            }
        }
    }

    /** PCSX2.ini + every gamesettings ini — keeps ini aligned with SP for OSD toggles. */
    static void mergeGsKeyEverywhere(File filesDir, String key, String value) {
        if (filesDir == null || key == null || value == null) return;
        try {
            mergeIniGsKey(new File(filesDir, "PCSX2.ini"), key, value);
        } catch (IOException e) {
            android.util.Log.w("VulkanShim", "mergeGsKeyEverywhere PCSX2.ini " + key
                    + ": " + e.getMessage());
        }
        File gsDir = new File(filesDir, "gamesettings");
        File[] inis = gsDir.isDirectory()
                ? gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak")) : null;
        if (inis == null) return;
        for (File ini : inis) {
            try {
                mergeIniGsKey(ini, key, value);
            } catch (IOException e) {
                android.util.Log.w("VulkanShim", "mergeGsKeyEverywhere " + ini.getName()
                        + ": " + e.getMessage());
            }
        }
    }

    /** Write a key into [EmuCore/GS] and [GSD] so per-game ini cannot ignore Graphics. */
    static void mergeIniGsKey(File ini, String key, String value) throws IOException {
        List<String> lines = new ArrayList<>();
        if (ini.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
        }
        boolean inGs = false;
        boolean inGsd = false;
        boolean sawGs = false;
        boolean sawGsd = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                inGs = t.equalsIgnoreCase("[EmuCore/GS]");
                inGsd = t.equalsIgnoreCase("[GSD]");
                continue;
            }
            if ((!inGs && !inGsd) || t.isEmpty() || t.startsWith(";")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            if (key.equalsIgnoreCase(t.substring(0, eq).trim())) {
                lines.set(i, key + " = " + value);
                if (inGs) sawGs = true;
                if (inGsd) sawGsd = true;
            }
        }
        if (!sawGs) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[EmuCore/GS]")) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add("[EmuCore/GS]");
                insertAt = lines.size();
            }
            lines.add(insertAt, key + " = " + value);
        }
        if (!sawGsd) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[GSD]")) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add("[GSD]");
                insertAt = lines.size();
            }
            lines.add(insertAt, key + " = " + value);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ini), StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                w.write(lines.get(i));
                w.write('\n');
            }
        }
    }

    static void mergeIniEmuCore(File ini, boolean widescreen60, boolean enableCheats)
            throws IOException {
        String crc = ini.getName();
        if (crc != null && crc.endsWith(".ini"))
            crc = crc.substring(0, crc.length() - 4);
        boolean usmIni = USM_CRC.equalsIgnoreCase(crc);
        boolean globalLowEnd = HandheldTier.isLowEndHandheld()
                && ini.getName() != null
                && ini.getName().equalsIgnoreCase("PCSX2.ini");
        /* USM + SD865 global: NI patches ON with 60fps costs ~10–12 fps (RP Mini V2). */
        final boolean niPatches = enableCheats && !usmIni && !globalLowEnd;
        List<String> lines = new ArrayList<>();
        if (ini.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
        }
        boolean inSection = false;
        boolean inGs = false;
        boolean sawWidescreen = false;
        boolean sawCheats = false;
        boolean sawNoInterlace = false;
        boolean saw60Mode = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                inSection = t.equalsIgnoreCase("[EmuCore]");
                inGs = t.equalsIgnoreCase("[EmuCore/GS]");
                continue;
            }
            if (t.isEmpty() || t.startsWith(";")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            if (inSection) {
                if ("EnableWideScreenPatches".equalsIgnoreCase(key)) {
                    lines.set(i, "EnableWideScreenPatches = " + (widescreen60 ? "true" : "false"));
                    sawWidescreen = true;
                } else if ("EnableCheats".equalsIgnoreCase(key)) {
                    lines.set(i, "EnableCheats = " + (enableCheats ? "true" : "false"));
                    sawCheats = true;
                } else if ("EnableNoInterlacingPatches".equalsIgnoreCase(key)) {
                    lines.set(i, "EnableNoInterlacingPatches = "
                            + (niPatches ? "true" : "false"));
                    sawNoInterlace = true;
                }
            } else if (inGs && "60FPSMode".equalsIgnoreCase(key)) {
                lines.set(i, "60FPSMode = " + (enableCheats ? "true" : "false"));
                saw60Mode = true;
            }
        }
        if (!sawWidescreen || !sawCheats || !sawNoInterlace) {
            int insertAt = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[EmuCore]")) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                    lines.add("");
                lines.add("[EmuCore]");
                insertAt = lines.size();
            }
            if (!sawWidescreen)
                lines.add(insertAt++, "EnableWideScreenPatches = "
                        + (widescreen60 ? "true" : "false"));
            if (!sawCheats)
                lines.add(insertAt++, "EnableCheats = " + (enableCheats ? "true" : "false"));
            if (!sawNoInterlace)
                lines.add(insertAt, "EnableNoInterlacingPatches = "
                        + (niPatches ? "true" : "false"));
        }
        if (!saw60Mode) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[EmuCore/GS]")) {
                    lines.add(i + 1, "60FPSMode = " + (enableCheats ? "true" : "false"));
                    break;
                }
            }
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ini), StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                w.write(lines.get(i));
                w.write('\n');
            }
        }
    }

    /** Keep gamesettings/*.ini [VulkanShim] aligned with Graphics toggles. */
    /**
     * Exactly what we last wrote into each {@code gamesettings/<CRC>.ini}, so a
     * later read-back can tell a user edit from our own write.
     *
     * <p>The in-game Game Properties → Graphics tab is backed by an INI
     * PreferenceDataStore, not SharedPreferences, so flipping a switch there lands
     * in the game ini and never reaches the SP the watchdog polls. Reading those
     * inis back unconditionally is what previously glued the toggles on (we write
     * every ini, then OR'd them back in). Comparing against these maps instead
     * makes the read-back loop-free.
     */
    private static final Map<String, Boolean> INI_WROTE_60 = new HashMap<>();
    private static final Map<String, Boolean> INI_WROTE_FSR = new HashMap<>();
    private static final Map<String, String> INI_WROTE_IR = new HashMap<>();

    static String crcOfIni(File ini) {
        String n = ini.getName();
        return n.endsWith(".ini") ? n.substring(0, n.length() - 4) : n;
    }

    static void syncGameIniVulkanShim(File filesDir, boolean fsr) {
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini"));
        if (inis == null) return;
        for (File ini : inis) {
            try {
                mergeIniVulkanShim(ini, fsr);
                INI_WROTE_FSR.put(crcOfIni(ini), readIniVulkanShimFlags(ini).fsr);
            } catch (IOException e) {
                logIoFailure("ini sync", ini, e);
            }
        }
    }

    /** One switch edit made in the in-game Game Properties → Graphics tab. */
    public static final class IniEdit {
        public Boolean want60;
        public Boolean wantFsr;
        public String wantIr;
        public String crc;
        public boolean any() {
            return want60 != null || wantFsr != null || wantIr != null;
        }
    }

    /** Baseline per-game IR so {@link #detectGameIniEdit} can spot user ini edits. */
    static void seedIniWroteIrFromDisk(File filesDir) {
        if (filesDir == null) return;
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return;
        for (File ini : inis) {
            String ir = formatUpscaleMultiplier(readIniUpscalePublic(ini));
            if (ir != null)
                INI_WROTE_IR.put(crcOfIni(ini), ir);
        }
    }

    /**
     * Look for a {@code gamesettings/<CRC>.ini} value that differs from the value
     * we ourselves last wrote there — that difference is the user having flipped
     * the switch on the in-game Graphics tab.
     */
    public static IniEdit detectGameIniEdit(File filesDir) {
        IniEdit out = new IniEdit();
        if (filesDir == null) return out;
        File gsDir = new File(filesDir, "gamesettings");
        if (!gsDir.isDirectory()) return out;
        File[] inis = gsDir.listFiles((d, n) -> n.endsWith(".ini") && !n.contains(".bak"));
        if (inis == null) return out;
        for (File ini : inis) {
            String crc = crcOfIni(ini);
            Boolean wrote60 = INI_WROTE_60.get(crc);
            Boolean wroteFsr = INI_WROTE_FSR.get(crc);
            String wroteIr = INI_WROTE_IR.get(crc);
            if (wrote60 != null) {
                boolean now = readIniEnableCheatsPublic(ini);
                if (now != wrote60) {
                    out.want60 = now;
                    out.crc = crc;
                }
            }
            if (wroteFsr != null) {
                boolean now = readIniVulkanShimFlags(ini).fsr;
                if (now != wroteFsr) {
                    out.wantFsr = now;
                    out.crc = crc;
                }
            }
            if (wroteIr != null) {
                String now = formatUpscaleMultiplier(readIniUpscalePublic(ini));
                if (now != null && !now.equals(wroteIr)) {
                    out.wantIr = now;
                    out.crc = crc;
                }
            }
        }
        return out;
    }

    static void mergeIniVulkanShim(File ini, boolean fsr) throws IOException {
        List<String> lines = new ArrayList<>();
        if (ini.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(ini), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
        }
        int secStart = -1, secEnd = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.equalsIgnoreCase("[VulkanShim]")) {
                secStart = i;
                for (int j = i + 1; j < lines.size(); j++) {
                    String u = lines.get(j).trim();
                    if (u.startsWith("[") && u.endsWith("]")) {
                        secEnd = j;
                        break;
                    }
                }
                break;
            }
        }
        List<String> section = new ArrayList<>();
        section.add("[VulkanShim]");
        section.add("; synced from Graphics toggles");
        section.add("Upscaler = " + (fsr ? "fsr1" : "off"));
        section.add("UpscalerFsr1 = " + (fsr ? "true" : "false"));
        /* Frame gen is owned by prefs -> turnip.conf. Do not write FrameGen into
         * per-game inis — a leftover FrameGen=false used to turn the native path
         * off even when the Graphics switch was on. */

        if (secStart >= 0) {
            lines.subList(secStart, secEnd).clear();
            if (!ShimBuildConfig.FSR) {
                /* Orange: delete the section. Do not rewrite Upscaler/Fsr1 keys. */
            } else {
                lines.addAll(secStart, section);
            }
        } else if (ShimBuildConfig.FSR) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty())
                lines.add("");
            lines.addAll(section);
        } else {
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ini), StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                w.write(lines.get(i));
                w.write('\n');
            }
        }
    }

    static Object dataStoreGetRaw(Object store, String key) {
        if (store == null || key == null) return null;
        try {
            Object v = store.getClass()
                    .getMethod("getString", String.class, String.class)
                    .invoke(store, key, null);
            if (v != null) return v;
        } catch (Throwable ignored) { }
        try {
            Object v = store.getClass()
                    .getMethod("getFloat", String.class, float.class)
                    .invoke(store, key, Float.NaN);
            if (v instanceof Float && !((Float) v).isNaN()) return v;
        } catch (Throwable ignored) { }
        try {
            for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                if (!"getString".equals(m.getName()) && m.getName().length() != 1) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == String.class
                        && m.getReturnType() == String.class) {
                    return m.invoke(store, key, null);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static boolean dataStoreGetBoolean(Object store, String key, boolean def) {
        if (store == null) return def;
        try {
            return (Boolean) store.getClass()
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(store, key, def);
        } catch (Throwable ignored) { }
        try {
            for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == boolean.class && p.length == 2
                        && p[0] == String.class && p[1] == boolean.class) {
                    return (Boolean) m.invoke(store, key, def);
                }
            }
        } catch (Throwable ignored) { }
        return def;
    }

    static String readConfKey(File conf, String key) {
        if (!conf.isFile()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String raw = line.trim();
                if (raw.startsWith("#") || raw.isEmpty()) continue;
                int eq = raw.indexOf('=');
                if (eq <= 0) continue;
                String k = raw.substring(0, eq).trim();
                if (k.equals(key)) return raw.substring(eq + 1).trim();
            }
        } catch (IOException ignored) { }
        return null;
    }

    /* ---- in-process frame generation (lsfg_overlay) ------------------------
     * Ported from NetherSX2-fg. The old Vulkan-layer route is a DIFFERENT
     * feature under the keys "lsfg"/"framegen"; both must stay off or they
     * fight the in-process path for the same GS target. Note stripFramegenConf()
     * above only removes the old framegen_* keys, so lsfg_overlay/fg_osd
     * survive a cold start. */
    static final String LSFG_CONF_KEY = "lsfg";
    static final String LSFG_CONF_KEY_LEGACY = "framegen";

    /**
     * fg_osd is availability, not the user's six OSD row choices. Disabling FG
     * writes it off to avoid idle native hooks; enabling FG must undo that write.
     * Keeping the generated off value made Show FPS ineffective after every
     * off/on cycle and on a new Pink installation. Stat preferences still hide
     * all rows when requested; force/always remain explicit diagnostics.
     */
    static String writeFramegenMode(File conf, boolean enable) {
        String previous = readConfKey(conf, ShimFrameGen.OSD_KEY);
        boolean forced = "force".equalsIgnoreCase(previous)
                || "always".equalsIgnoreCase(previous);
        String osdMode = enable ? (forced ? previous : "on") : "off";
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(ShimFrameGen.CONF_KEY, enable ? "on" : "off");
        kv.put(ShimFrameGen.OSD_KEY, osdMode);
        kv.put(LSFG_CONF_KEY, "off");
        if (readConfKey(conf, LSFG_CONF_KEY_LEGACY) != null)
            kv.put(LSFG_CONF_KEY_LEGACY, "off");
        mergeConf(conf, kv);
        return osdMode;
    }

    /** Turn in-process frame generation on or off and write turnip.conf. */
    static boolean applyLsfgMode(Context ctx, boolean requestedEnable) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return false;
        if (!FramegenBuild.AVAILABLE) {
            stripFramegenFromConf(files);
            note(files, "framegen not available in this build");
            return false;
        }
        String displayReason = HandheldTier.framegenDisplayBlockReason(ctx);
        File conf = new File(files, "turnip.conf");
        if (displayReason != null
                && HandheldTier.framegenDisplayCapabilityBlockReason(ctx) == null) {
            // A capable panel temporarily set to 60 Hz suspends output, but
            // retains ON intent so returning to 120 Hz can resume it.
            writeFramegenMode(conf, requestedEnable);
            ShimFrameGen.refreshDisplayEligibility(ctx);
            return false;
        }
        final boolean enable = requestedEnable && displayReason == null;
        String val = enable ? "on" : "off";
        String osdMode = writeFramegenMode(conf, enable);
        if (displayReason != null) {
            // Clear stale ON state so the dimmed row, file and runtime agree.
            SharedPreferences sp = defaultPrefsOrNull(ctx);
            if (sp != null && !Boolean.FALSE.equals(sp.getAll().get("VulkanShim/Lsfg")))
                sp.edit().putBoolean("VulkanShim/Lsfg", false).commit();
            ShimFrameGen.disableOverlay(ctx);
            note(files, "framegen unavailable: " + displayReason);
            return false;
        }
        boolean haveDll = new File(new File(files, "lsfg"), "Lossless.dll").isFile();
        /* "MISSING" alone was a lie once the shaders existed: a validated cache
         * is all framegen needs, and reading MISSING next to a working 120 fps
         * sends the next reader looking for a file that is not the problem. */
        boolean haveShaders = ShimFrameGen.shaderCacheUsable(files);
        note(files, "framegen " + (enable ? "ON" : "OFF") + " ("
                + ShimFrameGen.CONF_KEY + "=" + val
                + ", fg_osd=" + osdMode
                + ", Lossless.dll " + (haveDll ? "present"
                        : haveShaders ? "absent (shaders already extracted)" : "MISSING")
                + ")");
        Context act = ctx instanceof android.app.Activity ? ctx
                : (ToggleWatchdog.foregroundActivity() != null
                        ? ToggleWatchdog.foregroundActivity() : ctx);
        // Default GS capture toggles live. The optional swapchain source may
        // need a restart to gain transfer usage; OFF always takes effect live.
        ShimRestartPrompt.askResetFramegen(enable);
        if (enable)
            ShimFrameGen.enableOverlay(act);
        else
            ShimFrameGen.disableOverlay(act);
        return true;
    }

    /** Force every framegen key off — used when the build has it compiled out. */
    static void stripFramegenFromConf(File files) {
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(ShimFrameGen.CONF_KEY, "off");
        kv.put(ShimFrameGen.OSD_KEY, "off");
        kv.put(LSFG_CONF_KEY, "off");
        kv.put(LSFG_CONF_KEY_LEGACY, "off");
        mergeConf(conf, kv);
        note(files, "framegen disabled (lsfg_overlay=off, fg_osd=off)");
    }

    /** Cold start: adopt lsfg_overlay from turnip.conf if it is set. */
    static void applyLsfgFromConf(Context ctx) {
        File files = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (files == null) return;
        if (!FramegenBuild.AVAILABLE) { stripFramegenFromConf(files); return; }
        File conf = new File(files, "turnip.conf");
        String key = readConfKey(conf, ShimFrameGen.CONF_KEY);
        if (key == null || key.isEmpty()) return;
        applyLsfgMode(ctx, key.equalsIgnoreCase("on") || key.equals("1")
                || key.equalsIgnoreCase("true"));
    }

    static void mergeConf(File conf, Map<String, String> updates) {
        List<String> lines = new ArrayList<>();
        if (conf.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            } catch (IOException ignored) { }
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.startsWith("#") || raw.isEmpty()) continue;
            int eq = raw.indexOf('=');
            if (eq <= 0) continue;
            String key = raw.substring(0, eq).trim();
            if (updates.containsKey(key)) {
                lines.set(i, key + "=" + updates.get(key));
                seen.add(key);
            }
        }
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!seen.contains(e.getKey()))
                lines.add(e.getKey() + "=" + e.getValue());
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(conf), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException e) {
            logIoFailure("turnip.conf merge", conf, e);
        }
    }

    private static void logIoFailure(String op, File file, IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        boolean eacces = msg.contains("EACCES") || msg.contains("Permission denied")
                || (e.getCause() != null && String.valueOf(e.getCause().getMessage())
                        .contains("Permission denied"));
        if (eacces) {
            android.util.Log.e("VulkanShim", op + " EACCES " + file.getAbsolutePath()
                    + " (copied file may need recreation by the app): " + msg);
        } else {
            android.util.Log.w("VulkanShim", op + " failed " + file.getName() + ": " + msg);
        }
    }
}
