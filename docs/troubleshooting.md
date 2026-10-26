# Troubleshooting

Start with `jtrac status` (exit code 0 = healthy) and `jtrac logs --lines 200`. Log files are
in `<data>/logs`: `jtrac.log` (server), `jtrac-console.log` (startup output),
`postgresql.log` (managed database).

| Symptom | Likely cause | What to do |
|---|---|---|
| `start` says the port is in use | Another program uses the HTTP port | Stop it, or `jtrac configure http` and pick another port. JTRAC never stops other programs. |
| "The managed PostgreSQL server did not start" | Database port taken, or a stale lock | Check `postgresql.log`. Another PostgreSQL on the same port: `jtrac configure` can't change the managed port, so stop the other server or re-run setup with a different `database.port` in a new data directory. |
| JTRAC doesn't become healthy | Schema mismatch, wrong DB password, missing settings | `jtrac-console.log` shows the reason. "Flyway validate failed": run `jtrac migrate` with the matching release. |
| Browser shows "Something went wrong" right after an update | Old tab with stale assets | Reload with Ctrl+Shift+R. |
| Sign-in works on `localhost` but not through the proxy | Secure cookies without HTTPS, or proxy headers not trusted | Public address must be the `https://` URL and "behind a reverse proxy" = yes (`jtrac configure http`). |
| Test email: "host name was not found" | DNS spelling or no DNS from the server | Check the host your provider lists; try `nslookup <host>` on the server. |
| Test email: "Connection refused" / "No answer" | Wrong port, or a firewall blocks outgoing SMTP | Try 587 (STARTTLS) or 465 (implicit TLS); port 25 is often blocked. |
| Test email: "doesn't offer STARTTLS" | Port expects implicit TLS | Choose Implicit TLS on port 465. |
| Test email: "certificate is not trusted" | Private CA or a TLS-intercepting proxy | Use the provider's official host name; for an internal relay, add your CA as extra trusted certificates. Verification is never disabled. |
| Test email: "rejected the user name or password" | App password required, SMTP access disabled | Create an app-specific password or enable SMTP for the mailbox in your provider's console. |
| Test email: "refused the message (553)" | Sender not allowed for this mailbox | Use the mailbox address or a verified alias as From address. |
| Emails never arrive but tests pass | Spam filtering, missing SPF/DKIM for your domain | Check spam folders; configure SPF/DKIM/DMARC with your mail provider. |
| "Email isn't set up" banner | Email-pending mode | Configure SMTP in Server settings or `jtrac configure smtp`; queued mail is then sent. |
| Restore fails to start the database | Incomplete or foreign backup | JTRAC puts the previous database back automatically; run `jtrac verify-backup` on the file. |
| macOS: "cannot be opened because the developer cannot be verified" | Package not notarized | `xattr -dr com.apple.quarantine <package folder>` |
| Windows: SmartScreen warning | Package not signed | Verify the SHA-256, then "More info > Run anyway". |
| Setup in a non-interactive shell shows passwords | No console available | Use `setup --answers` with passwords in environment variables. |

Diagnostics and logs never contain passwords; if you share logs, still review them for
personal data (email addresses) first.
