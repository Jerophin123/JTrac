package com.jtrac.launcher;

import com.jtrac.launcher.TuiCanvas.Palette;
import com.jtrac.launcher.TuiCanvas.Style;
import com.jtrac.launcher.TuiDevice.Click;
import com.jtrac.launcher.TuiDevice.Input;
import com.jtrac.launcher.TuiDevice.Key;
import com.jtrac.launcher.TuiDevice.KeyKind;
import com.jtrac.launcher.TuiDevice.Wheel;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The full-screen setup: a header with progress, the list of steps, the current step's
 * activity, and a card with the question (text fields, password fields, option lists, yes/no
 * buttons). Everything works with the keyboard; with a mouse, fields, options and buttons
 * are clickable and the wheel scrolls. The same questions and checks as the plain prompts.
 */
final class SetupScreen implements Prompter.Screen, Out.Sink {

    private static final Style BASE = Style.of(Palette.TEXT, Palette.CANVAS);
    private static final Style PANEL = Style.of(Palette.TEXT, Palette.SURFACE);
    private static final Style BORDER = Style.of(Palette.BORDER_STRONG, Palette.SURFACE);
    private static final Style MUTED = Style.of(Palette.MUTED, Palette.SURFACE);
    private static final Style SUBTLE = Style.of(Palette.SUBTLE, Palette.SURFACE);
    private static final Style ACCENT = Style.of(Palette.ACCENT, Palette.SURFACE);
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final long TICK_MS = 120;

    /** One line of activity, kept for the screen and replayed to the console afterwards. */
    private record Entry(Out.Kind kind, String text, int step) {
    }

    private record Region(int x, int y, int w, int h, String action) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record Button(String action, String label, boolean primary) {
    }

    /** A text or password field. */
    private static final class Field {
        final String label;
        final boolean masked;
        final StringBuilder value;
        int cursor;

        Field(String label, String initial, boolean masked) {
            this.label = label;
            this.masked = masked;
            this.value = new StringBuilder(initial == null ? "" : initial);
            this.cursor = value.length();
        }
    }

    private enum FormKind { TEXT, SECRET, CHOICE, YES_NO }

    /** The question on screen and its state. */
    private static final class Form {
        final FormKind kind;
        final String question;
        final List<Field> fields = new ArrayList<>();
        final List<String[]> options = new ArrayList<>();
        final List<Button> buttons = new ArrayList<>();
        int selected;
        int listTop;
        /** Focus order: fields, then the option list (if any), then buttons. */
        int focus;
        boolean reveal;
        String error;
        /** The yes/no answer button that was pressed. */
        String chosen;

        Form(FormKind kind, String question) {
            this.kind = kind;
            this.question = question;
        }

        int focusCount() {
            return fields.size() + (options.isEmpty() ? 0 : 1) + buttons.size();
        }

        Field focusedField() {
            return focus < fields.size() ? fields.get(focus) : null;
        }

        boolean listFocused() {
            return !options.isEmpty() && focus == fields.size();
        }

        Button focusedButton() {
            int index = focus - fields.size() - (options.isEmpty() ? 0 : 1);
            return index >= 0 && index < buttons.size() ? buttons.get(index) : null;
        }
    }

    /** A dialog over everything (leave setup, finished, stopped). */
    private record Modal(String title, List<String> lines, List<Button> buttons, Style accent) {
    }

    private final TuiDevice device;
    private final String product;
    private final List<String> steps = new ArrayList<>();
    private final List<Entry> transcript = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private String title = "Setup";
    private int step = -1;
    private int total;
    private Form form;
    private Modal modal;
    private int modalFocus;
    private int logScroll;
    private int spinner;
    private long busySince = System.currentTimeMillis();
    private volatile boolean open = true;
    private boolean cancelled;
    private final Thread ticker;

    SetupScreen(TuiDevice device, String product) {
        this.device = device;
        this.product = product;
        ticker = new Thread(this::tick, "jtrac-setup-screen");
        ticker.setDaemon(true);
        ticker.start();
    }

    // --- Out.Sink -------------------------------------------------------------------------

    @Override
    public synchronized void title(String text) {
        title = text;
        repaint();
    }

