/**
 * Issue attachments on local (or mounted) storage. Uploads are streamed to disk while a
 * SHA-256 is computed; files are served only after an authorization check, as downloads
 * (images whose magic bytes match an allow-listed type may be shown inline), with
 * {@code nosniff} and a sandboxing CSP. Orphaned files are swept periodically.
 */
@ApplicationModule(displayName = "Attachments")
package com.jtac.attachment;

import org.springframework.modulith.ApplicationModule;
