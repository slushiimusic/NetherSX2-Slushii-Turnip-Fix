package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports the user's own Lossless.dll through the system document picker.
 *
 * <p>Frame generation needs the shaders out of Lossless Scaling's Lossless.dll,
 * which cannot be shipped. It has to live at
 * {@code <externalFiles>/lsfg/Lossless.dll} — and on Android 11+ the user cannot
 * put a file there with a normal file manager, because
 * {@code Android/data/<pkg>} is off limits to everything but the app itself.
 * Before this, the only way in was adb. Hence a picker.
 *
 * <h3>Why a platform Fragment</h3>
 * The picker is {@code ACTION_OPEN_DOCUMENT}: this app holds NO storage
 * permission at all (only INTERNET and VIBRATE — it reaches ROMs through SAF
 * too), so a File-based browser of our own could not read the card. SAF returns
 * its answer through {@code onActivityResult}, which we cannot add to the app's
 * own activities.
 *
 * <p>A headless fragment can receive that result itself. It is the PLATFORM
 * {@code android.app.Fragment} rather than the androidx one deliberately: this
 * APK is R8-obfuscated and ships androidx's Fragment as {@code
 * androidx.fragment.app.z}, so it cannot be subclassed by name — while
 * {@code getFragmentManager()} returns a real {@code
 * android.app.FragmentManagerImpl}. Deprecated, and correct here.
 */
public final class ShimDllImport {

    private static final String TAG = "VulkanShim";
    private static final String FRAG_TAG = "shim_dll_picker";
    private static final int REQ_PICK_DLL = 0x1053;
    /** Real Lossless.dll is ~8 MB; anything tiny is the wrong file. */
    private static final long MIN_PLAUSIBLE_BYTES = 256 * 1024;

    /** One offer per process — the prompt must not nag on every poll. */
    private static boolean offered;

    private ShimDllImport() {
    }

    static File dllFile(Activity a) {
        File files = a.getExternalFilesDir(null);
        return files == null ? null : new File(new File(files, "lsfg"), "Lossless.dll");
    }

    /**
     * The private second copy, kept in internal storage.
     *
     * <p>{@code Android/data/<pkg>} is where the dll has to land — it is the only
     * directory the picker's destination can be — but it is not a place the app
     * controls: a reinstall, a storage cleaner or a restore can remove the file
     * without the app knowing. Internal storage is invisible to all of them, so
     * one extra copy at import time means "I already gave you Lossless.dll"
     * stays true.
     */
    static File internalDll(Context c) {
        File files = c == null ? null : c.getFilesDir();
        return files == null ? null : new File(new File(files, "lsfg"), "Lossless.dll");
    }

