package xyz.aethersx2.android.shim;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.io.File;

/**
 * turnip.conf surface_scale=<0.25..1.0> — render the emulator's SurfaceView at a
 * fraction of the panel and let the display hardware scale it back up.
 *
 * Why this exists: the LSFG frame-generation layer works on the swapchain image,
 * so its cost tracks that image's pixel count. Measured on this device at the
 * panel's 960x1280 (1.23 MP), SOTC's frame time went 16.67 ms -> ~43 ms with one
 * generated frame: the layer alone costs ~25 ms, more than the entire 16.67 ms
 * budget for 60 fps. flow_scale is already at its floor (0.25) and only affects
 * the optical-flow passes, not the full-resolution warp and blend. The only
 * remaining lever is the resolution the layer operates at.
 *
 * Why it costs almost no image quality: the GS renders at 512x448 at Native
 * internal resolution, so presenting into a 960x1280 surface is ALREADY an
 * upscale. Doing that upscale in the display pipeline instead of on the GPU is
 * free — the compositor scales every layer anyway.
 *
 * Why setFixedSize and not a swapchain substitution: an earlier attempt swapped
 * a smaller VkImage in under the swapchain and took SurfaceFlinger down with it.
 * setFixedSize is the sanctioned path — the buffer really is allocated at this
 * size, the emulator's surfaceChanged runs normally, and the scaler is hardware.
 *
 * Off unless surface_scale is present, {@code lsfg_overlay=on}, and scale &lt; 1.0.
 *
 * <p>Stock PCSX2 OSD and notification toasts are drawn into the emulator
 * SurfaceView buffer. Scaling that buffer below panel size makes every overlay
 * look soft — keep {@code surface_scale=1.0} (or omit the key) when framegen is
 * off and you want crisp stats.
 */
public final class ShimSurfaceScale {
    private ShimSurfaceScale() {}

    /** Last size we requested, so a re-poll does not retrigger surfaceChanged. */
    private static int lastW = 0, lastH = 0;
    private static boolean loggedOff = false;

    /**
     * Catch the SurfaceView the moment it is laid out, which is before the
     * emulator's surfaceCreated runs and therefore before the first swapchain
     * exists. That ordering is the whole point: the LSFG layer can build its
     * AHB-backed images once, at whatever size the surface already is, and
     * never has to take its recreate path — which fails with
     * VK_ERROR_INITIALIZATION_FAILED and leaves frame generation off for the
     * rest of the session.
     *
     * The listener removes itself once it has managed a real (laid-out) pass,
     * so it does not sit on every layout for the life of the activity.
     */
    /**
     * Give the emulator SurfaceView its full panel-sized buffer back.
     *
     * <p>{@code surface_scale} shrinks that buffer to save the framegen layer
     * work, which is invisible while the framegen output panel covers the
     * emulator surface. The moment the panel goes away — an IR change, a
     * teardown, framegen switched off — the scaled buffer is what the user
     * actually sees, upscaled by the compositor: at 0.5 that is 640x480 blown
     * up to 1280x960, i.e. a quarter-resolution image. Reported as "the image
     * downsizes during the change".
     *
     * <p>So the scale must live and die with the panel: call this whenever the
     * panel is torn down, and {@link #applyEarly} when it comes back.
     */
    public static void restoreFullSize(Activity activity) {
        if (activity == null) return;
        try {
            SurfaceView sv = findSurfaceView(activity.getWindow().getDecorView());
            if (sv == null) return;
            SurfaceHolder holder = sv.getHolder();
            if (holder == null) return;
            /* setSizeFromLayout() undoes setFixedSize — the buffer tracks the
             * view again, which is panel size. */
            holder.setSizeFromLayout();
            lastW = lastH = -1;   /* so applyEarly re-applies rather than no-oping */
            Log.i("VulkanShim", "surface_scale released — emulator buffer back to panel size");
        } catch (Throwable t) {
            Log.w("VulkanShim", "restoreFullSize failed: " + t);
        }
    }

