package com.jtrac.serverconfig;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds the Jakarta Mail session for a set of SMTP settings. Used for real delivery and for
 * the test email, so a successful test means the same configuration delivers mail.
 *
 * <p>TLS: STARTTLS is <em>required</em> (never silently skipped), the server certificate is
 * validated against the Java trust store plus any configured extra CA certificates, and the
 * host name must match the certificate.
 */
public final class SmtpSessions {

    private SmtpSessions() {
    }

    /** The session and the protocol name to request a transport for. */
    public record SmtpSession(Session session, String protocol) {
    }

    public static SmtpSession create(SmtpSettings smtp, String password) {
        String protocol = smtp.tls() == TlsMode.IMPLICIT ? "smtps" : "smtp";
        String prefix = "mail." + protocol + ".";
        String timeout = Long.toString(smtp.timeoutSeconds() * 1000L);
        Properties props = new Properties();
        props.put("mail.transport.protocol", protocol);
        props.put(prefix + "host", smtp.host());
        props.put(prefix + "port", Integer.toString(smtp.port()));
        props.put(prefix + "auth", Boolean.toString(smtp.auth()));
        props.put(prefix + "connectiontimeout", timeout);
        props.put(prefix + "timeout", timeout);
        props.put(prefix + "writetimeout", timeout);
        // Never log the protocol exchange: it contains the base64-encoded credentials.
        props.put("mail.debug", "false");
        switch (smtp.tls()) {
            case STARTTLS -> {
                props.put(prefix + "starttls.enable", "true");
                props.put(prefix + "starttls.required", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
            }
            case IMPLICIT -> {
                props.put(prefix + "ssl.enable", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
            }
            case NONE -> props.put(prefix + "starttls.enable", "false");
        }
        if (smtp.tls() != TlsMode.NONE) {
            props.put(prefix + "ssl.protocols", "TLSv1.3 TLSv1.2");
            SSLSocketFactory factory = socketFactory(smtp.trustedCertificates());
            if (factory != null) {
                props.put(prefix + "ssl.socketFactory", factory);
            }
        }
        Authenticator authenticator = smtp.auth() ? new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtp.username(), password == null ? "" : password);
            }
        } : null;
        return new SmtpSession(Session.getInstance(props, authenticator), protocol);
    }

    /**
     * A socket factory trusting the default CAs plus the certificates in {@code pemFile}, or
     * null to use the defaults.
     */
    static SSLSocketFactory socketFactory(String pemFile) {
        if (pemFile == null || pemFile.isBlank()) {
            return null;
        }
        try {
            X509TrustManager defaults = trustManager(null);
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            int i = 0;
            for (Certificate certificate : readCertificates(Path.of(pemFile))) {
                extra.setCertificateEntry("jtrac-extra-" + i++, certificate);
            }
            X509TrustManager custom = trustManager(extra);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new EitherTrustManager(defaults, custom)}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("The trusted certificates file can't be read: " + e.getMessage(), e);
        }
    }

    private static Collection<? extends Certificate> readCertificates(Path file) throws IOException, CertificateException {
        try (InputStream in = Files.newInputStream(file)) {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certificates.isEmpty()) {
                throw new CertificateException("no certificates found");
            }
            return certificates;
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    /** Accepts a server chain trusted by either manager; still a full validation, never "trust all". */
    private record EitherTrustManager(X509TrustManager first, X509TrustManager second) implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>(List.of(first.getAcceptedIssuers()));
            all.addAll(List.of(second.getAcceptedIssuers()));
            return all.toArray(X509Certificate[]::new);
        }
    }
}
