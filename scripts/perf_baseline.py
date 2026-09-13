#!/usr/bin/env python3
"""Run the existing benchmark module against an already prepared isolated app."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

REPOSITORY = Path(__file__).resolve().parents[1]
PACKAGES = ("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline")
RUNNER = "com.nuvio.tv.baselineprofile/androidx.test.runner.AndroidJUnitRunner"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("benchmark", "generate-profile", "release-with-profile"))
    parser.add_argument("--serial", help="One explicitly selected ADB device")
    parser.add_argument("--package", choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument("--iterations", type=int, choices=range(3, 21), default=5)
    parser.add_argument("--journey", choices=("coldStart", "horizontalRow", "verticalRows", "sidebar", "details", "settings"))
    parser.add_argument("--output", type=Path, default=REPOSITORY / "build" / "nuvio-performance")
    parser.add_argument("--skip-build", action="store_true", help="Use the already built benchmark APK")
    args = parser.parse_args()
    gradle = str(REPOSITORY / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    if args.command == "release-with-profile":
        subprocess.run([gradle, ":app:assembleFullRelease", ":app:bundleFullRelease"], cwd=REPOSITORY, check=True)
        return
    if not args.serial:
        parser.error("--serial is required; never run device tests on an implicit target")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb_path = shutil.which("adb") or (str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else None)
    if not adb_path:
        parser.error("Set ANDROID_HOME or put adb on PATH")
    adb = [adb_path, "-s", args.serial]
    def read(*command):
        return subprocess.check_output(adb + list(command), text=True, encoding="utf-8", errors="replace").strip()
    if read("get-state") != "device":
        parser.error("ADB device must be authorized and ready")
    if not read("shell", "pm", "path", args.package).startswith("package:"):
        parser.error("Install and prepare the selected isolated app before benchmarking")
    apk = REPOSITORY / "baselineprofile/build/outputs/apk/benchmarkRelease/baselineprofile-benchmarkRelease.apk"
    if not args.skip_build:
        subprocess.run([gradle, ":baselineprofile:assembleBenchmarkRelease"], cwd=REPOSITORY, check=True)
    subprocess.run(adb + ["install", "-r", str(apk)], check=True)
    if args.command == "benchmark":
        compiled = read("shell", "cmd", "package", "compile", "-m", "speed", "-f", args.package)
        if compiled != "Success":
            raise SystemExit("Explicit speed compilation failed: " + compiled)
    elif int(read("shell", "getprop", "ro.build.version.sdk")) < 34:
        raise SystemExit("Use an API 34+ device for profile generation without resetting prepared app data")
    args.output.mkdir(parents=True, exist_ok=True)
    metadata = {"package": args.package, "iterations": args.iterations, "journey": args.journey,
                "compilation": ("external speed AOT; CompilationMode.Ignore" if args.command == "benchmark"
                                else "BaselineProfileRule collection"),
                "model": read("shell", "getprop", "ro.product.model"),
                "firmware": read("shell", "getprop", "ro.build.fingerprint"),
                "api": read("shell", "getprop", "ro.build.version.sdk"),
                "abi": read("shell", "getprop", "ro.product.cpu.abilist"),
                "window": read("shell", "wm", "size"), "density": read("shell", "wm", "density")}
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    test_class = "com.nuvio.tv.baselineprofile." + (
        "BaselineProfileGenerator" if args.command == "generate-profile" else "NuvioNavigationBenchmark")
    if args.journey and args.command == "benchmark":
        test_class += "#" + args.journey
    remote_output = "/sdcard/Android/media/com.nuvio.tv.baselineprofile/nuvio-performance-" + uuid.uuid4().hex[:12]
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", test_class,
                     "-e", "nuvioPackage", args.package, "-e", "nuvioIterations", str(args.iterations),
                     "-e", "nuvioCompilation", "speed",
                     "-e", "additionalTestOutputDir", remote_output, RUNNER]
    with (args.output / "instrumentation.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        lines = []
        for line in process.stdout:
            log.write(line)
            log.flush()
            print(line, end="", flush=True)
            lines.append(line)
        exit_code = process.wait()
    result = "".join(lines)
    # Keep traces from successful iterations even if a later journey fails.
    pulled = subprocess.run(adb + ["pull", remote_output, str(args.output / "artifacts")])
    (args.output / "memory-after.txt").write_text(read("shell", "dumpsys", "meminfo", args.package), encoding="utf-8")
    # am instrument can exit 0 for failed tests; inspect the runner's summary too.
    if exit_code or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result or "OK (" not in result:
        raise SystemExit("Benchmark did not pass; see instrumentation.log")
    pulled.check_returncode()


if __name__ == "__main__":
    main()
