package com.jtrac.launcher;

/** The terminal as the full-screen setup sees it: a size, input events and whole frames. */
interface TuiDevice {

    /** One input event. */
    sealed interface Input permits Key, Click, Wheel, Timeout, Resize {
    }

    enum KeyKind { CHAR, ENTER, TAB, BACK_TAB, BACKSPACE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN, ESCAPE, CLEAR_LINE }

    /** A key press; {@code ch} is set for {@link KeyKind#CHAR}. */
    record Key(KeyKind kind, char ch) implements Input {
        static Key of(KeyKind kind) {
            return new Key(kind, '\0');
        }

        static Key ch(char c) {
            return new Key(KeyKind.CHAR, c);
        }
    }

    /** A primary-button press at a 0-based column and row. */
    record Click(int x, int y) implements Input {
    }

    /** A wheel step: negative scrolls up. */
    record Wheel(int x, int y, int delta) implements Input {
    }

    /** Nothing happened within the timeout (lets the caller animate or check for resizes). */
    record Timeout() implements Input {
    }

    /** The terminal changed size. */
    record Resize() implements Input {
    }

    /** Whether clicks and the wheel are reported (otherwise the keyboard does everything). */
    boolean mouse();

    int width();

    int height();

    /** The next event, waiting at most {@code timeoutMillis}. */
    Input next(long timeoutMillis);

    void draw(TuiCanvas canvas);

    /** Restores the terminal exactly as it was; safe to call more than once. */
    void close();
}
