package com.lsfg.android.session;

import android.hardware.HardwareBuffer;
import android.view.Surface;

/**
 * JNI surface of liblsfg-android.so, the frame-generation pipeline from the
 * LSFG-Android app (FrankBarretta/LSFG-Android).
 *
 * <p>The package and class name are NOT a style choice — they are the ABI.
 * lsfg_jni.cpp exports plain statically-bound entry points named
 * {@code Java_com_lsfg_android_session_NativeBridge_<method>}, with no
 * RegisterNatives call, so the JVM binds them purely by mangled name. Rename or
 * repackage this class and every method silently fails to link.
 *
 * <p>Declared as instance methods on a singleton to match the Kotlin
 * {@code object NativeBridge} the natives were generated against: each C++
 * function takes an (ignored) {@code jobject thiz}, so a static declaration
 * would hand it a jclass instead.
 *
 * <p>Why this lives inside NetherSX2 at all: {@link #pushFrame} takes an
 * AHardwareBuffer and does not care where the frame came from. As a separate
 * app the only available source is a MediaProjection screen capture, which is
 * where that project's documented 50-80ms of latency comes from. In-process we
 * already intercept vkQueuePresentKHR in libvulkad.so, so the swapchain image
 * can be copied straight into an AHB and pushed — no capture, no VirtualDisplay,
 * and no consent prompt.
 *
 * <p>Only the entry points actually used are declared. An undeclared native is
 * simply absent; a declared one with the wrong descriptor corrupts the stack
 * when called, so the subset is deliberate.
 */
public final class NativeBridge {

    /** The Kotlin original is an object; callers here use this instance. */
    public static final NativeBridge INSTANCE = new NativeBridge();

    private NativeBridge() {}

    /** @return null on success, else the failure to report. */
    public static String load() {
        try {
            System.loadLibrary("lsfg-android");
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    public native String nativeVersion();

    /**
     * Unpacks the LSFG shaders out of a user-supplied Lossless.dll into
     * cacheDir. Must be run before {@link #initContext}.
     */
    public native int extractShaders(String dllPath, String dllSha256, String cacheDir);

    /** Verifies previously extracted shaders in cacheDir. */
    public native int probeShaders(String cacheDir);

    public native int initContext(
            String cacheDir,
            int width,
            int height,
            int multiplier,
            float flowScale,
            boolean performance,
            boolean hdr,
            boolean antiArtifacts,
            boolean framegenFp16,
            boolean npuPostProcessing,
            int npuPreset,
            int npuUpscaleFactor,
            float npuAmount,
            float npuRadius,
            float npuThreshold,
            boolean npuFp16,
            boolean cpuPostProcessing,
            int cpuPreset,
            float cpuStrength,
            float cpuSaturation,
            float cpuVibrance,
            float cpuVignette,
            boolean gpuPostProcessing,
            int gpuStage,
            int gpuMethod,
            float gpuUpscaleFactor,
            float gpuSharpness,
            float gpuStrength,
            int targetFpsCap,
            float emaAlpha,
            float outlierRatio,
            float vsyncSlackMs,
            int queueDepth);

    /** Where generated frames are drawn. Pass null to detach. */
    public native void setOutputSurface(Surface surface, int w, int h);

    /** Hand the pipeline one real frame. */
    public native void pushFrame(HardwareBuffer hardwareBuffer, long timestampNs);

    public native long getGeneratedFrameCount();
    public native long getSuppressedFrameCount();

    public native long getPostedFrameCount();

    /** Frames whose pixel content differs from the previous capture (game render rate). */
    public native long getUniqueCaptureCount();

    /** True passes real frames straight through, generating nothing. */
    public native void setBypass(boolean bypass);

    /** Keep every pushed frame even when content hash matches (in-process capture). */
    public native void setSkipDuplicateCapture(boolean skip);

    public native void destroyContext();
}
