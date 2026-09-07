package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Force Vulkan GS renderer when prefs were stuck on OpenGL after init failures. */
public final class RendererPrefs {
    private static final String KEY = "EmuCore/GS/Renderer";
    private static final int VULKAN = 14;

    private RendererPrefs() {}

    public static void ensureVulkanRenderer(Context ctx) {
        try {
            Object pm = Class.forName("androidx.preference.PreferenceManager")
                    .getMethod("getDefaultSharedPreferences", Context.class)
                    .invoke(null, ctx);
            SharedPreferences sp = (SharedPreferences) pm;
            int cur = prefInt(sp, KEY, VULKAN);
            Log.i("VulkanShim", "Renderer pref = " + cur + " (want " + VULKAN + ")");
            Object raw = sp.getAll().get(KEY);
            if (cur != VULKAN || !(raw instanceof String)) {
                SharedPreferences.Editor ed = sp.edit();
                ed.remove(KEY);
                ed.putString(KEY, Integer.toString(VULKAN));
                ed.commit();
                Log.i("VulkanShim", "Reset Renderer pref " + raw + " -> \"" + VULKAN + "\" (Vulkan String)");
            }
        } catch (Throwable t) {
            Log.w("VulkanShim", "ensureVulkanRenderer failed: " + t.getMessage());
        }
    }

    private static int prefInt(SharedPreferences sp, String key, int def) {
        Object val = sp.getAll().get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
