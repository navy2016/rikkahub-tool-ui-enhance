#!/usr/bin/env python3
"""Package one SHA-bound Release-derived target/driver, then run one bounded production scenario."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from collections import deque
from pathlib import Path

from production_summary import CLASS, SCENARIOS, VERSION, load, notices, report, validate

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "artifacts/production-build"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def annotation(level, title, message):
    text = message[:2800].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::{level} title={title}::{text}", flush=True)


def source_files():
    build = ROOT / "benchmarks/terminal-target/build.gradle.kts"
    copied = re.findall(r'"(me/rerere/rikkahub/[^"\n]+\.kt)"', build.read_text())
    paths = {ROOT / "app/src/main/java" / path for path in copied}
    paths.update(ROOT / path for path in (
        "app/src/main/res/font/jetbrains_mono.ttf", "app/compose_compiler_config.conf",
        "benchmarks/terminal-target/build.gradle.kts", "benchmarks/terminal-macrobenchmark/build.gradle.kts",
        "benchmarks/terminal-target/src/main/AndroidManifest.xml", "gradle/libs.versions.toml",
        "benchmarks/production-contract/src/main/kotlin/me/rerere/rikkahub/benchmark/ProductionBenchmarkSpec.kt",
        "benchmarks/terminal-target/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmarkActivity.kt",
        "benchmarks/terminal-target/src/main/java/me/rerere/rikkahub/benchmark/TerminalBenchmarkWorkload.kt",
        "benchmarks/terminal-macrobenchmark/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmark.kt",
        "benchmarks/production_ci.py", "benchmarks/production_summary.py",
    ))
    if len(copied) < 20 or any(not path.is_file() for path in paths):
        raise ValueError("Missing shared production source or source-copy contract")
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(paths)}


def env_identity():
    sha = os.environ.get("GITHUB_SHA", "")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    if not re.fullmatch(r"[a-f0-9]{40}", sha) or not re.fullmatch(r"[0-9]+", run_id):
        raise ValueError("Expected exact GitHub SHA and workflow run ID")
    return sha, run_id


def package():
    sha, run_id = env_identity()
    BUNDLE.mkdir(parents=True, exist_ok=True)
    manifest = {"sourceSha": sha, "buildRun": run_id, "suite": VERSION, "sources": source_files(), "apks": {}}
    tools = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").glob("*/aapt"),
                   key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))[-1].parent
    for role, module in (("target", "terminal-target"), ("driver", "terminal-macrobenchmark")):
        files = list((ROOT / f"benchmarks/{module}/build/outputs/apk/benchmark").glob("*.apk"))
        if len(files) != 1:
            raise ValueError(f"Expected one {role} benchmark APK, found {len(files)}")
        apk = BUNDLE / f"{role}.apk"
        shutil.copyfile(files[0], apk)
        with zipfile.ZipFile(apk) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Corrupt {role} APK")
        info = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True, timeout=30)
        app = re.search(r"package: name='([^']+)'", info)
        if app is None or (role == "target" and (app[1] != "me.rerere.rikkahub.terminalbenchmark" or
                                                  "application-debuggable" in info)):
            raise ValueError(f"Wrong/non-Release {role} APK")
        signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                                            text=True, timeout=30)
        (BUNDLE / f"{role}-signature.txt").write_text(signature)
        (BUNDLE / f"{role}-manifest.txt").write_text(info)
        manifest["apks"][role] = {"file": apk.name, "sha256": digest(apk), "bytes": apk.stat().st_size, "package": app[1]}
    manifest["instrumentation"] = manifest["apks"]["driver"]["package"] + "/androidx.test.runner.AndroidJUnitRunner"
    (BUNDLE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"bundle_name=production-benchmark-build-{sha}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}\n")
    annotation("notice", "Production benchmark build", json.dumps({key: value for key, value in manifest.items() if key != "sources"}))


def verify_bundle():
    sha, run_id = env_identity()
    manifest = json.loads((BUNDLE / "manifest.json").read_text())
    if (manifest.get("sourceSha") != sha or manifest.get("buildRun") != run_id or
            manifest.get("suite") != VERSION or manifest.get("sources") != source_files()):
        raise ValueError("APK bundle does not match this run/SHA/production source tree")
    for role in ("target", "driver"):
        apk = manifest["apks"][role]
        path = BUNDLE / f"{role}.apk"
        if apk["file"] != path.name or apk["sha256"] != digest(path) or apk["bytes"] != path.stat().st_size:
            raise ValueError(f"Modified {role} APK")
    return manifest


def command(args, timeout=30):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=timeout)


def adb(*args, timeout=30):
    return command(["adb", *args], timeout=timeout)


def safe_diagnostic(path, args, timeout=15):
    try:
        path.write_text(command(args, timeout))
    except (OSError, subprocess.SubprocessError) as error:
        path.write_text(f"Diagnostic unavailable: {type(error).__name__}\n")


def require_instrumentation_success(log, expected):
    text = log.read_text(errors="replace")
    if (f"OK ({expected} tests)" not in text or "INSTRUMENTATION_CODE: -1" not in text or
            any(marker in text for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "shortMsg="))):
        raise ValueError(f"Instrumentation did not complete {expected} successful tests")


def run_group(scenario, iterations, smoke):
    manifest = verify_bundle()
    sha, run_id = env_identity()
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "1")
    if not attempt.isdecimal() or not 1 <= iterations <= 50 or scenario not in SCENARIOS:
        raise ValueError("Invalid scenario/repetition/attempt")
    kind = "smoke" if smoke else "full"
    group_id = f"{run_id}-{attempt}-{scenario}-{kind}"
    out = ROOT / "artifacts/terminal-production" / scenario / group_id
    out.mkdir(parents=True, exist_ok=False)  # A rerun must not mistake earlier JSON for fresh output.
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    driver = manifest["apks"]["driver"]["package"]
    target = manifest["apks"]["target"]["package"]
    device_output = f"/sdcard/Android/media/{driver}/production-{group_id}"
    payload = {"sourceSha": sha, "runId": run_id, "groupId": group_id, "suite": VERSION,
               "scenario": scenario, "smoke": str(smoke).lower(), "targetApkSha256": manifest["apks"]["target"]["sha256"]}
    (out / "request.json").write_text(json.dumps(dict(payload, iterations=iterations), indent=2) + "\n")
    log = out / "instrumentation.log"
    error = None
    try:
        adb("wait-for-device", timeout=60)
        adb("logcat", "-c")
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb("shell", "settings", "put", "global", name, "1")
        adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        (out / "device.txt").write_text("\n".join(adb("shell", *args) for args in (
            ("getprop", "ro.build.fingerprint"), ("wm", "size"), ("wm", "density"),
            ("getprop", "dalvik.vm.heapsize"), ("settings", "get", "secure", "default_input_method"))))
        for role in ("target", "driver"):
            result = adb("install", "-r", "-t", str(BUNDLE / f"{role}.apk"), timeout=90)
            if "Success" not in result:
                raise ValueError(f"{role} APK installation failed")
        args = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS,
                "-e", "productionScenario", scenario, "-e", "productionSmoke", str(smoke).lower(),
                "-e", "terminalIterations", str(iterations), "-e", "additionalTestOutputDir", device_output,
                "-e", "androidx.benchmark.suppressErrors", "EMULATOR"]
        for key, value in payload.items():
            args += ["-e", f"androidx.benchmark.output.payload.{key}", value]
        args.append(manifest["instrumentation"])
        with log.open("w") as stream:
            result = subprocess.run(args, stdout=stream, stderr=subprocess.STDOUT, timeout=900, check=False)
        if result.returncode != 0:
            raise ValueError(f"ADB instrumentation exited {result.returncode}")
        require_instrumentation_success(log, 2 if smoke else 6)
    except (OSError, ValueError, subprocess.SubprocessError) as caught:
        error = caught
    finally:
        safe_diagnostic(out / "pull.log", ["adb", "pull", device_output, str(out / "measurements")], timeout=90)
        # Preserve partial JSON/traces but never validate them as a completed scenario.
        safe_diagnostic(out / "logcat.txt", ["adb", "logcat", "-d", "-v", "threadtime", "-s",
            "ProductionTerminalBenchmark:I", "AndroidRuntime:E", "TestRunner:E", "Benchmark:I", "*:S"])
        safe_diagnostic(out / "meminfo-after.txt", ["adb", "shell", "dumpsys", "meminfo", target])
        if error is not None:
            safe_diagnostic(out / "stop.log", ["adb", "shell", "am", "force-stop", driver])
    if error is not None:
        tail = ""
        if log.exists():
            with log.open(errors="replace") as stream:
                tail = "".join(deque(stream, maxlen=25))[-2200:]
        annotation("error", "Production benchmark instrumentation failed", f"{scenario}: {error}\n{tail}")
        with (out / "logcat.txt").open(errors="replace") as stream:
            annotation("error", "Production benchmark fixture diagnostics", "".join(deque(stream, maxlen=30))[-2800:])
        raise error
    results, context = load(out / "measurements")
    validate(results, context, sha, run_id, group_id, scenario, iterations, smoke,
             apk_sha=manifest["apks"]["target"]["sha256"])
    text = report(results, context, sha, scenario, smoke)
    (out / "summary.md").write_text(text)
    (out / "validated.json").write_text(json.dumps(dict(payload, cases=len(results), iterations=iterations, verified=True), indent=2))
    annotation("notice", f"Production viewport {scenario}", notices(results, sha, scenario, smoke))
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a") as stream:
            stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    plan = sub.add_parser("plan")
    plan.add_argument("--scenario", choices=("all", *SCENARIOS), default="all")
    sub.add_parser("package")
    run = sub.add_parser("run")
    run.add_argument("--scenario", choices=SCENARIOS, required=True)
    run.add_argument("--iterations", type=int, required=True)
    run.add_argument("--smoke", choices=("true", "false"), required=True)
    args = parser.parse_args()
    if args.action == "plan":
        scenarios = SCENARIOS if args.scenario == "all" else (args.scenario,)
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("scenarios=" + json.dumps(scenarios) + "\n")
    elif args.action == "package":
        package()
    else:
        run_group(args.scenario, args.iterations, args.smoke == "true")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        annotation("error", "Production benchmark validation failed", str(error))
        sys.exit(1)
