package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.io.File;

/**
 * Adds an "Import Lossless.dll" entry to the library's navigation drawer.
 *
 * <p>Frame generation needs the shaders out of Lossless Scaling's Lossless.dll.
 * That file is not ours to ship, so the user supplies their own copy, and on
 * Android 11+ they cannot place it in Android/data themselves. A prompt offers
 * the import at startup, but pressing NOT NOW used to leave no way back to it:
 * the file could never be supplied afterwards, and frame generation could never
 * work at all.
 *
 * <p>The drawer, deliberately, rather than a row injected into the settings
 * preference screens. The shim patches a prebuilt APK and does not own those
 * resources; adding a Preference by reflection meant guessing which fragment was
 * live and when, and it never once landed. The drawer menu is a single object
 * the shim already locates by id for theming, and adding an item to it cannot
 * disturb the preference screens.
 */
final class ShimDllRow {
    private static final String TAG = "VulkanShim";
    private static final String MAIN_ACTIVITY = "xyz.aethersx2.android.MainActivity";
    private static final String TITLE = "Import Lossless.dll";
    /** Arbitrary, high enough not to collide with the app's own item ids. */
    private static final int ITEM_ID = 0x5105D11;

    private static final java.util.WeakHashMap<View, Boolean> WIRED =
            new java.util.WeakHashMap<>();

    private ShimDllRow() {}

    /**
     * DISABLED. Adding an entry here broke the whole drawer.
     *
     * NavigationView ignores MenuItem.setOnMenuItemClickListener and routes taps
     * through its own OnNavigationItemSelectedListener, so the entry appeared
     * and did nothing. Wrapping that listener to delegate to the app's own made
     * it worse: EVERY row in the drawer stopped responding, because the delegate
     * lookup did not reliably find the listener the app had installed and the
     * proxy then swallowed each tap.
     *
     * A menu the user relies on is not the place to be clever. The way back to
     * the importer is now the prompt itself, which is re-offered when Settings is
     * opened while the dll is still missing (see ShimDllImport.onSettingsOpened).
     */
    static void ensureRow(Activity activity) {
        /* intentionally empty */
    }

    /** Show at a glance whether a dll is already in place. */
    private static void refresh(Activity a, MenuItem item) {
        if (item == null) return;
        try {
            File files = a.getExternalFilesDir(null);
            File dll = files == null ? null
                    : new File(new File(files, "lsfg"), "Lossless.dll");
            boolean have = dll != null && dll.isFile();
            item.setTitle(have ? "Replace Lossless.dll" : TITLE);
        } catch (Throwable ignored) { }
    }
}
