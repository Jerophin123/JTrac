#!/usr/bin/env python3
"""Installer verification for a built JTRAC release archive, on the machine it targets.

It unpacks the FINAL archive (never the workspace) into a temporary folder and checks, with a
data directory and ports of its own and a controlled SMTP server (nothing is sent anywhere):

  1  the package needs no JDK, Node.js or Gradle (PATH stripped, JAVA_HOME removed)
  2  unattended setup provisions a new, empty managed database and starts a healthy server
  3  the configured HTTP port is used; secrets are stored owner-only
  4  SMTP settings are validated and a test email is accepted by the controlled server
  5  sign-in as the administrator, terms acceptance, organization, project, issue, comment
  6  the test email from Server settings works; the SMTP password is never returned
  7  status, stop, start, restart, logs and test-email
  8  backup, verify-backup, restore (data created after the backup is gone afterwards)
  9  update from a second copy of the package keeps settings, secrets and data
 10  uninstall keeps the data; uninstall --delete-data removes it only with confirmation

Usage:
  python -I tools/installer-smoke.py --archive distribution/releases/3.0.0/jtrac-3.0.0-<target>.zip
         --smtp-fixtures serverconfig/build/libs/serverconfig-3.0.0-test-fixtures.jar
         [--http-port 18080] [--db-port 55432] [--smtp-port 2525] [--keep]
Writes a Markdown report next to the archive's verification folder and exits non-zero on failure.
"""
import argparse
import http.cookiejar
import json
import os
import pathlib
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ADMIN_EMAIL = "admin@jtrac.test"
ADMIN_PASSWORD = "Installer-Smoke-Pass-2026"
SMTP_USER, SMTP_PASSWORD = "jtrac@example.test", "correct-horse"
results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition), detail))
    print(f"  [{' ok ' if condition else 'FAIL'}] {name}{(': ' + detail) if detail else ''}", flush=True)
    if not condition:
        raise SystemExit(f"FAILED: {name} {detail}")


def windows():
    return platform.system() == "Windows"


def clean_env(extra):
    env = {k: v for k, v in os.environ.items() if k.upper() not in ("JAVA_HOME", "JDK_HOME", "PATH")}
    env["PATH"] = os.pathsep.join([r"C:\Windows\System32", r"C:\Windows", r"C:\Windows\System32\WindowsPowerShell\v1.0"]
                                  if windows() else ["/usr/bin", "/bin", "/usr/sbin", "/sbin"])
    env.update(extra)
    return env


def jtrac(home, *args, env_extra=None, timeout=900):
    script = home / "bin" / ("jtrac.cmd" if windows() else "jtrac")
    cmd = [str(script), *args]
    proc = subprocess.run(cmd, env=clean_env(env_extra or {}), capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout, stdin=subprocess.DEVNULL)
    return proc.returncode, proc.stdout + proc.stderr


def extract(archive, target):
    target.mkdir(parents=True)
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(target)
    else:
        with tarfile.open(archive) as t:
            t.extractall(target, filter="tar")
    homes = [p for p in target.iterdir() if p.is_dir()]
    return homes[0]


