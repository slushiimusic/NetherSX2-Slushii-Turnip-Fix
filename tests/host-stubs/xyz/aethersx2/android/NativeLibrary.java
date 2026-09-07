package xyz.aethersx2.android; public class NativeLibrary {
 public static String aspect;
 public static String getStringSettingValue(String section, String key, String fallback) {
  return "EmuCore/GS".equals(section) && "AspectRatio".equals(key) && aspect != null ? aspect : fallback;
 }
 public static boolean running; public static int reloads, applies, resets;
 public static boolean hasEmulationThread() { return running; }
 public static void resetVM() { resets++; }
 public static boolean isVMPaused() { return false; }
 public static void applySettings() { applies++; }
 public static void reloadPatches() { reloads++; }
}