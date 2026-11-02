package com.jtrac.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * A character grid with colors for one frame of the full-screen setup. Drawing clips to the
 * grid; {@link #toAnsi} turns the whole frame into one write, so the screen never flickers.
 */
final class TuiCanvas {

    /** A 24-bit color; rendered as true color or the nearest 256-color index. */
    record Rgb(int r, int g, int b) {
        static Rgb hex(int value) {
            return new Rgb((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
        }
    }

    /** Colors from the web app's dark theme (frontend/src/styles/tokens.css). */
    static final class Palette {
        static final Rgb CANVAS = Rgb.hex(0x141210);
        static final Rgb SURFACE = Rgb.hex(0x1c1916);
        static final Rgb SURFACE_MUTED = Rgb.hex(0x201d19);
        static final Rgb SUNKEN = Rgb.hex(0x110f0d);
        static final Rgb HOVER = Rgb.hex(0x2a2520);
        static final Rgb BORDER = Rgb.hex(0x2e2924);
        static final Rgb BORDER_STRONG = Rgb.hex(0x433c35);
        static final Rgb TEXT = Rgb.hex(0xeee8e1);
        static final Rgb MUTED = Rgb.hex(0xaaa196);
        static final Rgb SUBTLE = Rgb.hex(0x9c9287);
        static final Rgb ACCENT = Rgb.hex(0xe07c50);
        static final Rgb ACCENT_SOFT = Rgb.hex(0x3a2419);
        static final Rgb ON_ACCENT = Rgb.hex(0x1a0f0a);
        static final Rgb SUCCESS = Rgb.hex(0x6fbf8e);
        static final Rgb WARNING = Rgb.hex(0xe2b05a);
        static final Rgb DANGER = Rgb.hex(0xf08a7c);

        private Palette() {
        }
    }

    record Style(Rgb fg, Rgb bg, boolean bold, boolean underline) {
        static Style of(Rgb fg, Rgb bg) {
            return new Style(fg, bg, false, false);
        }

        Style strong() {
            return new Style(fg, bg, true, underline);
        }

        Style underlined() {
            return new Style(fg, bg, bold, true);
        }

        Style on(Rgb background) {
            return new Style(fg, background, bold, underline);
        }
    }

    enum ColorMode { TRUE_COLOR, COLOR_256 }

    private final int width;
    private final int height;
    private final char[][] chars;
    private final Style[][] styles;
    /** Where the terminal cursor goes (a focused text field), or -1 to hide it. */
    private int cursorX = -1;
    private int cursorY = -1;

    TuiCanvas(int width, int height, Style background) {
        this.width = width;
        this.height = height;
        this.chars = new char[height][width];
        this.styles = new Style[height][width];
        fill(0, 0, width, height, background);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void fill(int x, int y, int w, int h, Style style) {
        for (int row = Math.max(0, y); row < Math.min(height, y + h); row++) {
            for (int col = Math.max(0, x); col < Math.min(width, x + w); col++) {
                chars[row][col] = ' ';
                styles[row][col] = style;
            }
        }
    }

    /** Writes text at (x, y), clipped to {@code maxWidth} columns (an ellipsis marks the cut). */
    int text(int x, int y, String text, Style style, int maxWidth) {
        if (y < 0 || y >= height || maxWidth <= 0) {
            return 0;
        }
        String shown = text.length() > maxWidth ? text.substring(0, Math.max(0, maxWidth - 1)) + "…" : text;
        for (int i = 0; i < shown.length(); i++) {
            int col = x + i;
            if (col >= 0 && col < width) {
                chars[y][col] = shown.charAt(i);
                styles[y][col] = style;
            }
        }
        return shown.length();
    }

    int text(int x, int y, String text, Style style) {
        return text(x, y, text, style, width - x);
    }

    /** A rounded single-line box; the title (if any) sits in the top border. */
    void box(int x, int y, int w, int h, Style border, String title, Style titleStyle) {
        if (w < 2 || h < 2) {
            return;
        }
        text(x, y, "╭" + "─".repeat(w - 2) + "╮", border);
        for (int row = y + 1; row < y + h - 1; row++) {
            text(x, row, "│", border);
            text(x + w - 1, row, "│", border);
        }
        text(x, y + h - 1, "╰" + "─".repeat(w - 2) + "╯", border);
        if (title != null && !title.isEmpty()) {
            text(x + 2, y, " " + title + " ", titleStyle, w - 4);
        }
    }

    void hline(int x, int y, int w, Style style) {
        text(x, y, "─".repeat(Math.max(0, w)), style);
    }

    void vline(int x, int y, int h, Style style) {
        for (int row = y; row < y + h; row++) {
            text(x, row, "│", style);
        }
    }

    void cursor(int x, int y) {
        cursorX = x;
        cursorY = y;
    }

    /** The plain text of one row (for tests). */
    String row(int y) {
        return new String(chars[y]);
    }

    Style styleAt(int x, int y) {
        return styles[y][x];
    }

    /** The whole frame as one ANSI string: home, every row with minimal style changes, cursor. */
    String toAnsi(ColorMode mode) {
        StringBuilder out = new StringBuilder(width * height * 2);
        out.append("\u001b[?25l");
        Style current = null;
        for (int row = 0; row < height; row++) {
            out.append("\u001b[").append(row + 1).append(";1H");
            for (int col = 0; col < width; col++) {
                Style style = styles[row][col];
                if (!style.equals(current)) {
                    appendStyle(out, style, mode);
                    current = style;
                }
                out.append(chars[row][col]);
            }
        }
        out.append("\u001b[0m");
        if (cursorX >= 0 && cursorY >= 0 && cursorX < width && cursorY < height) {
            out.append("\u001b[").append(cursorY + 1).append(';').append(cursorX + 1).append('H').append("\u001b[?25h");
        }
        return out.toString();
    }

    private static void appendStyle(StringBuilder out, Style style, ColorMode mode) {
        List<String> codes = new ArrayList<>();
        codes.add("0");
        if (style.bold()) {
            codes.add("1");
        }
        if (style.underline()) {
            codes.add("4");
        }
        codes.add(color(38, style.fg(), mode));
        codes.add(color(48, style.bg(), mode));
        out.append("\u001b[").append(String.join(";", codes)).append('m');
    }

    private static String color(int base, Rgb rgb, ColorMode mode) {
        if (mode == ColorMode.TRUE_COLOR) {
            return base + ";2;" + rgb.r() + ";" + rgb.g() + ";" + rgb.b();
        }
        return base + ";5;" + nearest256(rgb);
    }

    /** The closest xterm-256 color: the 6×6×6 cube or the 24-step gray ramp, whichever is nearer. */
    static int nearest256(Rgb rgb) {
        int[] levels = {0, 95, 135, 175, 215, 255};
        int ri = nearestLevel(rgb.r(), levels);
        int gi = nearestLevel(rgb.g(), levels);
        int bi = nearestLevel(rgb.b(), levels);
        int cube = 16 + 36 * ri + 6 * gi + bi;
        int cubeDistance = distance(rgb, levels[ri], levels[gi], levels[bi]);
        int average = (rgb.r() + rgb.g() + rgb.b()) / 3;
        int grayIndex = Math.max(0, Math.min(23, (average - 8 + 5) / 10));
        int grayLevel = 8 + 10 * grayIndex;
        int grayDistance = distance(rgb, grayLevel, grayLevel, grayLevel);
        return grayDistance < cubeDistance ? 232 + grayIndex : cube;
    }

    private static int nearestLevel(int value, int[] levels) {
        int best = 0;
        for (int i = 1; i < levels.length; i++) {
            if (Math.abs(levels[i] - value) < Math.abs(levels[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    private static int distance(Rgb rgb, int r, int g, int b) {
        return (rgb.r() - r) * (rgb.r() - r) + (rgb.g() - g) * (rgb.g() - g) + (rgb.b() - b) * (rgb.b() - b);
    }
}
