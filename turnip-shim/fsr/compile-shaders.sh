#!/usr/bin/env bash
# Compile FSR1 GLSL compute shaders to SPIR-V and emit C headers.
set -euo pipefail
cd "$(dirname "$0")"

GLSLANG="${GLSLANG:-}"
if [[ -z "$GLSLANG" ]]; then
    for c in glslangValidator \
             /opt/homebrew/bin/glslangValidator \
             /usr/local/bin/glslangValidator; do
        if [[ -x "$c" ]]; then GLSLANG="$c"; break; fi
    done
fi
[[ -n "$GLSLANG" ]] || { echo "error: glslangValidator not found (brew install glslang)" >&2; exit 1; }

emit_header() {
    local name="$1" bin="$2" out="$3"
    python3 - "$name" "$bin" "$out" <<'PY'
import sys, pathlib
name, src, dst = sys.argv[1:4]
data = pathlib.Path(src).read_bytes()
lines = [f"static const uint32_t {name}[] = {{"]
for i in range(0, len(data), 4):
    chunk = data[i:i+4]
    while len(chunk) < 4:
        chunk += b'\0'
    val = int.from_bytes(chunk, 'little')
    lines.append(f"    0x{val:08x}u,")
lines.append("};")
lines.append(f"static const uint32_t {name}_size = {len(data)}u;")
pathlib.Path(dst).write_text("\n".join(lines) + "\n")
print(f"  wrote {dst} ({len(data)} bytes)")
PY
}

for shader in fsr_easu fsr_rcas fg_blend fg_flow; do
    echo "compiling ${shader}.comp"
    "$GLSLANG" -V "${shader}.comp" -o "${shader}.spv" --target-env vulkan1.1
    emit_header "${shader}_spv" "${shader}.spv" "${shader}_spv.h"
done
