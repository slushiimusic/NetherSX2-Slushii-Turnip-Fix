package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lets the user choose which bundled Turnip build the shim loads, and fetch
 * more from the driver repo.
 *
 * <p>Before this the only way to change driver was to hand-edit {@code driver=}
 * in turnip.conf, which on Android 11+ no file manager can reach: the file
 * lives under {@code Android/data/<pkg>} and only adb can get to it. The
 * drivers were shipped and unreachable.
 *
 * <h3>Why a switch and not a list</h3>
 * The preference rows are injected by cloning an existing SwitchPreference out
 * of the binary XML. A ListPreference would need its entries and entryValues as
 * resource arrays in resources.arsc, which the injector cannot add. So the row
 * is an honest boolean: ON means "a driver is chosen" and opens the picker, OFF
 * removes {@code driver=} and returns to the build default.
 */
public final class ShimDriverPicker {

    private static final String TAG = "VulkanShim";
    static final String PREF_KEY = "VulkanShim/Driver";
    private static final String CONF_KEY = "driver";
    /** Where downloaded/imported drivers land; turnip.conf takes absolute paths. */
    private static final String USER_DIR = "drivers";
    private static final String RELEASES =
            "https://api.github.com/repos/Vauzi-17/710/releases?per_page=10";
    /** A Turnip .so is tens of MB; anything tiny is not one. */
    private static final long MIN_PLAUSIBLE_BYTES = 1024 * 1024;

    private ShimDriverPicker() {
    }

    private static File confOf(Activity a) {
        File files = a.getExternalFilesDir(null);
        return files == null ? null : new File(files, "turnip.conf");
    }

    private static File userDirOf(Activity a) {
        File files = a.getExternalFilesDir(null);
        return files == null ? null : new File(files, USER_DIR);
    }

