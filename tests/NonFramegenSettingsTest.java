package xyz.aethersx2.android.shim;
import java.nio.file.*;
import android.content.Context;
import androidx.preference.PreferenceManager;
public final class NonFramegenSettingsTest {
 public static void main(String[] args) throws Exception {
  Path files=Paths.get(args[0]); Files.createDirectories(files);
  Path conf=files.resolve("turnip.conf");
  Files.writeString(conf,"lsfg_overlay=on\nfg_osd=on\nlsfg=on\nframegen=on\nfg_flow_scale=0.25\n");
  Context ctx=new Context(files.toFile());
  PreferenceManager.prefs=new SettingsRegressionTest.MemoryPrefs();
  SettingsRegressionTest.check(!FramegenBuild.AVAILABLE,"NONFRAMEGEN is compiled out");
  TurnipConfig.applyLsfgFromConf(ctx);
  for(String key:new String[]{"lsfg_overlay","fg_osd","lsfg","framegen"})
   SettingsRegressionTest.check("off".equals(TurnipConfig.readConfKey(conf.toFile(),key)),"old ON setting disabled: "+key);
  SettingsRegressionTest.field(ShimFrameGen.class,"overlayRuntimeEnabled",false);
  ShimFrameGen.enableOverlay(ctx);
  java.lang.reflect.Field enabled=ShimFrameGen.class.getDeclaredField("overlayRuntimeEnabled"); enabled.setAccessible(true);
  SettingsRegressionTest.check(!enabled.getBoolean(null),"cached enable cannot start NONFRAMEGEN");
  SettingsRegressionTest.check(!ShimFrameGen.prepare(ctx),"NONFRAMEGEN cannot prepare shaders");
  ShimDllImport.maybeOffer(null); ShimDllImport.onSettingsOpened(null);
  SettingsRegressionTest.check("0.25".equals(TurnipConfig.readConfKey(conf.toFile(),"fg_flow_scale")),"switching variant preserves saved flow choice");
  System.out.println("PASS: NONFRAMEGEN blocks old ON config, prepare, cached enable and DLL prompts; preserves other settings");
 }
}
