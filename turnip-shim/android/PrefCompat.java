package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * NetherSX2 ships an R8-obfuscated androidx.preference:
 * PreferenceFragmentCompat → androidx.preference.b, findPreference → e(CharSequence),
 * no isChecked() (field R on TwoStatePreference), change listener → Preference$d.c(Object).
 */
final class PrefCompat {
    private PrefCompat() {}

    /** True while aligning the Graphics IR row to the game ini (not a click). */
    private static volatile boolean suppressIrApply;

    private static Class<?> prefFragmentCls;
    private static Class<?> twoStateCls;
    private static Class<?> preferenceCls;
    private static Class<?> changeListenerCls;
    private static Method findPrefMethod;
    private static Field checkedField;
    private static Field changeListenerField;
    private static boolean resolved;
    private static boolean resolveFailed;

    static synchronized boolean resolve() {
        if (resolved) return true;
        if (resolveFailed) return false;
        try {
            try {
                prefFragmentCls = Class.forName("androidx.preference.PreferenceFragmentCompat");
            } catch (ClassNotFoundException e) {
                prefFragmentCls = Class.forName("androidx.preference.b");
            }
            twoStateCls = Class.forName("androidx.preference.TwoStatePreference");
            preferenceCls = Class.forName("androidx.preference.Preference");
            try {
                changeListenerCls = Class.forName(
                        "androidx.preference.Preference$OnPreferenceChangeListener");
            } catch (ClassNotFoundException e) {
                changeListenerCls = Class.forName("androidx.preference.Preference$d");
            }

            for (Method m : prefFragmentCls.getMethods()) {
                if (m.getParameterTypes().length == 1
                        && (m.getParameterTypes()[0] == CharSequence.class
                        || m.getParameterTypes()[0] == String.class)
                        && preferenceCls.isAssignableFrom(m.getReturnType())) {
                    findPrefMethod = m;
                    break;
                }
            }
            if (findPrefMethod == null) {
                try {
                    findPrefMethod = prefFragmentCls.getMethod("findPreference", CharSequence.class);
                } catch (NoSuchMethodException e) {
                    findPrefMethod = prefFragmentCls.getMethod("e", CharSequence.class);
                }
            }

            checkedField = twoStateCls.getDeclaredField("R");
            checkedField.setAccessible(true);

            for (Field f : preferenceCls.getDeclaredFields()) {
                if (changeListenerCls.isAssignableFrom(f.getType())) {
                    changeListenerField = f;
                    changeListenerField.setAccessible(true);
                    break;
                }
            }

            resolved = findPrefMethod != null && checkedField != null;
            if (!resolved) resolveFailed = true;
            else android.util.Log.i("VulkanShim", "PrefCompat resolved frag="
                    + prefFragmentCls.getName()
                    + " find=" + findPrefMethod.getName()
                    + " listenerField="
                    + (changeListenerField != null ? changeListenerField.getName() : "none"));
            return resolved;
        } catch (Throwable t) {
            resolveFailed = true;
            android.util.Log.w("VulkanShim", "PrefCompat resolve failed: " + t);
            return false;
        }
    }

    static boolean isPreferenceFragment(Object frag) {
        return resolve() && frag != null && prefFragmentCls.isInstance(frag);
    }

    static Object findPreference(Object frag, String key) {
        if (!resolve() || frag == null || key == null) return null;
        try {
            return findPrefMethod.invoke(frag, key);
        } catch (Throwable t) {
            return null;
        }
    }

    static Boolean isChecked(Object pref) {
        if (!resolve() || pref == null || !twoStateCls.isInstance(pref)) return null;
        try {
            return checkedField.getBoolean(pref);
        } catch (Throwable t) {
            return null;
        }
    }

