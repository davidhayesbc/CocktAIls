#!/usr/bin/env bash
set -uo pipefail

readonly app_id="com.davidhayesbc.cocktails"
readonly adb="$ANDROID_HOME/platform-tools/adb"
readonly screenshot_dir="files/test-screenshots"
readonly screenshot_names=(
  camera-permission-denialKeepsManualEntryAvailableThenGrantOpensScannerAcrossLifecycle.png
  batch-intake-count-failure.png
  onboarding-setup-component-simulated.png
  onboarding-integrated-setup.png
  onboarding-scanning-route-simulated.png
  onboarding-barcode-scanner.png
  onboarding-label-step-simulated.png
  onboarding-label-preview-simulated.png
  onboarding-label-photo-preview.png
  onboarding-label-crop-dialog.png
  onboarding-label-camera-layout-simulated.png
  onboarding-queue-simulated.png
  onboarding-review-simulated.png
  onboarding-review-real-route.png
  onboarding-pause-simulated.png
  detailed-lookup-debug-mode-settings-real-route.png
  detailed-lookup-debug-mode-settings-on-real-route.png
  detailed-lookup-debug-mode-on.png
  detailed-lookup-debug-mode-off.png
  detailed-lookup-debug-mode-large-text.png
  detailed-lookup-capture-diagnostics-with-report.png
  detailed-lookup-capture-diagnostics-large-text.png
  more-export-bar.png
  more-export-bar-large-text.png
)
readonly required_visual_screenshots=(
  onboarding-integrated-setup.png
  onboarding-barcode-scanner.png
  onboarding-label-photo-preview.png
  onboarding-label-crop-dialog.png
  onboarding-review-real-route.png
  onboarding-review-simulated.png
  detailed-lookup-debug-mode-settings-real-route.png
  detailed-lookup-debug-mode-settings-on-real-route.png
  detailed-lookup-debug-mode-on.png
  detailed-lookup-debug-mode-off.png
  detailed-lookup-debug-mode-large-text.png
  detailed-lookup-capture-diagnostics-with-report.png
  detailed-lookup-capture-diagnostics-large-text.png
  more-export-bar.png
  more-export-bar-large-text.png
)

readonly test_log="$RUNNER_TEMP/cocktails-instrumentation.log"
readonly test_exit="$RUNNER_TEMP/cocktails-instrumentation.exit"
rm -f "$test_exit"

(
  timeout 900 ./gradlew --no-daemon :apps:androidApp:connectedDebugAndroidTest >"$test_log" 2>&1
  result=$?
  printf '%s\n' "$result" >"$test_exit"
  exit "$result"
) &
test_pid=$!
declare -A collected=()

collect_screenshots() {
  local listing name destination
  listing="$(timeout 5 "$adb" -s emulator-5554 shell run-as "$app_id" ls "$screenshot_dir" 2>/dev/null || true)"
  for name in "${screenshot_names[@]}"; do
    [[ -z "${collected[$name]+present}" ]] || continue
    if ! printf '%s\n' "$listing" | awk -v target="$name" '$0 == target { found = 1 } END { exit !found }'; then
      continue
    fi

    destination="$RUNNER_TEMP/$name"
    if timeout 20 "$adb" -s emulator-5554 exec-out run-as "$app_id" cat "$screenshot_dir/$name" >"$destination"; then
      if python3 - "$destination" <<'PY'
from pathlib import Path
import sys
import struct
import zlib

path = Path(sys.argv[1])
data = path.read_bytes()
if len(data) > 8 * 1024 * 1024 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit(1)

offset = 8
width = height = bit_depth = color_type = None
idat = bytearray()
seen_iend = False
while offset + 12 <= len(data):
    length = struct.unpack_from(">I", data, offset)[0]
    chunk_end = offset + 12 + length
    if chunk_end > len(data):
        raise SystemExit(1)
    kind = data[offset + 4:offset + 8]
    content = data[offset + 8:offset + 8 + length]
    crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
    if zlib.crc32(kind + content) & 0xFFFFFFFF != crc:
        raise SystemExit(1)
    if kind == b"IHDR":
        if length != 13:
            raise SystemExit(1)
        width, height, bit_depth, color_type = struct.unpack_from(">IIBB", content)
        if width < 320 or height < 480 or bit_depth != 8:
            raise SystemExit(1)
    elif kind == b"IDAT":
        idat.extend(content)
    elif kind == b"IEND":
        if length != 0:
            raise SystemExit(1)
        seen_iend = True
        offset = chunk_end
        break
    offset = chunk_end

channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
if not seen_iend or offset != len(data) or channels is None or not idat:
    raise SystemExit(1)
pixels = zlib.decompress(bytes(idat))
if len(pixels) != (width * channels + 1) * height:
    raise SystemExit(1)
PY
      then
        collected["$name"]=1
        echo "Collected app-private emulator screenshot: $name"
        continue
      fi
    fi
    rm -f "$destination"
    echo "Could not collect screenshot through run-as: $name"
  done
}

while [[ ! -s "$test_exit" ]] && kill -0 "$test_pid" 2>/dev/null; do
  collect_screenshots
  sleep 1
done

wait "$test_pid"
test_result=$?
collect_screenshots

python3 .github/scripts/report_instrumentation_failures.py \
  apps/androidApp/build/outputs/androidTest-results/connected \
  || echo 'Could not summarize instrumentation XML; consult the diagnostic artifact.'

for name in "${screenshot_names[@]}"; do
  if [[ -z "${collected[$name]+present}" ]]; then
    if [[ "$name" == onboarding-*.png ]]; then
      echo "Onboarding screenshot missing from emulator test artifacts: $name"
    fi
  fi
done

if [[ "$test_result" -eq 0 ]]; then
  missing_required=()
  for name in "${required_visual_screenshots[@]}"; do
    [[ -n "${collected[$name]+present}" ]] || missing_required+=("$name")
  done
  if ((${#missing_required[@]} > 0)); then
    printf 'Required real intake screenshots were not collected: %s\n' "${missing_required[*]}" >&2
    exit 1
  fi
fi

tail -n 120 "$test_log"
exit "$test_result"
