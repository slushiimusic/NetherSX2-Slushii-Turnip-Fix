package xyz.aethersx2.android.shim;

import android.content.Context;
import androidx.preference.PreferenceManager;
import java.nio.file.*;
import java.util.concurrent.*;

/** Regressions for user choices and a slow shader worker blocking the UI. */
public final class GameplayStartupTest {
    public static void main(String[] args) throws Exception {
        Path files = Paths.get(args[0]); Files.createDirectories(files);
        Context ctx = new Context(files.toFile());
        SettingsRegressionTest.MemoryPrefs prefs = new SettingsRegressionTest.MemoryPrefs();
        PreferenceManager.prefs = prefs;
        String renderer = "EmuCore/GS/Renderer";
        RendererPrefs.ensureVulkanRenderer(ctx);
        SettingsRegressionTest.check("14".equals(prefs.values.get(renderer)), "New install defaults to Vulkan");
        for (Object choice : new Object[]{"12", "13", "14", 12, 13}) {
            prefs.values.put(renderer, choice);
            RendererPrefs.ensureVulkanRenderer(ctx);
            SettingsRegressionTest.check(choice.toString().equals(prefs.values.get(renderer)),
                    "Startup preserves Software/OpenGL/Vulkan and normalizes legacy integers");
        }
        TurnipConfig.seedDefaultIrIfUnset(ctx);
        SettingsRegressionTest.check("1.000000".equals(TurnipConfig.readUserIr(ctx)),
                "A fresh install seeds native resolution");
        SettingsRegressionTest.check("1.000000".equals(prefs.values.get("EmuCore/GS/upscale_multiplier")),
                "Fresh-install Graphics preference agrees with native default");
        TurnipConfig.saveUserIr(ctx, "2.500000");
        TurnipConfig.seedDefaultIrIfUnset(ctx);
        SettingsRegressionTest.check("2.500000".equals(TurnipConfig.readUserIr(ctx)),
                "Startup preserves an existing resolution choice");
        SettingsRegressionTest.field(ShimFrameGen.class, "shadersReady", true);
        SettingsRegressionTest.field(ShimFrameGen.class, "displaySuspended", false);
        java.lang.reflect.Field lockField = ShimFrameGen.class.getDeclaredField("PREPARE_LOCK");
        lockField.setAccessible(true);
        Object lock = lockField.get(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> { synchronized (lock) {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }});
        worker.start(); entered.await();
        ExecutorService ui = Executors.newSingleThreadExecutor();
        try {
            SettingsRegressionTest.check(ui.submit(ShimFrameGen::ready).get(1, TimeUnit.SECONDS),
                    "UI readiness cannot block behind shader work");
        } finally { release.countDown(); worker.join(); ui.shutdownNow(); }

        Files.writeString(files.resolve("turnip.conf"), "lsfg_overlay=off\nfg_flow_scale=0.25\n");
        SettingsRegressionTest.field(ShimFrameGen.class, "overlayRuntimeEnabled", false);
        ShimFrameGen.finishPreparation(ctx);
        java.lang.reflect.Field enabled = ShimFrameGen.class.getDeclaredField("overlayRuntimeEnabled");
        enabled.setAccessible(true);
        SettingsRegressionTest.check(!(Boolean) enabled.get(null), "Late prepare completion must leave OFF intact");
        SettingsRegressionTest.check(Files.readString(files.resolve("turnip.conf")).contains("lsfg_overlay=off"),
                "Late completion cannot rewrite saved OFF intent");
        System.out.println("PASS: native default and existing resolution choice, compatibility renderer survives startup, shader worker does not block readiness, delayed completion respects OFF");
    }
}
