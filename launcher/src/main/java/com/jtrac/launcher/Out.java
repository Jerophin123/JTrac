package com.jtrac.launcher;

import java.io.PrintStream;

/** Console output in plain ASCII markers, so it reads the same in every terminal. */
final class Out {

    private static final PrintStream OUT = System.out;

    private Out() {
    }

    static void title(String text) {
        OUT.println();
        OUT.println("== " + text + " " + "=".repeat(Math.max(3, 66 - text.length())));
    }

    static void step(int number, int total, String text) {
        OUT.println();
        OUT.println("-- Step " + number + " of " + total + ": " + text);
    }

    static void line(String text) {
        OUT.println(text);
    }

    static void info(String text) {
        OUT.println("   " + text);
    }

    static void ok(String text) {
        OUT.println("  [ ok ] " + text);
    }

    static void warn(String text) {
        OUT.println("  [warn] " + text);
    }

    static void fail(String text) {
        OUT.println("  [FAIL] " + text);
    }

    static void blank() {
        OUT.println();
    }
}
