package com.jtac.issue;

public enum Priority {
    URGENT, HIGH, MEDIUM, LOW, NONE;

    /** Sort weight: urgent first. */
    public int weight() {
        return ordinal();
    }
}
