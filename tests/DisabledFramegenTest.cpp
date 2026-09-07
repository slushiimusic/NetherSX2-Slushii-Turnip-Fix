#include "fg_source.h"
#include <cassert>
int main() {
 fg_set_enabled(1); fg_set_force(1); fg_set_capture_source(1); fg_arm_present_route();
 fg_set_capture_size(1920,1080); fg_set_expected_gs(1280,896);
 fg_note_current_gs(1,1280,896,37); fg_note_present_image(1,1920,1080);
 assert(!fg_enabled()); assert(!fg_capture_and_push());
 assert(!fg_capture_is_from_present()); assert(!fg_present_route_armed());
}
