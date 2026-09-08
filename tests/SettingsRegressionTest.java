package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import xyz.aethersx2.android.NativeLibrary;
import java.nio.file.*;
import java.util.*;

/** Runs production callbacks and ini writers with only the Android/VM boundary replaced. */
public final class SettingsRegressionTest {
    static final String WS="EmuCore/EnableWideScreenPatches", AR="EmuCore/GS/AspectRatio";
    static void check(boolean ok,String why) { if (!ok) throw new AssertionError(why); }
    static void field(Class<?> c,String key,Object value) throws Exception {
        java.lang.reflect.Field f=c.getDeclaredField(key); f.setAccessible(true); f.set(null,value);
    }
    static void forgetOsdIntent() throws Exception {
        field(OsdPrefs.class,"userIntentKnown",false);
        field(OsdPrefs.class,"userWantsAnyStat",false);
    }
    public static void main(String[] args) throws Exception {
        Path files=Paths.get(args[0]); Files.createDirectories(files.resolve("gamesettings"));
        Path ini=files.resolve("gamesettings/SLUS-20870_FDD12792.ini");
        Files.writeString(ini,"[EmuCore]\nEnableWideScreenPatches = false\nEnableCheats = true\n[EmuCore/GS]\nOsdShowFPS = true\n");
        Files.writeString(files.resolve("PCSX2.ini"),"[EmuCore/GS]\nOsdShowFPS = true\n");
        Files.createDirectories(files.resolve("cheats"));
        Files.writeString(files.resolve("cheats/FDD12792.pnach"),"patch=1,EE,00100000,word,00000001\n");
        Context ctx=new Context(files.toFile()); MemoryPrefs sp=new MemoryPrefs(); PreferenceManager.prefs=sp;
        sp.values.put("EmuCore/EnableCheats",true);
        ShimSettingsBridge.registerGlobalPrefListener(ctx);
        check(OsdPrefs.anyStatOn(ctx),"absent FPS defaults on for visibility and ini sync");
        PrefCompat.applyKey(ctx,OsdPrefs.KEY_CPU,true);
        check(Files.readString(ini).contains("OsdShowFPS = true"),"enabling CPU must preserve absent/default FPS");
        PrefCompat.applyKey(ctx,OsdPrefs.KEY_CPU,false);
        PrefCompat.applyKey(ctx,OsdPrefs.KEY_FPS,false);
        check(OsdPrefs.userWantsNoStats(ctx),"last explicit toggle must record NONE before ini sync");
        check(Files.readString(ini).contains("OsdShowFPS = false"),"last toggle must persist all-off to game ini");
        forgetOsdIntent(); OsdPrefs.applyPolicy(ctx);
        check(!OsdPrefs.anyStatOn(ctx),"all-off must survive process intent reset and orphan repair");
        check(OsdPrefs.userWantsNoStats(ctx),"all-off must be remembered from disk");
        PrefCompat.applyKey(ctx,OsdPrefs.KEY_RESOLUTION,true);
        check(!OsdPrefs.userWantsNoStats(ctx),"enabling a row must clear NONE");
        // androidx may persist before our click hook is called; unchanged isn't unknown intent.
        sp.edit().putBoolean(OsdPrefs.KEY_RESOLUTION,false).commit();
        PrefCompat.applyKey(ctx,OsdPrefs.KEY_RESOLUTION,false);
        check(OsdPrefs.userWantsNoStats(ctx),"already-persisted last-off still records explicit intent");
        // No time advances in this fixture: UI clicks immediately after shim writes must work.
        sp.edit().putBoolean(OsdPrefs.KEY_SPEED,true).commit();
        check(!OsdPrefs.userWantsNoStats(ctx),"rapid SP-only UI tap must not be discarded by shim quiet window");
        check(Files.readString(ini).contains("OsdShowSpeed = true"),"SP-only callback reaches ini immediately");
        OsdPrefs.rememberUserIntent(ctx);
        sp.edit().putInt(OsdPrefs.KEY_SPEED,0).commit();
        check(!OsdPrefs.userWantsNoStats(ctx),"native Integer stomp is not user all-off");
        check(OsdPrefs.restoreIfStomped(ctx),"native stomp still restores known user choice");
        sp.deferred=true;
        OsdPrefs.shimCommit(sp,sp.edit().putBoolean(OsdPrefs.KEY_CPU,true));
        sp.drain();
        sp.edit().putBoolean(OsdPrefs.KEY_GPU,true).commit(); sp.drain();
        check(Files.readString(ini).contains("OsdShowGPU = true"),"real UI click after queued shim callback must work");
        sp.deferred=false;
        Path conf=files.resolve("turnip.conf");
        Files.writeString(conf,"lsfg_overlay=off\nfg_osd=off\nlsfg=off\nfg_flow_scale=0.25\n");
        TurnipConfig.writeFramegenMode(conf.toFile(),true);
        check("on".equals(TurnipConfig.readConfKey(conf.toFile(),"fg_osd")),
                "enabling FG must recover the OSD from startup's generated off value");
        check("0.25".equals(TurnipConfig.readConfKey(conf.toFile(),"fg_flow_scale")),
                "OSD availability must preserve other framegen configuration");
        TurnipConfig.writeFramegenMode(conf.toFile(),false);
        check("off".equals(TurnipConfig.readConfKey(conf.toFile(),"fg_osd")),
                "disabled FG must not request idle native instrument hooks");
        TurnipConfig.writeFramegenMode(conf.toFile(),true);
        check(OsdPrefs.instrumentVisible(TurnipConfig.readConfKey(conf.toFile(),"fg_osd"),true,false),
                "Show FPS must work after a framegen off/on cycle");
        check(!OsdPrefs.instrumentVisible(TurnipConfig.readConfKey(conf.toFile(),"fg_osd"),false,true),
                "enabling FG must still respect all stock OSD switches off");
        Files.writeString(conf,"lsfg_overlay=on\nfg_osd=force\n");
        TurnipConfig.writeFramegenMode(conf.toFile(),true);
        check("force".equals(TurnipConfig.readConfKey(conf.toFile(),"fg_osd")),
                "explicit diagnostic mode survives an enabled-mode sync");
        check(!OsdPrefs.instrumentVisible("on",false,true),"fg_osd=on must honor all-off");
        check(!OsdPrefs.instrumentVisible("off",true,false),"explicit instrument off must hide");
        check(OsdPrefs.instrumentVisible("force",false,true),"explicit diagnostic force remains available");
        PrefCompat.applyKey(ctx,"EmuCore/GS/OsdPerformancePos","2");
        check(!(sp.getAll().get("EmuCore/GS/OsdPerformancePos") instanceof Boolean),"position list must not become a Boolean");
        sp.edit().putString(AR,"4:3").commit();
        NativeLibrary.running=true;
        android.app.Activity activity=new android.app.Activity(files.toFile());
        field(ToggleWatchdog.class,"currentActivity",new java.lang.ref.WeakReference<>(activity));
        field(TurnipConfig.class,"lastSyncedWidescreen",null);
        PrefCompat.applyKey(ctx,WS,true);
        check(sp.getBoolean(WS,false),"widescreen click must persist before framework callback");
        check(Files.readString(ini).contains("EnableWideScreenPatches = true"),"USM ini receives widescreen on");
        check(Files.readString(ini).contains("EnableCheats = true"),"widescreen must preserve USM 60 FPS gate");
        check(sp.getString(AR,"").equals("4:3"),"running game keeps original aspect until restart");
        check(android.app.AlertDialog.shown==1,"direct+SP routes must offer one restart");
        TurnipConfig.syncWidescreenLive(ctx,false); TurnipConfig.syncWidescreenLive(ctx,true);
        check(android.app.AlertDialog.shown==1,"watchdog seed/repeat must not duplicate restart");
        check(sp.getString(AR,"").equals("4:3"),"watchdog seed must not apply staged aspect early");
        TurnipConfig.flushPendingAspectFollow(ctx);
        check(sp.getString(AR,"").equals("16:9"),"restart applies widescreen aspect");
        sp.edit().putBoolean(WS,false).commit();
        check(Files.readString(ini).contains("EnableWideScreenPatches = false"),"SP-only widescreen off reaches USM ini");
        check(android.app.AlertDialog.shown==2,"SP-only change offers one restart");
        check(sp.getString(AR,"").equals("16:9"),"running patched game retains aspect until restart");
        TurnipConfig.flushPendingAspectFollow(ctx);
        check(sp.getString(AR,"").equals("4:3"),"restart restores pre-widescreen aspect");
        // Actual output resolver: global/per-game choices and staged widescreen.
        Files.writeString(conf,"fg_aspect=auto\nfg_flow_scale=0.25\n");
        field(ShimFrameGen.class,"bootedWide",true);
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-4f/3f)<0.0001f,
                "explicit 4:3 must beat enabled widescreen in framegen output");
        NativeLibrary.aspect="16:9";
        field(ShimFrameGen.class,"bootedWide",false);
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-16f/9f)<0.0001f,
                "per-game 16:9 must beat global 4:3 and disabled patch flag");
        NativeLibrary.aspect="4:3"; sp.edit().putString(AR,"16:9").commit();
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-4f/3f)<0.0001f,
                "effective core 4:3 must beat the global 16:9 preference");
        NativeLibrary.aspect="Stretch";
        check(ShimFrameGen.computeOutputAspect(ctx)==0f,"per-game Stretch fills the panel");
        NativeLibrary.aspect="Auto 4:3/3:2";
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-4f/3f)<0.0001f,
                "Auto retains booted 4:3 despite a pending widescreen change");
        field(ShimFrameGen.class,"bootedWide",true);
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-16f/9f)<0.0001f,
                "Auto uses widescreen after restart");
        Files.writeString(conf,"fg_aspect=4:3\nfg_flow_scale=0.25\n");
        check(Math.abs(ShimFrameGen.computeOutputAspect(ctx)-4f/3f)<0.0001f,
                "explicit diagnostic aspect override is retained");
        NativeLibrary.aspect=null; field(ShimFrameGen.class,"bootedWide",null);
        sp.edit().putString(AR,"4:3").commit();
        Files.writeString(conf,"lsfg_overlay=on\nfg_flow_scale=0.25\n");
        // Reproduce a widescreen reset in the same activity/context after 60-fps play.
        field(ShimFrameGen.class,"emuForeground",true);
        field(ShimFrameGen.class,"hostActivity",new xyz.aethersx2.android.EmulationActivity(files.toFile()));
        field(ShimFrameGen.class,"contextUp",true);
        field(ShimFrameGen.class,"costWindowFill",48);
        java.lang.reflect.Field window=ShimFrameGen.class.getDeclaredField("costWindow");
        window.setAccessible(true); Arrays.fill((double[])window.get(null),60.0);
        java.lang.reflect.Method guard=ShimFrameGen.class.getDeclaredMethod("frameCostGuard",double.class,double.class,long.class);
        guard.setAccessible(true);
        for(int i=0;i<80;i++) guard.invoke(null,30.0,60.0,0L);
        java.lang.reflect.Field tripped=ShimFrameGen.class.getDeclaredField("costTripped"); tripped.setAccessible(true);
        check(!tripped.getBoolean(null),"60-to-30 cadence change must not disable a pipeline adding 30 fps");
        check(ShimFrameGen.outputAddsFrames(30,60),"30-to-60 framegen is useful after a reboot");
        check(!ShimFrameGen.outputAddsFrames(60,60),"settled output equal to source still has no gain");
        check(!ShimFrameGen.outputAddsFrames(60,40),"output regression remains detectable");
        field(ShimFrameGen.class,"costWindowFill",48);
        field(ShimFrameGen.class,"losingTicks",14);
        field(ShimFrameGen.class,"lastSampleNs",123L);
        ShimRestartPrompt.resetVM();
        check(NativeLibrary.resets==1,"patch restart invokes the real VM boundary once");
        java.lang.reflect.Field fill=ShimFrameGen.class.getDeclaredField("costWindowFill"); fill.setAccessible(true);
        java.lang.reflect.Field losing=ShimFrameGen.class.getDeclaredField("losingTicks"); losing.setAccessible(true);
        java.lang.reflect.Field sample=ShimFrameGen.class.getDeclaredField("lastSampleNs"); sample.setAccessible(true);
        check(fill.getInt(null)==0 && losing.getInt(null)==0 && sample.getLong(null)==0,
                "widescreen restart must discard prior cadence, loss streak and sample timing");
        // A failed settings write must never create a new restart prompt every poll.
        Path failed=files.resolve("failed-settings"); Files.createDirectories(failed.resolve("turnip.conf"));
        Context failedCtx=new Context(failed.toFile());
        PreferenceManager.prefs=new MemoryPrefs();
        field(TurnipConfig.class,"lastApplied60",true);
        int dialogs=android.app.AlertDialog.shown;
        for(int i=0;i<12;i++) check(!TurnipConfig.apply60FpsMode(failedCtx,false),"failed persistence is not a successful toggle");
        check(android.app.AlertDialog.shown==dialogs,"failed write retries must not spam 60 FPS dialogs");
        Files.delete(failed.resolve("turnip.conf"));
        Files.writeString(failed.resolve("turnip.conf"),"enable_60fps=on\n");
        check(TurnipConfig.apply60FpsMode(failedCtx,false),"repaired persistence applies pending change");
        check(android.app.AlertDialog.shown==dialogs+1,"successful retry offers one restart");
        for(int i=0;i<12;i++) check(!TurnipConfig.apply60FpsMode(failedCtx,false),"repeat apply is not user intent");
        check(android.app.AlertDialog.shown==dialogs+1,"successful repeat must not spam");

        // Recorded Thor default-GS OFF/ON both took effect without restarting.
        int fgDialogs=android.app.AlertDialog.shown;
        String savedConf=Files.readString(conf);
        Files.writeString(conf,"lsfg_overlay=on\nfg_flow_scale=0.25\n");
        ShimRestartPrompt.askResetFramegen(false);
        ShimRestartPrompt.askResetFramegen(true);
        check(android.app.AlertDialog.shown==fgDialogs,"live GS toggles must not claim a restart is needed");
        for(String source:new String[]{"gs","target","0","GS"}) {
            Files.writeString(conf,"fg_capture_src="+source+"\n");
            check(!ShimRestartPrompt.framegenNeedsRestart(ctx,true),"GS source alias toggles live: "+source);
        }
        Files.writeString(conf,"fg_capture_src=swapchain\n");
        ShimRestartPrompt.askResetFramegen(false);
        check(android.app.AlertDialog.shown==fgDialogs,"OFF must not offer an unnecessary VM reset");
        ShimRestartPrompt.askResetFramegen(true);
        check(android.app.AlertDialog.shown==fgDialogs+1,"present-source enable still offers required restart");
        Files.writeString(conf,savedConf);

        // Both 60 and 120 Hz are supported; other physical modes remain gated.
        Path displayFiles=files.resolve("display-eligibility"); Files.createDirectories(displayFiles);
        Context displayCtx=new Context(displayFiles.toFile());
        Files.writeString(displayFiles.resolve("turnip.conf"),"fg_force=on\ndisplay_hz=120\nlsfg_overlay=on\nfg_osd=on\nfg_flow_scale=0.25\n");
        PreferenceManager.prefs=new MemoryPrefs();
        for(float hz:new float[]{75,90,144}) {
            displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(hz,hz);
            check(HandheldTier.framegenBlockReason(displayCtx)!=null,"unsupported display must not accept fg_force/display_hz override: "+hz);
            field(ShimFrameGen.class,"shadersReady",true);
            field(ShimFrameGen.class,"overlayRuntimeEnabled",false);
            check(!ShimFrameGen.prepare(displayCtx),"cached shaders must not bypass display gate");
            ShimFrameGen.enableOverlay(displayCtx);
            java.lang.reflect.Field enabled=ShimFrameGen.class.getDeclaredField("overlayRuntimeEnabled"); enabled.setAccessible(true);
            check(!enabled.getBoolean(null),"cached enable must not arm on "+hz+" Hz");
        }
        PreferenceManager.prefs.edit().putBoolean("VulkanShim/Lsfg",true).commit();
        int displayDialogs=android.app.AlertDialog.shown;
        TurnipConfig.applyLsfgMode(displayCtx,true);
        check(!PreferenceManager.prefs.getBoolean("VulkanShim/Lsfg",true),"unsupported display clears stale ON preference");
        check("off".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"lsfg_overlay")),"unsupported display clears native capture config");
        check("0.25".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"fg_flow_scale")),"display gate preserves flow");
        check(android.app.AlertDialog.shown==displayDialogs,"unavailable framegen must not spam restart dialogs");
        for(float hz:new float[]{59.94f,60,119.88f,119.999f,120}) {
            displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(hz,60,hz);
            check(HandheldTier.framegenBlockReason(displayCtx)==null,"active fractional 60/120 Hz is eligible: "+hz);
            displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(60,60,hz);
            check(HandheldTier.framegenBlockReason(displayCtx)==null,"active 60 Hz row stays available: "+hz);
            check(HandheldTier.framegenHardwareBlockReason(displayCtx)==null,"60 Hz must allow DLL import");
        }
        displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(90,60,120);
        PreferenceManager.prefs.edit().putBoolean("VulkanShim/Lsfg",true).commit();
        String beforeSuspend="fg_force=on\nlsfg_overlay=on\nfg_osd=on\nfg_flow_scale=0.25\n";
        Files.writeString(displayFiles.resolve("turnip.conf"),beforeSuspend);
        field(ShimFrameGen.class,"shadersReady",true);
        field(ShimFrameGen.class,"overlayRuntimeEnabled",true);
        int temporaryDialogs=android.app.AlertDialog.shown;
        TurnipConfig.applyLsfgMode(displayCtx,true);
        check(PreferenceManager.prefs.getBoolean("VulkanShim/Lsfg",false),"temporary unsupported mode preserves ON preference");
        check("on".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"lsfg_overlay")),"temporary unsupported mode preserves ON config");
        check(!ShimFrameGen.ready(),"cached startup cannot run while suspended");
        check(!ShimFrameGen.refreshDisplayEligibility(displayCtx),"repeated unsupported-mode checks remain suspended");
        check(android.app.AlertDialog.shown==temporaryDialogs,"temporary display changes do not prompt");
        TurnipConfig.applyLsfgMode(displayCtx,false);
        check("off".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"lsfg_overlay")),"OFF still persists while display blocked");
        displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(120,60,120);
        check(ShimFrameGen.refreshDisplayEligibility(displayCtx),"returning to 120 Hz clears suspension");
        check(ShimFrameGen.ready(),"cached pipeline becomes available after restoration");
        java.lang.reflect.Field runtime=ShimFrameGen.class.getDeclaredField("overlayRuntimeEnabled"); runtime.setAccessible(true);
        check(!runtime.getBoolean(null),"restoration respects OFF selected while suspended");
        displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(90,60,120);
        TurnipConfig.applyLsfgMode(displayCtx,true);
        check(!runtime.getBoolean(null),"temporary unsupported mode gates native runtime");
        displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(120,60,120) {
            @Override public float getRefreshRate() { return 60; }
        };
        check(HandheldTier.framegenBlockReason(displayCtx)==null,"per-app 60 FPS override is not physical panel 60 Hz");
        field(ToggleWatchdog.class,"currentActivity",null);
        field(ShimFrameGen.class,"hostActivity",null);
        check(ShimFrameGen.refreshDisplayEligibility(displayCtx),"120 Hz resumes saved ON intent");
        check(runtime.getBoolean(null),"return to 120 Hz re-arms runtime without another toggle");
        check("0.25".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"fg_flow_scale")),"suspend and restore preserve flow");
        // Runtime remains enabled through 120 -> 60 -> 120. Pacing uses the
        // active physical mode even when supported modes or app FPS differ.
        field(ShimFrameGen.class,"contextUp",false);
        field(ShimFrameGen.class,"fgRequestedFpsCap",240);
        java.lang.reflect.Field cap=ShimFrameGen.class.getDeclaredField("fgTargetFpsCap"); cap.setAccessible(true);
        for(float hz:new float[]{60,120,59.94f,119.88f}) {
            displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(hz,60,120) {
                @Override public float getRefreshRate() { return 30; }
            };
            check(ShimFrameGen.refreshDisplayEligibility(displayCtx),"60/120 mode switches stay eligible");
            check(runtime.getBoolean(null),"mode switch keeps framegen enabled");
            check(ShimFrameGen.ready(),"mode switch preserves cached shaders");
            check(cap.getInt(null)==Math.round(hz),"pacing caps to active mode, ignoring stale 240 override and app FPS");
        }
        field(ShimFrameGen.class,"fgRequestedFpsCap",0);
        displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(60,60,120);
        TurnipConfig.applyLsfgMode(displayCtx,false);
        check(!runtime.getBoolean(null),"OFF works at 60 Hz");
        check("off".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"lsfg_overlay")),"60 Hz OFF persists");
        TurnipConfig.applyLsfgMode(displayCtx,true);
        check(runtime.getBoolean(null),"ON works at 60 Hz");
        check(ShimFrameGen.prepare(displayCtx),"cached prepare allows 60 Hz");
        check(cap.getInt(null)==60,"default output cap is 60 on a 60 Hz active mode");
        check("0.25".equals(TurnipConfig.readConfKey(displayFiles.resolve("turnip.conf").toFile(),"fg_flow_scale")),"60 Hz toggles preserve flow");
        // Legacy ON settings cannot restore artifact protection at either rate.
        java.lang.reflect.Method load=ShimFrameGen.class.getDeclaredMethod("loadFramegenConfig",java.io.File.class); load.setAccessible(true);
        java.lang.reflect.Field anti=ShimFrameGen.class.getDeclaredField("fgAntiArtifacts"); anti.setAccessible(true);
        java.lang.reflect.Field flow=ShimFrameGen.class.getDeclaredField("fgFlowScale"); flow.setAccessible(true);
        for(int hz:new int[]{60,120}) for(String value:new String[]{"on","true","1","off"}) {
            displayCtx.windowService=(android.view.WindowManager)()->new android.view.Display(hz,60,120);
            check(ShimFrameGen.refreshDisplayEligibility(displayCtx),"display remains supported with legacy artifact setting");
            Files.writeString(displayFiles.resolve("turnip.conf"),"fg_anti_artifacts="+value+"\nfg_flow_scale=0.25\n");
            load.invoke(null,displayFiles.toFile());
            check(!anti.getBoolean(null),"artifact protection stays OFF at "+hz+" Hz with saved "+value);
            check(flow.getFloat(null)==0.25f,"disabling artifact protection preserves flow");
        }
        displayCtx.windowService=null;
        check(HandheldTier.framegenBlockReason(displayCtx)!=null,"unknown display cannot arm until capability is known");

        Path repaired=files.resolve("ownership");
        Path staging=repaired.resolve(StorageOwnershipRepair.STAGING);
        Files.createDirectories(staging.resolve("sstates"));
        byte[] state=new byte[70001];new Random(7).nextBytes(state);
        Files.write(staging.resolve("sstates/USM.01.p2s"),state);
        Files.writeString(staging.resolve("turnip.conf"),"fg_flow_scale=0.25\n");
        Files.createDirectories(staging.resolve("lsfg/shaders/fp16"));
        Files.writeString(staging.resolve("lsfg/Lossless.dll"),"existing user DLL");
        // A broken link models an unusable disposable cache; import must ignore it.
        Files.createSymbolicLink(staging.resolve("lsfg/shaders/fp16/cache"),Paths.get("/missing-shader-cache"));
        check(StorageOwnershipRepair.restore(repaired.toFile()),"app-owned import completes");
        check(!Files.exists(repaired.resolve("lsfg/shaders")),"shader cache must regenerate in the app");
        check(Files.readString(repaired.resolve("lsfg/Lossless.dll")).equals("existing user DLL"),"retain original DLL for cache regeneration");
        check(Arrays.equals(state,Files.readAllBytes(repaired.resolve("sstates/USM.01.p2s"))),"save copied byte for byte");
        check(Arrays.equals(state,Files.readAllBytes(staging.resolve("sstates/USM.01.p2s"))),"original save retained");
        Path imported=repaired.resolve("sstates/USM.01.p2s");
        Files.setPosixFilePermissions(imported,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.delete(repaired.resolve(StorageOwnershipRepair.STAGING+".complete"));
        check(StorageOwnershipRepair.restore(repaired.toFile()),"resume owner-only copies from 12812657");
        check(Files.getPosixFilePermissions(imported).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")),"normal app data permissions, no access for others");
        Files.writeString(repaired.resolve("sstates/USM.01.p2s"),"newer user save");
        check(StorageOwnershipRepair.restore(repaired.toFile()),"completed import is never replayed");
        check(Files.readString(repaired.resolve("sstates/USM.01.p2s")).equals("newer user save"),"later save is preserved");
        Files.delete(repaired.resolve(StorageOwnershipRepair.STAGING+".complete"));
        check(!StorageOwnershipRepair.restore(repaired.toFile()),"partial import stops on an existing different save");
        check(Files.readString(repaired.resolve("sstates/USM.01.p2s")).equals("newer user save"),"conflicting save must never be replaced");
        System.out.println("PASS: OSD persistence and callbacks, widescreen/cadence recovery, failed-write dialog suppression, live FG prompt gating, effective display aspect, 60/120 Hz eligibility, active-mode pacing, artifact protection OFF despite legacy settings, cached-start gate, app-owned import and newer-save protection");
    }
    static final class MemoryPrefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        final List<OnSharedPreferenceChangeListener> listeners=new ArrayList<>();
        final Deque<Runnable> queue=new ArrayDeque<>(); boolean deferred;
        public Map<String,?> getAll() { return new HashMap<>(values); }
        public boolean getBoolean(String k,boolean d) { return (Boolean)values.getOrDefault(k,d); }
        public int getInt(String k,int d) { return (Integer)values.getOrDefault(k,d); }
        public long getLong(String k,long d) { return (Long)values.getOrDefault(k,d); }
        public float getFloat(String k,float d) { return (Float)values.getOrDefault(k,d); }
        public String getString(String k,String d) { return (String)values.getOrDefault(k,d); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d) { return (Set<String>)values.getOrDefault(k,d); }
        public boolean contains(String k) { return values.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.add(l); }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }
        void drain() { while(!queue.isEmpty()) queue.remove().run(); }
        public Editor edit() { return new Editor() {
            Map<String,Object> pending=new LinkedHashMap<>(); boolean clear;
            public Editor putBoolean(String k,boolean v) { pending.put(k,v); return this; }
            public Editor putInt(String k,int v) { pending.put(k,v); return this; }
            public Editor putLong(String k,long v) { pending.put(k,v); return this; }
            public Editor putFloat(String k,float v) { pending.put(k,v); return this; }
            public Editor putString(String k,String v) { pending.put(k,v); return this; }
            public Editor putStringSet(String k,Set<String> v) { pending.put(k,v); return this; }
            public Editor remove(String k) { pending.put(k,null); return this; }
            public Editor clear() { clear=true; return this; }
            public void apply() { commit(); }
            public boolean commit() {
                if(clear) values.clear(); List<String> changed=new ArrayList<>();
                for(Map.Entry<String,Object> e:pending.entrySet()) {
                    if(!Objects.equals(values.get(e.getKey()),e.getValue())) changed.add(e.getKey());
                    if(e.getValue()==null) values.remove(e.getKey()); else values.put(e.getKey(),e.getValue());
                }
                for(String k:changed) for(OnSharedPreferenceChangeListener l:new ArrayList<>(listeners)) {
                    Runnable callback=()->l.onSharedPreferenceChanged(MemoryPrefs.this,k);
                    if(deferred) queue.add(callback); else callback.run();
                }
                return true;
            }
        }; }
    }
}
