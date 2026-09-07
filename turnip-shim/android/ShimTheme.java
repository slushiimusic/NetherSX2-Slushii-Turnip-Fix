package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
final class ShimTheme {
    private static final String EMULATION_ACTIVITY = "xyz.aethersx2.android.EmulationActivity";
    private static final int LOCAL_NIGHT_USE_GLOBAL = -100;
    private static final String MAIN_ACTIVITY = "xyz.aethersx2.android.MainActivity";
    private static final int MODE_NIGHT_FOLLOW_SYSTEM = -1;
    private static final int MODE_NIGHT_NO = 1;
    private static final int MODE_NIGHT_YES = 2;
    static final String PREF_THEME = "UI/Theme";
    private static final String SETTINGS_ACTIVITY = "xyz.aethersx2.android.SettingsActivity";
    private static volatile String lastAppliedTheme;
    private static volatile String lastInGameTheme;
    /**
     * How long after a theme apply the on-screen preference widgets are not to
     * be believed.
     *
     * <p>Applying a theme RESTARTS the activity, and for a moment the old
     * instance and its ListPreference — still holding the value from before the
     * change — are alive alongside the new one. Anything that reads the widget
     * and writes what it finds back into SharedPreferences will undo the user's
     * choice with it. See ToggleWatchdog's theme backstop.
     */
    private static volatile long themeSettleUntilMs;
    private static volatile boolean needsMainRecreate;
    private static volatile String pendingMainTheme;
    /** Set when a preference list was found in a dialog window, not the decor. */
    private static volatile boolean dialogNeedsReopen;
    /**
     * Let the app own the in-game theme change instead of the shim.
     *
     * <p>Kept as a switch because it answered a real question. With this TRUE
     * the shim does nothing on the in-game path, and the menu still did not
     * repaint: the Theme row read "Light" — so the setting really had changed —
     * while the menu stayed dark (luminance 13.9). The app does not live-repaint
     * its in-game settings dialog on its own, so the shim is not what blocks it,
     * and close-and-reopen is the mechanism that actually works.
     */
    private static final boolean THEME_PASSTHROUGH = false;
    static final String SLUSHII = "slushii";
    private static final String[] VALUES = {"follow_system", "light", "dark", SLUSHII};
    private static final List<WeakReference<Activity>> activities = new ArrayList();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShimTheme() {
    }

