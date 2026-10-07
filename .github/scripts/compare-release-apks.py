#!/usr/bin/env python3
"""Read-only comparison of two already-built APKs, bound to their embedded source revisions."""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import zipfile
import zlib
from collections import Counter
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def category(name):
    if re.fullmatch(r"classes\d*\.dex", name):
        return "dex"
    if name.startswith("META-INF/"):
        return "metadata"
    if name.startswith("lib/"):
        return "native"
    if name.startswith("res/") or name in ("AndroidManifest.xml", "resources.arsc"):
        return "resources"
    if name.startswith("static/"):
        return "web"
    return "assets"


def snapshot(root, sha, tools):
    apks = list(root.rglob("*.apk"))
    if len(apks) != 1:
        raise ValueError(f"Expected one APK in {root}, found {len(apks)}")
    apk = apks[0]
    signature = subprocess.check_output(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True, timeout=60)
    certificates = re.findall(r"^.*?\bcertificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", signature, re.M)
    if not certificates or not re.search(r"Verified using v[23](?:\.1)? scheme .*: true", signature):
        raise ValueError("APK has no verified v2/v3 signer")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True, timeout=60)
    if "application-debuggable" in badging or "application-testOnly" in badging:
        raise ValueError("Expected production Release APK")
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package[1] != "me.rerere.rikkahub.dev.next.mod":
        raise ValueError("Unexpected Release package")
    entries, versions, fonts = {}, {}, {}
    with zipfile.ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("Duplicate APK entries")
        revision = archive.read("META-INF/version-control-info.textproto").decode()
        if re.findall(r'revision:\s*"([a-f0-9]{40})"', revision) != [sha]:
            raise ValueError("Embedded source revision mismatch")
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            # Read every member, verifying its ZIP CRC; retain hashes, never APK bytes or app text.
            value = archive.read(entry)
            entries[entry.filename] = {"bytes": len(value), "sha256": digest(value)}
            if entry.filename.startswith("META-INF/") and entry.filename.endswith(".version"):
                versions[entry.filename] = value.decode().strip()
            if entry.filename.lower().endswith((".ttf", ".otf", ".ttc")):
                fonts[entry.filename] = digest(value)
    with apk.open("rb") as stream:
        apk_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    groups = {}
    for kind in ("dex", "metadata", "native", "resources", "web", "assets"):
        members = {name: value for name, value in entries.items() if category(name) == kind}
        groups[kind] = {"count": len(members), "bytes": sum(v["bytes"] for v in members.values()),
                        "sha256": digest(json.dumps(members, sort_keys=True).encode())}
    return dict(source_sha=sha, sha256=apk_digest, bytes=apk.stat().st_size,
                package=package[1], version_code=package[2], version_name=package[3],
                manifest_identity=[line for line in badging.splitlines() if line.startswith(
                    ("package:", "sdkVersion:", "targetSdkVersion:", "native-code:"))],
                signer_certificate_sha256=certificates, signature_verified=True, zip_verified=True,
                versions=versions, fonts=fonts, groups=groups, entries=entries)


def compare(good, candidate):
    changed = {}
    for name in sorted(set(good["entries"]) | set(candidate["entries"])):
        before, after = good["entries"].get(name), candidate["entries"].get(name)
        if before != after:
            changed.setdefault(category(name), []).append(dict(name=name, before=before, after=after))
    versions = {name: dict(before=good["versions"].get(name), after=candidate["versions"].get(name))
                for name in sorted(set(good["versions"]) | set(candidate["versions"]))
                if good["versions"].get(name) != candidate["versions"].get(name)}
    return dict(same_signer=good["signer_certificate_sha256"] == candidate["signer_certificate_sha256"],
                same_manifest_identity=good["manifest_identity"] == candidate["manifest_identity"],
                dependency_version_changes=versions,
                same_font_content_multiset=Counter(good["fonts"].values()) == Counter(candidate["fonts"].values()),
                changed_entry_counts={kind: len(items) for kind, items in changed.items()}, changed_entries=changed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--reference-sha", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if any(not re.fullmatch(r"[a-f0-9]{40}", sha) for sha in (args.reference_sha, args.candidate_sha)):
        raise ValueError("Expected exact source SHAs")
    tools = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").glob("*/aapt"),
                   key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))[-1].parent
    good = snapshot(args.reference, args.reference_sha, tools)
    candidate = snapshot(args.candidate, args.candidate_sha, tools)
    comparison = compare(good, candidate)
    report = dict(reference=good, candidate=candidate, comparison=comparison)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    summary = {k: v for k, v in comparison.items() if k != "changed_entries"}
    summary.update(reference_sha256=good["sha256"], candidate_sha256=candidate["sha256"])
    print("::notice title=Release comparison summary::" + json.dumps(summary))
    # Compact evidence carries versions and exact changed hashes, but not thousands of equal entries.
    compact = dict(reference={k: v for k, v in good.items() if k != "entries"},
                   candidate={k: v for k, v in candidate.items() if k != "entries"}, comparison=comparison)
    data = json.dumps(compact, separators=(",", ":")).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 3000] for i in range(0, len(encoded), 3000)]
    if len(parts) > 8:
        print("::warning title=Release comparison evidence::Detailed hashes exceed annotation budget; see artifact.")
    else:
        for number, part in enumerate(parts, 1):
            print(f"::notice title=Release comparison evidence {number}/{len(parts)}::"
                  f"zlib-base64 sha256={digest(data)}%0A{part}")


if __name__ == "__main__":
    main()
