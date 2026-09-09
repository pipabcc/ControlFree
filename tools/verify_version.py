#!/usr/bin/env python3
"""校验 Gradle 版本声明与 APK Manifest、CycloneDX SBOM 一致。"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


def read_gradle_version(path: Path) -> tuple[int, str]:
    text = path.read_text(encoding="utf-8")
    code_match = re.search(r"\bversionCode\s*=\s*(\d+)", text)
    name_match = re.search(r'\bversionName\s*=\s*["\']([^"\']+)["\']', text)
    if not code_match or not name_match:
        raise ValueError(f"无法从 {path} 读取 versionCode/versionName")
    return int(code_match.group(1)), name_match.group(1)


def find_apkanalyzer(explicit: str | None) -> str:
    candidates = [explicit, shutil.which("apkanalyzer")]
    android_home = Path(__import__("os").environ.get("ANDROID_HOME", ""))
    if android_home:
        candidates.extend(
            str(path)
            for path in (
                android_home / "cmdline-tools" / "latest" / "bin" / "apkanalyzer",
                android_home / "tools" / "bin" / "apkanalyzer",
            )
        )
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    raise FileNotFoundError(
        "找不到 apkanalyzer；请安装 Android SDK command-line tools，或通过 --apkanalyzer 指定路径"
    )


def read_manifest_version(apk: Path, analyzer: str) -> tuple[int, str]:
    def query(field: str) -> str:
        completed = subprocess.run(
            [analyzer, "manifest", field, str(apk)],
            check=True,
            capture_output=True,
            text=True,
        )
        return completed.stdout.strip().strip('"')

    return int(query("version-code")), query("version-name")


def read_sbom_version(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"SBOM 不存在: {path}")
    document = json.loads(path.read_text(encoding="utf-8"))
    component = document.get("metadata", {}).get("component", {})
    version = component.get("version")
    if (
        not isinstance(version, str)
        or not version.strip()
        or version.strip().lower() == "unspecified"
    ):
        raise ValueError(f"SBOM 根组件缺少有效 version: {path}")
    return version.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apkanalyzer")
    parser.add_argument("--sbom", type=Path)
    args = parser.parse_args()

    expected_code, expected_name = read_gradle_version(args.gradle)
    print(f"Gradle version: code={expected_code}, name={expected_name}")
    if args.sbom:
        actual_sbom_name = read_sbom_version(args.sbom)
        print(f"SBOM version:    name={actual_sbom_name}")
        if actual_sbom_name != expected_name:
            raise ValueError("Gradle 与 SBOM 的版本名不一致")
    if not args.apk:
        return 0
    if not args.apk.is_file():
        raise FileNotFoundError(f"APK 不存在: {args.apk}")
    actual_code, actual_name = read_manifest_version(
        args.apk, find_apkanalyzer(args.apkanalyzer)
    )
    print(f"APK version:    code={actual_code}, name={actual_name}")
    if (expected_code, expected_name) != (actual_code, actual_name):
        raise ValueError("Gradle 与 APK 的版本号不一致")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError, subprocess.CalledProcessError) as error:
        print(f"版本一致性检查失败: {error}", file=sys.stderr)
        raise SystemExit(1) from error
