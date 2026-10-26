#!/usr/bin/env python3
"""Checks every component shipped in a JTRAC release against the OSV vulnerability database.

Inputs (from the release build):
  distribution/build/generated/licenses/sbom-server.json     CycloneDX, server runtime
  distribution/build/generated/licenses/sbom-launcher.json   CycloneDX, launcher runtime
  distribution/build/generated/frontend-licenses.raw.json    pnpm licenses list --prod --json

Usage:  python -I tools/osv-scan.py [--report FILE] [--allow FILE]
Exit code 0 when no known vulnerability affects a shipped component (or each one is listed,
with a reason, in the allow file: lines "OSV-ID  reason"), 1 otherwise, 2 on errors.
Needs network access to https://api.osv.dev (nothing but package names and versions is sent).
"""
import argparse
import json
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
GEN = ROOT / "distribution" / "build" / "generated"
API = "https://api.osv.dev/v1"


def post(path, payload):
    req = urllib.request.Request(API + path, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def get(path):
    with urllib.request.urlopen(API + path, timeout=60) as r:
        return json.load(r)


def maven_components(sbom):
    data = json.loads(sbom.read_text(encoding="utf-8"))
    out = []
    for c in data.get("components", []):
        purl = c.get("purl", "")
        if purl.startswith("pkg:maven/") and not purl.startswith(("pkg:maven/com.jtac/", "pkg:maven/com.jtrac/")):
            out.append(("Maven", f"{c.get('group')}:{c.get('name')}", c.get("version"), purl.split("?")[0]))
    return out


def npm_components(raw):
    data = json.loads(raw.read_text(encoding="utf-8"))
    out = []
    for packages in data.values():
        for p in packages:
            for v in p.get("versions", []):
                out.append(("npm", p["name"], v, f"pkg:npm/{p['name'].replace('@', '%40', 1) if p['name'].startswith('@') else p['name']}@{v}"))
    return out


def severity(vuln):
    for s in vuln.get("severity", []):
        if s.get("score"):
            return s["score"]
    return (vuln.get("database_specific") or {}).get("severity", "unknown")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", default=str(ROOT / "distribution" / "build" / "verification" / "osv-report.md"))
    parser.add_argument("--allow", default=str(ROOT / "distribution" / "osv-allowlist.txt"))
    args = parser.parse_args()

    components = []
    for sbom in ("sbom-server.json", "sbom-launcher.json"):
        path = GEN / "licenses" / sbom
        if not path.exists():
            print(f"missing {path}: run the release build first", file=sys.stderr)
            return 2
        components += maven_components(path)
    raw = GEN / "frontend-licenses.raw.json"
    if raw.exists():
        components += npm_components(raw)
    components = sorted(set(components))

    allowed = {}
    allow_file = pathlib.Path(args.allow)
    if allow_file.exists():
        for line in allow_file.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                vid, _, reason = line.strip().partition(" ")
                allowed[vid] = reason.strip()

    findings = []
    for i in range(0, len(components), 500):
        batch = components[i:i + 500]
        result = post("/querybatch", {"queries": [{"package": {"purl": c[3]}} for c in batch]})
        for component, entry in zip(batch, result.get("results", [])):
            for v in entry.get("vulns", []) or []:
                findings.append((component, v["id"]))

    lines = [f"# OSV scan of the JTRAC release", "", f"Components checked: {len(components)} "
             f"({sum(1 for c in components if c[0] == 'Maven')} Maven, {sum(1 for c in components if c[0] == 'npm')} npm)", ""]
    blocking = 0
    if not findings:
        lines.append("No known vulnerabilities affect the shipped components.")
    else:
        lines += ["| Component | Version | Vulnerability | Severity | Summary | Status |", "|---|---|---|---|---|---|"]
        for (eco, name, version, _), vid in findings:
            detail = get(f"/vulns/{vid}")
            status = f"allowed: {allowed[vid]}" if vid in allowed else "BLOCKING"
            if vid not in allowed:
                blocking += 1
            summary = (detail.get("summary") or detail.get("details", "")[:120]).replace("|", "/").replace("\n", " ")
            lines.append(f"| {eco} {name} | {version} | {vid} | {severity(detail)} | {summary} | {status} |")
    report = pathlib.Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\nReport: {report}")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