    /** The dll wherever it survives, external copy first, or null if it is gone. */
    static File locateDll(Context c) {
        try {
            File ext = c instanceof Activity ? dllFile((Activity) c) : null;
            if (ext == null) {
                File files = c.getExternalFilesDir(null);
                ext = files == null ? null : new File(new File(files, "lsfg"), "Lossless.dll");
            }
            if (ext != null && ext.isFile()) return ext;
            File keep = internalDll(c);
            return keep != null && keep.isFile() ? keep : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when nothing on disk can give frame generation its shaders. */
    static boolean dllMissing(Activity a) {
        try {
            File files = a.getExternalFilesDir(null);
            if (files == null) return false;
            /* NOT gated on lsfg_overlay any more.
             *
             * The offer used to require frame generation to be ON already, which
             * on a fresh install is never true: turnip.conf does not exist yet,
             * so the key reads null and the offer was skipped. The app therefore
             * never once asked for the file it cannot work without, and no entry
             * point existed to supply it. Offering whenever the dll is absent is
             * the point of the prompt. */

            /* THE SHADER CACHE COUNTS AS HAVING SUPPLIED THE DLL.
             *
             * The dll is only a source of shaders; once they are extracted and
             * the driver has accepted them, frame generation never reads it
             * again. Asking a user who has already imported their dll to import
             * it a second time because the file itself went missing is the nag
             * this check exists to prevent. */
            if (ShimFrameGen.shaderCacheUsable(files)) return false;
            return locateDll(a) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Offers the import once per process, when framegen is on and the dll is
     * absent. Safe to call from the watchdog poll.
     */
    /** Set by ShimFrameGen.prepare() when it cannot find the dll. */
    private static volatile boolean reportedMissing;

    public static void noteMissing() {
        reportedMissing = true;
    }

    /** The dialog we last put up, so a lost one can be noticed and re-offered. */
    private static java.lang.ref.WeakReference<android.app.Dialog> shown;
    /** Set when the user presses NOT NOW. An explicit no is final. */
    private static volatile boolean declined;
    /** Set by EITHER button. Any decision ends the re-offer, see maybeOffer. */
    private static volatile boolean acted;
    private static int offerAttempts;
    private static long lastOfferMs;

    /**
     * Why frame generation cannot work here; "" for no reason, null until asked.
     * Cached per process: maybeOffer runs off the watchdog poll AND off every
     * layout pass, and the answer is a panel plus a GPU, neither of which changes
     * under a running process.
     */
    private static volatile String fgBlocked;

    /**
     * True when frame generation can never run here, so there is nothing to import
     * a dll FOR. Same test that greys the Frame Generation row out, so the prompt
     * and the row now agree: asking a 60 Hz owner for a file they would have to buy
     * a Windows program to obtain, which this build then refuses to use, is a worse
     * first run than never mentioning the feature. fg_force=on clears it.
     */
    private static boolean framegenImpossible(Context c) {
        String why = fgBlocked;
        if (why == null) {
            try {
                why = HandheldTier.framegenHardwareBlockReason(c);
            } catch (Throwable ignored) {
                why = null;
            }
            if (why == null) why = "";
            fgBlocked = why;
            if (!why.isEmpty())
                Log.i(TAG, "dll import: never offering it here - " + why);
        }
        return !why.isEmpty();
    }

    static void maybeOffer(Activity a) {
        if (!FramegenBuild.AVAILABLE) return;
        if (a == null || a.isFinishing()) return;
        if (framegenImpossible(a)) return;
        /* THE FILE ON DISK IS THE TRUTH, NOT THE LATCH.
         *
         * reportedMissing is set once by ShimFrameGen.prepare() at startup and
         * nothing ever cleared it, so `reportedMissing || dllMissing()` stayed
         * true forever: the dialog kept being offered even after the user had
         * successfully imported the dll. Clear the latch as soon as the file is
         * actually there. */
        if (!dllMissing(a)) {
            if (reportedMissing) {
                reportedMissing = false;
                Log.i(TAG, "dll import: file present, clearing the missing latch");
            }
            return;
        }
        /* RE-OFFER IF THE DIALOG DID NOT SURVIVE.
         *
         * A single `offered` latch was not enough: the prompt is raised during
         * startup and the shim's own theming recreates the activity moments
         * later, which tears the dialog down with it. show() had already
         * succeeded and nothing threw, so the latch stayed set and the user
         * never saw a prompt at all — the app silently never asked for the one
         * file it cannot run without. Bounded so it cannot nag. */
        /* An explicit NOT NOW is final. The re-offer below exists only for a
         * dialog torn down by an activity recreate, not to nag past a decision;
         * the drawer entry is the way back. */
        if (declined) return;
        /* ONE PROMPT PER DECISION.
         *
         * The re-offer exists only for a dialog torn down by an activity
         * recreate before the user could answer it. Once either button has been
         * pressed the question is answered, and re-offering showed a second
         * identical prompt straight after the first — reported as double
         * prompting. */
        if (acted) return;
        /* ONCE ANSWERED, ONLY SETTINGS ASKS AGAIN.
         *
         * A Settings visit re-arms the offer (that is the way back after NOT
         * NOW), but an activity change tears the dialog down before it can be
         * answered, and the bounded re-offer then put the same prompt up on
         * whatever came next — press NOT NOW in Settings, walk back to the
         * library, get asked again there. Once the user has answered the
         * question even once, the prompt belongs on the screen they went
         * looking for it on and nowhere else. */
        if (everAnswered && !isSettings(a)) return;
        android.app.Dialog live = shown == null ? null : shown.get();
        if (live != null && live.isShowing()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (offerAttempts >= 3 || now - lastOfferMs < 4000L) return;
        lastOfferMs = now;
        offerAttempts++;
        Log.i(TAG, "dll import: offering (prepare reported missing="
                + reportedMissing + ", own check=" + dllMissing(a) + ")");
        offered = true;
        prompt(a, "Frame generation needs Lossless.dll",
                "Frame generation uses the shaders from Lossless Scaling's "
                + "Lossless.dll, which cannot be bundled.\n\n"
                + "If you own Lossless Scaling, pick your Lossless.dll and it "
                + "will be copied in. Nothing leaves the device.");
    }

    /**
     * Re-arm the offer when the user opens Settings.
     *
     * Pressing NOT NOW is final for the session, which left no way to supply the
     * dll afterwards. Settings is where someone goes looking for it, so opening
     * Settings while the file is still absent offers it again. This replaces the
     * drawer entry, which could not be made to work without breaking the drawer.
     */
    /** Identity of the Settings screen we last re-armed for. */
    private static int rearmedFor;
    private static long rearmedAtMs;
    /** True once either button has ever been pressed in this process. */
    private static volatile boolean everAnswered;

    static boolean isSettings(Activity a) {
        return a != null && a.getClass().getName().endsWith("SettingsActivity");
    }

    static void onSettingsOpened(Activity a) {
        if (!FramegenBuild.AVAILABLE) return;
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        if (framegenImpossible(a)) return;
        /* ONCE PER VISIT. This is called from the 4 Hz watchdog poll, so
         * re-arming unconditionally re-offered the dialog the instant it was
         * dismissed and the message could not be got rid of at all. Keyed on the
         * activity instance: leaving Settings and coming back offers again, a
         * dismissal inside one visit stays dismissed. */
        int id = System.identityHashCode(a);
        long now = android.os.SystemClock.uptimeMillis();
        if (id == rearmedFor) return;
        /* A THEME CHANGE RESTARTS SettingsActivity, and for a moment two
         * instances are alive. The identity key alone then flipped between them
         * every tick and re-armed on each one — observed as a dozen re-arms a
         * second. One re-arm per few seconds is all a real visit needs. */
        if (rearmedAtMs != 0 && now - rearmedAtMs < 3000L) return;
        rearmedFor = id;
        rearmedAtMs = now;
        if (!dllMissing(a)) return;
        declined = false;
        acted = false;
        offerAttempts = 0;
        lastOfferMs = 0;
        Log.i(TAG, "dll import: re-armed for this Settings visit");
    }

    /** Explicit entry point — always shows the prompt. */
    public static void startImport(Activity a) {
        if (a == null || a.isFinishing()) return;
        prompt(a, "Import Lossless.dll",
                "Pick the Lossless.dll from your copy of Lossless Scaling. "
                + "It is copied into this app's private folder.");
    }

    private static void prompt(final Activity a, String title, String body) {
        try {
            shown = new java.lang.ref.WeakReference<android.app.Dialog>(
                new AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton("CHOOSE FILE", (d, w) -> {
                        acted = true;
                        everAnswered = true;
                        launchPicker(a);
                    })
                    .setNegativeButton("NOT NOW", (d, w) -> {
                        declined = true;
                        acted = true;
                        everAnswered = true;
                        Log.i(TAG, "dll import declined; Settings offers it again");
                    })
                    .show());
        } catch (Throwable t) {
            Log.w(TAG, "dll import prompt: " + t);
        }
    }

    private static void launchPicker(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            /* Deliberately */ i.setType("*/*");
            /* A .dll has no registered mime type, and picking one by
             * application/octet-stream hid it on this device. */

            android.app.FragmentManager fm = a.getFragmentManager();
            if (fm == null) {
                Log.w(TAG, "dll import: no platform FragmentManager");
                toast(a, "Cannot open the file picker here");
                return;
            }
            PickerFragment f = (PickerFragment) fm.findFragmentByTag(FRAG_TAG);
            if (f == null) {
                f = new PickerFragment();
                fm.beginTransaction().add(f, FRAG_TAG).commitAllowingStateLoss();
                fm.executePendingTransactions();
            }
            f.startActivityForResult(i, REQ_PICK_DLL);
        } catch (Throwable t) {
            Log.w(TAG, "dll import launch: " + t);
            toast(a, "Could not open the file picker");
        }
    }

    /** Headless; exists only to receive the picker's result. */
    public static final class PickerFragment extends android.app.Fragment {

        @Override
        public void onCreate(Bundle b) {
            super.onCreate(b);
            setRetainInstance(true);
        }

        @Override
        public void onActivityResult(int req, int res, Intent data) {
            super.onActivityResult(req, res, data);
            if (req != REQ_PICK_DLL) return;
            Activity a = getActivity();
            if (a == null) return;
            if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
                Log.i(TAG, "dll import cancelled");
                return;
            }
            copyIn(a, data.getData());
        }
    }

    private static void copyIn(Activity a, Uri uri) {
        File dst = dllFile(a);
        if (dst == null) {
            toast(a, "No storage available");
            return;
        }
        File tmp = new File(dst.getParentFile(), "Lossless.dll.part");
        try {
            File dir = dst.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                toast(a, "Could not create " + dir.getName());
                return;
            }
            long n = 0;
            try (InputStream in = a.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    toast(a, "Could not read that file");
                    return;
                }
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    n += r;
                }
            }
            /* Validate BEFORE it becomes Lossless.dll: prepare() hashes the
             * file and caches shaders per hash, so a wrong file that lands under
             * the real name gets a cache entry and its failure then looks
             * permanent. */
            if (n < MIN_PLAUSIBLE_BYTES || !looksLikePe(tmp)) {
                tmp.delete();
                toast(a, "That does not look like Lossless.dll");
                Log.w(TAG, "dll import rejected: " + n + " bytes, PE=" + looksLikePe(tmp));
                return;
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                toast(a, "Could not replace the existing file");
                return;
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                toast(a, "Could not save the file");
                return;
            }
            Log.i(TAG, "dll import: wrote " + n + " bytes to " + dst.getAbsolutePath());
            keepPrivateCopy(a, dst);
            TurnipConfig.note(a.getExternalFilesDir(null),
                    "Lossless.dll imported (" + (n / (1024 * 1024)) + " MB)");
            new AlertDialog.Builder(a)
                    .setTitle("Lossless.dll imported")
                    .setMessage("Restart the game to build the frame generation "
                            + "shaders from it. That takes a few seconds the "
                            + "first time.")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Throwable t) {
            tmp.delete();
            Log.w(TAG, "dll import copy: " + t);
            toast(a, "Import failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Mirror the imported file into internal storage. Best effort — a failure
     * here costs nothing today, it only means the external copy is the sole one.
     */
    private static void keepPrivateCopy(Activity a, File src) {
        File keep = internalDll(a);
        if (keep == null) return;
        try {
            File dir = keep.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, "Lossless.dll.part");
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            if (keep.exists() && !keep.delete()) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(keep)) {
                tmp.delete();
                return;
            }
            Log.i(TAG, "dll import: private copy kept at " + keep.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "dll import: private copy failed: " + t);
        }
    }

    /** MZ magic — enough to reject a picked photo or zip without parsing PE. */
    private static boolean looksLikePe(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 'M' && in.read() == 'Z';
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Activity a, String msg) {
        try {
            PrefCompat.toast(a, msg);
        } catch (Throwable ignored) {
        }
    }
}
