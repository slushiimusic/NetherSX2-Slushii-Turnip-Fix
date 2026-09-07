package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;


/* Neon cube logo palette: cyan → lime → magenta → hot pink. */
final class ShimAccent {
    private static final int TEAL = 0xFF008080;
    private static final int TEAL_DARK = 0xFF006666;
    private static final int NEON_CYAN = 0xFF00E8FF;
    private static final int LIME = 0xFF7AE02E;
    private static final int MAGENTA = 0xFF9B2DB8;
    private static final int HOT_PINK = 0xFFFF3D9A;
    private static final int PINK_SOFT = 0xFFFF8FB3;
    private static final String MAIN_ACTIVITY = "xyz.aethersx2.android.MainActivity";
    private static final String SETUP_MUSIC_ASSET = "slushii_setup.mp3";
    private static MediaPlayer setupMusic;
    /** Setup-wizard music level, 0..1. Deliberately well below full scale. */
    private static final float SETUP_MUSIC_VOLUME = 0.10f;
    private static final Set<Integer> pendingSettingsFirstDraw = new HashSet<>();
    /* Prevent the platform ActionBar from being visible before its packaged
     * Slushii drawable is attached. This gate is removed after one layout. */
    private static final Set<Integer> hiddenSettingsUntilBranded = new HashSet<>();

    /* The base APK's own colorAccent — a near-black olive. Measured off the
     * device rather than guessed: brightest pixel of the selected tab label,
     * the tab indicator and a dialog's CANCEL are all exactly #344529, and the
     * white labels beside them are a full #FFFFFF, so nothing is dimming it.
     *
     * It survives everything else in this file because it comes from
     * resources.arsc, which this build never repacks — we only ever recolour
     * views at runtime. On the dark settings background it is very nearly
     * invisible, which is why it had to go. */
    private static final int STOCK_ACCENT_RGB = 0x344529;
    /** Replacement: the cube logo's cyan, already the palette's aqua. */
    private static final int ACCENT_AQUA = NEON_CYAN;

    private ShimAccent() {
    }

    /**
     * Repaints the stock olive accent aqua wherever it survived our theming.
     *
     * <p>Three places use it: the selected tab's label, the tab indicator, and
     * dialog buttons (CANCEL on the pickers, and the load/save-state dialog).
     * Dialogs are the reason this walks WindowManagerGlobal.mViews rather than
     * the activity's decor view — a dialog is its own window and is NOT in the
     * activity's tree, which is the same reason ShimTheme reaches for mViews.
     *
     * <p>Matched by COLOUR rather than by view id. The ids belong to the base
     * app and to the material library, differ between the two, and are not
     * stable across its versions; the colour is one constant that is by
     * definition exactly the thing being complained about. Anything already
     * themed (the pink headers, the pink radio buttons) does not match and is
     * left alone.
     */
    /** turnip.conf accent_aqua — off disables the recolour, for A/B testing. */
    private static int accentAquaGate;   /* 0 unknown, 1 on, 2 off */

    static void recolorStockAccent(Activity activity) {
        try {
            if (accentAquaGate == 0 && activity != null) {
                java.io.File files = activity.getExternalFilesDir(null);
                String v = files == null ? null : TurnipConfig.readConfKey(
                        new java.io.File(files, "turnip.conf"), "accent_aqua");
                accentAquaGate = (v != null
                        && (v.equalsIgnoreCase("off") || v.equals("0"))) ? 2 : 1;
            }
            if (accentAquaGate == 2) return;
            if (activity != null && activity.getWindow() != null)
                recolorTree(activity.getWindow().getDecorView());
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object global = cls.getMethod("getInstance", (Class<?>[]) null)
                    .invoke(null, (Object[]) null);
            java.lang.reflect.Field f = cls.getDeclaredField("mViews");
            f.setAccessible(true);
            Object raw = f.get(global);
            if (!(raw instanceof java.util.List)) return;
            /* Snapshot: a dialog can be added or removed while we walk. */
            for (Object o : new java.util.ArrayList<Object>((java.util.List<?>) raw))
                if (o instanceof View) recolorTree((View) o);
        } catch (Throwable ignored) {
        }
    }

