# Outgoing email (SMTP)

JTRAC sends invitations, password-reset links, email confirmations, notifications and
erasure-request acknowledgements through an SMTP server you choose. Any standards-compliant
provider works: Zoho Mail, Microsoft 365, Google Workspace, Amazon SES, your organization's
relay, and so on. JTRAC assumes no provider: enter the host, port and credentials **your**
provider documents for your account and region.

## Where to configure it

| When | How |
|---|---|
| During installation | setup stage 7 |
| Later, on the server | `jtrac configure smtp`, then `jtrac test-email --to you@example.com` |
| Later, in the browser | **Server settings > Outgoing email** (server administrators only); applied without a restart |

All three use the same validation (`ConfigValidator.validateSmtp`) and the same SMTP client
setup (`SmtpSessions`), and write the same files.

## Settings

| Setting | Notes |
|---|---|
| Send email | Off = **email pending**: messages are queued, not sent, and delivered once email is turned on. |
| SMTP server | Host name from your provider (e.g. the SMTP host shown in your mail admin console). Zoho, for example, uses different hosts per data centre: use the one for your account. |
| Encryption | **STARTTLS** (usually port 587, recommended), **Implicit TLS** (usually 465), or **None** (only for a trusted relay on a private network). STARTTLS is *required* when chosen: if the server doesn't offer it, sending fails instead of continuing unencrypted. |
| Port | 587 / 465 / 25. Port 25 is for relays and is often blocked by ISPs and cloud providers. |
| Sign in | On for providers. Off only for a relay that accepts mail from this server's address (a warning is shown). Signing in over an unencrypted connection is refused. |
| User name / password | Many providers require an **app-specific password** when multi-factor authentication is on, or SMTP access to be enabled for the mailbox. The password is stored in the owner-only `secrets.properties` and never returned by the API or logged. |
| From address | Providers usually only accept the signed-in mailbox or a verified alias. |
| From name, reply-to | Optional. |
| Timeout | Connect/read/write timeout (default 15 s). |
| Delivery attempts | Failed sends are retried with exponential backoff (1, 2, 4 ... minutes, capped at 6 h) up to this many attempts (default 8), then marked failed. |
| Extra trusted CA certificates | Optional PEM file for a relay whose certificate comes from a private CA. Certificates are **always** verified and the host name must match; verification is never switched off. |

## The test email

The test (setup, `jtrac test-email`, or **Send test email** in Server settings) reports each step:

1. **Check settings**: the validation rules above.
2. **Resolve host**: DNS lookup ("not found" = spelling, or no DNS access from the server).
3. **Connect**: TCP connection ("refused" = wrong port or firewall; "no answer" = filtered port).
   A connection alone is never reported as success.
4. **SMTP greeting**, **Start TLS** / **TLS**: TLS negotiation and certificate validation
   (untrusted certificate, wrong host name, server without STARTTLS, implicit TLS against a
   STARTTLS port).
5. **Sign in**: rejected credentials (often: app password needed, SMTP access disabled).
6. **Send test message**: the server accepted the message for delivery, or rejected the sender
   or recipient (relay denied, sender not owned by the mailbox, sending limits).

"Accepted" means the SMTP server took the message; check the recipient's inbox (and spam
folder) to confirm delivery. Diagnostics never include the password. Test emails are limited
to 10 per hour per administrator.

## Storage, precedence and environment variables

Settings live in `<data>/config/jtrac.properties` (keys below) and the password in
`<data>/config/secrets.properties`. Spring Boot environment variables override the files:

| Key | Environment variable that overrides it |
|---|---|
| `jtac.mail.transport` (`smtp` / `disabled`) | `JTAC_MAIL_TRANSPORT` |
| `jtac.mail.smtp.host`, `.port`, `.auth`, `.username`, `.tls`, `.timeout-seconds`, `.trusted-certificates` | `JTAC_MAIL_SMTP_HOST`, `JTAC_MAIL_SMTP_PORT`, ... |
| `jtac.mail.smtp.password` (secrets file) | `JTAC_MAIL_SMTP_PASSWORD` |
| `jtac.mail.from`, `jtac.mail.from-name`, `jtac.mail.reply-to`, `jtac.mail.max-attempts` | `JTAC_MAIL_FROM`, ... |

If any of these variables is set, Server settings lists it as "fixed by the server
environment" and saved changes won't take effect until it's removed. The short
`JTRAC_SMTP_*` variables from development (`JTRAC_SMTP_HOST`, `JTRAC_SMTP_PORT`,
`JTRAC_SMTP_USER`, `JTRAC_SMTP_PASSWORD`, `JTRAC_SMTP_AUTH`, `JTRAC_SMTP_TLS`,
`JTRAC_MAIL_FROM`) only provide defaults when the configuration files don't set the key.

## Queue

Email is written to an outbox in the same transaction as the action that caused it and
delivered in the background, so an SMTP outage never loses mail or blocks users. Server
settings shows waiting, sent (24 h) and failed counts and the last delivery error (secrets
removed).
