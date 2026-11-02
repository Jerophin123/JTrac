package com.jtrac.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jtrac.launcher.TuiDevice.Click;
import com.jtrac.launcher.TuiDevice.Input;
import com.jtrac.launcher.TuiDevice.Key;
import com.jtrac.launcher.TuiDevice.KeyKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SetupScreenTests {

    /** A terminal that plays a script; each step sees the last frame, so clicks can aim at text. */
    static final class FakeDevice implements TuiDevice {
        private final Deque<Function<TuiCanvas, Input>> script = new ArrayDeque<>();
        volatile TuiCanvas last;
        boolean closed;

        FakeDevice then(Input input) {
            script.add(canvas -> input);
            return this;
        }

        FakeDevice type(String text) {
            text.chars().forEach(c -> then(Key.ch((char) c)));
            return this;
        }

        FakeDevice key(KeyKind kind) {
            return then(Key.of(kind));
        }

        /** Clicks the first place the frame shows {@code text}. */
        FakeDevice click(String text) {
            script.add(canvas -> {
                for (int y = 0; y < canvas.height(); y++) {
                    int x = canvas.row(y).indexOf(text);
                    if (x >= 0) {
                        return new Click(x + 1, y);
                    }
                }
                throw new AssertionError("Not on screen: " + text + "\n" + screen(canvas));
            });
            return this;
        }

        @Override
        public boolean mouse() {
            return true;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 32;
        }

        @Override
        public synchronized Input next(long timeoutMillis) {
            Function<TuiCanvas, Input> step = script.poll();
            if (step == null) {
                throw new AssertionError("The script ended while the screen still waited for input.");
            }
            return step.apply(last);
        }

        @Override
        public synchronized void draw(TuiCanvas canvas) {
            last = canvas;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private FakeDevice device;
    private SetupScreen screen;

    private SetupScreen screen(FakeDevice fake) {
        device = fake;
        screen = new SetupScreen(fake, "JTRAC");
        return screen;
    }

    @AfterEach
    void closeScreen() {
        if (screen != null) {
            screen.close(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        }
    }

    static String screen(TuiCanvas canvas) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            out.append(canvas.row(y)).append('\n');
        }
        return out.toString();
    }

    @Test
    void textFieldEditsTheDefaultAndSubmitsWithEnter() {
        SetupScreen s = screen(new FakeDevice().key(KeyKind.CLEAR_LINE).type("Ada Lovelace").key(KeyKind.ENTER));
        assertThat(s.text("Name", "Admin", null)).isEqualTo("Ada Lovelace");
    }

    @Test
    void emptyFieldMeansTheDefault() {
        SetupScreen s = screen(new FakeDevice().key(KeyKind.CLEAR_LINE).key(KeyKind.ENTER));
        assertThat(s.text("HTTP port", "8080", null)).isEqualTo("8080");
    }

    @Test
    void invalidAnswersStayOnTheCardWithTheReason() {
        Prompter.Check atLeastThree = v -> v.length() < 3 ? Optional.of("Use at least 3 characters.") : Optional.empty();
        FakeDevice fake = new FakeDevice().type("ab").key(KeyKind.ENTER);
        fake.script.add(canvas -> {
            assertThat(screen(canvas)).contains("✗ Use at least 3 characters.");
            return Key.ch('c');
        });
        fake.key(KeyKind.ENTER);
        assertThat(screen(fake).text("Handle", null, atLeastThree)).isEqualTo("abc");
    }

    @Test
    void choicesAreClickedAndConfirmedWithTheContinueButton() {
        SetupScreen s = screen(new FakeDevice().click("Implicit TLS").click("Continue"));
        String choice = s.choice("Encryption", List.of(
                new String[] {"STARTTLS", "STARTTLS (recommended)"},
                new String[] {"IMPLICIT", "Implicit TLS / SSL"},
                new String[] {"NONE", "None"}), "STARTTLS");
        assertThat(choice).isEqualTo("IMPLICIT");
    }

    @Test
    void clickingTheChosenOptionAgainConfirmsIt() {
        SetupScreen s = screen(new FakeDevice().click("Open").click("Open"));
        assertThat(s.choice("Who can create accounts", List.of(
                new String[] {"INVITE_ONLY", "Invited people only"},
                new String[] {"OPEN", "Open sign-up"}), "INVITE_ONLY")).isEqualTo("OPEN");
    }

    @Test
    void choicesFollowArrowsAndNumbers() {
        SetupScreen s = screen(new FakeDevice().key(KeyKind.DOWN).key(KeyKind.DOWN).type("1").key(KeyKind.DOWN).key(KeyKind.ENTER));
        assertThat(s.choice("Pick", List.of(new String[] {"a", "A"}, new String[] {"b", "B"}, new String[] {"c", "C"}), "a"))
                .isEqualTo("b");
    }

    @Test
    void yesNoAnswersByKeyOrClick() {
        assertThat(screen(new FakeDevice().type("n")).yesNo("Configure email now", true)).isFalse();
        screen.close(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(screen(new FakeDevice().click("Yes")).yesNo("Start JTRAC now", false)).isTrue();
        screen.close(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(screen(new FakeDevice().key(KeyKind.ENTER)).yesNo("Apply these settings", true)).isTrue();
    }

    @Test
    void passwordsAreMaskedConfirmedAndCanBeRevealed() {
        FakeDevice fake = new FakeDevice().type("correct horse").key(KeyKind.TAB).type("correct hors").key(KeyKind.ENTER);
        fake.script.add(canvas -> {
            String text = screen(canvas);
            assertThat(text).contains("The two entries don't match.").doesNotContain("correct horse");
            assertThat(text).contains("•••••••••••••");
            return new Click(0, 0);
        });
        fake.click("Show");
        fake.script.add(canvas -> {
            assertThat(screen(canvas)).contains("correct horse");
            return Key.ch('e');
        });
        fake.key(KeyKind.ENTER);
        assertThat(screen(fake).secret("Password (12 to 128 characters)", true, false, null)).isEqualTo("correct horse");
    }

    @Test
    void escapeAsksBeforeLeavingAndCanBeUndone() {
        FakeDevice fake = new FakeDevice().key(KeyKind.ESCAPE);
        fake.script.add(canvas -> {
            assertThat(screen(canvas)).contains("Leave setup?").contains("Keep going");
            return Key.of(KeyKind.ESCAPE);
        });
        fake.type("x").key(KeyKind.ENTER);
        assertThat(screen(fake).text("Bind address", "", null)).isEqualTo("x");
        assertThat(screen.cancelled()).isFalse();
    }

    @Test
    void enterInTheLeaveDialogKeepsGoing() {
        FakeDevice fake = new FakeDevice().key(KeyKind.ESCAPE).key(KeyKind.ENTER).type("y").key(KeyKind.ENTER);
        assertThat(screen(fake).text("Bind address", "", null)).isEqualTo("y");
        assertThat(screen.cancelled()).isFalse();
    }

    @Test
    void leavingSetupStopsWithAClearMessage() {
        // The button (padded label), not the dialog title "Leave setup?".
        SetupScreen s = screen(new FakeDevice().key(KeyKind.ESCAPE).click("Leave setup  "));
        assertThatThrownBy(() -> s.text("Bind address", "127.0.0.1", null))
                .isInstanceOf(SetupException.class).hasMessageContaining("cancelled");
        assertThat(s.cancelled()).isTrue();
    }

    @Test
    void showsStepsActivityAndProgress() {
        SetupScreen s = screen(new FakeDevice());
        s.title("JTRAC server setup");
        s.plan(List.of("Welcome", "Database", "Done"));
        s.step(1, 3, "Welcome");
        s.message(Out.Kind.OK, "Data directory D:\\JTRAC");
        s.step(2, 3, "Database");
        s.message(Out.Kind.WARN, "Port 54329 is in use; suggesting 54330.");

        String text = screen(s.render(110, 32));
        assertThat(text).contains("JTRAC").contains("Step 2 of 3")
                .contains("✓ Welcome").contains("● Database").contains("○ Done")
                .contains("! Port 54329 is in use; suggesting 54330.")
                // Only the current step's activity is shown.
                .doesNotContain("Data directory");
    }

    @Test
    void asksForABiggerWindowWhenTooSmall() {
        SetupScreen s = screen(new FakeDevice());
        assertThat(screen(s.render(60, 18))).contains("Make the window at least 72×22");
    }

    @Test
    void closingRestoresTheTerminalAndPrintsTheTranscript() {
        SetupScreen s = screen(new FakeDevice());
        s.title("JTRAC server setup");
        s.plan(List.of("Welcome"));
        s.step(1, 1, "Welcome");
        s.message(Out.Kind.OK, "All good");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.close(new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(device.closed).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("== JTRAC server setup").contains("-- Step 1 of 1: Welcome")
                .contains("[ ok ] All good");
    }

    @Test
    void thePrompterAsksOnTheScreenUnlessAnswersAreGiven() throws Exception {
        Prompter prompter = Prompter.interactive();
        prompter.attach(screen(new FakeDevice().key(KeyKind.CLEAR_LINE).type("9090").key(KeyKind.ENTER)));
        assertThat(prompter.askInt("http.port", "HTTP port", 8080, 1, 65535)).isEqualTo(9090);
    }

    @Test
    void wrapsWordsAndBreaksLongOnes() {
        assertThat(SetupScreen.wrap("the quick brown fox", 9)).containsExactly("the quick", "brown fox");
        assertThat(SetupScreen.wrap("abcdefghij", 4)).containsExactly("abcd", "efgh", "ij");
    }

    @Test
    void framesUseTrueColorOrTheNearest256Color() {
        TuiCanvas canvas = new TuiCanvas(4, 1, TuiCanvas.Style.of(TuiCanvas.Palette.TEXT, TuiCanvas.Palette.CANVAS));
        assertThat(canvas.toAnsi(TuiCanvas.ColorMode.TRUE_COLOR)).contains("38;2;238;232;225");
        assertThat(canvas.toAnsi(TuiCanvas.ColorMode.COLOR_256)).contains("38;5;").doesNotContain(";2;");
        assertThat(TuiCanvas.nearest256(TuiCanvas.Rgb.hex(0xffffff))).isEqualTo(231);
        assertThat(TuiCanvas.nearest256(TuiCanvas.Rgb.hex(0xff0000))).isEqualTo(196);
    }
}
