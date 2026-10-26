package com.jtrac.serverconfig;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

/**
 * Sends a real test email and explains, step by step, where it failed: invalid settings, DNS,
 * TCP connection, TLS negotiation and certificate validation, authentication, sender or
 * recipient rejection. Diagnostics never contain the password.
 */
public final class SmtpTester {

    private SmtpTester() {
    }

    public static SmtpTestResult test(SmtpSettings smtp, String password, String recipient) {
        List<SmtpTestResult.Step> steps = new ArrayList<>();
        Diagnostics d = new Diagnostics(steps, password);

        // 1. Settings
        SmtpSettings settings = smtp.enabled() ? smtp : new SmtpSettings(true, smtp.host(), smtp.port(), smtp.auth(),
                smtp.username(), smtp.tls(), smtp.fromAddress(), smtp.fromName(), smtp.replyTo(), smtp.timeoutSeconds(),
                smtp.maxAttempts(), smtp.trustedCertificates());
        List<Problem> problems = new ArrayList<>(ConfigValidator.validateSmtp(settings, password));
        problems.addAll(ConfigValidator.email("recipient", recipient, true));
        List<Problem> errors = problems.stream().filter(Problem::isError).toList();
        if (!errors.isEmpty()) {
            d.failed("Check settings", String.join(" ", errors.stream().map(p -> p.field() + ": " + p.message()).toList()));
            return d.result(false, "The settings are incomplete or invalid; nothing was sent.");
        }
        problems.stream().filter(p -> !p.isError() && !p.field().equals("smtp.enabled"))
                .forEach(p -> d.warning("Check settings", p.message()));
        d.ok("Check settings", "Host, port, TLS mode, sign-in and addresses are valid.");

        // 2. DNS
        InetAddress[] addresses;
        try {
            addresses = resolve(settings.host(), settings.timeoutSeconds());
            d.ok("Resolve " + settings.host(), "Found " + addresses[0].getHostAddress()
                    + (addresses.length > 1 ? " and " + (addresses.length - 1) + " more" : "") + ".");
        } catch (UnknownHostException e) {
            d.failed("Resolve " + settings.host(), "The host name was not found in DNS. Check the spelling, or that this "
                    + "server can reach a DNS resolver.");
            return d.result(false, "DNS lookup failed; nothing was sent.");
        } catch (TimeoutException e) {
            d.failed("Resolve " + settings.host(), "DNS lookup timed out after " + settings.timeoutSeconds() + " s.");
            return d.result(false, "DNS lookup timed out; nothing was sent.");
        }

        // 3. TCP
        String connectProblem = connect(addresses, settings.port(), settings.timeoutSeconds());
        if (connectProblem != null) {
            d.failed("Connect to port " + settings.port(), connectProblem);
            return d.result(false, "Could not connect to the SMTP server; nothing was sent.");
        }
        d.ok("Connect to port " + settings.port(), "TCP connection opened. (This alone doesn't prove email can be sent.)");

        // 4. SMTP greeting, TLS and sign-in
        SmtpSessions.SmtpSession session;
        try {
            session = SmtpSessions.create(settings, password);
        } catch (IllegalArgumentException e) {
            d.failed("Prepare TLS", e.getMessage());
            return d.result(false, "The trusted certificates file is unusable; nothing was sent.");
        }
        try (Transport transport = session.session().getTransport(session.protocol())) {
            try {
                if (settings.auth()) {
                    transport.connect(settings.host(), settings.port(), settings.username(), password);
                } else {
                    transport.connect();
                }
            } catch (AuthenticationFailedException e) {
                d.ok("SMTP greeting", "The server answered.");
                d.tlsOk(settings.tls());
                d.failed("Sign in as " + settings.username(), "The server rejected the user name or password"
                        + d.serverSaid(e.getMessage()) + " Some providers need an app-specific password, or SMTP access "
                        + "enabled in the mailbox settings.");
                return d.result(false, "Authentication failed; nothing was sent.");
            } catch (MessagingException e) {
                explainConnectFailure(d, settings, e);
                return d.result(false, "The SMTP conversation failed before sending; nothing was sent.");
            }
            d.ok("SMTP greeting", "The server answered and the SMTP session started.");
            d.tlsOk(settings.tls());
            if (settings.auth()) {
                d.ok("Sign in as " + settings.username(), "Accepted.");
            } else {
                d.warning("Sign in", "Skipped: authentication is disabled (trusted relay).");
            }

            // 5. Send
            MimeMessage message = testMessage(session, settings, recipient);
            try {
                transport.sendMessage(message, message.getAllRecipients());
            } catch (SMTPSendFailedException e) {
                d.failed("Send test message", "The server refused the message (" + e.getReturnCode() + ")" + d.serverSaid(e.getMessage())
                        + " " + rejectionHint(e.getReturnCode(), e.getMessage()));
                return d.result(false, "The server refused the test message.");
            } catch (SendFailedException e) {
                d.failed("Send test message", "The server rejected " + describe(e) + d.serverSaid(rootMessage(e)));
                return d.result(false, "The server refused the sender or the recipient.");
            } catch (MessagingException e) {
                d.failed("Send test message", "Sending failed: " + d.clean(rootMessage(e)));
                return d.result(false, "Sending failed.");
            }
            d.ok("Send test message", "Accepted for delivery by the server" + d.serverSaid(lastResponse(transport))
                    + " Check the inbox (and spam folder) of " + recipient + " to confirm it arrived.");
            return d.result(true, "The SMTP server accepted the test email for " + recipient + ".");
        } catch (MessagingException e) {
            d.failed("Close connection", d.clean(rootMessage(e)));
            return d.result(false, "The SMTP connection failed.");
        }
    }