    @Override
    public synchronized void plan(List<String> names) {
        steps.clear();
        steps.addAll(names);
        repaint();
    }

    @Override
    public synchronized void step(int number, int of, String text) {
        step = number - 1;
        total = of;
        while (steps.size() < of) {
            steps.add("");
        }
        if (step >= 0 && step < steps.size() && steps.get(step).isEmpty()) {
            steps.set(step, text);
        }
        logScroll = 0;
        busySince = System.currentTimeMillis();
        repaint();
    }

    @Override
    public synchronized void message(Out.Kind kind, String text) {
        for (String line : (text == null ? "" : text).split("\\R", -1)) {
            transcript.add(new Entry(kind, line, step));
        }
        logScroll = 0;
        repaint();
    }

    // --- Prompter.Screen -------------------------------------------------------------------

    @Override
    public String text(String question, String defaultValue, Prompter.Check check) {
        Form f = new Form(FormKind.TEXT, question);
        f.fields.add(new Field(null, defaultValue, false));
        f.buttons.add(new Button("cancel", "Cancel", false));
        f.buttons.add(new Button("submit", "Continue", true));
        return run(f, form -> {
            String value = form.fields.getFirst().value.toString().strip();
            String answer = value.isEmpty() && defaultValue != null ? defaultValue : value;
            Optional<String> problem = check == null ? Optional.empty() : check.apply(answer);
            return problem.isPresent() ? Result.error(problem.get()) : Result.of(answer);
        });
    }

    @Override
    public boolean yesNo(String question, boolean defaultValue) {
        Form f = new Form(FormKind.YES_NO, question);
        f.buttons.add(new Button("cancel", "Cancel", false));
        f.buttons.add(new Button("no", "No", !defaultValue));
        f.buttons.add(new Button("yes", "Yes", defaultValue));
        f.focus = defaultValue ? 2 : 1;
        return Boolean.parseBoolean(run(f, form -> Result.of(Boolean.toString("yes".equals(form.chosen)))));
    }