class Api:
    def __init__(self, base):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def status(self, method, path):
        req = urllib.request.Request(self.base + path, method=method)
        try:
            with self.opener.open(req, timeout=60) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def call(self, method, path, body=None, expect=(200,)):
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), method=method)
        req.add_header("Content-Type", "application/json")
        token = next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), "")
        if token:
            req.add_header("X-XSRF-TOKEN", token)
        try:
            with self.opener.open(req, timeout=60) as r:
                status, text = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode()
        if status not in expect:
            raise SystemExit(f"FAILED: {method} {path} -> {status} {text[:300]}")
        return json.loads(text) if text.strip().startswith(("{", "[")) else None

    def sign_in(self):
        self.call("GET", "/api/v1/auth/csrf", expect=(200, 204))
        session = self.call("POST", "/api/v1/auth/login", {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
        self.call("GET", "/api/v1/auth/csrf", expect=(200, 204))
        return session

    def issue_exists(self, key):
        status = self.status("GET", f"/api/v1/orgs/smoke-org/issues/{key}")
        if status not in (200, 404):
            raise SystemExit(f"FAILED: unexpected status {status} for issue {key}")
        return status == 200


def owner_only(path):
    if windows():
        out = subprocess.run(["icacls", str(path)], capture_output=True, text=True, errors="replace").stdout
        principals = [l.strip() for l in out.splitlines()[0:] if ":(" in l]
        return len(principals) == 1, out.strip().splitlines()[0] if out else ""
    mode = path.stat().st_mode & 0o777
    return mode & 0o077 == 0, oct(mode)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--archive", required=True)
    ap.add_argument("--smtp-fixtures", required=True)
    ap.add_argument("--http-port", type=int, default=18080)
    ap.add_argument("--db-port", type=int, default=55432)
    ap.add_argument("--smtp-port", type=int, default=2525)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    archive = pathlib.Path(args.archive).resolve()
    work = pathlib.Path(tempfile.mkdtemp(prefix="jtrac-installer-smoke-"))
    data = work / "data"
    print(f"Installer smoke test of {archive.name} in {work}")
    smtp = None
    home = None
    try:
        home = extract(archive, work / "install")
        check("archive unpacks", (home / "bin").is_dir(), str(home))
        java = home / "runtime" / "bin" / ("java.exe" if windows() else "java")
        check("bundled Java runtime present", java.exists())
        bundled_pg = (home / "pgsql" / "bin" / ("pg_ctl.exe" if windows() else "pg_ctl")).exists()

        # Controlled SMTP server on the package's own runtime.
        smtp_log = work / "smtp.log"
        smtp = subprocess.Popen([str(java), f"-Djtrac.testsmtp.inbox={work / 'inbox'}", "-cp", args.smtp_fixtures,
                                 "com.jtrac.serverconfig.testing.TestSmtpServer", str(args.smtp_port), "STARTTLS"],
                                stdout=open(smtp_log, "w"), stderr=subprocess.STDOUT)
        cert = None
        for _ in range(60):
            text = smtp_log.read_text(errors="replace") if smtp_log.exists() else ""
            if "certificate=" in text:
                cert = text.split("certificate=")[1].strip().splitlines()[0]
                break
            time.sleep(1)
        check("controlled SMTP server started", cert is not None)

        if not bundled_pg:
            check("bundled PostgreSQL", False, "this target has no bundled PostgreSQL: run the external-database procedure in docs/testing.md")
        answers = work / "answers.properties"
        answers.write_text("\n".join([
            "database.mode=MANAGED", f"database.port={args.db_port}", f"data.dir={data}",
            "admin.name=Installer Smoke", f"admin.email={ADMIN_EMAIL}",
            "http.bind=127.0.0.1", f"http.port={args.http_port}", f"http.public-url=http://localhost:{args.http_port}",
            "http.behind-proxy=n", "smtp.enabled=y", "smtp.host=localhost", "smtp.tls=STARTTLS", f"smtp.port={args.smtp_port}",
            "smtp.auth=y", f"smtp.username={SMTP_USER}", f"smtp.from={SMTP_USER}", "smtp.from-name=JTRAC Smoke", "smtp.reply-to=",
            "smtp.timeout=10", "smtp.private-ca=y", f"smtp.trusted-certificates={cert}", "smtp.test=y",
            "smtp.test.recipient=ops@example.test", "security.registration=INVITE_ONLY", "security.session-hours=8",
            "legal.configure=n", "confirm=y", "start=y", ""]), encoding="utf-8")
        env = {"JTRAC_ADMIN_PASSWORD": ADMIN_PASSWORD, "JTRAC_SMTP_PASSWORD": SMTP_PASSWORD}
        code, out = jtrac(home, "setup", "--answers", str(answers), env_extra=env)
        (work / "setup.log").write_text(out, encoding="utf-8")
        check("unattended setup without JDK/Node/Gradle on PATH", code == 0, out.strip().splitlines()[-1] if out.strip() else "")
        check("setup sent a test email (SMTP accepted)", "accepted the test email" in out)
        check("fresh database: setup saw no existing accounts", "already contains" not in out)
        config = data / "config"
        for f in ("secrets.properties", "admin-secrets.properties"):
            ok, detail = owner_only(config / f)
            check(f"{f} is owner-only", ok, detail)
        settings = (config / "jtrac.properties").read_text(encoding="iso-8859-1")
        check("settings file has no secrets", "password=" not in settings.replace("jtac.mail.smtp.password", ""))
        check("configured HTTP port in use", f"server.port={args.http_port}" in settings)

        api = Api(f"http://127.0.0.1:{args.http_port}")
        html = api.opener.open(api.base + "/", timeout=30).read().decode()
        check("web UI served", 'id="root"' in html)
        session = api.sign_in()
        check("administrator signs in and is a server administrator", session["user"].get("serverAdmin") is True)
        if session["consentRequired"]:
            api.call("POST", "/api/v1/me/consent", expect=(204,))
        check("terms accepted", api.call("GET", "/api/v1/auth/session")["consentRequired"] is False)
        api.call("POST", "/api/v1/orgs", {"name": "Smoke Org", "slug": "smoke-org"}, expect=(201,))
        api.call("POST", "/api/v1/orgs/smoke-org/projects", {"key": "SMK", "name": "Smoke", "template": "KANBAN",
                                                             "visibility": "ORGANIZATION"}, expect=(201,))
        key = api.call("POST", "/api/v1/orgs/smoke-org/projects/SMK/issues", {"title": "Installer smoke issue"}, expect=(201,))["key"]
        api.call("POST", f"/api/v1/orgs/smoke-org/issues/{key}/comments", {"body": "smoke comment"}, expect=(201,))
        check("organization, project, issue and comment created", api.issue_exists(key), key)
        mail = api.call("GET", "/api/v1/system/mail")
        check("SMTP password never returned", SMTP_PASSWORD not in json.dumps(mail) and mail["passwordSet"])
        result = api.call("POST", "/api/v1/system/mail/test", {"settings": mail["settings"], "recipient": "ops@example.test"})
        check("test email from Server settings accepted", result["accepted"], result["summary"])

        for command, expected in (("status", 0), ("stop", 0), ("status", 3), ("start", 0), ("restart", 0), ("logs", 0), ("test-email", 0)):
            extra = ["--to", "ops@example.test"] if command == "test-email" else (["--lines", "5"] if command == "logs" else [])
            code, out = jtrac(home, command, *extra)
            check(f"jtrac {command} exits {expected}", code == expected, f"exit {code}")
        log_text = (data / "logs" / "jtrac.log").read_text(errors="replace")
        check("stop was graceful", "Stop requested by the jtrac command" in log_text)
        check("SMTP password not in logs", SMTP_PASSWORD not in log_text and ADMIN_PASSWORD not in log_text)

        code, out = jtrac(home, "backup")
        check("backup verified", code == 0 and "Backup verified" in out)
        backup = next(l for l in out.splitlines() if "jtrac-backup-" in l and l.strip().endswith(".zip")).split("] ")[-1].strip()
        code, out = jtrac(home, "verify-backup", backup)
        check("verify-backup", code == 0)
        api = Api(f"http://127.0.0.1:{args.http_port}")
        api.sign_in()
        later = api.call("POST", "/api/v1/orgs/smoke-org/projects/SMK/issues", {"title": "After backup"}, expect=(201,))["key"]
        code, out = jtrac(home, "restore", backup, "--yes")
        check("restore", code == 0, out.strip().splitlines()[-1] if out.strip() else "")
        code, out = jtrac(home, "start")
        api = Api(f"http://127.0.0.1:{args.http_port}")
        api.sign_in()
        check("restored data: original issue present", api.issue_exists(key))
        check("restored data: post-backup issue gone", not api.issue_exists(later))

        before = {f: (config / f).read_bytes() for f in ("jtrac.properties", "secrets.properties", "admin-secrets.properties")}
        home2 = extract(archive, work / "install2")
        code, out = jtrac(home2, "update", "--from", str(home))
        check("update from a second copy", code == 0, out.strip().splitlines()[-1] if out.strip() else "")
        check("update kept settings and secrets", all((config / f).read_bytes() == b for f, b in before.items()))
        api = Api(f"http://127.0.0.1:{args.http_port}")
        api.sign_in()
        check("update kept data", api.issue_exists(key))

        code, out = jtrac(home2, "uninstall")
        check("uninstall keeps data", code == 0 and (data / "pgsql" / "data" / "PG_VERSION").exists() and (config / "jtrac.properties").exists())
        confirm = work / "confirm.properties"
        confirm.write_text("confirm-delete=wrong path\n", encoding="utf-8")
        code, out = jtrac(home2, "uninstall", "--delete-data", "--data-dir", str(data), "--answers", str(confirm))
        check("delete-data refuses without exact confirmation", code != 0 and data.exists())
        confirm.write_text(f"confirm-delete={data}\n", encoding="utf-8")
        code, out = jtrac(home2, "uninstall", "--delete-data", "--data-dir", str(data), "--answers", str(confirm))
        check("delete-data removes data only after typing the path", code == 0 and not data.exists())
    finally:
        if smtp:
            smtp.terminate()
        report = archive.parent / f"{archive.name}.installer-smoke.md"
        passed = all(r[1] for r in results) and results
        report.write_text("\n".join([f"# Installer smoke test: {archive.name}", "",
                                     f"Machine: {platform.system()} {platform.machine()} ({platform.platform()})",
                                     f"Result: {'PASSED' if passed else 'FAILED'} ({sum(r[1] for r in results)}/{len(results)} checks)", "",
                                     "| Check | Result | Detail |", "|---|---|---|"]
                                    + [f"| {n} | {'pass' if ok else 'FAIL'} | {d.replace('|', '/')} |" for n, ok, d in results]) + "\n",
                          encoding="utf-8")
        print(f"Report: {report}")
        if not args.keep:
            if home and data.exists():
                jtrac(home, "stop")
            shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
