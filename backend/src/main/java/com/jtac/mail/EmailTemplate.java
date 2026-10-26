package com.jtac.mail;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds a plain-text and a matching HTML email in the JTRAC style. All user-supplied text
 * is HTML-escaped; links are only ever produced by {@link Mailer#link}.
 */
public final class EmailTemplate {

    private final String subject;
    private final List<String> paragraphs = new ArrayList<>();
    private String actionLabel;
    private String actionUrl;
    private String footnote;

    private EmailTemplate(String subject) {
        this.subject = subject;
    }

    public static EmailTemplate subject(String subject) {
        return new EmailTemplate(subject);
    }

    public EmailTemplate paragraph(String text) {
        paragraphs.add(text);
        return this;
    }

    public EmailTemplate action(String label, String url) {
        this.actionLabel = label;
        this.actionUrl = url;
        return this;
    }

    public EmailTemplate footnote(String text) {
        this.footnote = text;
        return this;
    }

    public EmailMessage to(String address) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><body style=\"margin:0;padding:24px;background:#f5f3ef;")
                .append("font-family:'IBM Plex Sans',Segoe UI,Arial,sans-serif;color:#1d1a16\">")
                .append("<div style=\"max-width:520px;margin:0 auto;background:#ffffff;border:1px solid #e3ded6;")
                .append("border-top:3px solid #b4532a;border-radius:12px;padding:28px\">")
                .append("<p style=\"margin:0 0 16px;font-weight:600;letter-spacing:.08em\">JTRAC</p>")
                .append("<h1 style=\"font-size:20px;margin:0 0 16px\">").append(escape(subject)).append("</h1>");
        for (String paragraph : paragraphs) {
            text.append(paragraph).append("\n\n");
            html.append("<p style=\"margin:0 0 14px;line-height:1.5\">").append(escape(paragraph)).append("</p>");
        }
        if (actionUrl != null) {
            text.append(actionLabel).append(": ").append(actionUrl).append("\n\n");
            html.append("<p style=\"margin:20px 0\"><a href=\"").append(escape(actionUrl))
                    .append("\" style=\"background:#b4532a;color:#fff8f2;padding:10px 16px;border-radius:8px;")
                    .append("text-decoration:none;font-weight:500\">").append(escape(actionLabel)).append("</a></p>")
                    .append("<p style=\"font-size:12px;color:#5a5249;word-break:break-all\">")
                    .append(escape(actionUrl)).append("</p>");
        }
        if (footnote != null) {
            text.append(footnote).append("\n");
            html.append("<p style=\"font-size:12px;color:#6c645a;margin-top:20px\">").append(escape(footnote)).append("</p>");
        }
        html.append("</div></body></html>");
        return new EmailMessage(address, subject, text.toString().strip() + "\n", html.toString());
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
