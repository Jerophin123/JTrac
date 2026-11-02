package com.jtrac.launcher;

import com.jtrac.launcher.TuiDevice.Click;
import com.jtrac.launcher.TuiDevice.Input;
import com.jtrac.launcher.TuiDevice.Key;
import com.jtrac.launcher.TuiDevice.KeyKind;
import com.jtrac.launcher.TuiDevice.Resize;
import com.jtrac.launcher.TuiDevice.Timeout;
import com.jtrac.launcher.TuiDevice.Wheel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * The real terminal through JLine: raw input, the alternate screen, mouse tracking (SGR
 * encoding, also on the Windows console) and resize signals. Opening fails softly, so setup
 * falls back to plain prompts wherever a full-screen interface can't work.
 */
final class TerminalDevice implements TuiDevice {

    /** Smallest size the layout is designed for; smaller terminals get the plain prompts. */
    static final int MIN_WIDTH = 72;
    static final int MIN_HEIGHT = 22;

    private static final int READ_EXPIRED = -2;
    private static final long ESCAPE_WAIT_MS = 40;

    private final Terminal terminal;
    private final Attributes original;
    private final NonBlockingReader reader;
    private final TuiCanvas.ColorMode colorMode;
    private final AtomicBoolean resized = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread restoreOnExit;

    private TerminalDevice(Terminal terminal) {
        this.terminal = terminal;
        this.original = terminal.enterRawMode();
        this.reader = terminal.reader();
        this.colorMode = detectColorMode();
        terminal.handle(Terminal.Signal.WINCH, signal -> resized.set(true));
        // Alternate screen, bracketed paste, then mouse buttons and wheel (SGR coordinates).
        write("\u001b[?1049h\u001b[?2004h\u001b[?25l");
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        terminal.flush();
        restoreOnExit = new Thread(this::close, "jtrac-terminal-restore");
        Runtime.getRuntime().addShutdownHook(restoreOnExit);
    }

