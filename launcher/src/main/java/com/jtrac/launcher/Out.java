package com.jtrac.launcher;

import java.io.PrintStream;
import java.util.List;

/**
 * Console output in plain ASCII markers, so it reads the same in every terminal. While the
 * full-screen setup is open, the same messages go to it instead (see {@link #use}).
 */
final class Out {

    /** What a message is, so the full-screen setup can mark it. */
    enum Kind { LINE, INFO, OK, WARN, FAIL, BLANK }

    /** Where messages go. */
    interface Sink {
        void title(String text);

        /** The names of all steps, before the first one starts (full-screen progress). */
        void plan(List<String> steps);

        void step(int number, int total, String text);

        void message(Kind kind, String text);
    }

    private static final Sink CONSOLE = new Console(System.out);
    private static volatile Sink sink = CONSOLE;

    private Out() {
    }

    /** Sends messages to {@code next} (null: the console); returns the previous destination. */
    static Sink use(Sink next) {
        Sink previous = sink;
        sink = next == null ? CONSOLE : next;
        return previous;
    }

    static void title(String text) {
        sink.title(text);
    }

    static void plan(List<String> steps) {
        sink.plan(steps);
    }

    static void step(int number, int total, String text) {
        sink.step(number, total, text);
    }

    static void line(String text) {
        sink.message(Kind.LINE, text);
    }

    static void info(String text) {
        sink.message(Kind.INFO, text);
    }

    static void ok(String text) {
        sink.message(Kind.OK, text);
    }

    static void warn(String text) {
        sink.message(Kind.WARN, text);
    }

    static void fail(String text) {
        sink.message(Kind.FAIL, text);
    }

    static void blank() {
        sink.message(Kind.BLANK, "");
    }

    /** The plain console format (also used to replay the full-screen transcript afterwards). */
    record Console(PrintStream out) implements Sink {

        @Override
        public void title(String text) {
            out.println();
            out.println("== " + text + " " + "=".repeat(Math.max(3, 66 - text.length())));
        }

        @Override
        public void plan(List<String> steps) {
            // the console shows each step as it starts
        }

        @Override
        public void step(int number, int total, String text) {
            out.println();
            out.println("-- Step " + number + " of " + total + ": " + text);
        }

        @Override
        public void message(Kind kind, String text) {
            switch (kind) {
                case LINE -> out.println(text);
                case INFO -> out.println("   " + text);
                case OK -> out.println("  [ ok ] " + text);
                case WARN -> out.println("  [warn] " + text);
                case FAIL -> out.println("  [FAIL] " + text);
                case BLANK -> out.println();
            }
        }
    }
}
