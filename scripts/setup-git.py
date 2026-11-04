#!/usr/bin/env python3
"""Enable this repository's commit hook/template, preserving unrelated local Git configuration."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)


settings = {"core.hooksPath": ".githooks", "commit.template": str(ROOT / ".gitmessage")}
for key, wanted in settings.items():
    current = git("config", "--get", key)
    if current.returncode not in (0, 1):
        raise SystemExit(current.stderr)
    equivalent_template = key == "commit.template" and current.stdout.strip() == ".gitmessage"
    if current.returncode == 0 and current.stdout.strip() != wanted and not equivalent_template:
        raise SystemExit(f"기존 {key}={current.stdout.strip()} 설정을 보존했습니다. 팀 훅과 직접 병합하세요.")
for key, wanted in settings.items():
    result = git("config", "--local", key, wanted)
    if result.returncode:
        raise SystemExit(result.stderr)
print("Configured repository-local commit hook and template. Global Git settings unchanged.")
