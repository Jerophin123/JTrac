package com.jtac.mail;

/** A message to deliver. {@code html} is optional and must already be escaped (see {@link EmailTemplate}). */
public record EmailMessage(String to, String subject, String text, String html) {
}