    public static void applyEarly(Activity activity) {
        if (activity == null) return;
        try {
            /* New activity, new SurfaceView: the remembered size belongs to the
             * old one, and keeping it would make the guard below skip the only
             * call that matters. */
            lastW = lastH = 0;
            final View decor = activity.getWindow().getDecorView();
            final ViewTreeObserver.OnGlobalLayoutListener[] holder =
                    new ViewTreeObserver.OnGlobalLayoutListener[1];
            holder[0] = () -> {
                if (apply(activity)) {
                    ViewTreeObserver vto = decor.getViewTreeObserver();
                    if (vto.isAlive() && holder[0] != null)
                        vto.removeOnGlobalLayoutListener(holder[0]);
                }
            };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(holder[0]);
            /* And try straight away, in case layout already happened. */
            apply(activity);
        } catch (Throwable t) {
            Log.w("VulkanShim", "surface_scale early hook failed: " + t);
        }
    }

    /** @return true once a real size has been applied (or deliberately skipped). */
    public static boolean apply(Activity activity) {
        if (activity == null) return false;
        try {
            Context ctx = activity.getApplicationContext();
            File files = ctx.getExternalFilesDir(null);
            if (files == null) return false;
            File conf = new File(files, "turnip.conf");
            String raw = TurnipConfig.readConfKey(conf, "surface_scale");
            if (raw == null || raw.isEmpty()) return true;   /* key absent: nothing to wait for */

            /* surface_scale shrinks the swapchain the GS presents into — stock OSD,
             * keyed IR banners, and toast notifications are all composited there,
             * so anything below 1.0 looks blurry when upscaled to the panel.
             * Only apply when in-process framegen is actually on. */
            if (!surfaceScaleAllowed(conf)) {
                if (!loggedOff) {
                    Log.i("VulkanShim", "surface_scale ignored — lsfg_overlay off "
                            + "(stock OSD stays at panel resolution)");
                    TurnipConfig.note(files, "surface_scale skipped (lsfg_overlay off, crisp OSD)");
                    loggedOff = true;
                }
                return true;
            }

            float scale;
            try {
                scale = Float.parseFloat(raw.trim());
            } catch (NumberFormatException e) {
                Log.w("VulkanShim", "surface_scale='" + raw + "' is not a number");
                return true;
            }
            /* 1.0 is "leave the surface alone" rather than "scale by one": below
             * 0.25 the surface drops under the 512x448 the GS actually renders,
             * which throws away real pixels instead of free ones. */
            if (scale >= 1.0f) {
                if (!loggedOff) {
                    Log.i("VulkanShim", "surface_scale=" + scale + " — panel size, no override");
                    loggedOff = true;
                }
                return true;   /* deliberately not overriding */
            }
            if (scale < 0.25f) {
                Log.w("VulkanShim", "surface_scale=" + scale + " below 0.25, clamping");
                scale = 0.25f;
            }

            SurfaceView sv = findSurfaceView(activity.getWindow().getDecorView());
            if (sv == null) return false;   /* not in the tree yet */

            int pw = sv.getWidth(), ph = sv.getHeight();
            if (pw <= 0 || ph <= 0) return false;   /* not laid out yet; a later pass gets it */

            /* Even dimensions: the layer's AHB images and the GS present blit
             * both prefer them, and an odd width costs a padded row for free. */
            int w = Math.max(64, ((int) (pw * scale)) & ~1);
            int h = Math.max(64, ((int) (ph * scale)) & ~1);

            if (w == lastW && h == lastH) return true;   /* already applied; no surfaceChanged storm */

            SurfaceHolder holder = sv.getHolder();
            if (holder == null) return false;
            holder.setFixedSize(w, h);
            lastW = w; lastH = h;
            Log.i("VulkanShim", "surface_scale=" + scale + " — SurfaceView "
                    + pw + "x" + ph + " -> buffer " + w + "x" + h
                    + " (" + String.format("%.2f", (pw * (float) ph) / (w * (float) h))
                    + "x fewer pixels for the framegen layer)");
            TurnipConfig.note(files, "surface_scale " + pw + "x" + ph + " -> " + w + "x" + h);
            return true;
        } catch (Throwable t) {
            Log.w("VulkanShim", "surface_scale failed: " + t);
        }
        return false;
    }

    /** True when turnip.conf arms in-process framegen — the only case we shrink. */
    private static boolean surfaceScaleAllowed(File conf) {
        if (!FramegenBuild.AVAILABLE) return false;
        String on = TurnipConfig.readConfKey(conf, ShimFrameGen.CONF_KEY);
        return on != null && (on.equalsIgnoreCase("on") || on.equals("1")
                || on.equalsIgnoreCase("true"));
    }

    /** The emulator's output SurfaceView, wherever it sits in the hierarchy. */
    private static SurfaceView findSurfaceView(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView found = findSurfaceView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
