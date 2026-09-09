package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;

public final class SettingsHeaderInsetsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void screen(Activity activity) {
        SettingsHeaderInsets.attach(activity);
        SettingsHeaderInsets.attach(activity);
        ViewTreeObserver observer = activity.window.decor.observer;
        check(observer.listeners.size() == 1, "one layout listener per screen");
        check(observer.draw(), "wait for asynchronous fragment inflation without blocking");
        View settings = activity.settings = new View();
        settings.left = 3; settings.top = 8; settings.right = 5; settings.bottom = 7;
        View bar = activity.bar = new View();
        check(observer.draw() && settings.top == 8, "wait for measurement");

        settings.height = 720; bar.y = 48; bar.height = 128;
        check(!observer.draw(), "layout must settle before covered tabs can draw");
        check(settings.top == 184, "tabs clear the header with original spacing");
        check(settings.left == 3 && settings.right == 5 && settings.bottom == 7,
                "preserve other padding");
        check(observer.draw(), "next frame is ready");
        int writes = settings.paddingWrites;
        for (int i = 0; i < 100; i++) check(observer.draw(), "steady layout can draw");
        check(settings.paddingWrites == writes, "no cumulative inset or redraw loop");

        // Simulate the platform moving the content's parent below the ActionBar.
        // Its local getTop would still be zero; its window position is now 176.
        settings.y = 176;
        check(!observer.draw() && settings.top == 8, "do not double the platform inset");
        check(observer.draw(), "platform inset settles");
        // Rotation changes both status-bar spacing and header height.
        settings.y = 20; bar.y = 20; bar.height = 64;
        check(!observer.draw() && settings.top == 72, "recompute after rotation");
        check(observer.draw(), "rotation settles");
        bar.shown = false;
        check(!observer.draw() && settings.top == 8, "remove padding when header hides");
        check(observer.draw(), "hidden header settles");
        bar.shown = true;
        check(!observer.draw() && settings.top == 72, "restore padding when header returns");
        settings = activity.settings = new View();
        settings.height = 720; settings.y = 20; settings.top = 2;
        check(!observer.draw() && settings.top == 66, "replacement root uses its own padding");
        check(observer.draw(), "replacement settles");
    }

    public static void main(String[] args) {
        screen(new xyz.aethersx2.android.ControllerSettingsActivity());
        screen(new xyz.aethersx2.android.SettingsActivity());
        Activity other = new Activity();
        SettingsHeaderInsets.attach(other);
        check(other.window.decor.observer.listeners.isEmpty(), "leave other screens alone");
        SettingsHeaderInsets.attach(null);
        System.out.println("PASS: Control/App Settings tabs before first draw, late inflation, "
                + "rotation, platform insets, hidden header, replacement root and stable redraws");
    }
}