    /**
     * Opens the full-screen interface when this is an interactive terminal of a usable size
     * that isn't "dumb"; otherwise empty, and setup uses the plain prompts.
     */
    static Optional<TerminalDevice> open() {
        java.io.Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return Optional.empty();
        }
        Terminal terminal = null;
        try {
            // FFM (pure Java native access, JDK 22+) or exec (stty on Unix); never the JNI
            // provider, which would extract a native library into the temp directory.
            terminal = TerminalBuilder.builder().system(true).providers("ffm,exec").dumb(false).build();
            // Mouse support is a bonus: without it everything still works from the keyboard.
            boolean usable = !terminal.getType().toLowerCase(Locale.ROOT).startsWith("dumb")
                    && terminal.getSize().getColumns() >= MIN_WIDTH && terminal.getSize().getRows() >= MIN_HEIGHT;
            if (!usable) {
                terminal.close();
                return Optional.empty();
            }
            return Optional.of(new TerminalDevice(terminal));
        } catch (IOException | RuntimeException e) {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (IOException ignored) {
                    // nothing more to restore
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public boolean mouse() {
        return terminal.hasMouseSupport();
    }

    @Override
    public int width() {
        return Math.max(1, terminal.getSize().getColumns());
    }

    @Override
    public int height() {
        return Math.max(1, terminal.getSize().getRows());
    }

    @Override
    public Input next(long timeoutMillis) {
        if (resized.getAndSet(false)) {
            return new Resize();
        }
        try {
            int c = reader.read(timeoutMillis);
            if (c == READ_EXPIRED) {
                return resized.getAndSet(false) ? new Resize() : new Timeout();
            }
            if (c < 0) {
                throw new SetupException("The terminal closed.");
            }
            return decode(c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Input decode(int c) throws IOException {
        return switch (c) {
            case '\r', '\n' -> Key.of(KeyKind.ENTER);
            case '\t' -> Key.of(KeyKind.TAB);
            case 127, 8 -> Key.of(KeyKind.BACKSPACE);
            case 1 -> Key.of(KeyKind.HOME);
            case 5 -> Key.of(KeyKind.END);
            case 21 -> Key.of(KeyKind.CLEAR_LINE);
            case 3 -> Key.of(KeyKind.ESCAPE);
            case 27 -> decodeEscape();
            default -> c >= 32 ? Key.ch((char) c) : new Timeout();
        };
    }

    private Input decodeEscape() throws IOException {
        int next = reader.read(ESCAPE_WAIT_MS);
        if (next == READ_EXPIRED || next == 27) {
            return Key.of(KeyKind.ESCAPE);
        }
        if (next == 'O') {
            return arrowOrHome(reader.read(ESCAPE_WAIT_MS));
        }
        if (next != '[') {
            return new Timeout();
        }
        int first = reader.read(ESCAPE_WAIT_MS);
        if (first == '<' || first == 'M') {
            MouseEvent event = terminal.readMouseEvent(this::readForMouse, first == '<' ? "\u001b[<" : "\u001b[M");
            return mouse(event);
        }
        StringBuilder params = new StringBuilder();
        int ch = first;
        while (ch != READ_EXPIRED && ch >= 0 && !(ch >= 0x40 && ch <= 0x7e)) {
            params.append((char) ch);
            ch = reader.read(ESCAPE_WAIT_MS);
        }
        if (ch == '~') {
            return switch (params.toString()) {
                case "1", "7" -> Key.of(KeyKind.HOME);
                case "4", "8" -> Key.of(KeyKind.END);
                case "3" -> Key.of(KeyKind.DELETE);
                case "5" -> Key.of(KeyKind.PAGE_UP);
                case "6" -> Key.of(KeyKind.PAGE_DOWN);
                // 200~/201~ frame a paste: the pasted characters arrive as ordinary input.
                default -> new Timeout();
            };
        }
        if (ch == 'Z') {
            return Key.of(KeyKind.BACK_TAB);
        }
        return arrowOrHome(ch);
    }

    private static Input arrowOrHome(int ch) {
        return switch (ch) {
            case 'A' -> Key.of(KeyKind.UP);
            case 'B' -> Key.of(KeyKind.DOWN);
            case 'C' -> Key.of(KeyKind.RIGHT);
            case 'D' -> Key.of(KeyKind.LEFT);
            case 'H' -> Key.of(KeyKind.HOME);
            case 'F' -> Key.of(KeyKind.END);
            default -> new Timeout();
        };
    }

    private int readForMouse() {
        try {
            int c = reader.read(ESCAPE_WAIT_MS * 5);
            return c == READ_EXPIRED ? -1 : c;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Input mouse(MouseEvent event) {
        if (event == null) {
            return new Timeout();
        }
        if (event.getType() == MouseEvent.Type.Wheel || event.getButton() == MouseEvent.Button.WheelUp
                || event.getButton() == MouseEvent.Button.WheelDown) {
            return new Wheel(event.getX(), event.getY(), event.getButton() == MouseEvent.Button.WheelUp ? -1 : 1);
        }
        if (event.getType() == MouseEvent.Type.Pressed && event.getButton() == MouseEvent.Button.Button1) {
            return new Click(event.getX(), event.getY());
        }
        return new Timeout();
    }

    @Override
    public void draw(TuiCanvas canvas) {
        write("\u001b[?2026h" + canvas.toAnsi(colorMode) + "\u001b[?2026l");
        terminal.flush();
    }

    private void write(String text) {
        terminal.writer().write(text);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            write("\u001b[0m\u001b[?2004l\u001b[?25h\u001b[?1049l");
            terminal.flush();
            terminal.setAttributes(original);
            terminal.close();
        } catch (IOException | RuntimeException ignored) {
            // best effort: the terminal is going away
        }
        if (Thread.currentThread() != restoreOnExit) {
            try {
                Runtime.getRuntime().removeShutdownHook(restoreOnExit);
            } catch (IllegalStateException ignored) {
                // already shutting down
            }
        }
    }

    /** True color where the terminal says so (or is known to), otherwise the 256-color palette. */
    private static TuiCanvas.ColorMode detectColorMode() {
        String colorTerm = System.getenv().getOrDefault("COLORTERM", "").toLowerCase(Locale.ROOT);
        String program = System.getenv().getOrDefault("TERM_PROGRAM", "");
        boolean trueColor = colorTerm.contains("truecolor") || colorTerm.contains("24bit")
                || System.getenv("WT_SESSION") != null
                || program.equals("vscode") || program.equals("iTerm.app") || program.equals("WezTerm");
        return trueColor ? TuiCanvas.ColorMode.TRUE_COLOR : TuiCanvas.ColorMode.COLOR_256;
    }
}
