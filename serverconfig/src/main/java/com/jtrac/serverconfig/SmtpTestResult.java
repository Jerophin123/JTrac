package com.jtrac.serverconfig;

import java.util.List;

/**
 * The outcome of a test email, step by step.
 *
 * @param accepted true only when the SMTP server accepted the message for delivery (a
 *                 connection alone never counts)
 * @param summary  one sentence for the operator
 */
public record SmtpTestResult(boolean accepted, String summary, List<Step> steps) {

    public enum Status { OK, WARNING, FAILED, SKIPPED }

    /** One step, e.g. "Resolve smtp.example.com" with its result. */
    public record Step(String name, Status status, String detail) {
    }
}
