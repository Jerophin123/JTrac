#!/usr/bin/env python3
"""Static inspection of a JTRAC Linux/macOS package, runnable on any OS (no target machine).

Independent of the Gradle check (different implementation), it verifies:
  - one top-level folder; no absolute paths, no "..", no device files
  - executables are executable (scripts, java, jspawnhelper, PostgreSQL tools)
  - every symlink resolves to a file inside the archive
  - shell scripts: #!/bin/sh shebang, LF line endings, `sh -n` syntax check (when sh exists)
  - runtime/release: OS and architecture of the linked Java runtime, version and modules
  - ELF / Mach-O CPU type of key binaries; on macOS, a code signature (LC_CODE_SIGNATURE) in
    every Mach-O slice, which Apple Silicon requires before it will run a binary

Usage: python -I tools/inspect-unix-package.py ARCHIVE [--modules-from WINDOWS_ZIP]
"""
import argparse
import io
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile

LC_CODE_SIGNATURE = 0x1D
CPU = {0x01000007: "x86_64", 0x0100000C: "arm64"}


def macho_slices(data):
    """Yields (cpu, has_signature) for each slice of a thin or universal Mach-O."""
    magic_be = struct.unpack(">I", data[:4])[0]
    if magic_be == 0xCAFEBABE:
        n = struct.unpack(">I", data[4:8])[0]
        for i in range(n):
            cputype, _, offset, size, _ = struct.unpack(">iiIII", data[8 + i * 20: 28 + i * 20])
            yield from macho_slices(data[offset:offset + size])
        return
    magic, cputype, _, _, ncmds, _, _, _ = struct.unpack("<IiiIIIII", data[:32])
    if magic != 0xFEEDFACF:
        yield ("not Mach-O 64", False)
        return
    pos, signed = 32, False
    for _ in range(ncmds):
        cmd, size = struct.unpack("<II", data[pos:pos + 8])
        if cmd == LC_CODE_SIGNATURE:
            signed = True
        pos += size
    yield (CPU.get(cputype & 0xFFFFFFFF, hex(cputype)), signed)


