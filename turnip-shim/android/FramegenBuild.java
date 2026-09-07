package xyz.aethersx2.android.shim;

/**
 * Compile-time gate for in-process frame generation.
 *
 * <p>Both halves of the port from {@code NetherSX2-fg} have landed here:
 * {@code ShimFrameGen} / {@code NativeBridge} / {@code ShimSurfaceScale} on the
 * Java side, and on the native side {@code fg_source.cpp} plus a self-contained
 * GS-target tracker in {@code vulkan_shim.cpp} (image → view → framebuffer maps
 * feeding {@code fg_note_current_gs} from {@code vkCmdBeginRenderPass} and
 * {@code vkCmdBeginRendering}).
 *
 * <p>Three traps if this is ever rebuilt from scratch:
 * <ul>
 *   <li>{@code g_framegen} in {@code vulkan_shim.cpp} is the OLD Vulkan-layer
 *       route and is compiled out by {@code NETHER_NO_FSR_FRAMEGEN}. The
 *       in-process gate is {@code g_fg_overlay} / {@code shim_framegen_wanted()}.
 *       Gating the GS observers on the upscaler instead is what produces
 *       {@code GS 0x0}.</li>
 *   <li>{@code -landroid} must be linked into {@code libvulkad.so} or it fails
 *       to load <em>entirely</em> on {@code AHardwareBuffer_allocate}, silently
 *       taking the whole shim with it.</li>
 *   <li>The GS heuristic also matches PCSX2's 256×256 scratch targets, so the
 *       tracker keeps only the LARGEST extent seen — otherwise you capture a
 *       scratch buffer at a perfect 60 fps and generate nothing.</li>
 * </ul>
 *
 * <p>Runtime gate is {@code turnip.conf lsfg_overlay}; with it absent or off
 * nothing is allocated and the present path is untouched.
 */
final class FramegenBuild {
    /** Set false to strip framegen from this APK variant. */
    static final boolean AVAILABLE = true;

    private FramegenBuild() {}
}