    private static void recolorTree(View view) {
        if (view == null) return;
        try {
            /* TabLayout needs its own call: the label colour is a
             * ColorStateList (selected vs not), so a flat setTextColor would
             * leave a tab aqua after the user moved to another one. */
            if (view.getClass().getName().endsWith("TabLayout"))
                retintTabLayout(view);
            if (view instanceof TextView)
                retintText((TextView) view);
            android.graphics.drawable.Drawable bg = view.getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable
                    && (((android.graphics.drawable.ColorDrawable) bg).getColor()
                            & 0xFFFFFF) == STOCK_ACCENT_RGB)
                ((android.graphics.drawable.ColorDrawable) bg).setColor(ACCENT_AQUA);
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++)
                recolorTree(g.getChildAt(i));
        }
    }

    /**
     * A label whose colour depends on selection must stay a ColorStateList.
     *
     * <p>Flattening it with setTextColor(int) is what turned an UNSELECTED tab
     * aqua: the first pass caught whichever tab happened to be selected, set a
     * single colour, and that colour then survived deselection. So a label that
     * carries the olive in its selected state is rebuilt as a state list, and
     * only a genuinely single-colour view (a dialog button) is set flat.
     *
     * <p>Idempotent both ways: after the rewrite the selected entry is aqua, so
     * neither branch fires again.
     */
    static void styleDialog(android.app.AlertDialog dialog) {
        if (dialog == null || accentAquaGate == 2) return;
        try {
            for (int which : new int[]{-1, -2, -3}) {
                android.widget.Button button = dialog.getButton(which);
                if (button != null) applyDialogButtonAccent(button);
            }
            if (dialog.getWindow() != null)
                recolorTree(dialog.getWindow().getDecorView());
        } catch (Throwable ignored) { }
    }

    private static void applyDialogButtonAccent(TextView view) {
        // Preserve a visibly disabled button and use aqua in all enabled states.
        view.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0x6100E8FF, ACCENT_AQUA}));
    }

    private static void retintText(TextView tv) {
        ColorStateList csl = tv.getTextColors();
        if (csl != null && (!csl.isStateful() || tv instanceof android.widget.Button)) {
            int enabled = csl.getColorForState(new int[]{android.R.attr.state_enabled},
                    csl.getDefaultColor());
            if ((enabled & 0xFFFFFF) == STOCK_ACCENT_RGB) {
                applyDialogButtonAccent(tv);
                return;
            }
        }
        if (csl != null) {
            int sel = csl.getColorForState(
                    new int[]{android.R.attr.state_selected}, 0);
            if ((sel & 0xFFFFFF) == STOCK_ACCENT_RGB) {
                tv.setTextColor(new ColorStateList(
                        new int[][]{{android.R.attr.state_selected}, {}},
                        new int[]{ACCENT_AQUA, csl.getDefaultColor()}));
                return;
            }
        }
        if ((tv.getCurrentTextColor() & 0xFFFFFF) == STOCK_ACCENT_RGB)
            tv.setTextColor(ACCENT_AQUA);
    }

    /* The indicator is drawn by the TabLayout itself, not by a child view, so
     * no colour match can reach it. Set unconditionally — it is idempotent, and
     * conditioning it on the label colour is what left the underline olive
     * while the labels went aqua. */
    private static void retintTabLayout(View tabLayout) {
        try {
            tabLayout.getClass()
                    .getMethod("setSelectedTabIndicatorColor", int.class)
                    .invoke(tabLayout, ACCENT_AQUA);
        } catch (Throwable ignored) {
        }
    }

    static void hideSettingsUntilBranded(Activity activity) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        if (!"xyz.aethersx2.android.SettingsActivity".equals(name)
                && !"xyz.aethersx2.android.ControllerSettingsActivity".equals(name)) {
            return;
        }
        Window window = activity.getWindow();
        View decor = window != null ? window.getDecorView() : null;
        if (decor == null) return;
        synchronized (hiddenSettingsUntilBranded) {
            hiddenSettingsUntilBranded.add(System.identityHashCode(activity));
        }
        // Alpha preserves measure/layout/pre-draw dispatch, unlike INVISIBLE,
        // while keeping the stock teal ActionBar out of the transition.
        decor.setAlpha(0f);
    }

    private static void revealSettingsAfterBranding(Activity activity, View decor) {
        if (activity == null || decor == null) return;
        boolean wasHidden;
        synchronized (hiddenSettingsUntilBranded) {
            wasHidden = hiddenSettingsUntilBranded.remove(System.identityHashCode(activity));
        }
        if (wasHidden) decor.setAlpha(1f);
    }

    static void apply(Activity activity, String theme) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String name = activity.getClass().getName();
        if ("xyz.aethersx2.android.EmulationActivity".equals(name)) {
            return;
        }
        boolean slushii = ShimTheme.isSlushii(theme);
        boolean dark = isDark(activity, theme);
        try {
            applyWelcomeBranding(activity);
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            // Window bars cannot accept a Drawable on this Android version.
            // Use the two ends of the same cube gradient: cyan/teal at the
            // header edge and hot pink at the taskbar edge.
            int status = NEON_CYAN;
            // Keep the navigation/task bar neutral; the Slushii gradient is
            // reserved for the app chrome and settings surfaces.
            int nav = dark ? 0xFF121212 : 0xFFF8F8FA;
            window.setStatusBarColor(status);
            if (Build.VERSION.SDK_INT >= 26) {
                window.setNavigationBarColor(nav);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                int flags = window.getDecorView().getSystemUiVisibility();
                if (dark && !slushii) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    if (dark) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                window.getDecorView().setSystemUiVisibility(flags);
            }
            applyToolbarGradient(activity, dark, slushii);
            applyNavigationSelectionGradient(activity, dark);
            applySettingsHeaderGradient(activity, dark);
            View decor = window.getDecorView();
            if (decor != null) {
                applySettingsBeforeFirstDraw(activity, decor, dark);
                // MainActivity invokes the hook before setContentView(); retry
                // once the All Games/App Settings AppBar has been inflated.
                decor.postDelayed(() -> applyToolbarGradient(activity, dark, slushii), 450L);
                decor.postDelayed(() -> applyToolbarGradient(activity, dark, slushii), 1100L);
                decor.postDelayed(() -> applyNavigationSelectionGradient(activity, dark), 500L);
                decor.postDelayed(() -> applySettingsHeaderGradient(activity, dark), 500L);
                decor.postDelayed(() -> applySettingsHeaderGradient(activity, dark), 1100L);
                applyBeforeFirstDraw(decor, () -> {
                    applyToolbarGradient(activity, dark, slushii);
                    applyNavigationSelectionGradient(activity, dark);
                    applySettingsHeaderGradient(activity, dark);
                });
            }
            // Keep the page/content area on its native light or dark surface.
            // The Slushii colour belongs only on the main navigation chrome.
            clearSlushiiMainBackground(activity);
            Log.i("VulkanShim", "ShimAccent " + (slushii ? "slushii" : (dark ? "dark" : "light"))
                    + " on " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.w("VulkanShim", "ShimAccent: " + t.getMessage());
        }
    }

    /** The resource-table label is length constrained; fix the visible English
     * setup-wizard title after it inflates instead of altering resources.arsc. */
    private static void applyWelcomeBranding(Activity activity) {
        if (!"xyz.aethersx2.android.SetupWizardActivity".equals(activity.getClass().getName()))
            return;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        replaceWelcomeTitle(decor);
        startSetupMusic(activity);
        if (decor != null) {
            decor.postDelayed(() -> replaceWelcomeTitle(decor), 250);
        }
    }

    private static synchronized void startSetupMusic(Activity activity) {
        if (setupMusic != null) return;
        try (AssetFileDescriptor fd = activity.getAssets().openFd(SETUP_MUSIC_ASSET)) {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setLooping(false);
            player.setOnCompletionListener(done -> {
                synchronized (ShimAccent.class) {
                    if (setupMusic == done) setupMusic = null;
                }
                done.release();
            });
            /* Play it quietly. At full volume this is the first thing a new user
             * hears on every launch, with no way to turn it down — a tester's
             * first note was that they had to lower the device volume rather
             * than listen to it. Kept (it belongs to the setup wizard), but at a
             * level that reads as a flourish instead of an intrusion. */
            player.setVolume(SETUP_MUSIC_VOLUME, SETUP_MUSIC_VOLUME);
            player.prepare();
            player.start();
            setupMusic = player;
            Log.i("VulkanShim", "setup music started (volume "
                    + SETUP_MUSIC_VOLUME + ")");
        } catch (Throwable t) {
            Log.w("VulkanShim", "setup music: " + t.getMessage());
        }
    }

    static synchronized void stopSetupMusic() {
        MediaPlayer player = setupMusic;
        setupMusic = null;
        if (player == null) return;
        try {
            if (player.isPlaying()) player.stop();
        } catch (Throwable ignored) { }
        try { player.release(); } catch (Throwable ignored) { }
        Log.i("VulkanShim", "setup music stopped");
    }

    private static void replaceWelcomeTitle(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence value = text.getText();
            if (value != null && value.toString().contains("Slushii's Fix")) {
                text.setText("Welcome to NetherSX2 (Slushii's Turnip Fix)!");
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                replaceWelcomeTitle(group.getChildAt(i));
        }
    }

    private static boolean isDark(Activity activity, String theme) {
        if ("dark".equals(theme)) {
            return true;
        }
        if ("light".equals(theme)) {
            return false;
        }
        int night = activity.getResources().getConfiguration().uiMode & 48;
        return night == 32;
    }

    private static void applyToolbarGradient(Activity activity, boolean dark, boolean slushii) {
        int toolbarId = activity.getResources().getIdentifier(
                "toolbar", "id", activity.getPackageName());
        if (toolbarId == 0) {
            return;
        }
        View toolbar = activity.findViewById(toolbarId);
        if (toolbar == null) {
            return;
        }
        // The Slushii build keeps its cube gradient in the header even before
        // the user selects the optional full Slushii theme.
        int[] colors = dark
                ? new int[]{0xFF006E78, 0xFF27586A, 0xFF6A285D, 0xFF9B1768}
                : new int[]{NEON_CYAN, LIME, MAGENTA, HOT_PINK};
        setNavigationStripGradient(toolbar, colors);
        setTextColorRecursively(toolbar, 0xFFFFFFFF);
        if (toolbar.getParent() instanceof View) {
            View appBar = (View) toolbar.getParent();
            setNavigationStripGradient(appBar, colors);
            setTextColorRecursively(appBar, 0xFFFFFFFF);
            extendGradientIntoStatusBar(activity, appBar);
        }
        // MainActivity restores its ordinary toolbar colour after lifecycle
        // callbacks. Reapply after that pass so the All Games/App Settings
        // strip, rather than the content view, keeps the gradient.
        toolbar.postDelayed(() -> setNavigationStripGradient(toolbar, colors), 350L);
        toolbar.postDelayed(() -> setNavigationStripGradient(toolbar, colors), 900L);
        insetSettingsBelowActionBar(activity);
        toolbar.postDelayed(() -> insetSettingsBelowActionBar(activity), 350L);
        toolbar.postDelayed(() -> insetSettingsBelowActionBar(activity), 900L);
    }

    /**
     * Push the Settings content below the gradient header.
     *
     * <p>Settings is a ViewPager with a tab strip, and the strip is what tells
     * the user the other pages exist. Measured on device:
     * {@code id/tab_layout} occupies [0,0]-[1280,96] while
     * {@code id/action_bar_container} occupies [0,48]-[1280,176] — the header is
     * drawn straight over the tabs, and over the first category title too. A
     * tester reported exactly this: "you can only see the first page … you have
     * to swipe … no idea that you need to."
     *
     * <p>The tabs were never removed; the fullscreen layout used to extend the
     * gradient into the status bar makes the content start at y=0, and only the
     * app bar was compensated. Inset the settings root by the header's bottom
     * edge so the strip sits under it and becomes visible.
     */
    static void insetSettingsBelowActionBar(Activity activity) {
        try {
            String pkg = activity.getPackageName();
            int settingsId = activity.getResources().getIdentifier("settings", "id", pkg);
            int barId = activity.getResources().getIdentifier(
                    "action_bar_container", "id", pkg);
            if (settingsId == 0 || barId == 0) return;
            View settings = activity.findViewById(settingsId);
            View bar = activity.findViewById(barId);
            if (settings == null || bar == null) return;
            int barBottom = bar.getBottom();
            /* Not laid out yet, or already inset by the platform — either way
             * padding now would be wrong or doubled. */
            if (barBottom <= 0 || settings.getTop() >= barBottom) return;
            if (settings.getPaddingTop() == barBottom) return;
            settings.setPadding(settings.getPaddingLeft(), barBottom,
                    settings.getPaddingRight(), settings.getPaddingBottom());
            Log.i("VulkanShim", "settings inset below action bar (+" + barBottom
                    + "px) — tab strip visible");
        } catch (Throwable t) {
            Log.w("VulkanShim", "settings inset failed: " + t.getMessage());
        }
    }

    private static void extendGradientIntoStatusBar(Activity activity, View appBar) {
        if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
        Window window = activity.getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= 21) {
            window.setStatusBarColor(0x00000000);
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        int id = activity.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        int top = id != 0 ? activity.getResources().getDimensionPixelSize(id) : 0;
        if (appBar.getPaddingTop() != top) {
            appBar.setPadding(appBar.getPaddingLeft(), top,
                    appBar.getPaddingRight(), appBar.getPaddingBottom());
        }
    }

    private static void applyNavigationSelectionGradient(Activity activity, boolean dark) {
        if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
        int navId = activity.getResources().getIdentifier(
                "nav_view", "id", activity.getPackageName());
        View view = navId != 0 ? activity.findViewById(navId) : null;
        if (view == null) return;
        // Dark mode uses the same spectrum in a subdued, low-luminance form
        // so the selected All Games row belongs to the dark UI.
        int[] colors = dark
                ? new int[]{0xFF006E78, 0xFF27586A, 0xFF6A285D, 0xFF9B1768}
                : new int[]{NEON_CYAN, LIME, MAGENTA, HOT_PINK};
        StateListDrawable rows = new StateListDrawable();
        GradientDrawable checked = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        checked.setCornerRadius(0f);
        rows.addState(new int[]{android.R.attr.state_checked}, checked);
        rows.addState(new int[]{android.R.attr.state_selected}, checked);
        rows.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            view.getClass().getMethod("setItemBackground",
                    android.graphics.drawable.Drawable.class).invoke(view, rows);
        } catch (Throwable ignored) { }
        setNavigationTextColors(view, dark);
        // NavigationView reserves a short header above the first item. Paint
        // it too so the All Games gradient is continuous to the top of drawer.
        int headerId = activity.getResources().getIdentifier(
                "navigation_header_container", "id", activity.getPackageName());
        View header = headerId != 0 ? activity.findViewById(headerId) : null;
        if (header != null) setNavigationStripGradient(header, colors);
    }

    private static void applySettingsHeaderGradient(Activity activity, boolean dark) {
        String activityName = activity.getClass().getName();
        if (!"xyz.aethersx2.android.SettingsActivity".equals(activityName)
                && !"xyz.aethersx2.android.ControllerSettingsActivity".equals(activityName)) {
            return;
        }
        int[] colors = dark
                ? new int[]{0xFF006D78, 0xFF2D6857, 0xFF6E315E, 0xFF9A1D66}
                : new int[]{NEON_CYAN, LIME, MAGENTA, HOT_PINK};
        Window window = activity.getWindow();
        if (window != null) {
            // Do not paint the decor: preference pages have transparent areas
            // and would inherit the gradient across the whole screen.
            // These screens have no fullscreen AppBar, so their status bar is
            // part of the visible header.  Keeping it on the first stop makes
            // the header continuous instead of leaving a white strip in light
            // mode above the gradient toolbar.
            extendSettingsGradientIntoStatusBar(activity, colors);
            if (Build.VERSION.SDK_INT >= 23) {
                int flags = window.getDecorView().getSystemUiVisibility();
                // The branded header is always saturated/dark enough to use
                // white status icons, including in light mode.
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        }
        for (String idName : new String[]{"action_bar_container", "tab_layout"}) {
            int id = activity.getResources().getIdentifier(
                    idName, "id", activity.getPackageName());
            View strip = id != 0 ? activity.findViewById(id) : null;
            if (strip != null) setNavigationStripGradient(strip, colors);
        }
        int actionId = activity.getResources().getIdentifier(
                "action_bar_container", "id", activity.getPackageName());
        View actionBar = actionId != 0 ? activity.findViewById(actionId) : null;
        // These two preference activities use different layout resource IDs
        // across upstream builds.  Fall back to their actual Toolbar rather
        // than allowing the platform's light action-bar background through.
        View toolbar = findToolbarLike(window != null ? window.getDecorView() : null);
        if (toolbar != null) {
            setNavigationStripGradient(toolbar, colors);
            setTextColorRecursively(toolbar, 0xFFFFFFFF);
        }
        if (actionBar != null && actionBar.getLayoutParams() != null) {
            int targetHeight = dp(activity, 64);
            if (actionBar.getLayoutParams().height != targetHeight) {
                actionBar.getLayoutParams().height = targetHeight;
                actionBar.requestLayout();
            }
        }
        normalizeSettingsHeaderText(actionBar, true);
        int tabsId = activity.getResources().getIdentifier(
                "tab_layout", "id", activity.getPackageName());
        View tabs = tabsId != 0 ? activity.findViewById(tabsId) : null;
        normalizeSettingsHeaderText(tabs, false);
    }

    /**
     * Window#setStatusBarColor accepts only one color.  The settings pages
     * previously passed it cyan, leaving a conspicuous teal strip above the
     * otherwise rainbow header.  Put a drawable behind a transparent system
     * bar instead so the gradient runs continuously from edge to edge.
     */
    private static void extendSettingsGradientIntoStatusBar(Activity activity, int[] colors) {
        Window window = activity.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);
        window.setStatusBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) window.setStatusBarContrastEnforced(false);

        int dimen = activity.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        int height = dimen != 0 ? activity.getResources().getDimensionPixelSize(dimen) : 0;
        if (height <= 0) return;

        ViewGroup root = (ViewGroup) decor;
        View strip = findTaggedView(root, "slushii_settings_status_gradient");
        if (strip == null) {
            strip = new View(activity);
            strip.setTag("slushii_settings_status_gradient");
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.TOP);
            root.addView(strip, params);
        }
        strip.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors));
        if (Build.VERSION.SDK_INT >= 21) strip.setElevation(100f);
    }

    private static View findTaggedView(View root, Object tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findTaggedView(group.getChildAt(i), tag);
                if (match != null) return match;
            }
        }
        return null;
    }

    /**
     * Settings builds its ActionBar after pre-create.  Do not permit Android to
     * draw that stock teal ActionBar before we have its gradient; wait for the
     * named header views, style them, then allow the next traversal to render.
     */
    private static void applySettingsBeforeFirstDraw(final Activity activity,
            final View decor, final boolean dark) {
        String activityName = activity.getClass().getName();
        if (!"xyz.aethersx2.android.SettingsActivity".equals(activityName)
                && !"xyz.aethersx2.android.ControllerSettingsActivity".equals(activityName)) {
            return;
        }
        final int identity = System.identityHashCode(activity);
        synchronized (pendingSettingsFirstDraw) {
            if (!pendingSettingsFirstDraw.add(identity)) return;
        }
        if (!decor.getViewTreeObserver().isAlive()) return;
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                int actionId = activity.getResources().getIdentifier(
                        "action_bar_container", "id", activity.getPackageName());
                int tabsId = activity.getResources().getIdentifier(
                        "tab_layout", "id", activity.getPackageName());
                View action = actionId != 0 ? activity.findViewById(actionId) : null;
                View tabs = tabsId != 0 ? activity.findViewById(tabsId) : null;
                if (action == null || tabs == null) {
                    // Layout has not inflated yet: suppress this otherwise
                    // visible teal frame and try on the next traversal.
                    return false;
                }
                applySettingsHeaderGradient(activity, dark);
                if (decor.getViewTreeObserver().isAlive()) {
                    decor.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                synchronized (pendingSettingsFirstDraw) {
                    pendingSettingsFirstDraw.remove(identity);
                }
                revealSettingsAfterBranding(activity, decor);
                // One more traversal ensures the freshly applied Drawable is
                // what the user sees on the screen's first frame.
                return false;
            }
        });
    }


    private static View findToolbarLike(View view) {
        if (view == null) return null;
        String className = view.getClass().getName();
        if (className.contains("Toolbar") || className.contains("ActionBarContainer")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findToolbarLike(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    /** Applies branding between layout and the first rendered frame, avoiding
     * the brief teal/grey flash caused by waiting for delayed callbacks. */
    private static void applyBeforeFirstDraw(View decor, final Runnable apply) {
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try { apply.run(); } catch (Throwable ignored) { }
                if (decor.getViewTreeObserver().isAlive()) {
                    decor.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                return true;
            }
        });
    }

    private static void normalizeSettingsHeaderText(View view, boolean title) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(0xFFFFFFFF);
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, title ? 26f : 20f);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                normalizeSettingsHeaderText(group.getChildAt(i), title);
            }
        }
    }

    private static void setTextColorRecursively(View view, int color) {
        if (view instanceof TextView) ((TextView) view).setTextColor(color);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setTextColorRecursively(group.getChildAt(i), color);
            }
        }
    }

    private static void setNavigationTextColors(View navigationView, boolean dark) {
        ColorStateList colors = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{android.R.attr.state_selected},
                        new int[]{}
                },
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, dark ? 0xFFFFFFFF : 0xFF111111});
        try {
            navigationView.getClass().getMethod("setItemTextColor", ColorStateList.class)
                    .invoke(navigationView, colors);
        } catch (Throwable ignored) { }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void setNavigationStripGradient(View strip, int[] colors) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        bg.setCornerRadius(0f);
        strip.setBackground(bg);
    }

    private static void applySlushiiMainBackground(Activity activity, boolean dark) {
        if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        int[] colors = dark
                ? new int[]{0xFF0A0A0A, NEON_CYAN, LIME, MAGENTA, HOT_PINK}
                : new int[]{0xFFE8F8FF, NEON_CYAN, LIME, MAGENTA, PINK_SOFT};
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        if (content != null) {
            content.setForeground(null);
            content.setBackground(bg);
        } else {
            decor.setBackground(bg);
        }
    }


    private static void clearSlushiiMainBackground(Activity activity) {
        if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        if (content != null) {
            content.setBackground(null);
        }
    }

    private static void applyContentWash(Activity activity, boolean dark) {
        if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        int top = dark ? 0xFF1A2E2E : 0xFFE8F8F8;
        int bottom = dark ? 0xFF2A1A24 : 0xFFFFF0F5;
        GradientDrawable wash = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{top, bottom});
        wash.setAlpha(dark ? 48 : 72);
        content.setForeground(wash);
    }
}
