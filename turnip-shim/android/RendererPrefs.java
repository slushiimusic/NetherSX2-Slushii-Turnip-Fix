package xyz.aethersx2.android.shim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Seed Vulkan for a new install, preserving a user's compatibility renderer. */
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
            Object raw = sp.getAll().get(KEY);
            if (!(raw instanceof String) || !Integer.toString(cur).equals(raw)) {
                SharedPreferences.Editor ed = sp.edit();
                ed.remove(KEY);
                ed.putString(KEY, Integer.toString(cur));
                ed.commit();
                Log.i("VulkanShim", "Normalized renderer preference to " + cur);
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
