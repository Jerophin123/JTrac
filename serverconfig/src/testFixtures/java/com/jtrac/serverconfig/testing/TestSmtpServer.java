package com.jtrac.serverconfig.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * A small, controlled SMTP server for tests: plain, STARTTLS or implicit TLS, optional
 * AUTH PLAIN/LOGIN, and configurable rejections. It keeps received messages in memory and
 * never relays anything. Its TLS certificate is generated with {@code keytool} for
 * {@code localhost}/127.0.0.1 and exported as PEM so clients can trust it explicitly.
 */
public final class TestSmtpServer implements AutoCloseable {

    public enum Mode { PLAIN, STARTTLS, IMPLICIT }

    /** A received message. */
    public record Received(String from, List<String> to, String data, boolean overTls, String user) {
    }

    private final Mode mode;
    private final String user;
    private final String password;
    private final String rejectSenderContaining;
    private final String authFailureText;
    private final ServerSocket server;
    private final SSLContext tls;
    private final Path certificatePem;
    private final Path tempDir;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    private TestSmtpServer(Builder b) throws IOException {
        this.mode = b.mode;
        this.user = b.user;
        this.password = b.password;
        this.rejectSenderContaining = b.rejectSenderContaining;
        this.authFailureText = b.authFailureText;
        Path dir = Files.createTempDirectory("jtrac-test-smtp");
        this.tls = createTls(dir);
        this.certificatePem = dir.resolve("certificate.pem");
        this.tempDir = dir;
        this.server = mode == Mode.IMPLICIT
                ? tls.getServerSocketFactory().createServerSocket()
                : new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), b.port));
        Thread.ofVirtual().start(this::acceptLoop);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** PEM file with the server certificate, for the client's trusted-certificates setting. */
    public Path certificatePem() {
        return certificatePem;
    }

    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.close();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The folder holds the throwaway key pair; don't leave it behind in the temp directory.
        try (var files = Files.list(tempDir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(tempDir);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> handle(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket initial) {
        Socket socket = initial;
        boolean overTls = mode == Mode.IMPLICIT;
        String authenticated = null;
        String from = null;
        List<String> to = new ArrayList<>();
        try {
            socket.setSoTimeout(20_000);
            BufferedReader in = reader(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, "220 localhost JTRAC test SMTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    List<String> lines = new ArrayList<>(List.of("localhost greets you"));
                    if (mode == Mode.STARTTLS && !overTls) {
                        lines.add("STARTTLS");
                    }
                    if (user != null && (overTls || mode == Mode.PLAIN)) {
                        lines.add("AUTH PLAIN LOGIN");
                    }
                    lines.add("8BITMIME");
                    for (int i = 0; i < lines.size(); i++) {
                        send(out, "250" + (i == lines.size() - 1 ? " " : "-") + lines.get(i));
                    }
                } else if (upper.equals("STARTTLS") && mode == Mode.STARTTLS && !overTls) {
                    send(out, "220 2.0.0 Ready to start TLS");
                    SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
                    secure.setUseClientMode(false);
                    secure.startHandshake();
                    socket = secure;
                    overTls = true;
                    in = reader(socket.getInputStream());
                    out = socket.getOutputStream();
                } else if (upper.startsWith("AUTH PLAIN")) {
                    String payload = line.length() > 10 ? line.substring(10).strip() : "";
                    if (payload.isEmpty()) {
                        send(out, "334 ");
                        payload = in.readLine();
                    }
                    String[] parts = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8).split("\0", -1);
                    authenticated = checkAuth(out, parts.length == 3 ? parts[1] : "", parts.length == 3 ? parts[2] : "");
                } else if (upper.startsWith("AUTH LOGIN")) {
                    send(out, "334 VXNlcm5hbWU6");
                    String name = decode(in.readLine());
                    send(out, "334 UGFzc3dvcmQ6");
                    String pass = decode(in.readLine());
                    authenticated = checkAuth(out, name, pass);
                } else if (upper.startsWith("MAIL FROM:")) {
                    from = address(line.substring(10));
                    to.clear();
                    if (user != null && authenticated == null) {
                        send(out, "530 5.7.0 Authentication required");
                    } else if (rejectSenderContaining != null && from.contains(rejectSenderContaining)) {
                        send(out, "553 5.7.1 <" + from + ">: Sender address rejected: not owned by user");
                    } else {
                        send(out, "250 2.1.0 Ok");
                    }
                } else if (upper.startsWith("RCPT TO:")) {
                    to.add(address(line.substring(8)));
                    send(out, "250 2.1.5 Ok");
                } else if (upper.equals("DATA")) {
                    send(out, "354 End data with <CR><LF>.<CR><LF>");
                    StringBuilder data = new StringBuilder();
                    String dataLine;
                    while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                        data.append(dataLine.startsWith("..") ? dataLine.substring(1) : dataLine).append("\r\n");
                    }
                    received.add(new Received(from, List.copyOf(to), data.toString(), overTls, authenticated));
                    send(out, "250 2.0.0 Ok: queued as " + received.size());
                } else if (upper.equals("QUIT")) {
                    send(out, "221 2.0.0 Bye");
                    return;
                } else if (upper.equals("RSET") || upper.equals("NOOP")) {
                    send(out, "250 2.0.0 Ok");
                } else {
                    send(out, "502 5.5.2 Command not recognized");
                }
            }
        } catch (SocketException e) {
            // client went away
        } catch (IOException e) {
            // test client closed or failed TLS: nothing to do
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private String checkAuth(OutputStream out, String name, String pass) throws IOException {
        if (user != null && user.equals(name) && password.equals(pass)) {
            send(out, "235 2.7.0 Authentication successful");
            return name;
        }
        send(out, "535 5.7.8 " + (authFailureText == null ? "Authentication credentials invalid" : authFailureText.replace("{password}", pass)));
        return null;
    }

    private static String decode(String line) {
        return line == null ? "" : new String(Base64.getDecoder().decode(line.strip()), StandardCharsets.UTF_8);
    }

    private static String address(String value) {
        String v = value.strip();
        int start = v.indexOf('<');
        int end = v.indexOf('>');
        return start >= 0 && end > start ? v.substring(start + 1, end) : v.split(" ")[0];
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static SSLContext createTls(Path dir) throws IOException {
        Path keystore = dir.resolve("smtp.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "smtp", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
        run(keytool, "-exportcert", "-rfc", "-alias", "smtp", "-keystore", keystore.toString(), "-storepass", "changeit",
                "-file", dir.resolve("certificate.pem").toString());
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, "changeit".toCharArray());
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "changeit".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static void run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("keytool failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Builder: defaults to STARTTLS with authentication required. */
    public static final class Builder {
        private Mode mode = Mode.STARTTLS;
        private int port;
        private String user = "jtrac@example.test";
        private String password = "correct-horse";
        private String rejectSenderContaining;
        private String authFailureText;

        public Builder mode(Mode value) {
            mode = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Requires AUTH with these credentials; pass null to accept mail without authentication. */
        public Builder credentials(String name, String pass) {
            user = name;
            password = pass;
            return this;
        }

        public Builder rejectSenderContaining(String value) {
            rejectSenderContaining = value;
            return this;
        }

        /** Text after "535 5.7.8"; {password} is replaced by the submitted password (redaction tests). */
        public Builder authFailureText(String value) {
            authFailureText = value;
            return this;
        }

        public TestSmtpServer start() {
            try {
                return new TestSmtpServer(this);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Standalone use (installer verification): {@code TestSmtpServer [port] [STARTTLS|IMPLICIT|PLAIN]}.
     * Prints the port, credentials and certificate path, then runs until stopped, logging each
     * received message's sender and recipients.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2525;
        Mode mode = args.length > 1 ? Mode.valueOf(args[1].toUpperCase(Locale.ROOT)) : Mode.STARTTLS;
        TestSmtpServer server = builder().mode(mode).port(port).start();
        // Runs until stopped (Ctrl+C or SIGTERM); clean up the throwaway key pair on the way out.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException ignored) {
                // exiting anyway
            }
        }));
        {
            System.out.println("TEST-SMTP port=" + server.port() + " mode=" + mode + " user=jtrac@example.test password=correct-horse");
            System.out.println("TEST-SMTP certificate=" + server.certificatePem().toAbsolutePath());
            Path inbox = Path.of(System.getProperty("jtrac.testsmtp.inbox", "test-smtp-inbox"));
            Files.createDirectories(inbox);
            int seen = 0;
            while (true) {
                List<Received> all = server.received();
                for (; seen < all.size(); seen++) {
                    Received r = all.get(seen);
                    Files.writeString(inbox.resolve("message-" + (seen + 1) + ".eml"), r.data());
                    System.out.println("TEST-SMTP received from=" + r.from() + " to=" + r.to() + " tls=" + r.overTls());
                }
                Thread.sleep(250);
            }
        }
    }
}