    @Override
    public String choice(String question, List<String[]> options, String defaultKey) {
        Form f = new Form(FormKind.CHOICE, question);
        f.options.addAll(options);
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i)[0].equals(defaultKey)) {
                f.selected = i;
            }
        }
        f.buttons.add(new Button("cancel", "Cancel", false));
        f.buttons.add(new Button("submit", "Continue", true));
        return run(f, form -> Result.of(form.options.get(form.selected)[0]));
    }

    @Override
    public String secret(String question, boolean confirm, boolean allowBlank, Prompter.Check check) {
        Form f = new Form(FormKind.SECRET, question);
        f.fields.add(new Field(confirm ? "Password" : null, "", true));
        if (confirm) {
            f.fields.add(new Field("Repeat to confirm", "", true));
        }
        f.buttons.add(new Button("reveal", "Show", false));
        f.buttons.add(new Button("cancel", "Cancel", false));
        f.buttons.add(new Button("submit", "Continue", true));
        return run(f, form -> {
            String first = form.fields.getFirst().value.toString();
            if (first.isEmpty() && allowBlank) {
                return Result.of("");
            }
            Optional<String> problem = check == null ? Optional.empty() : check.apply(first);
            if (problem.isPresent()) {
                return Result.error(problem.get());
            }
            if (confirm && !first.equals(form.fields.get(1).value.toString())) {
                return Result.error("The two entries don't match.");
            }
            if (first.isEmpty()) {
                return Result.error("Enter a value.");
            }
            return Result.of(first);
        });
    }

    /** What submitting a form produced: an answer, or an error to show on the card. */
    private record Result(String value, String error) {
        static Result of(String value) {
            return new Result(value, null);
        }

        static Result error(String message) {
            return new Result(null, message);
        }
    }

    // --- Lifecycle ---------------------------------------------------------------------------

    /** Whether the user chose "Leave setup" (no closing dialog is needed then). */
    synchronized boolean cancelled() {
        return cancelled;
    }

    /**
     * Shows the outcome and waits for the user to close it. Without {@code lines}, the last
     * messages of the final step are shown (for example where to open JTRAC).
     */
    void conclude(boolean success, String headline, List<String> lines) {
        List<String> body = lines;
        if (body.isEmpty()) {
            synchronized (this) {
                body = transcript.stream()
                        .filter(e -> e.step() == step && e.kind() != Out.Kind.BLANK && !e.text().isBlank())
                        .map(Entry::text)
                        .toList();
                body = body.subList(Math.max(0, body.size() - 6), body.size());
            }
        }
        Modal done = new Modal(headline, body, List.of(new Button("close", success ? "Finish" : "Close", true)),
                Style.of(success ? Palette.SUCCESS : Palette.DANGER, Palette.SURFACE).strong());
        showModal(done);
    }

    /** Restores the terminal and prints the transcript, so the record stays in the scrollback. */
    void close(PrintStream out) {
        synchronized (this) {
            if (!open) {
                return;
            }
            open = false;
        }
        ticker.interrupt();
        device.close();
        Out.Console console = new Out.Console(out);
        console.title(title);
        int shownStep = -2;
        synchronized (this) {
            for (Entry entry : transcript) {
                if (entry.step() != shownStep && entry.step() >= 0 && entry.step() < steps.size()) {
                    console.step(entry.step() + 1, total, steps.get(entry.step()));
                }
                shownStep = entry.step();
                console.message(entry.kind(), entry.text());
            }
        }
    }

    // --- The question loop ---------------------------------------------------------------------

    private String run(Form f, Function<Form, Result> submit) {
        synchronized (this) {
            form = f;
            repaint();
        }
        try {
            while (true) {
                Input input = device.next(TICK_MS);
                String action;
                synchronized (this) {
                    action = handle(f, input);
                    repaint();
                }
                if (action == null) {
                    continue;
                }
                switch (action) {
                    case "cancel" -> confirmLeave();
                    case "yes", "no" -> {
                        f.chosen = action;
                        return submit.apply(f).value();
                    }
                    case "submit" -> {
                        Result result = submit.apply(f);
                        synchronized (this) {
                            if (result.error() == null) {
                                return result.value();
                            }
                            f.error = result.error();
                            repaint();
                        }
                    }
                    default -> {
                        // handled in place
                    }
                }
            }
        } finally {
            synchronized (this) {
                form = null;
                busySince = System.currentTimeMillis();
                repaint();
            }
        }
    }

    /** Applies one input to the form; returns an action to perform outside the lock, if any. */
    private String handle(Form f, Input input) {
        if (input instanceof Click click) {
            Region hit = regions.stream().filter(r -> r.contains(click.x(), click.y())).reduce((a, b) -> b).orElse(null);
            return hit == null ? null : clickAction(f, hit.action(), click.x());
        }
        if (input instanceof Wheel wheel) {
            if (!f.options.isEmpty() && f.kind == FormKind.CHOICE && regions.stream().anyMatch(r -> r.action().startsWith("option:") && r.contains(wheel.x(), wheel.y()))) {
                f.selected = Math.max(0, Math.min(f.options.size() - 1, f.selected + wheel.delta()));
            } else {
                logScroll = Math.max(0, logScroll - wheel.delta() * 3);
            }
            return null;
        }
        if (!(input instanceof Key key)) {
            return null;
        }
        Field field = f.focusedField();
        switch (key.kind()) {
            case ESCAPE -> {
                return "cancel";
            }
            case TAB -> f.focus = (f.focus + 1) % f.focusCount();
            case BACK_TAB -> f.focus = (f.focus - 1 + f.focusCount()) % f.focusCount();
            case PAGE_UP -> logScroll += 5;
            case PAGE_DOWN -> logScroll = Math.max(0, logScroll - 5);
            case ENTER -> {
                Button button = f.focusedButton();
                if (button != null) {
                    return press(f, button.action());
                }
                if (field != null && f.focus < f.fields.size() - 1) {
                    f.focus++;
                    return null;
                }
                return "submit";
            }
            case UP, DOWN -> {
                int delta = key.kind() == KeyKind.UP ? -1 : 1;
                if (!f.options.isEmpty()) {
                    f.selected = Math.max(0, Math.min(f.options.size() - 1, f.selected + delta));
                    f.focus = f.fields.size();
                } else if (f.fields.size() > 1) {
                    f.focus = Math.max(0, Math.min(f.fields.size() - 1, f.focus + delta));
                }
            }
            case LEFT, RIGHT -> {
                int delta = key.kind() == KeyKind.LEFT ? -1 : 1;
                if (field != null) {
                    field.cursor = Math.max(0, Math.min(field.value.length(), field.cursor + delta));
                } else if (f.focusedButton() != null) {
                    int first = f.fields.size() + (f.options.isEmpty() ? 0 : 1);
                    f.focus = Math.max(first, Math.min(f.focusCount() - 1, f.focus + delta));
                }
            }
            case HOME -> {
                if (field != null) {
                    field.cursor = 0;
                } else if (f.listFocused()) {
                    f.selected = 0;
                }
            }
            case END -> {
                if (field != null) {
                    field.cursor = field.value.length();
                } else if (f.listFocused()) {
                    f.selected = f.options.size() - 1;
                }
            }
            case BACKSPACE -> {
                if (field != null && field.cursor > 0) {
                    field.value.deleteCharAt(--field.cursor);
                    f.error = null;
                }
            }
            case DELETE -> {
                if (field != null && field.cursor < field.value.length()) {
                    field.value.deleteCharAt(field.cursor);
                    f.error = null;
                }
            }
            case CLEAR_LINE -> {
                if (field != null) {
                    field.value.setLength(0);
                    field.cursor = 0;
                }
            }
            case CHAR -> {
                if (field != null) {
                    field.value.insert(field.cursor++, key.ch());
                    f.error = null;
                } else if (f.kind == FormKind.YES_NO && (key.ch() == 'y' || key.ch() == 'Y')) {
                    return "yes";
                } else if (f.kind == FormKind.YES_NO && (key.ch() == 'n' || key.ch() == 'N')) {
                    return "no";
                } else if (!f.options.isEmpty() && key.ch() >= '1' && key.ch() <= '9' && key.ch() - '1' < f.options.size()) {
                    f.selected = key.ch() - '1';
                    f.focus = f.fields.size();
                }
            }
            default -> {
                // nothing to do for other keys
            }
        }
        return null;
    }

    private String clickAction(Form f, String action, int x) {
        if (action.startsWith("field:")) {
            String[] parts = action.split(":");
            int index = Integer.parseInt(parts[1]);
            int start = Integer.parseInt(parts[2]);
            int offset = Integer.parseInt(parts[3]);
            f.focus = index;
            Field field = f.fields.get(index);
            field.cursor = Math.max(0, Math.min(field.value.length(), offset + (x - start)));
            return null;
        }
        if (action.startsWith("option:")) {
            int index = Integer.parseInt(action.substring("option:".length()));
            boolean again = f.selected == index && f.listFocused();
            f.selected = index;
            f.focus = f.fields.size();
            // A second click on the chosen option confirms it, like a double click.
            return again ? "submit" : null;
        }
        if (action.startsWith("button:")) {
            String name = action.substring("button:".length());
            if (name.equals("reveal")) {
                // Show/Hide leaves the focus in the field, so typing simply continues.
                return press(f, name);
            }
            for (int i = 0; i < f.buttons.size(); i++) {
                if (f.buttons.get(i).action().equals(name)) {
                    f.focus = f.fields.size() + (f.options.isEmpty() ? 0 : 1) + i;
                }
            }
            return press(f, name);
        }
        return null;
    }

    private String press(Form f, String action) {
        if (action.equals("reveal")) {
            f.reveal = !f.reveal;
            return null;
        }
        return action;
    }

    private void confirmLeave() {
        boolean leave = "leave".equals(showModal(new Modal("Leave setup?",
                List.of("Nothing more will be changed. Settings already saved are kept, and running setup again "
                        + "continues where you left off."),
                List.of(new Button("stay", "Keep going", false), new Button("leave", "Leave setup", true)),
                Style.of(Palette.WARNING, Palette.SURFACE).strong())));
        if (leave) {
            synchronized (this) {
                cancelled = true;
            }
            throw new SetupException("Setup was cancelled. Run it again to continue where you left off.");
        }
    }

    /**
     * Shows a dialog and waits for one of its buttons. The first button has the focus and is
     * what Escape picks, so the safe choice ("Keep going") is never one stray Enter away.
     */
    private String showModal(Modal m) {
        synchronized (this) {
            modal = m;
            modalFocus = 0;
            repaint();
        }
        try {
            while (true) {
                Input input = device.next(TICK_MS);
                synchronized (this) {
                    String chosen = null;
                    if (input instanceof Click click) {
                        chosen = regions.stream().filter(r -> r.action().startsWith("modal:") && r.contains(click.x(), click.y()))
                                .map(r -> r.action().substring("modal:".length())).findFirst().orElse(null);
                    } else if (input instanceof Key key) {
                        switch (key.kind()) {
                            case LEFT, BACK_TAB -> modalFocus = Math.max(0, modalFocus - 1);
                            case RIGHT, TAB -> modalFocus = Math.min(m.buttons().size() - 1, modalFocus + 1);
                            case ENTER -> chosen = m.buttons().get(modalFocus).action();
                            case ESCAPE -> chosen = m.buttons().getFirst().action();
                            default -> {
                                // ignored in a dialog
                            }
                        }
                    }
                    if (chosen != null) {
                        return chosen;
                    }
                    repaint();
                }
            }
        } finally {
            synchronized (this) {
                modal = null;
                repaint();
            }
        }
    }

    private void tick() {
        while (open) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                if (open && form == null && modal == null) {
                    spinner = (spinner + 1) % SPINNER.length;
                    repaint();
                }
            }
        }
    }

    // --- Drawing -------------------------------------------------------------------------------

    private void repaint() {
        if (!open) {
            return;
        }
        device.draw(render(device.width(), device.height()));
    }

    /** Draws the whole screen for the given size (package-private for tests). */
    synchronized TuiCanvas render(int width, int height) {
        regions.clear();
        TuiCanvas c = new TuiCanvas(width, height, BASE);
        if (width < TerminalDevice.MIN_WIDTH || height < TerminalDevice.MIN_HEIGHT) {
            String text = "Make the window at least " + TerminalDevice.MIN_WIDTH + "×" + TerminalDevice.MIN_HEIGHT;
            c.text(Math.max(0, (width - text.length()) / 2), height / 2, text, Style.of(Palette.MUTED, Palette.CANVAS), width);
            return c;
        }
        drawHeader(c);
        int bodyTop = 2;
        int bodyBottom = height - 2;
        int mainX = 1;
        if (!steps.isEmpty() && width >= 92) {
            // Wide enough for the longest step name when the window allows, never wider than 38.
            int longest = steps.stream().mapToInt(String::length).max().orElse(20);
            int sideWidth = Math.max(26, Math.min(longest + 7, Math.min(38, width / 3)));
            drawSteps(c, 1, bodyTop, sideWidth, bodyBottom - bodyTop);
            mainX = sideWidth + 2;
        }
        drawMain(c, mainX, bodyTop, width - mainX - 1, bodyBottom - bodyTop);
        drawFooter(c);
        if (modal != null) {
            drawModal(c, modal);
        }
        return c;
    }

    private void drawHeader(TuiCanvas c) {
        int w = c.width();
        Style bar = Style.of(Palette.TEXT, Palette.SURFACE);
        c.fill(0, 0, w, 1, bar);
        int x = c.text(1, 0, " ▞ ", Style.of(Palette.ACCENT, Palette.SURFACE).strong()) + 1;
        x += c.text(x, 0, product, bar.strong());
        c.text(x, 0, "  " + title, Style.of(Palette.MUTED, Palette.SURFACE), w / 2 - x);
        if (total > 0 && step >= 0) {
            int barWidth = Math.min(24, w / 5);
            int done = (int) Math.round(barWidth * (step + (form == null ? 0.5 : 0.25)) / total);
            String label = "Step " + (step + 1) + " of " + total + "  ";
            int start = w - barWidth - label.length() - 2;
            c.text(start, 0, label, Style.of(Palette.MUTED, Palette.SURFACE));
            c.text(start + label.length(), 0, "━".repeat(Math.max(0, done)), Style.of(Palette.ACCENT, Palette.SURFACE));
            c.text(start + label.length() + done, 0, "━".repeat(Math.max(0, barWidth - done)), Style.of(Palette.BORDER_STRONG, Palette.SURFACE));
        }
    }

    private void drawSteps(TuiCanvas c, int x, int y, int w, int h) {
        c.fill(x, y, w, h, PANEL);
        c.box(x, y, w, h, BORDER, "Steps", MUTED.strong());
        int row = y + 2;
        for (int i = 0; i < steps.size() && row < y + h - 1; i++, row++) {
            String name = steps.get(i).isEmpty() ? "…" : steps.get(i);
            Style style;
            String mark;
            if (i < step) {
                mark = "✓";
                style = Style.of(Palette.SUCCESS, Palette.SURFACE);
            } else if (i == step) {
                mark = "●";
                style = ACCENT.strong();
                c.fill(x + 1, row, w - 2, 1, Style.of(Palette.TEXT, Palette.ACCENT_SOFT));
            } else {
                mark = "○";
                style = SUBTLE;
            }
            Style text = i == step ? Style.of(Palette.TEXT, Palette.ACCENT_SOFT).strong() : i < step ? MUTED : SUBTLE;
            c.text(x + 2, row, mark, i == step ? style.on(Palette.ACCENT_SOFT) : style);
            c.text(x + 4, row, name, text, w - 6);
        }
    }

    private void drawMain(TuiCanvas c, int x, int y, int w, int h) {
        c.fill(x, y, w, h, PANEL);
        c.box(x, y, w, h, BORDER, null, null);
        int innerX = x + 3;
        int innerW = w - 6;
        String heading = step >= 0 && step < steps.size() && !steps.get(step).isEmpty() ? steps.get(step) : title;
        c.text(innerX, y + 1, heading, PANEL.strong(), innerW);
        c.text(innerX, y + 2, "━━ ━", ACCENT);

        int cardHeight = form == null ? 3 : cardHeight(form, innerW);
        int cardTop = y + h - 1 - cardHeight;
        int logTop = y + 4;
        drawLog(c, innerX, logTop, innerW, cardTop - logTop - 1);
        if (form == null) {
            drawBusy(c, innerX, cardTop + 1, innerW);
        } else {
            drawCard(c, x + 2, cardTop, w - 4, cardHeight, form);
        }
    }

    private void drawLog(TuiCanvas c, int x, int y, int w, int h) {
        if (h <= 0) {
            return;
        }
        List<String[]> lines = new ArrayList<>();
        for (Entry entry : transcript) {
            if (entry.step() != step) {
                continue;
            }
            String mark = switch (entry.kind()) {
                case OK -> "✓";
                case WARN -> "!";
                case FAIL -> "✗";
                case INFO -> "·";
                case LINE, BLANK -> " ";
            };
            List<String> wrapped = wrap(entry.text(), w - 3);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new String[] {i == 0 ? mark : " ", wrapped.get(i), entry.kind().name()});
            }
        }
        int maxScroll = Math.max(0, lines.size() - h);
        logScroll = Math.min(logScroll, maxScroll);
        int start = Math.max(0, lines.size() - h - logScroll);
        for (int i = 0; i < h && start + i < lines.size(); i++) {
            String[] line = lines.get(start + i);
            Out.Kind kind = Out.Kind.valueOf(line[2]);
            Style mark = switch (kind) {
                case OK -> Style.of(Palette.SUCCESS, Palette.SURFACE);
                case WARN -> Style.of(Palette.WARNING, Palette.SURFACE);
                case FAIL -> Style.of(Palette.DANGER, Palette.SURFACE);
                default -> SUBTLE;
            };
            Style text = switch (kind) {
                case FAIL -> Style.of(Palette.DANGER, Palette.SURFACE);
                case WARN -> Style.of(Palette.WARNING, Palette.SURFACE);
                case INFO -> MUTED;
                default -> PANEL;
            };
            c.text(x, y + i, line[0], mark.strong());
            c.text(x + 2, y + i, line[1], text, w - 2);
        }
        if (start > 0) {
            c.text(x + w - 12, y, " ↑ earlier ", Style.of(Palette.SUBTLE, Palette.SURFACE_MUTED), 12);
        }
        if (logScroll > 0) {
            c.text(x + w - 12, y + h - 1, " ↓ latest ", Style.of(Palette.SUBTLE, Palette.SURFACE_MUTED), 12);
        }
    }

    private void drawBusy(TuiCanvas c, int x, int y, int w) {
        long seconds = (System.currentTimeMillis() - busySince) / 1000;
        c.text(x, y, SPINNER[spinner], ACCENT.strong());
        c.text(x + 2, y, seconds >= 2 ? "Working… " + seconds + " s" : "Working…", MUTED, w - 2);
    }

    private static int cardHeight(Form f, int width) {
        int questionLines = Math.min(3, wrap(f.question, width - 4).size());
        int body = switch (f.kind) {
            case TEXT, SECRET -> f.fields.size() * 3 + (int) f.fields.stream().filter(fl -> fl.label != null).count();
            case CHOICE -> Math.min(f.options.size(), 8);
            case YES_NO -> 0;
        };
        return 2 + questionLines + 1 + body + 1 + 1 + 1;
    }

    private void drawCard(TuiCanvas c, int x, int y, int w, int h, Form f) {
        Style card = Style.of(Palette.TEXT, Palette.SURFACE_MUTED);
        c.fill(x, y, w, h, card);
        c.box(x, y, w, h, Style.of(Palette.BORDER_STRONG, Palette.SURFACE_MUTED), null, null);
        int innerX = x + 2;
        int innerW = w - 4;
        int row = y + 1;
        for (String line : wrap(f.question, innerW).stream().limit(3).toList()) {
            c.text(innerX, row++, line, card.strong(), innerW);
        }
        row++;
        switch (f.kind) {
            case TEXT, SECRET -> {
                for (int i = 0; i < f.fields.size(); i++) {
                    Field field = f.fields.get(i);
                    if (field.label != null) {
                        c.text(innerX, row++, field.label, Style.of(Palette.MUTED, Palette.SURFACE_MUTED), innerW);
                    }
                    drawField(c, innerX, row, innerW, field, i, f.focus == i, f.reveal);
                    row += 3;
                }
            }
            case CHOICE -> row = drawOptions(c, innerX, row, innerW, f);
            case YES_NO -> {
                // the answer buttons are the controls
            }
        }
        if (f.error != null && f.kind != FormKind.YES_NO) {
            c.text(innerX, row, "✗ " + f.error, Style.of(Palette.DANGER, Palette.SURFACE_MUTED), innerW);
        }
        drawButtons(c, innerX, x + w - 2, y + h - 2, f);
    }

    private void drawField(TuiCanvas c, int x, int y, int w, Field field, int index, boolean focused, boolean reveal) {
        Style border = Style.of(focused ? Palette.ACCENT : Palette.BORDER_STRONG, Palette.SURFACE_MUTED);
        c.fill(x + 1, y + 1, w - 2, 1, Style.of(Palette.TEXT, Palette.SUNKEN));
        c.box(x, y, w, 3, border, null, null);
        int visible = w - 4;
        String text = field.masked && !reveal ? "•".repeat(field.value.length()) : field.value.toString();
        int offset = Math.max(0, field.cursor - visible + 1);
        String shown = text.substring(Math.min(offset, text.length()), Math.min(text.length(), offset + visible));
        c.text(x + 2, y + 1, shown, Style.of(Palette.TEXT, Palette.SUNKEN), visible);
        regions.add(new Region(x, y, w, 3, "field:" + index + ":" + (x + 2) + ":" + offset));
        if (focused) {
            c.cursor(x + 2 + field.cursor - offset, y + 1);
        }
    }

    private int drawOptions(TuiCanvas c, int x, int y, int w, Form f) {
        int visible = Math.min(f.options.size(), 8);
        if (f.selected < f.listTop) {
            f.listTop = f.selected;
        } else if (f.selected >= f.listTop + visible) {
            f.listTop = f.selected - visible + 1;
        }
        for (int i = 0; i < visible; i++) {
            int index = f.listTop + i;
            boolean selected = index == f.selected;
            Style row = selected
                    ? Style.of(Palette.TEXT, f.listFocused() ? Palette.ACCENT_SOFT : Palette.HOVER)
                    : Style.of(Palette.MUTED, Palette.SURFACE_MUTED);
            c.fill(x, y + i, w, 1, row);
            c.text(x + 1, y + i, selected ? "◉" : "○", selected ? Style.of(Palette.ACCENT, row.bg()).strong() : row);
            c.text(x + 3, y + i, (index + 1) + "  " + f.options.get(index)[1], selected ? row.strong() : row, w - 4);
            regions.add(new Region(x, y + i, w, 1, "option:" + index));
        }
        return y + visible;
    }

    /** Actions are right-aligned (primary last); the show/hide toggle sits at the start of the row. */
    private void drawButtons(TuiCanvas c, int left, int right, int y, Form f) {
        int x = right;
        for (int i = f.buttons.size() - 1; i >= 0; i--) {
            Button b = f.buttons.get(i);
            boolean toggle = b.action().equals("reveal");
            String text = "  " + (toggle ? (f.reveal ? "Hide" : "Show") : b.label()) + "  ";
            boolean focused = f.focusedButton() == b;
            Style style = b.primary()
                    ? Style.of(Palette.ON_ACCENT, Palette.ACCENT).strong()
                    : Style.of(Palette.TEXT, focused ? Palette.BORDER_STRONG : Palette.HOVER);
            if (focused) {
                style = style.underlined();
            }
            if (toggle) {
                drawButton(c, left, y, text, style, b.action());
                continue;
            }
            x -= text.length();
            drawButton(c, x, y, text, style, b.action());
            x -= 2;
        }
    }

    private void drawButton(TuiCanvas c, int x, int y, String text, Style style, String action) {
        c.text(x, y, text, style);
        regions.add(new Region(x, y, text.length(), 1, "button:" + action));
    }

    private void drawFooter(TuiCanvas c) {
        int y = c.height() - 1;
        Style bar = Style.of(Palette.SUBTLE, Palette.CANVAS);
        c.fill(0, y, c.width(), 1, bar);
        String hints;
        if (modal != null) {
            hints = "←/→ choose  ·  Enter confirm  ·  Esc back";
        } else if (form == null) {
            hints = "Please wait…";
        } else {
            hints = switch (form.kind) {
                case TEXT -> "Enter continue  ·  Tab next  ·  Esc cancel";
                case SECRET -> "Enter continue  ·  Tab next  ·  Esc cancel  ·  input hidden";
                case CHOICE -> "↑/↓ or 1–9 choose  ·  Enter continue  ·  Esc cancel";
                case YES_NO -> "Y yes  ·  N no  ·  ←/→ and Enter  ·  Esc cancel";
            };
            hints += "  ·  PgUp/PgDn activity";
        }
        if (device.mouse() && form != null) {
            hints += "  ·  click to choose";
        }
        c.text(2, y, hints, bar, c.width() - 4);
    }

    private void drawModal(TuiCanvas c, Modal m) {
        int w = Math.min(64, c.width() - 8);
        List<String> body = new ArrayList<>();
        for (String line : m.lines()) {
            body.addAll(wrap(line, w - 6));
        }
        int h = Math.min(c.height() - 4, body.size() + 7);
        int x = (c.width() - w) / 2;
        int y = (c.height() - h) / 2;
        Style panel = Style.of(Palette.TEXT, Palette.SURFACE);
        c.fill(x, y, w, h, panel);
        c.box(x, y, w, h, Style.of(Palette.ACCENT, Palette.SURFACE), null, null);
        c.text(x + 3, y + 1, m.title(), m.accent(), w - 6);
        for (int i = 0; i < body.size() && i < h - 6; i++) {
            c.text(x + 3, y + 3 + i, body.get(i), Style.of(Palette.MUTED, Palette.SURFACE), w - 6);
        }
        int bx = x + w - 3;
        for (int i = m.buttons().size() - 1; i >= 0; i--) {
            Button b = m.buttons().get(i);
            String text = "  " + b.label() + "  ";
            bx -= text.length();
            Style style = b.primary() ? Style.of(Palette.ON_ACCENT, Palette.ACCENT).strong() : Style.of(Palette.TEXT, Palette.HOVER);
            if (i == modalFocus) {
                style = style.underlined();
            }
            c.text(bx, y + h - 2, text, style);
            regions.add(new Region(bx, y + h - 2, text.length(), 1, "modal:" + b.action()));
            bx -= 2;
        }
    }

    /** Word-wraps {@code text} to {@code width}, breaking long words. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (width <= 0) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() + word.length() + (line.isEmpty() ? 0 : 1) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
        return lines;
    }
}
