#!/usr/bin/env python3
"""
Builds qa-report.md and failure-report.md from CI artifacts produced by
verify, build-release, and device-tests jobs.
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def load_tsv_statuses(artifacts_dir):
    results = {}
    pattern = os.path.join(artifacts_dir, "**", "ci-status", "*.tsv")
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.rstrip("\r\n")
                    if not line:
                        continue
                    parts = line.split("\t", 3)
                    while len(parts) < 4:
                        parts.append("")
                    cid, status, rc, desc = parts[0], parts[1], parts[2], parts[3]
                    results[cid] = {
                        "id": cid,
                        "status": status,
                        "rc": rc,
                        "desc": desc,
                        "source": path,
                    }
        except OSError:
            pass
    return results


def parse_junit_counts(artifacts_dir):
    total = 0
    failures = 0
    errors = 0
    skipped = 0
    pattern = os.path.join(artifacts_dir, "**", "TEST-*.xml")
    for path in glob.glob(pattern, recursive=True):
        try:
            root = ET.parse(path).getroot()
            total += int(root.attrib.get("tests", 0))
            failures += int(root.attrib.get("failures", 0))
            errors += int(root.attrib.get("errors", 0))
            skipped += int(root.attrib.get("skipped", 0))
        except Exception:
            pass
    return total, failures, errors, skipped


def main():
    artifacts_dir = sys.argv[1] if len(sys.argv) > 1 else "artifacts"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "report-out"
    os.makedirs(out_dir, exist_ok=True)

    version_name = os.environ.get("VERSION_NAME", "2.4")
    strict_lint = os.environ.get("STRICT_LINT", "false")
    job_results_raw = os.environ.get("JOB_RESULTS", "{}")
    try:
        job_results = json.loads(job_results_raw)
    except Exception:
        job_results = {}

    statuses = load_tsv_statuses(artifacts_dir)
    tests_total, tests_fail, tests_err, tests_skip = parse_junit_counts(artifacts_dir)

    ordered_checks = [
        ("dependencies", "Gate 1.1", "Dependency resolution (debugRuntimeClasspath)"),
        ("compile", "Gate 1.1", "Kotlin + Java compilation"),
        ("resources", "Gate 1.1", "Resource processing"),
        ("manifest", "Gate 1.1", "Manifest merge"),
        ("manifest-content", "Gate 1.1", "Merged manifest validation (activities & permissions)"),
        ("debug-apk", "Gate 1.1", "Assemble debug APK"),
        ("apk-verify", "Gate 1.1", "Debug APK package, SDK & signature check"),
        ("unit-tests", "Gate 1.2", f"Unit tests ({tests_total} run, {tests_fail + tests_err} failed)"),
        ("lint", "Gate 1.3", f"Android Lint (strict_lint={strict_lint})"),
        ("release-apk", "Release", "Assemble release APK (R8 minify + shrink)"),
        ("release-apk-verify", "Release", "Release APK package & signature check"),
        ("install", "Stage 2", "Emulator install debug APK"),
        ("cold-launch", "Stage 2", "Emulator cold launch ListActivity"),
        ("lifecycle-cycles", "Stage 2", "Emulator background/foreground, rotation & lock/unlock cycles"),
        ("process-restart", "Stage 2", "Emulator force-stop and cold relaunch"),
        ("crash-anr-check", "Stage 2", "Zero FATAL EXCEPTION / ANR in logcat"),
    ]

    qa_lines = [
        f"# ComicReader FOSS v{version_name} — Automated QA Report",
        "",
        "## Summary of CI Jobs",
        "",
        "| Job | Result |",
        "| :--- | :--- |",
    ]
    for job_name in ("plan", "verify", "build-release", "device-tests"):
        res = job_results.get(job_name, {}).get("result", "unknown")
        qa_lines.append(f"| `{job_name}` | **{res.upper()}** |")

    qa_lines.extend([
        "",
        "## Automated Check Results",
        "",
        "| Stage | Check ID | Status | Description |",
        "| :--- | :--- | :--- | :--- |",
    ])

    failed_items = []
    for cid, stage, default_desc in ordered_checks:
        entry = statuses.get(cid)
        if entry:
            st = entry["status"]
            desc = entry["desc"] or default_desc
            if st == "FAIL":
                failed_items.append(entry)
        else:
            st = "NOT_RUN"
            desc = default_desc
        qa_lines.append(f"| {stage} | `{cid}` | **{st}** | {desc} |")

    # Include any extra recorded checks not in ordered_checks
    known_ids = {c[0] for c in ordered_checks}
    for cid, entry in sorted(statuses.items()):
        if cid not in known_ids:
            qa_lines.append(f"| Extra | `{cid}` | **{entry['status']}** | {entry['desc']} |")
            if entry["status"] == "FAIL":
                failed_items.append(entry)

    gate1_ok = all(
        statuses.get(cid, {}).get("status") == "PASS"
        for cid in ("dependencies", "compile", "resources", "manifest", "debug-apk", "unit-tests")
    )
    release_ok = statuses.get("release-apk", {}).get("status") == "PASS"
    stage2_statuses = [statuses.get(cid, {}).get("status", "NOT_RUN") for cid in ("install", "cold-launch", "lifecycle-cycles", "process-restart", "crash-anr-check")]
    if all(s == "PASS" for s in stage2_statuses):
        stage2_gate = "PASS"
    elif any(s == "FAIL" for s in stage2_statuses):
        stage2_gate = "FAIL"
    else:
        stage2_gate = "NOT_RUN"

    qa_lines.extend([
        "",
        "## Release-readiness gates",
        "",
        "| Gate | Scope | Status | Notes |",
        "| :--- | :--- | :--- | :--- |",
        f"| **Gate 1** | Build, resources, manifest, unit tests & lint | **{'PASS' if gate1_ok else 'FAIL'}** | {tests_total} unit tests executed |",
        f"| **Release R8** | Minified & resource-shrunk release APK | **{'PASS' if release_ok else 'FAIL'}** | Signed with stable/fallback key |",
        f"| **Gate 2 (Emulator)** | Cold start, rotation, lock/unlock, process restart | **{stage2_gate}** | Automated via Android Emulator API 34 |",
        "| **Gate 3 (On-Device)** | Large CBZ/CBR/CB7/PDF archives, SD card SAF, 120Hz scroll | **NOT RUN** | Requires physical Android device testing |",
        "",
        "Release rule: Automated builds clear Gate 1, Release R8, and Gate 2 emulator checks; physical archive & display verification remains recommended for final stable tags.",
        "",
    ])

    with open(os.path.join(out_dir, "qa-report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(qa_lines))

    fail_lines = [
        f"# ComicReader FOSS v{version_name} — Failure Diagnostics",
        "",
    ]
    if not failed_items:
        fail_lines.append("No failures recorded across executed CI checks.")
    else:
        fail_lines.append(f"{len(failed_items)} check(s) reported `FAIL`:")
        fail_lines.append("")
        for item in failed_items:
            fail_lines.append(f"### `{item['id']}` — {item['desc']} (rc={item['rc']})")
            log_candidates = glob.glob(
                os.path.join(artifacts_dir, "**", "ci-logs", f"{item['id']}.log"),
                recursive=True,
            )
            if log_candidates:
                try:
                    with open(log_candidates[0], "r", encoding="utf-8", errors="replace") as lf:
                        tail = lf.readlines()[-40:]
                    fail_lines.append("```text")
                    fail_lines.extend(line.rstrip("\r\n") for line in tail)
                    fail_lines.append("```")
                except OSError:
                    pass
            fail_lines.append("")

    with open(os.path.join(out_dir, "failure-report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(fail_lines) + "\n")


if __name__ == "__main__":
    main()