    static void registerActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            prune();
            int iIdentityHashCode = System.identityHashCode(activity);
            Iterator<WeakReference<Activity>> it = list.iterator();
            while (it.hasNext()) {
                Activity activity2 = it.next().get();
                if (activity2 != null && System.identityHashCode(activity2) == iIdentityHashCode) {
                    return;
                }
            }
            activities.add(new WeakReference<>(activity));
        }
    }

    /** Apply chrome once an activity has finished creating its content view.
     * This is the first point at which Settings' action-bar and tab views exist,
     * and still precedes the first rendered frame. */
    static void applyAccentAfterCreate(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || isEmulationActivity(activity)) {
            return;
        }
        ShimAccent.apply(activity, readSpTheme(activity));
    }

    static void unregisterActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            int iIdentityHashCode = System.identityHashCode(activity);
            Iterator<WeakReference<Activity>> it = list.iterator();
            while (it.hasNext()) {
                Activity activity2 = it.next().get();
                if (activity2 == null || System.identityHashCode(activity2) == iIdentityHashCode) {
                    it.remove();
                }
            }
        }
    }

    static String coerce(Object obj) {
        int iIntValue;
        if ((obj instanceof Integer) && (iIntValue = ((Integer) obj).intValue()) >= 0) {
            String[] strArr = VALUES;
            if (iIntValue < strArr.length) {
                return strArr[iIntValue];
            }
        }
        if (obj instanceof String) {
            String strTrim = ((String) obj).trim();
            for (String str : VALUES) {
                if (str.equals(strTrim)) {
                    return str;
                }
            }
            try {
                int i4 = Integer.parseInt(strTrim);
                if (i4 >= 0) {
                    String[] strArr2 = VALUES;
                    if (i4 < strArr2.length) {
                        return strArr2[i4];
                    }
                }
            } catch (NumberFormatException unused) {
            }
        }
        return VALUES[0];
    }

    static String readSpTheme(SharedPreferences sharedPreferences) {
        if (sharedPreferences == null) {
            return VALUES[0];
        }
        try {
            return coerce(sharedPreferences.getString(PREF_THEME, VALUES[0]));
        } catch (ClassCastException unused) {
            return coerce(sharedPreferences.getAll().get(PREF_THEME));
        }
    }

    private static String readSpTheme(Context context) {
        try {
            return readSpTheme((SharedPreferences) Class.forName("androidx.preference.PreferenceManager").getMethod("getDefaultSharedPreferences", Context.class).invoke(null, context.getApplicationContext()));
        } catch (Throwable unused) {
            return VALUES[0];
        }
    }

    static void fixStoredPref(Context context) {
        if (context == null) {
            return;
        }
        try {
            SharedPreferences sharedPreferences = (SharedPreferences) Class.forName("androidx.preference.PreferenceManager").getMethod("getDefaultSharedPreferences", Context.class).invoke(null, context);
            Object obj = sharedPreferences.getAll().get(PREF_THEME);
            String strCoerce = coerce(obj);
            if (obj == null || (obj instanceof Integer) || ((obj instanceof String) && !strCoerce.equals(obj))) {
                sharedPreferences.edit().remove(PREF_THEME).putString(PREF_THEME, strCoerce).commit();
            }
        } catch (Throwable th) {
            Log.w("VulkanShim", "ShimTheme.fix: " + th.getMessage());
        }
    }

    static void prepareActivityPreCreate(Activity activity) {
        if (activity == null) {
            return;
        }
        fixStoredPref(activity);
        if (isEmulationActivity(activity)) {
            return;
        }
        String spTheme = readSpTheme(activity);
        /* SettingsActivity too, not just the library.
         *
         * Only MainActivity had its theme applied here, so App Settings was left
         * to resolve the stock theme on its own: with the saved choice set to
         * Dark but the DEVICE in light mode, the library came up dark and App
         * Settings came up light every single time it was opened.
         * applyActivityTheme already picks "AppTheme" rather than
         * "AppTheme.NoActionBar" for this case, so it was written expecting to
         * be called here. forceGlobalNightMode below is not enough on its own —
         * the activity's own theme is resolved before it takes effect. */
        if (isMainActivity(activity) || isSettingsActivity(activity)) {
            applyActivityTheme(activity);
        }
        /* ONLY force the global night mode for an EXPLICIT choice.
         *
         * This ran unconditionally, so with UI/Theme at its default the shim
         * called forceGlobalNightMode(FOLLOW_SYSTEM) on every activity create
         * and overrode whatever the app itself had set. Picking Dark applied at
         * runtime and then opening App Settings snapped everything back to
         * light, library included, because that create re-forced follow-system.
         * The device's own night mode is off, so "follow system" meant light.
         *
         * follow_system is also indistinguishable from "never chosen", so when
         * it is what we read, the app owns the decision and the shim keeps its
         * hands off. An explicit light/dark/slushii is still enforced. */
        if (!VALUES[0].equals(spTheme)) {
            forceGlobalNightMode(modeFor(spTheme));
        }
        ShimAccent.apply(activity, spTheme);
    }

    static void onThemeSpChanged(Context context, Object obj) {
        if (context == null) {
            return;
        }
        String strCoerce = obj != null ? coerce(obj) : readSpTheme(context);
        if (obj == null) {
            obj = "sp";
        }
        Log.i("VulkanShim", "onThemeSpChanged raw=" + String.valueOf(obj) + " -> " + strCoerce);
        themeSettleUntilMs = android.os.SystemClock.uptimeMillis() + 3000L;
        try {
            SharedPreferences sharedPreferences = (SharedPreferences) Class.forName("androidx.preference.PreferenceManager").getMethod("getDefaultSharedPreferences", Context.class).invoke(null, context.getApplicationContext());
            if (!strCoerce.equals(readSpTheme(sharedPreferences))) {
                sharedPreferences.edit().putString(PREF_THEME, strCoerce).commit();
            }
        } catch (Throwable th) {
            Log.w("VulkanShim", "theme SP write: " + th.getMessage());
        }
        lastAppliedTheme = strCoerce;
        pendingMainTheme = strCoerce;
        needsMainRecreate = true;
        Activity activityResolveThemeTarget = resolveThemeTarget(context);
        Activity activityFindMainActivity = findMainActivity();
        boolean zShouldDeferMainRecreate = shouldDeferMainRecreate();
        String simpleName = activityResolveThemeTarget != null ? activityResolveThemeTarget.getClass().getSimpleName() : "null";
        String str = activityFindMainActivity != null ? "yes" : "no";
        Log.i("VulkanShim", "theme changed -> " + strCoerce + " target=" + simpleName + " main=" + str + " pending=" + needsMainRecreate + " deferMain=" + zShouldDeferMainRecreate);
        if (activityResolveThemeTarget != null) {
            if (isEmulationActivity(activityResolveThemeTarget)) {
                applyThemeInPlace(activityResolveThemeTarget, strCoerce);
            } else {
                applyThemeNow(activityResolveThemeTarget, strCoerce, isMainActivity(activityResolveThemeTarget) && !zShouldDeferMainRecreate);
            }
            // Settings can be a separate activity while the VM is alive. The
            // previous path only refreshed Settings, leaving its in-game menu
            // on the old light/dark palette until emulation ended. Refresh the
            // registered emulator in place, never recreate it.
            if (zShouldDeferMainRecreate && !isEmulationActivity(activityResolveThemeTarget)) {
                Activity emulation = findEmulationActivity();
                if (emulation != null) {
                    applyThemeInPlace(emulation, strCoerce);
                    Log.i("VulkanShim", "in-game theme refreshed immediately");
                }
            }
            if (!zShouldDeferMainRecreate || activityFindMainActivity == null) {
                return;
            }
            pendingMainTheme = strCoerce;
            needsMainRecreate = true;
            Log.i("VulkanShim", "MainActivity theme deferred (emulation active)");
            return;
        }
        if (!zShouldDeferMainRecreate && activityFindMainActivity != null) {
            applyThemeNow(activityFindMainActivity, strCoerce, true);
            return;
        }
        Activity activityForegroundActivity = ToggleWatchdog.foregroundActivity();
        if (activityForegroundActivity != null && isEmulationActivity(activityForegroundActivity) && !activityForegroundActivity.isFinishing() && !activityForegroundActivity.isDestroyed()) {
            applyThemeInPlace(activityForegroundActivity, strCoerce);
            if (!zShouldDeferMainRecreate || activityFindMainActivity == null) {
                return;
            }
            pendingMainTheme = strCoerce;
            needsMainRecreate = true;
            Log.i("VulkanShim", "MainActivity theme deferred (emulation active)");
            return;
        }
        Log.i("VulkanShim", "theme queued for MainActivity".concat(zShouldDeferMainRecreate ? " (emulation active)" : " (no fg)"));
    }

    /** True while an activity restart for a theme change may still be in flight. */
    static boolean themeSettling() {
        return android.os.SystemClock.uptimeMillis() < themeSettleUntilMs;
    }

    static void pollTheme(Context context) {
        if (context == null) {
            return;
        }
        String spTheme = readSpTheme(context);
        if (spTheme.equals(lastAppliedTheme)) {
            return;
        }
        Log.i("VulkanShim", "theme poll SP changed " + lastAppliedTheme + " -> " + spTheme);
        Activity activityForegroundActivity = ToggleWatchdog.foregroundActivity();
        if (activityForegroundActivity != null) {
            context = activityForegroundActivity;
        }
        onThemeSpChanged(context, spTheme);
    }

    /**
     * The in-game preference sheet writes UI/Theme but bypasses its normal
     * ListPreference listener on Pink. Poll the persisted value directly and
     * refresh only the live emulator window; never recreate the running VM.
     */
    static void pollInGameTheme(Context context) {
        if (context == null) return;
        Activity emulation = findEmulationActivity();
        if (emulation == null) {
            lastInGameTheme = null;
            return;
        }
        String theme = readSpTheme(context);
        if (theme.equals(lastInGameTheme)) return;
        lastInGameTheme = theme;
        applyThemeInPlace(emulation, theme);
        Log.i("VulkanShim", "in-game theme poll applied " + theme);
    }

    static void syncActivity(Activity activity) {
        if (!isMainActivity(activity) || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (shouldDeferMainRecreate()) {
            if (needsMainRecreate || pendingMainTheme != null) {
                Log.i("VulkanShim", "MainActivity theme sync deferred (emulation active)");
                return;
            }
            return;
        }
        if (!needsMainRecreate && pendingMainTheme == null) {
            forceGlobalNightMode(modeFor(readSpTheme(activity)));
            invokeStockG(activity);
            return;
        }
        String spTheme = pendingMainTheme != null ? pendingMainTheme : readSpTheme(activity);
        Log.i("VulkanShim", "MainActivity sync theme -> " + spTheme + " needsRecreate=" + needsMainRecreate);
        applyThemeNow(activity, spTheme, true);
    }

    static void onEmulationEnded() {
        MAIN.postDelayed(new Runnable() { // from class: xyz.aethersx2.android.shim.ShimTheme$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                ShimTheme.lambda$onEmulationEnded$0();
            }
        }, 500L);
    }

    static /* synthetic */ void lambda$onEmulationEnded$0() {
        Activity activityFindMainActivity = findMainActivity();
        if (activityFindMainActivity == null || activityFindMainActivity.isFinishing() || activityFindMainActivity.isDestroyed() || shouldDeferMainRecreate()) {
            return;
        }
        syncActivity(activityFindMainActivity);
    }

    private static void applyThemeNow(final Activity activity, final String str, final boolean z3) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || isEmulationActivity(activity)) {
            return;
        }
        if (isMainActivity(activity) && shouldDeferMainRecreate()) {
            pendingMainTheme = str;
            needsMainRecreate = true;
            Log.i("VulkanShim", "MainActivity recreate deferred (emulation active)");
        } else {
            final boolean zIsSettingsActivity = isSettingsActivity(activity);
            Runnable runnable = new Runnable() { // from class: xyz.aethersx2.android.shim.ShimTheme$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    ShimTheme.lambda$applyThemeNow$1(activity, z3, str, zIsSettingsActivity);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run();
            } else {
                MAIN.post(runnable);
            }
        }
    }

    static /* synthetic */ void lambda$applyThemeNow$1(Activity activity, boolean z3, String str, boolean z4) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activity.isChangingConfigurations()) {
            Log.i("VulkanShim", "theme apply skipped (changingConfigurations)");
            return;
        }
        if (z3) {
            pendingMainTheme = null;
            needsMainRecreate = false;
        }
        clearThemeCache(activity);
        clearPerActivityNightCache(activity);
        applyActivityTheme(activity);
        applyInstantNightMode(activity, str);
        if (z4) {
            Log.i("VulkanShim", "SettingsActivity restart for " + str + " clearPending=" + z3);
            restartSettingsActivity(activity);
            return;
        }
        Log.i("VulkanShim", activity.getClass().getSimpleName() + " recreate for " + str + " clearPending=" + z3);
        activity.recreate();
    }

    private static void restartSettingsActivity(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) {
                intent = new Intent(activity, activity.getClass());
            }
            intent.addFlags(65536);
            activity.finish();
            activity.overridePendingTransition(0, 0);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        } catch (Throwable th) {
            Log.w("VulkanShim", "Settings restart failed, recreate: " + th.getMessage());
            activity.recreate();
        }
    }

    private static void applyThemeInPlace(final Activity activity, final String str) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Runnable runnable = new Runnable() { // from class: xyz.aethersx2.android.shim.ShimTheme$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ShimTheme.lambda$applyThemeInPlace$2(activity, str);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN.post(runnable);
        }
    }

    static /* synthetic */ void lambda$applyThemeInPlace$2(Activity activity, String str) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        /* EXPERIMENT (theme_passthrough): stock NetherSX2 Turnip repaints the
         * in-game settings menu live, so the app CAN do this by itself — which
         * means the shim's night-mode machinery below (resetDelegateLocalMode,
         * forceGlobalNightMode, UiModeManager.setApplicationNightMode, poking
         * e.h.c0) is a prime suspect for cancelling the app's own handling.
         * Do nothing here and let the app's own listener run. */
        if (isEmulationActivity(activity) && THEME_PASSTHROUGH) {
            Log.i("VulkanShim", "in-game theme: passthrough, app handles " + str);
            return;
        }
        clearThemeCache(activity);
        clearPerActivityNightCache(activity);
        applyActivityTheme(activity);
        applyInstantNightMode(activity, str);
        refreshOverlayWindows(activity, dispatchNightConfiguration(activity, str));
        PrefCompat.refreshVisiblePreferenceUi(activity);
        /* The refresh above only rebinds; an already-inflated row keeps the old
         * palette. Rebuild the fragment views so the in-game settings menu
         * actually repaints — recreating the activity is not an option here, it
         * would kill emulation. */
        PrefCompat.reinflatePreferenceFragments(activity);
        /* Close the in-game settings dialog so it reopens in the new theme.
         * Deliberately gated on dialogNeedsReopen, which is only set when a
         * preference list was found in a window OTHER than the activity's decor
         * — i.e. a dialog really is on screen. Without that gate a stray back
         * press here would exit the game. */
        if (dialogNeedsReopen && isEmulationActivity(activity)) {
            dialogNeedsReopen = false;
            final Activity act = activity;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        /* RE-CHECK, do not trust the flag (fixed 2026-08-17).
                         *
                         * EmulationActivity.onBackPressed() does not close
                         * anything in this app — decompiled, it is exactly
                         * `N()`, and N() SHOWS the MenuDialogFragment. It only
                         * looks like a dismissal when the settings dialog is
                         * already on top and eats the press first.
                         *
                         * dialogNeedsReopen is set by the theme sweep whenever a
                         * list is found in a non-decor window — and the
                         * save-state prompt that appears when you launch a game
                         * from the library IS such a window. The flag was set on
                         * MainActivity, survived the activity change, and fired
                         * here, so EVERY library launch of a game with a save
                         * state opened the pause menu over the booting game.
                         * That also swallowed framegen, which will not attach
                         * its panel over an open menu.
                         *
                         * So: only press back if a preference list is on screen
                         * in one of THIS activity's non-decor windows right
                         * now. */
                        if (!preferenceDialogOnScreen(act)) {
                            Log.i("VulkanShim", "settings dismiss skipped — "
                                    + "no dialog on screen (a back press here "
                                    + "would OPEN the pause menu)");
                            return;
                        }
                        act.onBackPressed();
                        Log.i("VulkanShim",
                                "in-game settings closed for theme change — reopen to see it");
                    } catch (Throwable t) {
                        Log.w("VulkanShim", "settings dismiss: " + t.getMessage());
                    }
                }
            });
        }
        Log.i("VulkanShim", activity.getClass().getSimpleName() + " in-place theme " + str);
    }

    /**
     * Is a preference list showing in a window of {@code activity} that is not
     * its decor — i.e. is the in-game settings dialog actually open right now?
     *
     * <p>Deliberately evaluated at press time rather than remembered: the flag
     * that used to gate the back press was set on a different activity entirely.
     */
    private static boolean preferenceDialogOnScreen(Activity activity) {
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object global = cls.getMethod("getInstance", (Class<?>[]) null)
                    .invoke(null, (Object[]) null);
            Field f = cls.getDeclaredField("mViews");
            f.setAccessible(true);
            Object raw = f.get(global);
            if (!(raw instanceof List)) return false;
            for (Object o : new java.util.ArrayList<Object>((List<?>) raw)) {
                if (!(o instanceof View)) continue;
                View v = (View) o;
                if (v == decor || !v.isShown()) continue;
                /* mViews is PROCESS-wide, not per-activity. Without this the
                 * check passed on MainActivity's game list — a shown, non-decor
                 * window full of rows — and the back press went through exactly
                 * as before. */
                if (!windowBelongsTo(v, activity)) continue;
                if (PrefCompat.hasListIn(v)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** True when this root view's context resolves back to {@code act}. */
    private static boolean windowBelongsTo(View v, Activity act) {
        try {
            android.content.Context c = v.getContext();
            for (int i = 0; i < 12 && c != null; i++) {
                if (c == act) return true;
                if (!(c instanceof android.content.ContextWrapper)) break;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Configuration dispatchNightConfiguration(Activity activity, String str) {
        int i4;
        try {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            int iModeFor = modeFor(str);
            if (iModeFor == 2) {
                i4 = 32;
            } else {
                i4 = iModeFor == 1 ? 16 : configuration.uiMode & 48;
            }
            configuration.uiMode = i4 | (configuration.uiMode & (-49));
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            activity.onConfigurationChanged(configuration);
            View decorView = activity.getWindow().getDecorView();
            if (decorView != null) {
                decorView.dispatchConfigurationChanged(configuration);
            }
            Log.i("VulkanShim", "dispatched night config on " + activity.getClass().getSimpleName());
            return configuration;
        } catch (Throwable th) {
            Log.w("VulkanShim", "dispatchNightConfiguration: " + th.getMessage());
            return null;
        }
    }

    private static void refreshOverlayWindows(Activity activity, Configuration configuration) {
        if (activity == null) {
            return;
        }
        try {
            View decorView = activity.getWindow().getDecorView();
            if (decorView != null) {
                decorView.invalidate();
            }
            if (configuration != null) {
                Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
                Object objInvoke = cls.getMethod("getInstance", null).invoke(null, null);
                Field declaredField = cls.getDeclaredField("mViews");
                declaredField.setAccessible(true);
                List<View> list = (List) declaredField.get(objInvoke);
                if (list != null) {
                    int rebuilt = 0;
                    View activityDecor = activity.getWindow() != null
                            ? activity.getWindow().getDecorView() : null;
                    /* Snapshot first: re-setting an adapter can mutate mViews
                     * while we iterate it. */
                    for (View view : new java.util.ArrayList<>(list)) {
                        if (view != null) {
                            /* DO NOT updateConfiguration() on the window's own
                             * Resources here.
                             *
                             * It was tried, it did NOT achieve the repaint it
                             * was meant to (239.1 -> 241.3, i.e. nothing), and
                             * it actively BREAKS the in-game pause menu: the
                             * dialog's background flips to the new palette while
                             * its text colours stay baked in from the old one,
                             * giving white text on a white background — the menu
                             * becomes unreadable after a dark->light switch.
                             *
                             * A dialog cannot be half-re-themed. Either it is
                             * recreated (which is what the dismiss below does)
                             * or it is left completely alone. */
                            view.dispatchConfigurationChanged(configuration);
                            /* dispatchConfigurationChanged does NOT restyle rows
                             * that are already inflated — the in-game settings
                             * menu is a DialogFragment in its OWN window, so
                             * this loop is the only place that reaches it.
                             * Rebuild its lists so they re-inflate. */
                            int n = PrefCompat.reinflateListsIn(view);
                            rebuilt += n;
                            /* A preference list living in a window that is NOT
                             * the activity's decor is the in-game settings
                             * DIALOG. Its rows cannot be re-themed in place —
                             * the palette comes from a theme bound to the
                             * dialog's own context, which cannot be swapped once
                             * resolved — so it has to be closed and reopened. */
                            if (n > 0 && activityDecor != null && view != activityDecor)
                                dialogNeedsReopen = true;
                            view.invalidate();
                        }
                    }
                    if (rebuilt > 0)
                        Log.i("VulkanShim", "theme: re-inflated " + rebuilt
                                + " list(s) across " + list.size() + " window(s)"
                                + (dialogNeedsReopen ? " (dialog present)" : ""));
                }
            }
        } catch (Throwable th) {
            Log.w("VulkanShim", "refreshOverlayWindows: " + th.getMessage());
        }
    }

    private static void applyInstantNightMode(Activity activity, String str) {
        int iModeFor = modeFor(str);
        clearPerActivityNightCache(activity);
        resetDelegateLocalMode(activity);
        forceGlobalNightMode(iModeFor);
        applyApplicationNightMode(activity, str);
        invokeStockG(activity);
        applyDelegateDayNight(activity);
        ShimAccent.apply(activity, str);
        Log.i("VulkanShim", "instant night mode " + str + " on " + activity.getClass().getSimpleName());
    }

    private static void applyApplicationNightMode(Context context, String str) {
        int i4;
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            UiModeManager uiModeManager = (UiModeManager) context.getSystemService("uimode");
            if (uiModeManager == null) {
                return;
            }
            if ("dark".equals(str)) {
                i4 = 2;
            } else if ("light".equals(str) || isSlushii(str)) {
                i4 = 1;
            } else {
                i4 = 0;
            }
            uiModeManager.setApplicationNightMode(i4);
            Log.i("VulkanShim", "UiModeManager.setApplicationNightMode(" + i4 + ")");
        } catch (Throwable th) {
            Log.w("VulkanShim", "UiModeManager: " + th.getMessage());
        }
    }

    private static void clearPerActivityNightCache(Activity activity) {
        try {
            Field declaredField = Class.forName("e.h").getDeclaredField("c0");
            declaredField.setAccessible(true);
            Object obj = declaredField.get(null);
            if (obj == null) {
                return;
            }
            obj.getClass().getMethod("remove", Object.class).invoke(obj, activity.getClass().getName());
            Log.i("VulkanShim", "cleared night cache for " + activity.getClass().getSimpleName());
        } catch (Throwable th) {
            Log.w("VulkanShim", "night cache clear: " + th.getMessage());
        }
    }

    private static void applyDelegateDayNight(Activity activity) {
        try {
            Object objInvoke = activity.getClass().getMethod("x", null).invoke(activity, null);
            if (objInvoke == null) {
                return;
            }
            Field declaredField = objInvoke.getClass().getDeclaredField("P");
            declaredField.setAccessible(true);
            declaredField.setInt(objInvoke, LOCAL_NIGHT_USE_GLOBAL);
            objInvoke.getClass().getMethod("d", null).invoke(objInvoke, null);
            Log.i("VulkanShim", "delegate applyDayNight for " + activity.getClass().getSimpleName());
        } catch (Throwable th) {
            Log.w("VulkanShim", "delegate applyDayNight: " + th.getMessage());
        }
    }

    private static void applyActivityTheme(Activity activity) {
        try {
            int identifier = activity.getResources().getIdentifier(isMainActivity(activity) ? "AppTheme.NoActionBar" : "AppTheme", "style", activity.getPackageName());
            if (identifier != 0) {
                activity.setTheme(identifier);
            }
        } catch (Throwable th) {
            Log.w("VulkanShim", "setTheme: " + th.getMessage());
        }
    }

    private static void resetDelegateLocalMode(Activity activity) {
        try {
            Field declaredField = Class.forName("e.g").getDeclaredField("d");
            declaredField.setAccessible(true);
            Object obj = declaredField.get(null);
            if (obj == null) {
                return;
            }
            Iterator it = (Iterator) obj.getClass().getMethod("iterator", null).invoke(obj, null);
            while (it.hasNext()) {
                Object obj2 = ((WeakReference) it.next()).get();
                if (obj2 != null) {
                    Field declaredField2 = obj2.getClass().getDeclaredField("f");
                    declaredField2.setAccessible(true);
                    if (declaredField2.get(obj2) == activity) {
                        Field declaredField3 = obj2.getClass().getDeclaredField("P");
                        declaredField3.setAccessible(true);
                        declaredField3.setInt(obj2, LOCAL_NIGHT_USE_GLOBAL);
                        obj2.getClass().getMethod("d", null).invoke(obj2, null);
                        Log.i("VulkanShim", "delegate dayNight reset for " + activity.getClass().getSimpleName());
                        return;
                    }
                }
            }
        } catch (Throwable th) {
            Log.w("VulkanShim", "delegate reset: " + th.getMessage());
        }
    }

    private static void invokeStockG(Activity activity) {
        if (isMainActivity(activity)) {
            try {
                Method declaredMethod = activity.getClass().getDeclaredMethod("G", null);
                declaredMethod.setAccessible(true);
                declaredMethod.invoke(activity, null);
            } catch (Throwable th) {
                Log.w("VulkanShim", "MainActivity.G: " + th.getMessage());
            }
        }
    }

    private static void forceGlobalNightMode(int i4) {
        try {
            Class<?> cls = Class.forName("e.g");
            Field declaredField = cls.getDeclaredField("c");
            int i5 = 1;
            declaredField.setAccessible(true);
            if (declaredField.getInt(null) == i4) {
                if (i4 != 2 && i4 == 1) {
                    i5 = 2;
                }
                declaredField.setInt(null, i5);
            }
            cls.getMethod("w", Integer.TYPE).invoke(null, Integer.valueOf(i4));
        } catch (Throwable th) {
            Log.w("VulkanShim", "e.g.w: " + th.getMessage());
        }
    }

    private static Activity resolveThemeTarget(Context context) {
        Activity activity = context instanceof Activity ? (Activity) context : null;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            if (isThemeableSettingsUi(activity)) {
                return activity;
            }
            if (isEmulationActivity(activity) && hasInGameSettingsOverlay(activity)) {
                return activity;
            }
        }
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            prune();
            Iterator<WeakReference<Activity>> it = list.iterator();
            while (it.hasNext()) {
                Activity activity2 = it.next().get();
                if (activity2 != null && !activity2.isFinishing() && !activity2.isDestroyed() && isSettingsActivity(activity2)) {
                    return activity2;
                }
            }
            Iterator<WeakReference<Activity>> it2 = activities.iterator();
            while (it2.hasNext()) {
                Activity activity3 = it2.next().get();
                if (activity3 != null && !activity3.isFinishing() && !activity3.isDestroyed() && isThemeableSettingsUi(activity3)) {
                    return activity3;
                }
            }
            Activity activityForegroundActivity = ToggleWatchdog.foregroundActivity();
            if (activityForegroundActivity != null && !activityForegroundActivity.isFinishing() && !activityForegroundActivity.isDestroyed()) {
                if (isSettingsActivity(activityForegroundActivity)) {
                    return activityForegroundActivity;
                }
                if ((isEmulationActivity(activityForegroundActivity) && isThemeableSettingsUi(activityForegroundActivity)) || !isEmulationActivity(activityForegroundActivity)) {
                    return activityForegroundActivity;
                }
            }
            return null;
        }
    }

    private static boolean isThemeableSettingsUi(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (isSettingsActivity(activity) || PrefCompat.hasVisiblePreferenceUi(activity)) {
            return true;
        }
        return hasInGameSettingsOverlay(activity);
    }

    static boolean hasInGameSettingsOverlay(Activity activity) {
        if (activity == null || !isEmulationActivity(activity)) {
            return false;
        }
        if (hasMenuDialogFragment(activity)) {
            return true;
        }
        return hasSettingsOverlayUi(activity);
    }

    private static boolean hasMenuDialogFragment(Activity activity) {
        try {
            Class<?> cls = Class.forName("androidx.fragment.app.Fragment");
            Iterator<Object> it = PrefCompat.fragmentManagers(activity).iterator();
            while (it.hasNext()) {
                if (findMenuDialogInFm(it.next(), cls)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean findMenuDialogInFm(Object obj, Class<?> cls) {
        try {
            List list = (List) obj.getClass().getMethod("getFragments", null).invoke(obj, null);
            if (list == null) {
                return false;
            }
            for (Object obj2 : list) {
                if (obj2 != null) {
                    try {
                        if (findMenuDialogInFm(obj2.getClass().getMethod("getChildFragmentManager", null).invoke(obj2, null), cls)) {
                            return true;
                        }
                    } catch (Throwable unused) {
                    }
                    try {
                        if ("MenuDialogFragment".equals((String) cls.getMethod("getTag", null).invoke(obj2, null))) {
                            try {
                                Object objInvoke = cls.getMethod("getDialog", null).invoke(obj2, null);
                                if ((objInvoke instanceof Dialog) && ((Dialog) objInvoke).isShowing()) {
                                    return true;
                                }
                            } catch (Throwable unused2) {
                            }
                            if (Boolean.TRUE.equals((Boolean) cls.getMethod("isVisible", null).invoke(obj2, null))) {
                                return true;
                            }
                        } else {
                            continue;
                        }
                    } catch (Throwable unused3) {
                        continue;
                    }
                }
            }
        } catch (Throwable unused4) {
        }
        return false;
    }

    private static boolean hasSettingsOverlayUi(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            int identifier = activity.getResources().getIdentifier("tab_layout", "id", activity.getPackageName());
            if (identifier == 0) {
                return false;
            }
            if (viewShownInTree(activity.getWindow().getDecorView(), identifier)) {
                return true;
            }
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object objInvoke = cls.getMethod("getInstance", null).invoke(null, null);
            Field declaredField = cls.getDeclaredField("mViews");
            declaredField.setAccessible(true);
            List<View> list = (List) declaredField.get(objInvoke);
            if (list == null) {
                return false;
            }
            for (View view : list) {
                if (view != null && viewShownInTree(view, identifier)) {
                    return true;
                }
            }
        } catch (Throwable unused) {
        }
        return false;
    }

    private static boolean viewShownInTree(View view, int i4) {
        if (view == null) {
            return false;
        }
        try {
            View viewFindViewById = view.findViewById(i4);
            return viewFindViewById != null && viewFindViewById.isShown();
        } catch (Throwable unused) {
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                for (int i5 = 0; i5 < viewGroup.getChildCount(); i5++) {
                    if (viewShownInTree(viewGroup.getChildAt(i5), i4)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static Activity findMainActivity() {
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            prune();
            Iterator<WeakReference<Activity>> it = list.iterator();
            while (it.hasNext()) {
                Activity activity = it.next().get();
                if (activity != null && isMainActivity(activity) && !activity.isFinishing() && !activity.isDestroyed()) {
                    return activity;
                }
            }
            return null;
        }
    }

    private static Activity findEmulationActivity() {
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            prune();
            for (WeakReference<Activity> ref : list) {
                Activity activity = ref.get();
                if (activity != null && isEmulationActivity(activity)
                        && !activity.isFinishing() && !activity.isDestroyed()) {
                    return activity;
                }
            }
        }
        return null;
    }

    private static void prune() {
        Iterator<WeakReference<Activity>> it = activities.iterator();
        while (it.hasNext()) {
            Activity activity = it.next().get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                it.remove();
            }
        }
    }

    private static boolean isMainActivity(Activity activity) {
        return activity != null && MAIN_ACTIVITY.equals(activity.getClass().getName());
    }

    private static boolean isEmulationActivity(Activity activity) {
        return activity != null && EMULATION_ACTIVITY.equals(activity.getClass().getName());
    }

    private static boolean shouldDeferMainRecreate() {
        if (TurnipConfig.isEmuResumed()) {
            return true;
        }
        List<WeakReference<Activity>> list = activities;
        synchronized (list) {
            prune();
            Iterator<WeakReference<Activity>> it = list.iterator();
            while (it.hasNext()) {
                Activity activity = it.next().get();
                if (activity != null && isEmulationActivity(activity) && !activity.isFinishing() && !activity.isDestroyed()) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean isSettingsActivity(Activity activity) {
        return activity != null && SETTINGS_ACTIVITY.equals(activity.getClass().getName());
    }

    private static void clearThemeCache(Activity activity) {
        if (activity == null) {
            return;
        }
        for (Class<?> superclass = activity.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            try {
                Field declaredField = superclass.getDeclaredField("H");
                if (declaredField.getType() == String.class) {
                    declaredField.setAccessible(true);
                    declaredField.set(activity, null);
                    return;
                }
            } catch (Throwable unused) {
            }
        }
    }

    static boolean isSlushii(String str) {
        return SLUSHII.equals(str);
    }

    private static int modeFor(String str) {
        if (isSlushii(str)) {
            return 1;
        }
        if ("dark".equals(str)) {
            return 2;
        }
        if ("light".equals(str)) {
            return 1;
        }
        return MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
