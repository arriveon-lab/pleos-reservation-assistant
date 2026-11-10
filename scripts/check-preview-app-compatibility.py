#!/usr/bin/env python3
"""Read-only, commit-pinned app core against #16's local fixture server; no Android/device."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP_COMMIT = "26e80cfd72182b97963171ff8ff896f198f1d2d0"
APP_FILES = ["ux/core/src/main/java/ai/pleos/assistant/ux/" + name + ".java"
             for name in ("AuthSession", "MockRepository", "PreviewController")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-root", type=Path, required=True,
                        help="Read-only app checkout at the explicitly reviewed commit")
    args = parser.parse_args()
    checkout = args.app_root.resolve()
    revision = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"],
                                       stderr=subprocess.DEVNULL, text=True).strip()
    if revision != APP_COMMIT:
        raise ValueError("APP_COMMIT_MISMATCH")
    subprocess.run(["git", "-C", str(checkout), "diff", "--quiet", "HEAD", "--", *APP_FILES],
                   check=True)
    # Preserve the reviewed app's JVM JSON version, separate from Android's built-in JSON.
    assert 'org.json:json:20240303' in (checkout / "ux/core/build.gradle.kts").read_text()
    dependency = (ROOT / "server/build/preview-client-classpath.txt").read_text().strip()
    assert len(dependency.split(os.pathsep)) == 1 and Path(dependency).is_file()
    with zipfile.ZipFile(ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar") as jar:
        assert not any("json-20240303.jar" in name or "/ai/pleos/assistant/ux/" in name
                       for name in jar.namelist())
    spec = importlib.util.spec_from_file_location("acceptance", ROOT / "scripts/check-preview-acceptance.py")
    acceptance = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(acceptance)
    classpath = (ROOT / "server/build/smoke-classpath.txt").read_text().strip()
    (ROOT / "work").mkdir(exist_ok=True)
    app_requests = 0
    contract_requests = 0
    with tempfile.TemporaryDirectory(prefix="check-app-", dir=ROOT / "work") as temporary:
        directory = Path(temporary)
        classes = directory / "classes"
        classes.mkdir()
        source = ROOT / "scripts/fixtures/PreviewAppCompatibility.java"
        subprocess.run(["javac", "--release", "17", "-cp", dependency, "-d", str(classes),
                        *[str(checkout / name) for name in APP_FILES], str(source)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)

        def check_client(base, fixture, candidates):
            nonlocal app_requests
            child = subprocess.run(
                ["java", "-cp", str(classes) + os.pathsep + dependency,
                 "PreviewAppCompatibility", base, fixture, str(candidates)],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, text=True,
                env={k: v for k, v in os.environ.items()
                     if k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")})
            lines = child.stdout.strip().splitlines()
            assert len(lines) == 1 and lines[0].startswith("APP_COMPAT_RESULT ")
            result = json.loads(lines[0].split(" ", 1)[1])
            assert set(result) == {"fixture", "mode", "queries", "requests", "identityIssues"}
            assert result["fixture"] == fixture and result["mode"] == "MOCK"
            assert result["queries"] == 2 and result["identityIssues"] == 1
            assert result["requests"] == 13
            app_requests += result["requests"]
            print(f"PASS: pinned app {fixture}, HTTP={result['requests']}, refresh/clear/party20+10", flush=True)

        for fixture, candidates in (("RESULTS", 3), ("EMPTY", 0), ("FILTERED", 0), ("FAILED", 0)):
            print(f"CHECK: pinned app {APP_COMMIT[:7]} / MOCK {fixture}", flush=True)
            case = directory / fixture.lower()
            case.mkdir()
            contract_requests += acceptance.exercise(fixture, candidates, classpath, case, check_client)
    print(f"PASS: app {APP_COMMIT[:7]}, app HTTP={app_requests}, schema HTTP={contract_requests}; no device/external calls")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"FAIL: pinned app compatibility ({type(failure).__name__}); response bodies omitted")
        raise SystemExit(1) from None
