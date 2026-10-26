package com.jtac.field;

public enum FieldType {
    TEXT, LONG_TEXT, NUMBER, DATE, SELECT, MULTI_SELECT, USER, CHECKBOX, URL;

    public boolean hasOptions() {
        return this == SELECT || this == MULTI_SELECT;
    }
}
