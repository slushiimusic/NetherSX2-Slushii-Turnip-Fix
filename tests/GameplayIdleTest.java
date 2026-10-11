package xyz.aethersx2.android.shim;

import android.content.Context;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import xyz.aethersx2.android.NativeLibrary;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import static xyz.aethersx2.android.shim.SettingsRegressionTest.*;

/** Exercise OFF/resume paths without replacing the production lifecycle logic. */
public final class GameplayIdleTest {
    private static void monitor() throws Exception {
        Method m = ShimFrameGen.class.getDeclaredMethod("ensureOsdTicking");
        m.setAccessible(true); m.invoke(null);
    }

    public static void main(String[] args) throws Exception {
        Path files = Paths.get(args[0]); Files.createDirectories(files);
        Files.writeString(files.resolve("turnip.conf"), "lsfg_overlay=off\nfg_osd=off\n");
        Context ctx = new Context(files.toFile());
        MemoryPrefs prefs = new MemoryPrefs(); PreferenceManager.prefs = prefs;
        monitor();
        check(Handler.delayed.isEmpty(), "Cold start with FG OFF schedules no monitor");
        field(ShimFrameGen.class, "overlayRuntimeEnabled", true);
        monitor(); monitor();
        check(Handler.delayed.size() == 1, "ON starts exactly one monitor even with OSD hidden");
        Runnable oldTick = Handler.delayed.get(0);
        ShimFrameGen.disableOverlay(ctx);
        check(!Handler.delayed.contains(oldTick), "OFF removes the queued tick immediately");
        int idleTasks = Handler.delayed.size();
        oldTick.run(); monitor();
        check(Handler.delayed.size() == idleTasks, "A stale tick cannot poll or reschedule after OFF");
        field(ShimFrameGen.class, "irRecovering", true);
        monitor();
        check(Handler.delayed.size() == idleTasks + 1, "IR recovery retains its monitor");
        field(ShimFrameGen.class, "irRecovering", false);
        monitor();
        check(Handler.delayed.size() == idleTasks, "Finished recovery with FG OFF goes idle");
        field(ShimFrameGen.class, "overlayRuntimeEnabled", true);
        monitor();
        check(Handler.delayed.size() == idleTasks + 1, "Re-enable can start the monitor again");
        ShimFrameGen.disableOverlay(ctx);

        Handler.delayed.clear();
        NativeLibrary.running = true;
        prefs.values.put(OsdPrefs.KEY_FPS, true);
        prefs.values.put("EmuCore/GS/OsdPerformancePos", 2);
        TurnipConfig.onEmulationResumed(ctx);
        ArrayList<Runnable> oldResume = new ArrayList<>(Handler.delayed);
        int before = NativeLibrary.applies;
        for (int i = 0; i < 3; i++) oldResume.get(i).run();
        check(NativeLibrary.applies == before + 1, "Three resume checks apply unchanged OSD only once");
        prefs.values.put(OsdPrefs.KEY_FPS, 0);
        TurnipConfig.reassertOsdAfterResume(ctx);
        check(NativeLibrary.applies == before + 2, "Native OSD stomp is still repaired and applied");
        check(Boolean.TRUE.equals(prefs.values.get(OsdPrefs.KEY_FPS)), "FPS choice survives native stomp");
        TurnipConfig.reassertOsdAfterResume(ctx);
        check(NativeLibrary.applies == before + 2, "No repeated apply after repair");

        TurnipConfig.onEmulationPaused(ctx);
        TurnipConfig.onEmulationResumed(ctx);
        before = NativeLibrary.applies;
        for (Runnable callback : oldResume) callback.run();
        check(NativeLibrary.applies == before, "Old resume callbacks cannot affect the new session");
        NativeLibrary.failApply = true;
        TurnipConfig.reassertOsdAfterResume(ctx);
        NativeLibrary.failApply = false;
        TurnipConfig.reassertOsdAfterResume(ctx);
        check(NativeLibrary.applies == before + 2, "A failed native apply stays pending for retry");
        TurnipConfig.reassertOsdAfterResume(ctx);
        check(NativeLibrary.applies == before + 2, "Successful retry clears pending work");
        prefs.values.put(OsdPrefs.KEY_FPS, false);
        OsdPrefs.noteUserToggle(ctx);
        TurnipConfig.applyOsdSettingsLive(ctx);
        TurnipConfig.reassertOsdAfterResume(ctx);
        check(!OsdPrefs.anyStatOn(ctx), "Explicit FPS OFF stays off through resume checks");
        System.out.println("PASS: FG OFF stays idle; ON/IR recovery monitor works; resume reloads are coalesced; OSD repair and failed-apply retry survive");
    }
}