    /**
     * Every driver we can load: the ones baked into the apk plus anything the
     * user has fetched. Bundled ones are named bare (the shim looks them up in
     * the native lib dir); fetched ones are absolute paths.
     */
    static Map<String, String> available(Activity a) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            String libDir = a.getApplicationInfo().nativeLibraryDir;
            File[] libs = libDir == null ? null : new File(libDir).listFiles();
            if (libs != null) {
                for (File f : libs) {
                    String n = f.getName();
                    if (n.startsWith("libvulkan_freedreno") && n.endsWith(".so"))
                        out.put(pretty(n), n);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "driver picker: bundled scan: " + t);
        }
        try {
            File ud = userDirOf(a);
            File[] mine = ud == null ? null : ud.listFiles();
            if (mine != null) {
                for (File f : mine) {
                    if (f.getName().endsWith(".so") && f.length() > MIN_PLAUSIBLE_BYTES)
                        out.put(pretty(f.getName()) + " (downloaded)", f.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "driver picker: user scan: " + t);
        }
        return out;
    }

    /** Strip the fixed prefix/suffix so the dialog reads as build names. */
    private static String pretty(String soName) {
        String s = soName;
        if (s.startsWith("libvulkan_freedreno_")) s = s.substring(20);
        else if (s.startsWith("libvulkan_freedreno")) s = s.substring(19);
        if (s.endsWith(".so")) s = s.substring(0, s.length() - 3);
        if (s.startsWith("_")) s = s.substring(1);
        return s.isEmpty() ? "default" : s;
    }

    /** What driver= currently says, or null for the build default. */
    static String current(Activity a) {
        File conf = confOf(a);
        if (conf == null) return null;
        String v = TurnipConfig.readConfKey(conf, CONF_KEY);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** Human name for whatever is selected right now. */
    static String currentLabel(Activity a) {
        String cur = current(a);
        if (cur == null) return defaultAutoLabel();
        String base = cur.contains("/") ? cur.substring(cur.lastIndexOf('/') + 1) : cur;
        return pretty(base);
    }

    /** Label for the build-default entry in the picker dialog. */
    static String defaultAutoLabel() {
        String soc = readProp("ro.soc.model");
        String plat = readProp("ro.board.platform");
        String chip = readProp("ro.hardware.chipname");
        if (matchesSoc(soc, plat, chip, "SM6125", "trinket"))
            return "Default (T19, SD665)";
        if (matchesSoc(soc, plat, chip, "SM8250", "kona") ||
            matchesSoc(soc, plat, chip, "SM7325", null))
            return "Default (a6xx-Patched, SD865 class)";
        if (matchesSoc(soc, plat, chip, "SM8350", "lahaina") ||
            matchesSoc(soc, plat, chip, "napali", "sdm845"))
            return "Default (a6xx-Patched, SD888/845 class)";
        if (matchesSoc(soc, plat, chip, "SM6475", null))
            return "Default (V710/722 or Gmem)";
        if (matchesSoc(soc, plat, chip, "SM7125", "sdm720"))
            return "Default (T19 / a6xx, SD720G)";
        if (matchesSoc(soc, plat, chip, "SM8450", "taro"))
            return "Default (gen8-V31, SD8 Gen 1)";
        if (contains(soc, "8550") || matchesSoc(soc, plat, chip, "SM8635", "kalama"))
            return "Default (gen8-V31 + gmem, SD8 Gen 2)";
        if (matchesSoc(soc, plat, chip, "SM8650", null))
            return "Default (gen8-V31 + gmem, SD8 Gen 3)";
        if (matchesSoc(soc, plat, chip, "SM8750", "pineapple"))
            return "Default (gen8-V31, SD8 Elite)";
        if (matchesSoc(soc, plat, chip, "SM8850", "sun"))
            return "Default (gen8-V31, SD8 Elite Gen 2)";
        if (matchesSoc(soc, plat, chip, "SM7635", null) ||
            matchesSoc(soc, plat, chip, "SM6650", null))
            return "Default (T24, Adreno 810 class)";
        return "Default (automatic)";
    }

    private static boolean contains(String hay, String needle) {
        return hay != null && needle != null && hay.contains(needle);
    }

    private static boolean matchesSoc(String soc, String plat, String chip,
            String model, String platform) {
        if (model != null && soc != null && soc.contains(model)) return true;
        if (platform != null && plat != null && plat.equalsIgnoreCase(platform)) return true;
        if (model != null && chip != null && chip.toLowerCase().contains(model.toLowerCase()))
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

    /**
     * Open the picker.
     *
     * <p>THE ROW IS A BUTTON, NOT A STATE. It was written as ON = choose, OFF =
     * revert, which meant that once a driver was chosen the row sat checked and
     * there was no way to pick a DIFFERENT one without first unchecking it and
     * losing the selection. Reported as "it doesn't let you uncheck to select a
     * new one". Now either edge opens the picker and the list carries its own
     * Default entry, so every destination is one tap away and the checkbox
     * state means nothing.
     */
    public static void onToggled(final Activity a) {
        if (a == null || a.isFinishing()) return;
        final Map<String, String> avail = available(a);
        final List<String> labels = new ArrayList<String>();
        final List<String> values = new ArrayList<String>();
        final String cur = current(a);
        /* Default first and always present, so "put it back" needs no unchecking. */
        labels.add((cur == null ? "\u2713 " : "") + defaultAutoLabel());
        values.add("");
        for (Map.Entry<String, String> e : avail.entrySet()) {
            boolean active = cur != null && cur.equals(e.getValue());
            labels.add((active ? "\u2713 " : "") + e.getKey());
            values.add(e.getValue());
        }
        labels.add("Download more builds\u2026");
        values.add(null);
        final String[] items = labels.toArray(new String[0]);
        try {
            new AlertDialog.Builder(a)
                    /* SAY WHAT IS RUNNING. There was no way to tell which driver
                     * was in use, so a download that silently failed looked
                     * identical to one that worked. */
                    .setTitle("Turnip driver: " + currentLabel(a))
                    .setItems(items, (d, which) -> {
                        String v = values.get(which);
                        if (v == null) fetchList(a);
                        else if (v.isEmpty()) revertToDefault(a);
                        else select(a, items[which].replace("\u2713 ", ""), v);
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "driver picker: " + t);
        }
    }

    private static void revertToDefault(Activity a) {
        File conf = confOf(a);
        if (conf == null) return;
        Map<String, String> kv = new LinkedHashMap<String, String>();
        kv.put(CONF_KEY, "");
        TurnipConfig.mergeConf(conf, kv);
        TurnipConfig.note(a.getExternalFilesDir(null),
                "vulkan driver: back to the build default");
        restartNote(a, "the build default");
    }

    private static void select(Activity a, String label, String value) {
        File conf = confOf(a);
        if (conf == null) return;
        Map<String, String> kv = new LinkedHashMap<String, String>();
        kv.put(CONF_KEY, value);
        TurnipConfig.mergeConf(conf, kv);
        TurnipConfig.note(a.getExternalFilesDir(null), "vulkan driver -> " + value);
        restartNote(a, label);
    }

    /* THE DRIVER IS CHOSEN AT INSTANCE CREATION, SO NOTHING CAN APPLY IT LIVE.
     * Say so rather than letting the user wonder why nothing changed. */
    private static void restartNote(Activity a, String label) {
        try {
            new AlertDialog.Builder(a)
                    .setTitle("Driver set to " + label)
                    .setMessage("Fully close and reopen the app for this to take "
                            + "effect. The driver is chosen when Vulkan starts, so "
                            + "it cannot be swapped while a game is running.")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /* ---- fetching ---------------------------------------------------- */

    private static void fetchList(final Activity a) {
        toast(a, "Checking for driver builds…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String json = httpGet(RELEASES);
                    final List<String> names = new ArrayList<String>();
                    final List<String> urls = new ArrayList<String>();
                    parseAssets(json, names, urls);
                    if (names.isEmpty()) {
                        post(a, () -> toast(a, "No driver downloads found"));
                        return;
                    }
                    post(a, () -> {
                        try {
                            new AlertDialog.Builder(a)
                                    .setTitle("Download a driver")
                                    .setItems(names.toArray(new String[0]),
                                            (d, w) -> download(a, names.get(w), urls.get(w)))
                                    .setNegativeButton("CANCEL", null)
                                    .show();
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "driver fetch: " + t);
                    post(a, () -> toast(a, "Could not reach the driver repo"));
                }
            }
        }, "shim-driver-list").start();
    }

    /* Deliberately hand-rolled rather than org.json: this class is compiled into
     * a shim injected next to an R8-obfuscated app, and the fewer framework
     * classes it touches by name the fewer ways it breaks. Only two fields are
     * needed and both are flat strings. */
    private static void parseAssets(String json, List<String> names, List<String> urls) {
        int i = 0;
        while (true) {
            int u = json.indexOf("\"browser_download_url\":\"", i);
            if (u < 0) break;
            int s = u + 24;
            int e = json.indexOf('"', s);
            if (e < 0) break;
            String url = json.substring(s, e).replace("\\/", "/");
            if (url.endsWith(".zip") || url.endsWith(".so")) {
                names.add(url.substring(url.lastIndexOf('/') + 1));
                urls.add(url);
            }
            i = e;
        }
    }

    private static void download(final Activity a, final String name, final String url) {
        toast(a, "Downloading " + name + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                File dst = null;
                try {
                    File dir = userDirOf(a);
                    if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                        post(a, () -> toast(a, "No storage for drivers"));
                        return;
                    }
                    byte[] so = url.endsWith(".so") ? httpBytes(url) : soFromZip(httpBytes(url));
                    if (so == null || so.length < MIN_PLAUSIBLE_BYTES) {
                        post(a, () -> toast(a, "That download has no driver in it"));
                        return;
                    }
                    /* ELF magic. A wrong file that lands under a .so name would
                     * be offered in the picker forever and fail at dlopen with
                     * nothing on screen to explain it. */
                    if (!(so[0] == 0x7f && so[1] == 'E' && so[2] == 'L' && so[3] == 'F')) {
                        post(a, () -> toast(a, "That file is not a driver"));
                        return;
                    }
                    String base = name.endsWith(".zip")
                            ? name.substring(0, name.length() - 4) : name;
                    dst = new File(dir, "libvulkan_freedreno_" + safe(base) + ".so");
                    File part = new File(dst.getAbsolutePath() + ".part");
                    OutputStream os = new FileOutputStream(part);
                    try { os.write(so); } finally { os.close(); }
                    if (dst.exists()) dst.delete();
                    if (!part.renameTo(dst)) { part.delete(); throw new Exception("rename"); }
                    final File done = dst;
                    TurnipConfig.note(a.getExternalFilesDir(null),
                            "downloaded driver " + done.getName() + " (" + so.length + " bytes)");
                    post(a, () -> select(a, pretty(done.getName()) + " (downloaded)",
                            done.getAbsolutePath()));
                } catch (Throwable t) {
                    Log.w(TAG, "driver download: " + t);
                    post(a, () -> toast(a, "Download failed"));
                }
            }
        }, "shim-driver-get").start();
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static byte[] soFromZip(byte[] zip) throws Exception {
        if (zip == null) return null;
        ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.endsWith("libvulkan_freedreno.so") || n.endsWith(".so")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = zis.read(buf)) > 0) bo.write(buf, 0, r);
                    return bo.toByteArray();
                }
            }
        } finally {
            zis.close();
        }
        return null;
    }

    private static String httpGet(String url) throws Exception {
        byte[] b = httpBytes(url);
        return b == null ? "" : new String(b, "UTF-8");
    }

    private static byte[] httpBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "NetherSX2-Slushii");
            c.setRequestProperty("Accept", "application/octet-stream, application/json");
            if (c.getResponseCode() / 100 != 2) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void post(Activity a, Runnable r) {
        try { a.runOnUiThread(r); } catch (Throwable ignored) { }
    }

    private static void toast(Activity a, String m) {
        try { PrefCompat.toast(a, m); } catch (Throwable ignored) { }
    }
}