    private static InetAddress[] resolve(String host, int timeoutSeconds) throws UnknownHostException, TimeoutException {
        CompletableFuture<InetAddress[]> lookup = CompletableFuture.supplyAsync(() -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            return lookup.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            throw new UnknownHostException(host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException();
        }
    }

    private static String connect(InetAddress[] addresses, int port, int timeoutSeconds) {
        String problem = null;
        for (InetAddress address : addresses) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), timeoutSeconds * 1000);
                return null;
            } catch (ConnectException e) {
                problem = "Connection refused by " + address.getHostAddress() + ": nothing is listening on port " + port
                        + ", or a firewall rejects it. Check the port number.";
            } catch (SocketTimeoutException e) {
                problem = "No answer from " + address.getHostAddress() + " within " + timeoutSeconds + " s. A firewall may be "
                        + "blocking outgoing port " + port + " (port 25 is often blocked by ISPs and cloud providers).";
            } catch (NoRouteToHostException e) {
                problem = "No network route to " + address.getHostAddress() + ".";
            } catch (IOException e) {
                problem = "Connection to " + address.getHostAddress() + " failed: " + e.getMessage();
            }
        }
        return problem;
    }

    private static void explainConnectFailure(Diagnostics d, SmtpSettings settings, MessagingException e) {
        Throwable root = root(e);
        String message = String.valueOf(e.getMessage());
        if (hasCause(e, CertPathBuilderException.class) || hasCause(e, CertPathValidatorException.class)) {
            d.ok("SMTP greeting", "The server answered.");
            d.failed("Verify certificate", "The server's TLS certificate is not trusted (self-signed or issued by a private CA). "
                    + "Use the provider's official host name, or add your CA certificate as trusted certificates. "
                    + "Certificate checks are never switched off.");
        } else if (root instanceof CertificateException && String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT).contains("name")) {
            d.ok("SMTP greeting", "The server answered.");
            d.failed("Verify certificate", "The certificate doesn't match the host name " + settings.host()
                    + ". Use the exact host name from the certificate (often the provider's SMTP host)" + d.serverSaid(root.getMessage()));
        } else if (message.contains("STARTTLS is required")) {
            d.ok("SMTP greeting", "The server answered.");
            d.failed("Start TLS", "This server doesn't offer STARTTLS on port " + settings.port() + ". Try implicit TLS on port 465, "
                    + "or check the port your provider lists.");
        } else if (root instanceof SSLHandshakeException) {
            d.failed("TLS handshake", "TLS negotiation failed" + d.serverSaid(root.getMessage()));
        } else if (root instanceof SSLException) {
            d.failed("TLS handshake", "The server didn't answer with TLS. Port " + settings.port() + " probably expects "
                    + (settings.tls() == TlsMode.IMPLICIT ? "STARTTLS (usually 587)" : "something else") + d.serverSaid(root.getMessage()));
        } else if (root instanceof SocketTimeoutException) {
            d.failed("SMTP greeting", "The server didn't send an SMTP greeting in time."
                    + (settings.port() == 465 && settings.tls() != TlsMode.IMPLICIT ? " Port 465 usually needs implicit TLS." : ""));
        } else if (message.toLowerCase(Locale.ROOT).contains("auth") && !settings.auth()) {
            d.failed("Sign in", "The server requires authentication; enable it and enter a user name and password"
                    + d.serverSaid(message));
        } else {
            d.failed("SMTP conversation", d.clean(rootMessage(e)));
        }
    }

    private static String rejectionHint(int code, String message) {
        String lower = String.valueOf(message).toLowerCase(Locale.ROOT);
        if (code == 530 || lower.contains("authentication required")) {
            return "The server requires you to sign in.";
        }
        if (lower.contains("relay")) {
            return "The server won't relay for this sender: use an address of the authenticated mailbox, or allow this server.";
        }
        if (code == 553 || code == 550 || lower.contains("sender") || lower.contains("from")) {
            return "Providers usually only allow sending from the signed-in mailbox or a verified alias.";
        }
        if (code == 552 || code == 554) {
            return "The provider may restrict or limit sending from this account.";
        }
        return "";
    }

    private static MimeMessage testMessage(SmtpSessions.SmtpSession session, SmtpSettings settings, String recipient) {
        try {
            MimeMessage message = new MimeMessage(session.session());
            message.setFrom(settings.fromName() == null || settings.fromName().isBlank()
                    ? new InternetAddress(settings.fromAddress())
                    : new InternetAddress(settings.fromAddress(), settings.fromName(), "UTF-8"));
            if (settings.replyTo() != null && !settings.replyTo().isBlank()) {
                message.setReplyTo(new Address[] {new InternetAddress(settings.replyTo())});
            }
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(recipient));
            message.setSubject("JTRAC test email", "UTF-8");
            message.setSentDate(new Date());
            message.setText("""
                    This is a test email from your JTRAC server.

                    If you can read it, outgoing email is set up correctly: invitations, password
                    resets and notifications will be delivered.

                    Sent %s via %s:%d (%s).
                    """.formatted(OffsetDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME), settings.host(),
                    settings.port(), settings.tls()), "UTF-8");
            message.setHeader("X-JTRAC-Test", "true");
            return message;
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lastResponse(Transport transport) {
        return transport instanceof SMTPTransport smtp ? smtp.getLastServerResponse() : "";
    }

    private static String describe(SendFailedException e) {
        List<String> parts = new ArrayList<>();
        if (e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0) {
            parts.add("the address " + e.getInvalidAddresses()[0]);
        }
        if (e.getValidUnsentAddresses() != null && e.getValidUnsentAddresses().length > 0) {
            parts.add("delivery to " + e.getValidUnsentAddresses()[0]);
        }
        return parts.isEmpty() ? "the message" : String.join(" and ", parts);
    }

    private static Throwable root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = root(e);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** Collects steps and keeps every message free of the password and control characters. */
    private record Diagnostics(List<SmtpTestResult.Step> steps, String password) {

        void ok(String name, String detail) {
            steps.add(new SmtpTestResult.Step(name, SmtpTestResult.Status.OK, clean(detail)));
        }

        void warning(String name, String detail) {
            steps.add(new SmtpTestResult.Step(name, SmtpTestResult.Status.WARNING, clean(detail)));
        }

        void failed(String name, String detail) {
            steps.add(new SmtpTestResult.Step(name, SmtpTestResult.Status.FAILED, clean(detail)));
        }

        void tlsOk(TlsMode tls) {
            switch (tls) {
                case STARTTLS -> ok("Start TLS", "Connection upgraded with STARTTLS; certificate verified.");
                case IMPLICIT -> ok("TLS", "Encrypted from the start; certificate verified.");
                case NONE -> warning("Encryption", "Not encrypted (trusted relay only).");
            }
        }

        String serverSaid(String response) {
            String text = clean(response);
            return text.isBlank() ? "." : ": “" + text + "”.";
        }

        String clean(String text) {
            String scrubbed = Redaction.scrub(text, password).replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").strip();
            return scrubbed.length() > 400 ? scrubbed.substring(0, 400) + "…" : scrubbed;
        }

        SmtpTestResult result(boolean accepted, String summary) {
            return new SmtpTestResult(accepted, summary, List.copyOf(steps));
        }
    }
}