def elf(data):
    if data[:4] != b"\x7fELF":
        return None
    machine = struct.unpack("<H", data[18:20])[0]
    return {0x3E: "x86-64", 0xB7: "AArch64"}.get(machine, hex(machine)), "64-bit" if data[4] == 2 else "32-bit"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("archive")
    ap.add_argument("--modules-from", help="Windows zip to compare the runtime module list with")
    args = ap.parse_args()
    name = args.archive.replace("\\", "/").split("/")[-1]
    target = name.removesuffix(".tar.gz").split("-", 2)[-1]
    os_, arch = target.split("-")
    problems, notes = [], []
    with tarfile.open(args.archive, "r:gz") as tar:
        members = {m.name.rstrip("/"): m for m in tar.getmembers()}
        tops = {n.split("/")[0] for n in members}
        if len(tops) != 1:
            problems.append(f"expected one top folder, found {sorted(tops)}")
        prefix = next(iter(tops))
        for n, m in members.items():
            if n.startswith("/") or ".." in n.split("/"):
                problems.append(f"unsafe path {n}")
            if m.ischr() or m.isblk() or m.isfifo():
                problems.append(f"device file {n}")
            if m.issym():
                target_path = "/".join(n.split("/")[:-1] + [m.linkname])
                parts = []
                for p in target_path.split("/"):
                    parts = parts[:-1] if p == ".." else parts + ([p] if p not in ("", ".") else [])
                resolved = members.get("/".join(parts))
                if resolved is None or not resolved.isfile():
                    problems.append(f"dangling symlink {n} -> {m.linkname}")
        symlinks = sum(1 for m in members.values() if m.issym())
        notes.append(f"{len(members)} entries, {symlinks} symlinks")
        must_exec = ["bin/jtrac", "setup.sh", "start.sh", "stop.sh", "status.sh", "runtime/bin/java", "runtime/lib/jspawnhelper"]
        has_pg = f"{prefix}/pgsql/bin/postgres" in members
        if has_pg:
            must_exec += ["pgsql/bin/postgres", "pgsql/bin/initdb", "pgsql/bin/pg_ctl"]
        for rel in must_exec:
            m = members.get(f"{prefix}/{rel}")
            if m is None:
                problems.append(f"missing {rel}")
            elif m.mode & 0o111 != 0o111:
                problems.append(f"{rel} not executable ({oct(m.mode)})")
        # Scripts
        sh = shutil.which("sh")
        work = tempfile.mkdtemp()
        scripts = [n for n in members if n.endswith(".sh") or n.endswith("/bin/jtrac")]
        for n in scripts:
            text = tar.extractfile(members[n]).read()
            if not text.startswith(b"#!/bin/sh"):
                problems.append(f"{n} lacks #!/bin/sh")
            if b"\r" in text:
                problems.append(f"{n} has CR line endings")
            if sh:
                path = f"{work}/check.sh"
                open(path, "wb").write(text)
                r = subprocess.run([sh, "-n", path], capture_output=True, text=True)
                if r.returncode != 0:
                    problems.append(f"{n}: sh -n failed: {r.stderr.strip()}")
        notes.append(f"{len(scripts)} shell scripts checked" + (" with sh -n" if sh else " (no sh available for syntax check)"))
        # Runtime release file
        release = tar.extractfile(members[f"{prefix}/runtime/release"]).read().decode()
        props = dict(l.split("=", 1) for l in release.splitlines() if "=" in l)
        os_name, os_arch = props.get("OS_NAME", "").strip('"'), props.get("OS_ARCH", "").strip('"')
        notes.append(f"runtime/release: JAVA_VERSION={props.get('JAVA_VERSION', '').strip(chr(34))}"
                     + (f" OS_NAME={os_name} OS_ARCH={os_arch}" if os_name or os_arch else " (no OS fields; architecture checked from binaries)"))
        want_os = {"linux": "Linux", "macos": "Darwin"}[os_]
        want_arch = {"x64": ("x86_64", "amd64"), "arm64": ("aarch64", "arm64")}[arch]
        # jlink records only JAVA_VERSION and MODULES; when OS fields are present, they must match.
        if (os_name or os_arch) and (os_name != want_os or os_arch not in want_arch):
            problems.append(f"runtime is {os_name}/{os_arch}, expected {want_os}/{want_arch[0]}")
        windows_files = [n for n in members if n.lower().endswith((".exe", ".dll", ".bat", ".cmd"))]
        if windows_files:
            problems.append(f"Windows files in a {os_} package: {windows_files[:5]}")
        modules = sorted(props.get("MODULES", "").strip('"').split())
        if args.modules_from:
            with zipfile.ZipFile(args.modules_from) as z:
                rel = next(n for n in z.namelist() if n.endswith("runtime/release"))
                wprops = dict(l.split("=", 1) for l in z.read(rel).decode().splitlines() if "=" in l)
                wmodules = sorted(wprops.get("MODULES", "").strip('"').split())
                if modules != wmodules:
                    problems.append(f"runtime modules differ from the Windows package: {set(modules) ^ set(wmodules)}")
                else:
                    notes.append(f"runtime modules identical to the tested Windows package ({len(modules)})")
        # Binaries
        lib = "runtime/lib/server/libjvm." + ("dylib" if os_ == "macos" else "so")
        for rel in ["runtime/bin/java", lib, "runtime/lib/jspawnhelper"] + (["pgsql/bin/postgres", "pgsql/bin/initdb", "pgsql/bin/pg_ctl"] if has_pg else []):
            data = tar.extractfile(members[f"{prefix}/{rel}"]).read()
            if os_ == "linux":
                kind = elf(data)
                want = {"x64": "x86-64", "arm64": "AArch64"}[arch]
                if kind is None or kind[0] != want or kind[1] != "64-bit":
                    problems.append(f"{rel} is {kind}, expected ELF 64-bit {want}")
                notes.append(f"{rel}: ELF {kind[1]} {kind[0]}" if kind else f"{rel}: not ELF")
            else:
                slices = list(macho_slices(data))
                want = {"x64": "x86_64", "arm64": "arm64"}[arch]
                if want not in [c for c, _ in slices]:
                    problems.append(f"{rel} has no {want} slice: {slices}")
                unsigned = [c for c, s in slices if not s]
                if unsigned:
                    problems.append(f"{rel} has unsigned slice(s): {unsigned}")
                notes.append(f"{rel}: Mach-O {', '.join(f'{c}{' signed' if s else ' UNSIGNED'}' for c, s in slices)}")
        shutil.rmtree(work, ignore_errors=True)
    print(f"== {name}")
    for n in notes:
        print(f"   {n}")
    for p in problems:
        print(f"   FAIL {p}")
    print("   PASSED" if not problems else f"   FAILED ({len(problems)})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
