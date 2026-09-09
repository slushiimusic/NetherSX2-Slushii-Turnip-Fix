package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.view.View;
import android.view.Window;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps both settings tab strips below the branded, overlapping ActionBar. */
final class SettingsHeaderInsets {
    private static final Set<View> attached =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private SettingsHeaderInsets() { }

    static void attach(Activity activity) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        if (!name.equals("xyz.aethersx2.android.SettingsActivity")
                && !name.equals("xyz.aethersx2.android.ControllerSettingsActivity")) return;
        Window window = activity.getWindow();
        View decor = window != null ? window.getDecorView() : null;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        if (!attached.add(decor)) return;
        String pkg = activity.getPackageName();
        int settingsId = activity.getResources().getIdentifier("settings", "id", pkg);
        int barId = activity.getResources().getIdentifier("action_bar_container", "id", pkg);
        Adjustment adjustment = new Adjustment();
        // Attach before fragment inflation. Run after every layout, including
        // rotation/system-inset changes, without depending on preference polling
        // or an id/toolbar that these ActionBar activities do not contain.
        decor.getViewTreeObserver().addOnPreDrawListener(() ->
                !adjustment.update(activity.findViewById(settingsId),
                        activity.findViewById(barId)));
    }

    static final class Adjustment {
        private View content;
        private int originalTop;
        private final int[] contentPosition = new int[2];
        private final int[] barPosition = new int[2];

        /** Returns true when another layout is needed before drawing. */
        boolean update(View settings, View bar) {
            if (settings == null || bar == null) return false;
            if (content != settings) {
                content = settings;
                originalTop = settings.getPaddingTop();
            }
            int overlap = 0;
            if (bar.isShown()) {
                if (bar.getHeight() <= 0 || settings.getHeight() <= 0) return false;
                // getTop/getBottom belong to different parents here. Compare
                // window coordinates so platform insets are not counted twice.
                settings.getLocationInWindow(contentPosition);
                bar.getLocationInWindow(barPosition);
                overlap = Math.max(0, barPosition[1] + bar.getHeight() - contentPosition[1]);
            }
            int top = originalTop + overlap;
            if (settings.getPaddingTop() == top) return false;
            settings.setPadding(settings.getPaddingLeft(), top,
                    settings.getPaddingRight(), settings.getPaddingBottom());
            return true;
        }
    }
}
