package com.jtac.workflow;

public enum StatusCategory {
    TODO, IN_PROGRESS, DONE, CANCELED;

    /** Issues in DONE or CANCELED statuses are resolved (closed). */
    public boolean isResolved() {
        return this == DONE || this == CANCELED;
    }
}
