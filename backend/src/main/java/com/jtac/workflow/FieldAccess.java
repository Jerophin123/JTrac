package com.jtac.workflow;

/** What a role may do with a field in a status. Ordered from least to most permissive (by visibility). */
public enum FieldAccess {
    HIDDEN, READ_ONLY, REQUIRED, OPTIONAL;

    public boolean visible() {
        return this != HIDDEN;
    }

    public boolean editable() {
        return this == REQUIRED || this == OPTIONAL;
    }
}
