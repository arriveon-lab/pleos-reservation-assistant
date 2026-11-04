#!/usr/bin/env python3
"""Inspect an installed SDK; optionally connect this project to it. Never installs or accepts licenses."""
import argparse
import os
from pathlib import Path
import platform
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def read_property(path, key):
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(key + "="):
            value = line.split("=", 1)[1].strip()
            value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match.group(1), 16)), value)
            value = value.encode("utf-16", "surrogatepass").decode("utf-16")
            return re.sub(r"\\([\\:= ])", r"\1", value)
    return None


def update_property(path, key, value):
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    escaped = value.replace("\\", "\\\\").replace(":", "\\:")
    # java.util.Properties.load(InputStream) expects ISO-8859-1 plus Unicode escapes.
    escaped = "".join(
        char if ord(char) < 128 else "".join(
            "\\u" + char.encode("utf-16-be")[offset:offset + 2].hex()
            for offset in range(0, len(char.encode("utf-16-be")), 2)
        )
        for char in escaped
    )
    updated = [line for line in lines if not re.match(rf"\s*{re.escape(key)}\s*[=:]", line)]
    updated.append(key + "=" + escaped)
    path.write_text("\n".join(updated) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="SDK path shown by Android Studio SDK Manager")
    parser.add_argument("--configure", action="store_true", help="Write ignored project-local SDK path and Android opt-in after checks")
    args = parser.parse_args()
    default = Path.home() / ("Library/Android/sdk" if platform.system() == "Darwin" else "Android/Sdk")
    if os.name == "nt":
        default = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Android/Sdk"
    configured = read_property(ROOT / "local.properties", "sdk.dir")
    sdk = Path(args.sdk or configured or os.environ.get("ANDROID_HOME") or default).expanduser().resolve()
    print(f"Android SDK: {sdk}")
    suffix = ".exe" if os.name == "nt" else ""
    needed = {
        "Android SDK Platform 35": sdk / "platforms/android-35/android.jar",
        "Android SDK Build-Tools 35.0.0": sdk / ("build-tools/35.0.0/aapt2" + suffix),
        "Android SDK Platform-Tools": sdk / ("platform-tools/adb" + suffix),
    }
    missing = []
    for label, path in needed.items():
        present = path.is_file()
        print(f"{'OK' if present else 'MISSING'}: {label}")
        if not present:
            missing.append(label)
    emulator = sdk / ("emulator/emulator" + suffix)
    print(f"{'OK' if emulator.is_file() else 'NOT INSTALLED'}: Android Emulator (device validation)")
    sdkmanager = sdk / ("cmdline-tools/latest/bin/sdkmanager" + (".bat" if os.name == "nt" else ""))
    print(f"{'OK' if sdkmanager.is_file() else 'NOT INSTALLED'}: SDK Command-line Tools latest")
    android_cli = sdk / ("cmdline-tools/latest/bin/android" + suffix)
    print(f"{'OK' if android_cli.is_file() else 'NOT INSTALLED'}: Android CLI (optional; older tools use sdkmanager)")
    try:
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin" / ("java" + suffix)) if java_home else "java"
        version = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=10)
        match = re.search(r'version "(\d+)', version.stderr + version.stdout)
        major = int(match.group(1)) if match else None
        print(f"Java: {major or 'unavailable'} (team standard: 21)")
        if version.returncode != 0 or major != 21:
            missing.append("JDK 21 for the shared project")
    except (OSError, subprocess.TimeoutExpired):
        missing.append("JDK 21")
    if missing:
        print("Install/configure the missing items using docs/ANDROID_SETUP.md. No files changed.")
        return 1
    if args.configure:
        update_property(ROOT / "local.properties", "sdk.dir", sdk.as_posix())
        update_property(ROOT / "gradle-local.properties", "includeAndroid", "true")
        print("Configured local.properties and gradle-local.properties (both Git-ignored).")
    print("SDK files found. APK build, licenses, Pleos image and device operation require separate verification.")
    print("Next: ./gradlew -PincludeAndroid=true verifyAndroid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
