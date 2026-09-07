// NONFRAMEGEN link boundary: no capture buffers, worker, JNI bridge or synthesis.
#include "fg_source.h"

// Compatibility storage for the shared config parser; no runtime consumes it.
int g_fg_panel_transform = -1;
int g_fg_pretransform_identity = 1;
float g_fg_output_aspect = 4.0f / 3.0f;
int g_fg_prefer_mailbox = 1;
int g_fg_test_pattern = 0;

void fg_set_logger(fg_log_fn fn) {}
void fg_set_enabled(int on) {}
int fg_enabled(void) { return 0; }
void fg_set_device(void *device, void *device_gpa) {}
void fg_note_queue(void *queue, uint32_t family) {}
void fg_note_memory_properties(const void *props) {}
void fg_note_current_gs(uint64_t image, uint32_t w, uint32_t h, uint32_t format) {}
void fg_note_present_image(uint64_t image, uint32_t w, uint32_t h) {}
void fg_set_capture_source(int from_present) {}
void fg_arm_present_route(void) {}
int fg_capture_is_from_present(void) { return 0; }
int fg_present_route_armed(void) { return 0; }
void fg_note_image_layout(uint64_t img, uint32_t layout) {}
void fg_note_render_queue(void *queue, uint32_t family) {}
void fg_notify_device_lost(void) {}
void fg_notify_image_destroyed(uint64_t img) {}
int fg_expected_gs_is(uint32_t w, uint32_t h) { return 0; }
void fg_notify_gs_resize(uint32_t w, uint32_t h) {}
void fg_set_expected_gs(uint32_t w, uint32_t h) {}
void fg_set_capture_size(uint32_t w, uint32_t h) {}
int fg_capture_and_push(void) { return 0; }
void fg_set_capture_paused(int on) {}
void fg_set_force(int on) {}
void fg_set_push_direct(int on) {}
void fg_set_suppress_reason(int reason) {}
int fg_suppress_reason(void) { return 0; }
int fg_throttled(void) { return 0; }
