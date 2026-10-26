package com.jtrac.serverconfig;

/** How the SMTP connection is encrypted. Certificates are always verified when TLS is used. */
public enum TlsMode {
    /** Plain connection upgraded with STARTTLS (usually port 587); the upgrade is required. */
    STARTTLS,
    /** TLS from the first byte (usually port 465). */
    IMPLICIT,
    /** No encryption: only for a trusted relay on a private network. */
    NONE
}
