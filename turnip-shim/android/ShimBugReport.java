package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes everything needed to diagnose a fault on a device nobody has in hand.
 *
 * <p>The logs already exist, under {@code Android/data/<pkg>/files}, which on
 * Android 11+ no file manager can open: only adb. So a user reporting a bug
 * could describe it and nothing more, and faults that only reproduce on their
 * hardware (a rotated frame on a portrait-native panel, a driver that will not
 * load) could not be chased at all.
 *
 * <p>Lands in Downloads via MediaStore, which needs no permission on Android
 * 10+ and is somewhere the user can actually reach to attach it.
 */
public final class ShimBugReport {

    private static final String TAG = "VulkanShim";
    static final String PREF_KEY = "VulkanShim/BugReport";

    private ShimBugReport() {
    }

    public static void export(final Activity a) {
        if (a == null || a.isFinishing()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String name = write(a);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(a)
                                    .setTitle("Bug report saved")
                                    .setMessage("Written to Downloads as\n" + name
                                            + "\n\nIt contains the shim logs, your "
                                            + "turnip.conf, and what this device "
                                            + "reports about its GPU, driver and "
                                            + "display. No game data and nothing "
                                            + "personal.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "bug report: " + t);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            PrefCompat.toast(a, "Could not write the report");
                        }
                    });
                }
            }
        }, "shim-bugreport").start();
    }

    private static String write(Activity a) throws Exception {
        File files = a.getExternalFilesDir(null);
        String name = "nethersx2-bugreport-" + Build.MODEL.replaceAll("[^A-Za-z0-9]", "")
                + "-" + android.os.SystemClock.elapsedRealtime() + ".zip";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        if (Build.VERSION.SDK_INT >= 29)
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
        Uri uri = a.getContentResolver()
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("no Downloads uri");

        OutputStream os = a.getContentResolver().openOutputStream(uri);
        ZipOutputStream z = new ZipOutputStream(os);
        try {
            put(z, "device.txt", describe(a).getBytes("UTF-8"));
            if (files != null) {
                addFile(z, new File(files, "shim_java.log"));
                addFile(z, new File(files, "vulkan_shim.log"));
                addFile(z, new File(files, "turnip.conf"));
                addFile(z, new File(files, "user_ir.txt"));
            }
        } finally {
            z.close();
        }
        return name;
    }

    /* THE FIELDS THAT ACTUALLY DECIDE CROSS-DEVICE BUGS.
     *
     * Panel size and the app's own rotation are here because a frame that comes
     * out rotated 90 degrees on one handheld and upright on another is a surface
     * pre-transform difference, and there is no way to tell from a screenshot
     * which of the two it is. The driver string separates a Turnip build from
     * the stock Adreno blob, which changes what is even worth suspecting. */
    private static String describe(Activity a) {
        StringBuilder b = new StringBuilder(1024);
        b.append("model=").append(Build.MODEL).append('\n');
        b.append("device=").append(Build.DEVICE).append('\n');
        b.append("board=").append(Build.BOARD).append('\n');
        b.append("soc=").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "?").append('\n');
        b.append("android=").append(Build.VERSION.RELEASE)
         .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        try {
            android.content.pm.PackageInfo pi = a.getPackageManager()
                    .getPackageInfo(a.getPackageName(), 0);
            b.append("app=").append(pi.versionName).append(" code=").append(pi.versionCode).append('\n');
        } catch (Throwable ignored) {
        }
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            b.append("panel=").append(p.x).append('x').append(p.y).append('\n');
            b.append("refresh=").append(d.getRefreshRate()).append('\n');
            b.append("rotation=").append(d.getRotation())
             .append("  (0=0deg 1=90 2=180 3=270)\n");
        } catch (Throwable ignored) {
        }
        try {
            android.view.View decor = a.getWindow().getDecorView();
            b.append("decor=").append(decor.getWidth()).append('x')
             .append(decor.getHeight()).append('\n');
        } catch (Throwable ignored) {
        }
        b.append("driver_conf=").append(String.valueOf(ShimDriverPicker.current(a))).append('\n');
        b.append("nativeLibDir=").append(a.getApplicationInfo().nativeLibraryDir).append('\n');
        try {
            File lsfg = new File(a.getExternalFilesDir(null), "lsfg");
            b.append("lossless_dll=").append(new File(lsfg, "Lossless.dll").isFile()).append('\n');
            File sh = new File(lsfg, "shaders");
            String[] l = sh.list();
            b.append("shader_cache=").append(l == null ? 0 : l.length).append(" files\n");
        } catch (Throwable ignored) {
        }
        return b.toString();
    }

    private static void addFile(ZipOutputStream z, File f) {
        if (f == null || !f.isFile()) return;
        try {
            InputStream in = new FileInputStream(f);
            try {
                z.putNextEntry(new ZipEntry(f.getName()));
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                z.closeEntry();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "bug report: " + f.getName() + ": " + t);
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}
