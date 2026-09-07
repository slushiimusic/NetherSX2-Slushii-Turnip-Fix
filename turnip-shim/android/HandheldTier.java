package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * Low-end handheld detection and user-controlled GS performance profile.
 *
 * <p>The Graphics toggle {@link #PREF_KEY} applies lighter settings on SD865-class
 * and similar handhelds. OFF restores the quality profile (High blending, Full preload).
 */
public final class HandheldTier {

    private static final String TAG = "VulkanShim";
    static final String PREF_KEY = "VulkanShim/LowEndPerf";
    private static final String PREF_SEEDED = "VulkanShim/LowEndPerfSeeded";
    /** Bump when ON-profile GS keys change — reapplies once after update. */
    static final String PREF_PROFILE_VER = "VulkanShim/LowEndPerfProfileVer";
    private static final int PROFILE_VER = 3;
    static final int LOW_END_PROFILE_VER = PROFILE_VER;

    private HandheldTier() {
    }

    private static final int UNKNOWN = 0, LOW = 1, HIGH = 2;
    /** First Snapdragon that keeps the quality profile: SM8450 = 8 Gen 1. */
    private static final int FIRST_HIGH_END_SM = 8450;
    /** Adreno 660 = SD 888, the newest part that still gets the low profile. */
    private static final int HIGHEST_LOW_ADRENO = 660;
    /** Adreno 730 = 8 Gen 1. */
    private static final int LOWEST_HIGH_ADRENO = 730;
    private static final java.util.regex.Pattern SM_PAT = java.util.regex.Pattern
            .compile("(?i)\\b(SM|QCS|QCM|SDM|MSM|APQ)[ _-]?(\\d{3,4})\\b");
    private static volatile int cachedTier = -1;

    /**
     * Low-end is everything at or below SD 888 / Adreno 660; 8 Gen 1 and newer
     * keep the quality profile.
     *
     * <p>The Snapdragon model number is parsed NUMERICALLY instead of matched
     * against a table of names, because Qualcomm's numbering already orders the
     * parts: SM8250 (865) &lt; SM8350 (888) &lt; SM8450 (8 Gen 1). A chip that does
     * not exist yet therefore classifies correctly with no code change, which a
     * name list can never do. Series 4/6/7 are below the bar at every generation,
     * and SDM/MSM/APQ are all pre-2019 parts, so both are low without a number
     * comparison.
     *
     * <p>Marketing codenames (kona, lahaina, taro) carry no digits at all, so
     * they fall through to the Adreno number from the KGSL node, which is exact
     * where it is readable.
     */
    public static boolean isLowEndHandheld() {
        if (cachedTier == -1) cachedTier = classifyTier();
        return cachedTier == LOW;
    }

    private static int classifyTier() {
        String[] cands = {
            readProp("ro.soc.model"), buildSocModel(), readProp("ro.board.platform"),
            readProp("ro.hardware.chipname"), readProp("ro.chipname"),
            android.os.Build.BOARD, android.os.Build.HARDWARE,
        };
        int bySoc = tierFromSocModel(cands);
        int adreno = parseAdrenoGpuNum(readGpuModel());
        int byGpu = tierFromAdreno(adreno);
        int v = bySoc != UNKNOWN ? bySoc : byGpu;
        Log.i(TAG, "tier: " + (v == LOW ? "LOW" : v == HIGH ? "HIGH" : "UNKNOWN")
                + " (soc=" + firstNonEmpty(cands) + " adreno=" + adreno
                + " bySoc=" + bySoc + " byGpu=" + byGpu + ")");
        /* Unknown stays on the quality profile. Guessing low costs picture
         * quality on a flagship whose properties we simply could not read, and
         * the seed is written once, so a wrong guess is sticky. The evidence is
         * logged above and lands in the bug report, which is how an unreadable
         * device gets classified properly later. */
        return v;
    }

    /** LOW/HIGH from a Qualcomm part number, UNKNOWN if no candidate carries one. */
    private static int tierFromSocModel(String[] cands) {
        for (String s : cands) {
            if (s == null || s.isEmpty()) continue;
            java.util.regex.Matcher m = SM_PAT.matcher(s);
            while (m.find()) {
                String fam = m.group(1).toUpperCase(java.util.Locale.US);
                /* QCS8550 (Nova) / QCM8550 share the 8 Gen 2 GPU.
                 * Other IoT part numbers are not performance ordered. */
                if ("QCS".equals(fam) || "QCM".equals(fam)) {
                    if ("8550".equals(m.group(2))) return HIGH;
                    continue;
                }
                if (!"SM".equals(fam)) return LOW;      /* SDM/MSM/APQ: all pre-2019 */
                String digits = m.group(2);
                if (digits.length() != 4) continue;
                try {
                    return Integer.parseInt(digits) >= FIRST_HIGH_END_SM ? HIGH : LOW;
                } catch (NumberFormatException ignored) { }
            }
        }
        return UNKNOWN;
    }

    /**
     * The 7xx Adreno block is NOT ordered by performance: Adreno 702 is an entry
     * part and 730 is 8 Gen 1, so anything between 660 and 730 is genuinely
     * undecidable from the number alone and defers to the SoC.
     */
    private static int tierFromAdreno(int n) {
        if (n <= 0) return UNKNOWN;
        if (n <= HIGHEST_LOW_ADRENO) return LOW;
        if (n >= LOWEST_HIGH_ADRENO) return HIGH;
        /* 702 is the exception worth naming: it is an entry part (SD 680-class)
         * whose platform string is a codename with no digits, so without this it
         * would keep the quality profile on hardware that plainly cannot hold it.
         * 703-729 stay undecidable rather than guessed. */
        if (n == 702) return LOW;
        return UNKNOWN;
    }

    /** Accept normal fractional timing around the 120 Hz panel mode. */
    static boolean is120Hz(float hz) {
        return Math.abs(hz - 120.0f) < 1.0f;
    }

    /** Runtime eligibility follows the active physical mode, including a user's 60 Hz choice. */
    static String framegenDisplayBlockReason(Context ctx) {
        try {
            android.view.WindowManager wm = ctx == null ? null :
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.view.Display d = wm == null ? null : wm.getDefaultDisplay();
            // getRefreshRate can reflect a per-app FPS override on Android 13.
            if (d != null && is120Hz(d.getMode().getRefreshRate())) return null;
        } catch (Throwable ignored) { }
        String capability = framegenDisplayCapabilityBlockReason(ctx);
        return capability == null ? "Set display refresh rate to 120 Hz" : capability;
    }

    /** Hardware capability is separate from a temporary lower-refresh selection. */
    static String framegenDisplayCapabilityBlockReason(Context ctx) {
        try {
            android.view.WindowManager wm = ctx == null ? null :
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.view.Display d = wm == null ? null : wm.getDefaultDisplay();
            if (d != null) {
                if (is120Hz(d.getRefreshRate())) return null;
                for (android.view.Display.Mode m : d.getSupportedModes())
                    if (is120Hz(m.getRefreshRate())) return null;
            }
        } catch (Throwable ignored) { }
        return "Requires a 120 Hz display";
    }

    /** Display eligibility also applies to old settings and diagnostic overrides. */
    public static String framegenBlockReason(Context ctx) {
        String displayReason = framegenDisplayBlockReason(ctx);
        if (displayReason != null) return displayReason;
        return framegenHardwareBlockReason(ctx);
    }

    static String framegenHardwareBlockReason(Context ctx) {
        String displayReason = framegenDisplayCapabilityBlockReason(ctx);
        if (displayReason != null) return displayReason;
        try {
            if (confForce(ctx)) return null;
            int adreno = parseAdrenoGpuNum(readGpuModel());
            if (adreno == 0 && isLowEndHandheld())
                return "Frame generation is unavailable on this chipset";
            if (adreno > 0 && adreno < 700)
                return "Not supported on Adreno " + adreno + " — needs a 700-series GPU";
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean confForce(Context ctx) {
        try {
            File dir = ctx == null ? null : ctx.getExternalFilesDir(null);
            String v = dir == null ? null
                    : TurnipConfig.readConfKey(new File(dir, "turnip.conf"), "fg_force");
            return v != null && (v.equalsIgnoreCase("on") || v.equals("1")
                    || v.equalsIgnoreCase("true"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Highest refresh the panel can actually run, not the current mode. */
    private static int panelHz(Context ctx) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return 0;
            android.view.Display d = wm.getDefaultDisplay();
            if (d == null) return 0;
            float hz = d.getRefreshRate();
            try {
                for (android.view.Display.Mode m : d.getSupportedModes())
                    if (m.getRefreshRate() > hz) hz = m.getRefreshRate();
            } catch (Throwable ignored) { }
            return Math.round(hz);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Build.SOC_MODEL is API 31+; reflected so the shim still builds older. */
    private static String buildSocModel() {
        try {
            Object v = android.os.Build.class.getField("SOC_MODEL").get(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstNonEmpty(String[] xs) {
        for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    /**
     * First launch only: seed the toggle ON for detected low-end handhelds,
     * OFF everywhere else. Does not overwrite an existing user choice.
     *
     * @return true when this call freshly seeded (caller may apply profile once)
     */
    public static boolean seedDefaultIfNeeded(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences prefs = TurnipConfig.defaultPrefsOrNull(ctx);
        if (prefs == null || prefs.getBoolean(PREF_SEEDED, false)) return false;
        boolean def = isLowEndHandheld();
        prefs.edit()
                .putBoolean(PREF_KEY, def)
                .putBoolean(PREF_SEEDED, true)
                .commit();
        Log.i(TAG, "LowEndPerf seeded default=" + def
                + " (soc=" + readProp("ro.soc.model") + ")");
        return true;
    }

    /**
     * SD865-class devices must not ship with fg_osd=on: it installs present-path
     * hooks even when lsfg_overlay=off and tanked FPS on RP Mini V2.
     * In-process LSFG on capture-sized ImageReader must use CPU blit (patched lib).
     * Call before System.loadLibrary("vulkad").
     */
    public static void ensureTurnipConfBeforeNativeLoad(Context ctx) {
        if (ctx == null || !isLowEndHandheld()) return;
        File files = ctx.getExternalFilesDir(null);
        if (files == null) return;
        File conf = new File(files, "turnip.conf");
        java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
        String overlay = TurnipConfig.readConfKey(conf, ShimFrameGen.CONF_KEY);
        boolean fgOn = overlay != null && (overlay.equalsIgnoreCase("on")
                || overlay.equals("1") || overlay.equalsIgnoreCase("true"));
        kv.put("fg_osd", fgOn ? "on" : "off");
        if (!fgOn) {
            if (overlay == null || overlay.equalsIgnoreCase("off")
                    || overlay.equals("0") || overlay.equalsIgnoreCase("false"))
                kv.put(ShimFrameGen.CONF_KEY, "off");
        }
        kv.put("present_mode", "mailbox");
        kv.put("min_image_count", "4");
        kv.put("cadence_lock", "off");
        if (TurnipConfig.readConfKey(conf, "display_hz") == null) {
            int hz = panelHz(ctx);
            if (hz > 0) kv.put("display_hz", Integer.toString(hz));
        }
        TurnipConfig.mergeConf(conf, kv);
        Log.i(TAG, "SD865 turnip.conf: fg_osd=off mailbox cadence_lock=off");
    }

    /** Re-apply aggressive GS keys after a profile bump (no IR change). */
    public static void reapplyProfileIfStale(Context ctx, SharedPreferences prefs) {
        if (ctx == null || prefs == null || !prefs.getBoolean(PREF_KEY, false)) return;
        if (prefs.getInt(PREF_PROFILE_VER, 0) >= PROFILE_VER) return;
        applyProfile(ctx, true);
        prefs.edit().putInt(PREF_PROFILE_VER, PROFILE_VER).commit();
        Log.i(TAG, "LowEndPerf profile v" + PROFILE_VER + " reapplied");
    }

    /** Write perf or quality GS prefs to SP + ini layers. */
    public static void applyProfile(Context ctx, boolean enabled) {
        if (ctx == null) return;
        File files = ctx.getExternalFilesDir(null);
        Log.i(TAG, "LowEndPerf profile " + (enabled ? "ON (perf)" : "OFF (quality)"));

        if (enabled) {
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/accurate_blending_unit", "1");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/texture_preloading", "1");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/MaxAnisotropy", "0");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/HWDownloadMode", "1");
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowGPU", false);
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowFrameTimes", false);
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowCPU", false);
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowResolution", false);
        } else {
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/accurate_blending_unit", "3");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/texture_preloading", "2");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/MaxAnisotropy", "0");
            TurnipConfig.applyGsListPrefGlobal(ctx, files,
                    "EmuCore/GS/HWDownloadMode", "0");
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowGPU", true);
            TurnipConfig.applyBoolPrefGlobal(ctx, files,
                    "EmuCore/GS/OsdShowFrameTimes", true);
        }
    }

    private static String readGpuModel() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"));
            String line = br.readLine();
            br.close();
            return line != null ? line.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int parseAdrenoGpuNum(String gpuName) {
        if (gpuName == null || gpuName.isEmpty()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(?:Adreno|adreno)\\s*(\\d{3})")
                .matcher(gpuName);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private static boolean matchesSoc(String soc, String plat, String chip,
            String model, String platform) {
        if (model != null && soc != null && soc.contains(model)) return true;
        if (platform != null && plat != null && plat.equalsIgnoreCase(platform)) return true;
        if (model != null && chip != null
                && chip.toLowerCase().contains(model.toLowerCase()))
            return true;
        return false;
    }

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }
}
