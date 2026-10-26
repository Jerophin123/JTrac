package com.jtrac.launcher;

/** A problem the operator can fix; shown without a stack trace. */
class SetupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SetupException(String message) {
        super(message);
    }

    SetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