    static void setChangeListener(Object pref, String key, Context ctx) {
        // Keep the stock list-position listener; it does not carry a Boolean.
        if (OsdPrefs.isStockOsdPrefKey(key) && !OsdPrefs.isStatKey(key)) return;
        if (!resolve() || pref == null || changeListenerField == null) return;
        try {
            final boolean obfuscatedApi = hasMethod(changeListenerCls, "c", 1)
                    && !hasMethod(changeListenerCls, "onPreferenceChange", 2);
            Object listener = Proxy.newProxyInstance(
                    changeListenerCls.getClassLoader(),
                    new Class<?>[] { changeListenerCls },
                    (proxy, method, args) -> {
                        String n = method.getName();
                        if (obfuscatedApi && "c".equals(n) && args != null && args.length >= 1) {
                            applyKey(ctx, key, args[0]);
                            /* Must return true: null/false blocks persist, which is why
                             * Internal Resolution looked dead — we ate the click. */
                            return Boolean.TRUE;
                        }
                        if (!obfuscatedApi && args != null && args.length >= 2
                                && ("onPreferenceChange".equals(n) || args.length == 2)) {
                            applyKey(ctx, key, args[1]);
                            return true;
                        }
                        if (n.equals("hashCode")) return System.identityHashCode(proxy);
                        if (n.equals("equals")) return proxy == args[0];
                        if (n.equals("toString")) return "ShimPrefChangeListener(" + key + ")";
                        /* Obfuscated androidx treats null/false as "do not persist". */
                        return Boolean.TRUE;
                    });
            changeListenerField.set(pref, listener);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "setChangeListener: " + t.getMessage());
        }
    }

    private static boolean hasMethod(Class<?> cls, String name, int args) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args)
                return true;
        }
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args)
                return true;
        }
        return false;
    }

    static void applyKey(Context ctx, String key, Object newVal) {
        if (suppressIrApply && "EmuCore/GS/upscale_multiplier".equals(key))
            return;
        if ("EmuCore/GS/upscale_multiplier".equals(key)) {
            TurnipConfig.note(ctx.getExternalFilesDir(null),
                    "ListPreference IR click newVal=" + newVal
                            + (newVal != null ? " [" + newVal.getClass().getSimpleName() + "]" : ""));
            String ir = TurnipConfig.applyUserInternalResolution(ctx, newVal, true);
            if (ir != null)
                toast(ctx, TurnipConfig.irOsdLabel(ctx, ir));
            return;
        }
        boolean on = Boolean.TRUE.equals(newVal)
                || "true".equalsIgnoreCase(String.valueOf(newVal));
        if ("EmuCore/EnableCheats".equals(key)) {
            if (TurnipConfig.apply60FpsMode(ctx, on))
                toast(ctx, on ? "60 FPS ON" : "60 FPS OFF");
        } else if ("EmuCore/EnableWideScreenPatches".equals(key)) {
            TurnipConfig.applyWidescreenMode(ctx, on);
        } else if (ShimBuildConfig.FSR && ("VulkanShim/FsrQuality4x".equals(key)
                || "VulkanShim/UpscalerFsr1".equals(key))) {
            if (TurnipConfig.applyFsrMode(ctx, on))
                toast(ctx, on ? "FSR ON" : "FSR OFF");
        } else if ("UI/Theme".equals(key)) {
            android.util.Log.i("VulkanShim", "PrefCompat theme click newVal=" + newVal);
            ShimTheme.onThemeSpChanged(ctx, newVal);
        } else if (OsdPrefs.isStatKey(key)) {
            OsdPrefs.commitStockOsdToggle(ctx, key, on);
            TurnipConfig.applyOsdSettingsLive(ctx);
        }
    }

    static void toast(Context ctx, String msg) {
        try {
            if (ctx instanceof Activity) {
                ((Activity) ctx).runOnUiThread(
                        () -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
            }
        } catch (Throwable ignored) { }
    }

    /** Bind visible Switch/SwitchCompat rows by title — works even when Preference APIs differ. */
    static void bindVisibleSwitches(Activity activity) {
        if (activity == null) return;
        for (View root : allRootViews(activity)) bindVisibleSwitches(activity, root);
    }

    private static void bindVisibleSwitches(Activity activity, View decor) {
        try {
            bindNearTitle(activity, decor, "Enable 60 FPS Mode", (b, on) -> {
                if (TurnipConfig.apply60FpsMode(activity, on))
                    toast(activity, on ? "60 FPS ON" : "60 FPS OFF");
            }, null);
            if (ShimBuildConfig.FSR) {
                bindNearTitle(activity, decor, "FSR Mode", (b, on) -> {
                    if (TurnipConfig.applyFsrMode(activity, on))
                        toast(activity, on ? "FSR ON" : "FSR OFF");
                }, TurnipConfig.prefsFsrOn(TurnipConfig.defaultPrefsOrNull(activity)));
                bindNearTitle(activity, decor, "FSR x4 Mode", (b, on) -> {
                    if (TurnipConfig.applyFsrMode(activity, on))
                        toast(activity, on ? "FSR ON" : "FSR OFF");
                }, null);
            }
            bindOsdSwitch(activity, decor, "Show FPS", "EmuCore/GS/OsdShowFPS");
            bindOsdSwitch(activity, decor, "Show Speed", "EmuCore/GS/OsdShowSpeed");
            bindOsdSwitch(activity, decor, "Show CPU Usage", "EmuCore/GS/OsdShowCPU");
            bindOsdSwitch(activity, decor, "Show GPU Usage", "EmuCore/GS/OsdShowGPU");
            bindOsdSwitch(activity, decor, "Show Frame Times", "EmuCore/GS/OsdShowFrameTimes");
            bindOsdSwitch(activity, decor, "Show Resolution", "EmuCore/GS/OsdShowResolution");
            /* Button rows injected as SwitchPreference clones. Edge polling in
             * ToggleWatchdog misses taps when findCheckedInActivity returns null
             * on this obfuscated build, and users tap the row title not the
             * thumb. Bind directly like 60 FPS / FSR. */
            /* Grey out Frame Generation where it cannot work, rather than leaving a
             * switch that turns on a crash. The reason is shown in the row's own
             * summary so the user is not left guessing why it is dead. */
            String fgWhy = HandheldTier.framegenBlockReason(activity);
            if (fgWhy != null) dimRow(decor, "Frame Generation", fgWhy);
            else {
                restoreDimmedRow(decor, "Frame Generation");
                bindNearTitle(activity, decor, "Frame Generation", (b, on) -> {
                /* OBSERVE THE ROW DIRECTLY.
                 *
                 * Measured: turning this switch off never reached applyLsfgMode --
                 * "framegen disabled" had ZERO occurrences in the log while
                 * "framegen ON" had four, and turnip.conf stayed lsfg_overlay=on.
                 * The pref listener in ShimSettingsBridge is registered and correct,
                 * so nothing was persisting the switch for it to hear; the watchdog's
                 * view-hierarchy read returns null on this obfuscated APK, the same
                 * limitation documented on findCheckedInActivity.
                 *
                 * So do what the stock OSD rows do: notice the widget ourselves,
                 * commit the pref, and run the apply path. commit() not apply() --
                 * the listener has to see it before the screen is torn down. */
                try {
                    android.content.SharedPreferences sp =
                            TurnipConfig.defaultPrefsOrNull(activity);
                    if (sp != null) sp.edit().putBoolean("VulkanShim/Lsfg", on).commit();
                } catch (Throwable ignored) { }
                TurnipConfig.applyLsfgMode(activity, on);
                android.util.Log.i("VulkanShim", "framegen row observed -> " + on);
            }, null);
            }
            bindActionSwitch(activity, decor, "Turnip Driver",
                    () -> ShimDriverPicker.onToggled(activity));
            bindActionSwitch(activity, decor, "Export Bug Report",
                    () -> ShimBugReport.export(activity));
            bindNearTitle(activity, decor, "Low-End Performance", (b, on) -> {
                if (TurnipConfig.applyLowEndPerfMode(activity, on))
                    toast(activity, on ? "Low-end perf ON" : "Quality restored");
            }, null);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "bindVisibleSwitches: " + t.getMessage());
        }
    }

    private static void bindOsdSwitch(Activity activity, View root, String title, String key) {
        bindNearTitle(activity, root, title, (b, on) -> {
            OsdPrefs.commitStockOsdToggle(activity, key, on);
            TurnipConfig.applyOsdSettingsLive(activity);
        }, null);
    }

    /** True when a preference fragment is visible (Graphics / OSD settings open). */
    static boolean hasVisiblePreferenceUi(Activity activity) {
        if (activity == null) return false;
        try {
            View decor = activity.getWindow().getDecorView();
            if (findText(decor, "Show FPS") != null
                    || findText(decor, "On-Screen Display") != null
                    || findText(decor, "Graphics") != null
                    || findText(decor, "Theme") != null)
                return true;
            Class<?> fragCls = Class.forName("androidx.fragment.app.Fragment");
            for (Object fm : fragmentManagers(activity)) {
                if (hasVisiblePrefInFm(fm, fragCls))
                    return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    static void refreshVisiblePreferenceUi(Activity activity) {
        if (activity == null || !resolve()) return;
        try {
            Configuration configuration = activity.getResources().getConfiguration();
            /* NO Class.forName("androidx.fragment.app.Fragment") HERE.
             *
             * This APK is R8-obfuscated — the fragment class is shipped as
             * androidx.fragment.app.z — so that lookup threw
             * ClassNotFoundException on the FIRST line and the whole refresh
             * aborted. The only symptom was one warning whose message is just
             * the class name, and the theme change then logged success while
             * nothing on screen repainted: the in-game settings menu stayed on
             * the old light/dark palette.
             *
             * Everything below reflects off each fragment INSTANCE's own class,
             * which needs no name at all. */
            for (Object fm : fragmentManagers(activity))
                refreshPrefsInFm(fm, null, configuration);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "refreshVisiblePreferenceUi: " + t.getMessage());
        }
    }

    /**
     * Force every visible preference fragment to re-inflate.
     *
     * <p>A light/dark switch cannot be applied to an already-inflated view tree:
     * the rows were built against the old configuration, and neither
     * {@code updateConfiguration} nor {@code notifyDataSetChanged} restyles
     * them. In the app's own Settings the shim just recreates the activity, but
     * the in-game menu lives inside EmulationActivity and recreating THAT would
     * kill emulation — which is why the in-place path exists at all.
     *
     * <p>detach+attach in one transaction destroys and rebuilds the fragment's
     * view hierarchy while keeping the fragment instance and its state, so the
     * menu repaints in the new theme and the emulator is never touched.
     */
    static void reinflatePreferenceFragments(Activity activity) {
        if (activity == null || !resolve()) return;
        try {
            for (Object fm : fragmentManagers(activity))
                reinflateInFm(fm);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "reinflatePreferenceFragments: " + t.getMessage());
        }
    }

    private static void reinflateInFm(Object fm) {
        try {
            java.lang.reflect.Method getFrags = findMethod(fm.getClass(), "getFragments");
            if (getFrags == null) return;
            @SuppressWarnings("unchecked")
            List<Object> frags = (List<Object>) getFrags.invoke(fm);
            if (frags == null) return;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    java.lang.reflect.Method getChild =
                            findMethod(frag.getClass(), "getChildFragmentManager");
                    if (getChild != null) reinflateInFm(getChild.invoke(frag));
                } catch (Throwable ignored) { }
                /* Match on TAG, not class name. The in-game menu is a
                 * DialogFragment whose class R8 renamed to "b", with nested
                 * "j" and "e" inside it — a class-name match can never work.
                 * Its tag survives obfuscation: "MenuDialogFragment".
                 *
                 * (An earlier attempt matched SettingsNavFragment, which turned
                 * out to belong to the Daijishou LAUNCHER, not this app — the
                 * dumpsys block was misattributed.) */
                String fname = String.valueOf(frag.getClass().getSimpleName());
                String ftag = String.valueOf(fragTag(frag));
                boolean pref = isPreferenceFragment(frag);
                boolean menuHost = ftag.toLowerCase(java.util.Locale.US).contains("menu")
                        || ftag.toLowerCase(java.util.Locale.US).contains("settings")
                        || fname.toLowerCase(java.util.Locale.US).contains("settings");
                android.util.Log.i("VulkanShim", "theme walk: class=" + fname
                        + " tag=" + ftag + " pref=" + pref + " menu=" + menuHost);
                if (!pref && !menuHost) continue;
                if (!detachAttach(fm, frag))
                    android.util.Log.w("VulkanShim", "re-inflate: transaction unavailable");
                else
                    android.util.Log.i("VulkanShim",
                            "re-inflated preference fragment for theme change");
            }
        } catch (Throwable ignored) { }
    }

    /** detach(frag).attach(frag) committed now — resolved by arity, not by name. */
    private static boolean detachAttach(Object fm, Object frag) {
        try {
            java.lang.reflect.Method begin = findMethod(fm.getClass(), "beginTransaction");
            if (begin == null) return false;
            Object tx = begin.invoke(fm);
            if (tx == null) return false;
            java.lang.reflect.Method detach = findMethod1(tx.getClass(), "detach");
            java.lang.reflect.Method attach = findMethod1(tx.getClass(), "attach");
            java.lang.reflect.Method commit =
                    findMethod(tx.getClass(), "commitNowAllowingStateLoss");
            if (commit == null) commit = findMethod(tx.getClass(), "commitAllowingStateLoss");
            if (detach == null || attach == null || commit == null) return false;
            Object after = detach.invoke(tx, frag);
            attach.invoke(after != null ? after : tx, frag);
            commit.invoke(after != null ? after : tx);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "detachAttach: " + t.getMessage());
            return false;
        }
    }

    /**
     * Force every preference list in a window to re-inflate its rows.
     *
     * <p>This deliberately knows nothing about fragments. Four attempts to reach
     * the in-game menu through FragmentManagers all failed: the menu is a
     * DialogFragment R8-renamed to {@code b}, and from EmulationActivity every
     * manager reachable by method scan AND field scan holds only androidx's
     * lifecycle ReportFragment. But the dialog has its own WINDOW, and
     * {@code refreshOverlayWindows} already enumerates every window root — so
     * work from the view tree instead, where nothing is obfuscated away.
     *
     * <p>RecyclerView is identified by DUCK TYPING (it has getAdapter +
     * getRecycledViewPool), not by class name, because that class is renamed
     * too. Clearing the pool and re-setting the adapter forces
     * onCreateViewHolder, which re-inflates each row against the current
     * configuration — the one thing dispatchConfigurationChanged and
     * notifyDataSetChanged cannot do.
     *
     * @return number of lists rebuilt
     */
    static int reinflateListsIn(View root) {
        if (root == null) return 0;
        int n = 0;
        try {
            if (looksLikeRecycler(root) && reinflateOneList(root)) n++;
        } catch (Throwable ignored) { }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++)
                n += reinflateListsIn(g.getChildAt(i));
        }
        return n;
    }

    /**
     * Read-only counterpart of {@link #reinflateListsIn}: is there a list here?
     *
     * <p>Needed because "a dialog was on screen when the theme changed" and "a
     * dialog is on screen NOW" are different questions, and only the second one
     * may drive a back press.
     */
    static boolean hasListIn(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) return false;
        try {
            if (looksLikeRecycler(root) && root.isShown()) return true;
        } catch (Throwable ignored) { }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++)
                if (hasListIn(g.getChildAt(i))) return true;
        }
        return false;
    }

    private static boolean looksLikeRecycler(View v) {
        Class<?> c = v.getClass();
        return findMethod(c, "getAdapter") != null
                && findMethod(c, "getRecycledViewPool") != null;
    }

    private static boolean reinflateOneList(View v) {
        try {
            Class<?> c = v.getClass();
            java.lang.reflect.Method getA = findMethod(c, "getAdapter");
            java.lang.reflect.Method setA = findMethod1(c, "setAdapter");
            java.lang.reflect.Method getPool = findMethod(c, "getRecycledViewPool");
            if (getA == null || setA == null) return false;
            Object adapter = getA.invoke(v);
            if (adapter == null) return false;
            if (getPool != null) {
                Object pool = getPool.invoke(v);
                if (pool != null) {
                    java.lang.reflect.Method clear = findMethod(pool.getClass(), "clear");
                    if (clear != null) clear.invoke(pool);
                }
            }
            setA.invoke(v, new Object[]{null});
            setA.invoke(v, adapter);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fragment tag — survives R8 renaming, unlike the class name. */
    private static String fragTag(Object frag) {
        try {
            java.lang.reflect.Method m = findMethod(frag.getClass(), "getTag");
            Object t = m != null ? m.invoke(frag) : null;
            return t != null ? t.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Single-argument method by name, up the (possibly obfuscated) hierarchy. */
    private static java.lang.reflect.Method findMethod1(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** Public no-arg method by name, searched up the (possibly obfuscated) hierarchy. */
    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void refreshPrefsInFm(Object fragmentManager, Class<?> fragCls,
            Configuration configuration) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> frags = (List<Object>) fragmentManager.getClass()
                    .getMethod("getFragments").invoke(fragmentManager);
            if (frags == null) return;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    refreshPrefsInFm(child, fragCls, configuration);
                } catch (Throwable ignored) { }
                if (isPreferenceFragment(frag))
                    refreshPreferenceFragment(frag, fragCls, configuration);
            }
        } catch (Throwable ignored) { }
    }

    private static void refreshPreferenceFragment(Object frag, Class<?> fragCls,
            Configuration configuration) {
        try {
            /* Resolve off the instance, not a hardcoded class name — see the
             * note in refreshVisiblePreferenceUi about R8 obfuscation. */
            java.lang.reflect.Method getView = findMethod(frag.getClass(), "getView");
            Object viewObj = getView != null ? getView.invoke(frag) : null;
            if (viewObj instanceof View) {
                View view = (View) viewObj;
                if (configuration != null)
                    view.dispatchConfigurationChanged(configuration);
                view.invalidate();
            }
            Class<?> rvCls = Class.forName("androidx.recyclerview.widget.RecyclerView");
            Object rv = null;
            for (Field field : prefFragmentCls.getDeclaredFields()) {
                if (rvCls.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    rv = field.get(frag);
                    break;
                }
            }
            if (rv instanceof View) {
                View rvView = (View) rv;
                if (configuration != null)
                    rvView.dispatchConfigurationChanged(configuration);
                rvView.invalidate();
                try {
                    Object adapter = rvCls.getMethod("getAdapter").invoke(rv);
                    if (adapter != null)
                        adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                    /* notifyDataSetChanged only REBINDS DATA into views that
                     * already exist. Preference rows were inflated against the
                     * old theme, and RecyclerView recycles them happily, so a
                     * light/dark switch left the in-game settings menu on the
                     * old palette — the shim logged "in-place theme light"
                     * while the screen stayed dark.
                     *
                     * Dropping the recycled pool and re-setting the adapter
                     * forces onCreateViewHolder again, which re-inflates every
                     * row from the activity's now-current theme. */
                    Object pool = rvCls.getMethod("getRecycledViewPool").invoke(rv);
                    if (pool != null)
                        pool.getClass().getMethod("clear").invoke(pool);
                    if (adapter != null) {
                        java.lang.reflect.Method setAdapter = null;
                        for (java.lang.reflect.Method m : rvCls.getMethods()) {
                            if (m.getName().equals("setAdapter")
                                    && m.getParameterTypes().length == 1) {
                                setAdapter = m;
                                break;
                            }
                        }
                        if (setAdapter != null) {
                            setAdapter.invoke(rv, new Object[]{null});
                            setAdapter.invoke(rv, adapter);
                        }
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "refreshPreferenceFragment: " + t.getMessage());
        }
    }

    private static boolean hasVisiblePrefInFm(Object fragmentManager, Class<?> fragCls) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> frags = (List<Object>) fragmentManager.getClass()
                    .getMethod("getFragments").invoke(fragmentManager);
            if (frags == null) return false;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    if (hasVisiblePrefInFm(child, fragCls))
                        return true;
                } catch (Throwable ignored) { }
                if (!isPreferenceFragment(frag)) continue;
                try {
                    Object viewObj = fragCls.getMethod("getView").invoke(frag);
                    if (viewObj != null
                            && Boolean.TRUE.equals(viewObj.getClass().getMethod("isShown")
                                    .invoke(viewObj)))
                        return true;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    static List<Object> fragmentManagers(Activity activity) {
        ArrayList<Object> out = new ArrayList<>();
        if (activity == null) return out;
        ArrayList<Method> methods = new ArrayList<>();
        java.util.Collections.addAll(methods, activity.getClass().getMethods());
        java.util.Collections.addAll(methods, activity.getClass().getDeclaredMethods());
        for (Method method : methods) {
            if (method.getParameterTypes().length != 0) continue;
            Class<?> returnType = method.getReturnType();
            if (returnType == Void.TYPE || returnType.isPrimitive()) continue;
            try {
                if (!returnType.getName().startsWith("androidx.fragment.app.")) {
                    try {
                        returnType.getMethod("getFragments");
                    } catch (NoSuchMethodException noStockFragments) {
                        continue;
                    }
                }
                method.setAccessible(true);
                Object fm = method.invoke(activity);
                if (fm != null && !out.contains(fm)) {
                    out.add(fm);
                    android.util.Log.i("VulkanShim", "theme fragment manager "
                            + method.getName() + " -> " + fm.getClass().getName());
                }
            } catch (Throwable ignored) { }
        }
        /* Method scanning alone is not enough. On this APK it returns a manager
         * holding only androidx's lifecycle ReportFragment, while the in-game
         * MenuDialogFragment lives in a DIFFERENT manager (dumpsys shows its
         * host as an obfuscated "a", not the Activity). R8 renamed the
         * accessor, so no name-based lookup finds it — scan the fields instead
         * and take anything that quacks like a FragmentManager. */
        for (Class<?> c = activity.getClass(); c != null && c != Object.class;
             c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(activity);
                    if (v == null || out.contains(v)) continue;
                    if (findMethod(v.getClass(), "getFragments") == null) continue;
                    out.add(v);
                    android.util.Log.i("VulkanShim", "theme fragment manager field "
                            + f.getName() + " -> " + v.getClass().getName());
                } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    /** Supports both stock FragmentManager and NetherSX2's R8 manager (class y). */
    @SuppressWarnings("unchecked")
    static List<Object> fragmentsOf(Object fragmentManager) {
        if (fragmentManager == null) return null;
        try {
            Object value = fragmentManager.getClass().getMethod("getFragments").invoke(fragmentManager);
            if (value instanceof List) return (List<Object>) value;
        } catch (Throwable ignored) { }
        // NetherSX2 Pink's bundled FragmentManager is androidx.fragment.app.z,
        // with the active fragments held by y.f1362c (x.a).i().  R8 leaves
        // these member names stable in this build, so take this direct route
        // before the generic scan below.
        try {
            for (Class<?> type = fragmentManager.getClass(); type != null;
                    type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField("f1362c");
                    field.setAccessible(true);
                    Object holder = field.get(fragmentManager);
                    Method listMethod = holder.getClass().getDeclaredMethod("i");
                    listMethod.setAccessible(true);
                    Object value = listMethod.invoke(holder);
                    if (value instanceof List) {
                        android.util.Log.i("VulkanShim", "theme fragments via Pink manager");
                        return (List<Object>) value;
                    }
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
        try {
            for (Class<?> type = fragmentManager.getClass(); type != null;
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object holder = field.get(fragmentManager);
                        if (holder == null) continue;
                        if (holder instanceof List) return (List<Object>) holder;
                        ArrayList<Method> holderMethods = new ArrayList<>();
                        java.util.Collections.addAll(holderMethods, holder.getClass().getMethods());
                        java.util.Collections.addAll(holderMethods, holder.getClass().getDeclaredMethods());
                        for (Method method : holderMethods) {
                            if (method.getParameterTypes().length != 0
                                    || !List.class.isAssignableFrom(method.getReturnType())) continue;
                            method.setAccessible(true);
                            Object value = method.invoke(holder);
                            if (value instanceof List) {
                                android.util.Log.i("VulkanShim", "theme fragments via "
                                        + field.getName() + "." + method.getName());
                                return (List<Object>) value;
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static void augmentThemePreference(Object pref) {
        if (pref == null) return;
        try {
            CharSequence[] entries = getListEntries(pref);
            CharSequence[] values = getListEntryValues(pref);
            if (values != null) {
                for (CharSequence v : values) {
                    if (ShimTheme.SLUSHII.equals(String.valueOf(v)))
                        return;
                }
            }
            CharSequence[] newEntries = appendCharSeq(entries, "Slushii");
            CharSequence[] newValues = appendCharSeq(values, ShimTheme.SLUSHII);
            setListEntries(pref, newEntries);
            setListEntryValues(pref, newValues);
            clearListPrefResIds(pref);
            android.util.Log.i("VulkanShim", "augmented UI/Theme with Slushii");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "augmentThemePreference: " + t.getMessage());
        }
    }

    private static CharSequence[] getListEntries(Object pref) throws Exception {
        Method m = findListGetter(pref, CharSequence[].class, "getEntries");
        return m != null ? (CharSequence[]) m.invoke(pref) : null;
    }

    private static CharSequence[] getListEntryValues(Object pref) throws Exception {
        Method m = findListGetter(pref, CharSequence[].class, "getEntryValues");
        return m != null ? (CharSequence[]) m.invoke(pref) : null;
    }

    private static Method findListGetter(Object pref, Class<?> ret, String prefer) {
        Method hit = null;
        for (Method m : pref.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != ret)
                continue;
            if (m.getName().equals(prefer))
                return m;
            if (hit == null)
                hit = m;
        }
        return hit;
    }

    private static void setListEntries(Object pref, CharSequence[] entries) throws Exception {
        setListArray(pref, entries, "setEntries");
    }

    private static void setListEntryValues(Object pref, CharSequence[] values) throws Exception {
        setListArray(pref, values, "setEntryValues");
    }

    private static void setListArray(Object pref, CharSequence[] arr, String prefer) throws Exception {
        Class<?> arg = arr.getClass();
        for (Method m : pref.getClass().getMethods()) {
            if (m.getParameterTypes().length != 1 || !m.getParameterTypes()[0].isAssignableFrom(arg))
                continue;
            if (m.getName().equals(prefer) || m.getName().startsWith("set")) {
                m.invoke(pref, arr);
                return;
            }
        }
        throw new NoSuchMethodException(prefer);
    }

    /** Walk preference trees (nested categories) and augment every UI/Theme row. */
    static void augmentThemeInActivity(android.app.Activity activity) {
        if (activity == null || !resolve()) return;
        android.util.Log.i("VulkanShim", "augmentThemeInActivity " + activity.getClass().getName());
        try {
            for (Object fm : fragmentManagers(activity))
                augmentThemeInFm(fm);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "augmentThemeInActivity: " + t.getMessage());
        }
    }

    /** Re-augment before the list dialog opens (ListPreference reloads @array entries). */
    static void armThemePreferenceDialog(Object pref) {
        if (pref == null || !resolve()) return;
        try {
            Method setListener = null;
            Class<?> clickCls = null;
            for (Method m : pref.getClass().getMethods()) {
                if (!"setOnPreferenceClickListener".equals(m.getName())
                        || m.getParameterTypes().length != 1)
                    continue;
                setListener = m;
                clickCls = m.getParameterTypes()[0];
                break;
            }
            if (setListener == null || clickCls == null || !clickCls.isInterface())
                return;
            Object listener = Proxy.newProxyInstance(
                    clickCls.getClassLoader(),
                    new Class<?>[] { clickCls },
                    (proxy, method, args) -> {
                        augmentThemePreference(pref);
                        return false;
                    });
            setListener.invoke(pref, listener);
            android.util.Log.i("VulkanShim", "armed UI/Theme click augment");
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "armThemePreferenceDialog: " + t.getMessage());
        }
    }

    private static void augmentThemeInFm(Object fragmentManager) {
        try {
            List<Object> frags = fragmentsOf(fragmentManager);
            if (frags == null) return;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    augmentThemeInFm(child);
                } catch (Throwable ignored) { }
                if (isPreferenceFragment(frag)) {
                    Object root = findPreference(frag, "UI/Theme");
                    if (root != null) {
                        augmentThemePreference(root);
                        continue;
                    }
                    Object screen = getPreferenceScreen(frag);
                    if (screen != null)
                        walkPreferenceGroup(screen);
                } else {
                    tryAugmentThemeOnFragment(frag);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** ViewPager tabs may host preference fragments behind non-pref fragment wrappers. */
    private static void tryAugmentThemeOnFragment(Object frag) {
        try {
            Object pref = findPrefMethod.invoke(frag, "UI/Theme");
            if (pref != null)
                augmentThemePreference(pref);
        } catch (Throwable ignored) { }
    }

    private static Object getPreferenceScreen(Object frag) {
        if (frag == null) return null;
        for (Method m : frag.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (!"androidx.preference.PreferenceScreen".equals(m.getReturnType().getName()))
                continue;
            try {
                return m.invoke(frag);
            } catch (Throwable ignored) { }
        }
        try {
            return frag.getClass().getMethod("getPreferenceScreen").invoke(frag);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void walkPreferenceGroup(Object group) {
        if (group == null) return;
        try {
            String key = getPreferenceKey(group);
            if ("UI/Theme".equals(key)) {
                augmentThemePreference(group);
                return;
            }
            int count = 0;
            for (Method m : group.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != int.class) continue;
                if (!m.getName().equals("getPreferenceCount") && !m.getName().equals("b"))
                    continue;
                count = (Integer) m.invoke(group);
                break;
            }
            Method getPref = null;
            for (Method m : group.getClass().getMethods()) {
                if (m.getParameterTypes().length != 1) continue;
                if (m.getParameterTypes()[0] != int.class) continue;
                if (!preferenceCls.isAssignableFrom(m.getReturnType())) continue;
                getPref = m;
                break;
            }
            if (getPref == null) return;
            for (int i = 0; i < count; i++) {
                Object child = getPref.invoke(group, i);
                if (child == null) continue;
                if ("UI/Theme".equals(getPreferenceKey(child))) {
                    augmentThemePreference(child);
                } else {
                    walkPreferenceGroup(child);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "walkPreferenceGroup: " + t.getMessage());
        }
    }

    private static Object invokeList(Object pref, String name, Object... args) throws Exception {
        Class<?> argType = args != null && args.length == 1 && args[0] != null
                ? args[0].getClass() : null;
        if (args != null && args.length == 1 && args[0] != null
                && args[0].getClass().isArray()) {
            argType = args[0].getClass();
        }
        for (Method m : pref.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (args == null || args.length == 0) {
                if (m.getParameterTypes().length == 0)
                    return m.invoke(pref);
            } else if (m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isAssignableFrom(argType)) {
                return m.invoke(pref, args[0]);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object invokeList(Object pref, String name) throws Exception {
        return invokeList(pref, name, (Object[]) null);
    }

    /** Stop ListPreference from reloading @array/theme_* after setEntries(). */
    private static void clearListPrefResIds(Object pref) {
        if (pref == null) return;
        try {
            for (Field f : pref.getClass().getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                int v = f.getInt(pref);
                if ((v >>> 24) == 0x7f) {
                    f.setInt(pref, 0);
                }
            }
            Class<?> sup = pref.getClass().getSuperclass();
            while (sup != null && !sup.equals(Object.class)) {
                for (Field f : sup.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    f.setAccessible(true);
                    int v = f.getInt(pref);
                    if ((v >>> 24) == 0x7f)
                        f.setInt(pref, 0);
                }
                sup = sup.getSuperclass();
            }
        } catch (Throwable ignored) { }
    }

    private static CharSequence[] appendCharSeq(CharSequence[] src, String item) {
        int n = src != null ? src.length : 0;
        CharSequence[] out = new CharSequence[n + 1];
        if (src != null)
            System.arraycopy(src, 0, out, 0, n);
        out[n] = item;
        return out;
    }

    static String findVisibleThemeChoice(Activity activity) {
        if (activity == null) return null;
        String fromPref = findListValueInActivity(activity, "UI/Theme");
        if (fromPref != null)
            return ShimTheme.coerce(fromPref);
        if (!hasVisiblePreferenceUi(activity)) return null;
        try {
            TextView title = findText(activity.getWindow().getDecorView(), "Theme");
            if (title == null) return null;
            return themeFromSummary(nearbySummary(title));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String themeFromSummary(String summary) {
        if (summary == null) return null;
        String lower = summary.trim().toLowerCase(Locale.US);
        if (lower.equals("dark") || lower.contains("dark") || lower.contains("oscuro")
                || lower.contains("dunkel") || lower.contains("sombre")
                || lower.contains("gelap") || lower.contains("ダーク") || lower.contains("深色"))
            return "dark";
        if (lower.equals("light") || lower.contains("light") || lower.contains("claro")
                || lower.contains("hell") || lower.contains("clair")
                || lower.contains("terang") || lower.contains("ライト") || lower.contains("浅色"))
            return "light";
        if (lower.contains("follow") || lower.contains("system") || lower.contains("default")
                || lower.contains("sistema") || lower.contains("システム"))
            return "follow_system";
        if (lower.contains("slushii") || lower.contains("slush"))
            return ShimTheme.SLUSHII;
        return null;
    }

    private static String getPreferenceKey(Object pref) {
        if (pref == null) return null;
        try {
            Object key = pref.getClass().getMethod("getKey").invoke(pref);
            return key != null ? String.valueOf(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void bindNearTitle(Activity activity, View root, String title,
            CompoundButton.OnCheckedChangeListener listener, Boolean checked) {
        TextView tv = findText(root, title);
        if (tv == null) return;
        CompoundButton sw = findSwitchNear(tv);
        if (sw == null) return;
        /* Always rebind — Preference rebinding clears our listener. */
        sw.setOnCheckedChangeListener(null);
        if (checked != null && sw.isChecked() != checked)
            sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
    }

    /**
     * Rows that are buttons dressed as switches (Turnip Driver, Bug Report).
     * Any toggle opens the action; the checked state is meaningless so reset it.
     */
    /**
     * The preference ROW that contains this view: the ancestor that is a direct
     * child of the RecyclerView/ListView backing the screen.
     *
     * Walking up "until something isClickable()" is not safe here -- the list
     * itself can answer true, and disabling or dimming THAT takes every row on the
     * screen with it. Anchoring to the list's direct child cannot over-reach.
     * Returns null rather than guessing.
     */
    private static View rowContainerFor(View tv) {
        View v = tv;
        for (int up = 0; up < 8 && v != null; up++) {
            Object p = v.getParent();
            if (!(p instanceof View)) return null;
            String pn = p.getClass().getName();
            if (pn.contains("RecyclerView") || pn.contains("ListView")) return v;
            v = (View) p;
        }
        return null;
    }

    private static final java.util.WeakHashMap<View, Object[]> dimmedRows =
            new java.util.WeakHashMap<>();

    /** Undo only our temporary changes when the same open row becomes available. */
    private static void restoreDimmedRow(View root, String title) {
        TextView tv = findText(root, title);
        if (tv == null) return;
        View row = rowContainerFor(tv);
        View target = row != null ? row : tv;
        Object[] old = dimmedRows.remove(target);
        if (old == null) return;
        target.setEnabled((Boolean) old[0]);
        target.setClickable((Boolean) old[1]);
        target.setAlpha((Float) old[2]);
        CompoundButton sw = findSwitchNear(tv);
        if (sw != null) sw.setEnabled((Boolean) old[3]);
        TextView summary = target instanceof ViewGroup
                ? firstOtherTextView((ViewGroup) target, tv) : null;
        if (summary != null && old[4] != null) summary.setText((CharSequence) old[4]);
    }

    /** Disable a preference row in place and replace its summary with the reason. */
    private static void dimRow(View root, String title, String why) {
        try {
            TextView tv = findText(root, title);
            if (tv == null) return;
            View row = rowContainerFor(tv);
            /* Fall back to the TITLE ONLY. Dimming an unknown ancestor is how the
             * whole preference list got disabled. */
            View target = row != null ? row : tv;
            CompoundButton sw = findSwitchNear(tv);
            TextView originalSummary = target instanceof ViewGroup
                    ? firstOtherTextView((ViewGroup) target, tv) : null;
            if (!dimmedRows.containsKey(target))
                dimmedRows.put(target, new Object[] { target.isEnabled(), target.isClickable(),
                        target.getAlpha(), sw == null || sw.isEnabled(),
                        originalSummary == null ? null : originalSummary.getText().toString() });
            target.setEnabled(false);
            target.setClickable(false);
            target.setAlpha(0.45f);
            if (sw != null) {
                sw.setOnCheckedChangeListener(null);
                // Preserve the saved choice while the display is temporarily unavailable.
                sw.setEnabled(false);
            }
            /* The summary is the other TextView in the row; overwrite it so the row
             * explains itself. If the row has no summary view, leave the title be --
             * appending to the title would corrupt the string the watchdog and
             * findCheckedByTitle both match on. */
            if (target instanceof ViewGroup) {
                TextView summary = firstOtherTextView((ViewGroup) target, tv);
                if (summary != null) summary.setText(why);
            }
            android.util.Log.i("VulkanShim", "framegen row greyed out: " + why);
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "dimRow " + title + ": " + t);
        }
    }

    private static TextView firstOtherTextView(ViewGroup g, TextView skip) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == skip) continue;
            if (c instanceof TextView) return (TextView) c;
            if (c instanceof ViewGroup) {
                TextView t = firstOtherTextView((ViewGroup) c, skip);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static void bindActionSwitch(Activity activity, View root, String title,
            Runnable action) {
        if (activity == null || action == null) return;
        TextView tv = findText(root, title);
        if (tv == null) return;
        CompoundButton sw = findSwitchNear(tv);
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        /* The thumb is reset to unchecked so the row reads as a launcher, not a
         * setting. That reset must not re-enter this listener, so the listener is
         * detached around it -- and MUST be re-attached, or the row fires exactly
         * once per Settings visit and then looks like a dead toggle. */
        final CompoundButton.OnCheckedChangeListener[] self =
                new CompoundButton.OnCheckedChangeListener[1];
        self[0] = (b, on) -> {
            try {
                b.setOnCheckedChangeListener(null);
                b.setChecked(false);
                b.setOnCheckedChangeListener(self[0]);
                action.run();
            } catch (Throwable t) {
                try { b.setOnCheckedChangeListener(self[0]); } catch (Throwable ignored) { }
                android.util.Log.w("VulkanShim", "action switch " + title + ": " + t);
            }
        };
        sw.setOnCheckedChangeListener(self[0]);
        View row = tv;
        boolean rowBound = false;
        View rowView = rowContainerFor(tv);
        if (rowView != null) {
            /* Make it clickable rather than requiring it to already BE clickable.
             * An androidx preference row is a RecyclerView item whose clickability
             * is owned by the framework, so the old "walk up until isClickable()"
             * test usually failed -- which left the switch visible and the row
             * reading as a toggle. */
            rowView.setClickable(true);
            /* VERIFY THE ROW AT TAP TIME. This container belongs to a RecyclerView,
             * which RECYCLES it: scroll away and the same view is reused for a
             * different preference while this listener stays attached, so a tap on
             * an unrelated row (an OSD toggle, say) would run this action instead.
             * Re-find the title inside the view before acting, and no-op if it has
             * been recycled to something else. */
            final View boundRow = rowView;
            rowView.setOnClickListener(v -> {
                try {
                    if (findText(boundRow, title) == null) return;
                    action.run();
                } catch (Throwable ignored) { }
            });
            rowBound = true;
        }
        /* A switch on an action row reads as a setting the user can leave ON, and
         * they then go hunting for what it turned on. These rows are launchers --
         * they open a chooser and come straight back -- so hide the widget and let
         * the row be a plain tappable entry.
         *
         * ONLY when the row click actually bound. The switch is otherwise the last
         * remaining way to reach the action: an earlier session hid it
         * unconditionally, the sole detector went with it, and the row went dead. */
        if (rowBound) {
            try { sw.setVisibility(View.GONE); } catch (Throwable ignored) { }
        }
    }

    private static TextView findText(View root, String exact) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null) {
                String s = t.toString().trim();
                if (exact.equals(s) || s.startsWith(exact))
                    return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView f = findText(g.getChildAt(i), exact);
                if (f != null) return f;
            }
        }
        return null;
    }

    /**
     * Read a switch row straight off the screen, by its visible title.
     *
     * <p>{@link #findCheckedInActivity} reflects on androidx's Preference
     * internals, and on this R8-obfuscated APK every one of those reads comes
     * back null — which is why the poll line has printed {@code ui60=null
     * uiFsr=null} for as long as it has existed, and why the Frame Generation
     * switch did nothing at all: the watchdog polled a reader that could never
     * answer. The row is right there on the screen; read it there instead.
     *
     * <p>Only sees a row that is scrolled into view, which is exactly when
     * somebody is touching it.
     */
    static Boolean findCheckedByTitle(Activity activity, String title) {
        if (activity == null || title == null) return null;
        try {
            for (View root : allRootViews(activity)) {
                TextView t = findText(root, title);
                if (t == null) continue;
                CompoundButton sw = findSwitchNear(t);
                if (sw != null) return sw.isChecked();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static CompoundButton findSwitchNear(View from) {
        View walk = from;
        for (int up = 0; up < 8 && walk != null; up++) {
            CompoundButton sw = findSwitch(walk);
            if (sw != null) return sw;
            Object p = walk.getParent();
            walk = p instanceof View ? (View) p : null;
        }
        return null;
    }

    private static CompoundButton findSwitch(View root) {
        if (root instanceof CompoundButton) return (CompoundButton) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                CompoundButton sw = findSwitch(g.getChildAt(i));
                if (sw != null) return sw;
            }
        }
        return null;
    }

    /**
     * Read a switch row's on-screen state.
     *
     * <p>NO {@code Class.forName("androidx.fragment.app.*")} ANYWHERE BELOW — see
     * {@link #refreshVisiblePreferenceUi}. This APK is R8-obfuscated and ships
     * the fragment classes as {@code androidx.fragment.app.z} / {@code y}, so
     * both lookups this used to open with threw {@code
     * ClassNotFoundException} on their first line and the whole read returned
     * null, silently, forever. That is why the Frame Generation switch "did
     * nothing": ToggleWatchdog polls this for {@code VulkanShim/Lsfg}, got null
     * on every tick, and never called applyLsfgMode — no conf write, no log,
     * nothing to find. {@code ui60}/{@code uiFsr} in the poll line were reading
     * null for the same reason.
     *
     * <p>{@link #fragmentManagers} and {@link #fragmentsOf} already do this the
     * name-free way, and everything else here reflects off each instance's own
     * class, which needs no name at all.
     */
    static Boolean findCheckedInActivity(Activity activity, String key) {
        if (activity == null || !resolve()) return null;
        try {
            Boolean visible = null;
            Boolean any = null;
            for (Object fm : fragmentManagers(activity)) {
                Boolean found = findCheckedInFm(fm, key);
                if (found == null) continue;
                if (visible == null) visible = found;
                if (any == null) any = found;
            }
            return visible != null ? visible : any;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean findCheckedInFm(Object fragmentManager, String key) {
        try {
            List<Object> frags = fragmentsOf(fragmentManager);
            if (frags == null) return null;
            Boolean visible = null;
            Boolean any = null;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    Boolean nested = findCheckedInFm(child, key);
                    if (nested != null) {
                        if (isResumed(frag)) visible = nested;
                        else if (any == null) any = nested;
                    }
                } catch (Throwable ignored) { }
                if (!isPreferenceFragment(frag)) continue;
                Object pref = findPreference(frag, key);
                Boolean checked = isChecked(pref);
                if (checked == null) continue;
                boolean shown = false;
                try {
                    Object view = frag.getClass().getMethod("getView").invoke(frag);
                    if (view != null)
                        shown = (Boolean) view.getClass().getMethod("isShown").invoke(view);
                } catch (Throwable ignored) { }
                if (isResumed(frag) && shown) visible = checked;
                else if (any == null) any = checked;
                /* Keep change listener attached. */
                try {
                    Context ctx = (Context) frag.getClass().getMethod("requireContext").invoke(frag);
                    setChangeListener(pref, key, ctx);
                } catch (Throwable t) {
                    try {
                        Activity a = (Activity) frag.getClass().getMethod("getActivity").invoke(frag);
                        if (a != null) setChangeListener(pref, key, a);
                    } catch (Throwable ignored) { }
                }
            }
            return visible != null ? visible : any;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isResumed(Object frag) {
        try {
            return Boolean.TRUE.equals(frag.getClass().getMethod("isResumed").invoke(frag));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Point the on-screen Upscale Multiplier row at live GS. Not a user click.
     */
    /**
     * The activity's decor plus every other root window the app owns — dialogs
     * included. ShimTheme uses the same WindowManagerGlobal.mViews reflection;
     * an in-game settings screen or picker is a separate window and is invisible
     * to anything that only walks the activity's decor.
     */
    private static java.util.List<View> allRootViews(Activity activity) {
        java.util.List<View> out = new java.util.ArrayList<>();
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor != null) out.add(decor);
        } catch (Throwable ignored) { }
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object inst = cls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = cls.getDeclaredField("mViews");
            f.setAccessible(true);
            Object v = f.get(inst);
            if (v instanceof java.util.List) {
                for (Object o : new java.util.ArrayList<>((java.util.List<?>) v))
                    if (o instanceof View && !out.contains(o)) out.add((View) o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void setVisibleIrChoice(Activity activity, String ir) {
        if (activity == null) return;
        String want = TurnipConfig.formatUpscaleMultiplier(ir);
        if (want == null) return;
        suppressIrApply = true;
        try {
            setListValueInActivity(activity, "EmuCore/GS/upscale_multiplier", want);
            /* SEARCH EVERY ROOT VIEW, NOT JUST THE ACTIVITY'S DECOR.
             *
             * The in-game settings screen is a DIALOG and owns its own window,
             * so the row is not in the activity's decor at all — the summary
             * update silently did nothing there, and the Graphics row went on
             * showing the old value after a provider setIr. Measured: row read
             * "2.5x Native" while the log said "Internal Resolution write
             * 1.500000". findVisibleIrChoice already works around this on the
             * read side; this is the same trap on the write side. */
            TextView title = null;
            for (View root : allRootViews(activity)) {
                title = findText(root, "Upscale Multiplier");
                if (title == null) title = findText(root, "Internal Resolution");
                if (title != null) break;
            }
            if (title != null) {
                String summary = nearbySummary(title);
                String shown = TurnipConfig.formatUpscaleMultiplier(summary);
                if (shown != null && !want.equals(shown)) {
                    String label = TurnipConfig.IR_NATIVE.equals(want)
                            ? "Native" : want.replaceFirst("0+$", "").replaceFirst("\\.$", "") + "x";
                    setNearbySummary(title, label);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("VulkanShim", "setVisibleIrChoice: " + t.getMessage());
        } finally {
            suppressIrApply = false;
        }
    }

    static String findVisibleIrChoice(Activity activity) {
        if (activity == null) return null;
        String fromPref = findListValueInActivity(activity, "EmuCore/GS/upscale_multiplier");
        String formatted = TurnipConfig.formatUpscaleMultiplier(fromPref);
        if (formatted != null)
            return formatted;
        try {
            View decor = activity.getWindow().getDecorView();
            TextView title = findText(decor, "Upscale Multiplier");
            if (title == null)
                title = findText(decor, "Internal Resolution");
            if (title == null)
                return null;
            String summary = nearbySummary(title);
            return TurnipConfig.formatUpscaleMultiplier(summary);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nearbySummary(View title) {
        View walk = title;
        for (int up = 0; up < 6 && walk != null; up++) {
            String s = firstOtherText(walk, title);
            if (s != null)
                return s;
            Object p = walk.getParent();
            walk = p instanceof View ? (View) p : null;
        }
        return null;
    }

    private static String firstOtherText(View root, View skip) {
        if (root instanceof TextView && root != skip) {
            CharSequence t = ((TextView) root).getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty()
                        && !s.equals("Upscale Multiplier")
                        && !s.equals("Internal Resolution"))
                    return s;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                String s = firstOtherText(g.getChildAt(i), skip);
                if (s != null) return s;
            }
        }
        return null;
    }

    static String findListValueInActivity(Activity activity, String key) {
        if (activity == null || !resolve()) return null;
        try {
            Class<?> fragAct = Class.forName("androidx.fragment.app.FragmentActivity");
            if (!fragAct.isInstance(activity)) return null;
            Object fm = fragAct.getMethod("getSupportFragmentManager").invoke(activity);
            return findListValueInFm(fm, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String findListValueInFm(Object fragmentManager, String key) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> frags = (List<Object>) fragmentManager.getClass()
                    .getMethod("getFragments").invoke(fragmentManager);
            if (frags == null) return null;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    String nested = findListValueInFm(child, key);
                    if (nested != null) return nested;
                } catch (Throwable ignored) { }
                if (!isPreferenceFragment(frag)) continue;
                Object pref = findPreference(frag, key);
                if (pref == null) continue;
                try {
                    Context ctx = (Context) frag.getClass().getMethod("requireContext").invoke(frag);
                    setChangeListener(pref, key, ctx);
                } catch (Throwable t) {
                    try {
                        Activity a = (Activity) frag.getClass().getMethod("getActivity").invoke(frag);
                        if (a != null) setChangeListener(pref, key, a);
                    } catch (Throwable ignored) { }
                }
                String v = listPreferenceValue(pref);
                if (v != null) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void setListValueInActivity(Activity activity, String key, String value) {
        if (activity == null || !resolve()) return;
        try {
            Class<?> fragAct = Class.forName("androidx.fragment.app.FragmentActivity");
            if (!fragAct.isInstance(activity)) return;
            Object fm = fragAct.getMethod("getSupportFragmentManager").invoke(activity);
            setListValueInFm(fm, key, value);
        } catch (Throwable ignored) { }
    }

    private static boolean setListValueInFm(Object fragmentManager, String key, String value) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> frags = (List<Object>) fragmentManager.getClass()
                    .getMethod("getFragments").invoke(fragmentManager);
            if (frags == null) return false;
            for (Object frag : frags) {
                if (frag == null) continue;
                try {
                    Object child = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    if (setListValueInFm(child, key, value))
                        return true;
                } catch (Throwable ignored) { }
                if (!isPreferenceFragment(frag)) continue;
                Object pref = findPreference(frag, key);
                if (pref == null) continue;
                if (setListPreferenceValue(pref, value))
                    return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean setListPreferenceValue(Object pref, String value) {
        if (pref == null || value == null) return false;
        try {
            Method m = pref.getClass().getMethod("setValue", String.class);
            m.invoke(pref, value);
            return true;
        } catch (Throwable ignored) { }
        for (Method m : pref.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != String.class)
                continue;
            String n = m.getName();
            if (!n.startsWith("set") || n.equals("setKey") || n.equals("setTitle")
                    || n.equals("setSummary") || n.equals("setDependency"))
                continue;
            try {
                m.invoke(pref, value);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static void setNearbySummary(View title, String value) {
        View walk = title;
        for (int up = 0; up < 6 && walk != null; up++) {
            if (setFirstOtherText(walk, title, value))
                return;
            Object p = walk.getParent();
            walk = p instanceof View ? (View) p : null;
        }
    }

    private static boolean setFirstOtherText(View root, View skip, String value) {
        if (root instanceof TextView && root != skip) {
            CharSequence t = ((TextView) root).getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty()
                        && !s.equals("Upscale Multiplier")
                        && !s.equals("Internal Resolution")
                        && TurnipConfig.formatUpscaleMultiplier(s) != null) {
                    ((TextView) root).setText(value);
                    return true;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (setFirstOtherText(g.getChildAt(i), skip, value))
                    return true;
            }
        }
        return false;
    }

    private static String listPreferenceValue(Object pref) {
        if (pref == null) return null;
        try {
            Method m = pref.getClass().getMethod("getValue");
            Object v = m.invoke(pref);
            if (v != null) {
                if ("UI/Theme".equals(getPreferenceKey(pref)))
                    return ShimTheme.coerce(v);
                return String.valueOf(v);
            }
        } catch (Throwable ignored) { }
        for (Method m : pref.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class)
                continue;
            String n = m.getName();
            if (n.equals("getKey") || n.equals("getTitle") || n.equals("getSummary")
                    || n.equals("toString") || n.equals("getFragment")
                    || n.equals("getDependency") || n.equals("getExtras"))
                continue;
            try {
                Object v = m.invoke(pref);
                if (v == null) continue;
                String s = String.valueOf(v);
                if (TurnipConfig.formatUpscaleMultiplier(s) != null)
                    return s;
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
