#!/usr/bin/env python3
"""Guard ggml's dynamic CPU backend discovery contract in packaged APKs."""

import re
import subprocess
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify_native_library_packaging.py APK AAPT", file=sys.stderr)
        return 2

    apk_path, aapt_path = sys.argv[1:]
    manifest = subprocess.check_output(
        [aapt_path, "dump", "xmltree", apk_path, "AndroidManifest.xml"],
        text=True,
    )
    if not re.search(r"android:extractNativeLibs\b[^\n]*0xffffffff", manifest):
        raise SystemExit("APK must set android:extractNativeLibs=true for ggml backend discovery")

    with zipfile.ZipFile(apk_path) as apk:
        entries = set(apk.namelist())
        for abi in ("arm64-v8a", "x86_64"):
            prefix = f"lib/{abi}/"
            if f"{prefix}libai-chat.so" not in entries:
                raise SystemExit(f"APK is missing the JNI runtime for {abi}")
            backend_entries = [
                name for name in entries
                if name.startswith(prefix + "libggml-cpu-") and name.endswith(".so")
            ]
            if not backend_entries:
                raise SystemExit(f"APK is missing dynamic CPU backend libraries for {abi}")
            if any(apk.getinfo(name).compress_type != zipfile.ZIP_DEFLATED for name in backend_entries):
                raise SystemExit(f"CPU backend libraries for {abi} must be packaged for install-time extraction")

    print("APK extracts ggml CPU backend libraries into nativeLibraryDir for both packaged ABIs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
