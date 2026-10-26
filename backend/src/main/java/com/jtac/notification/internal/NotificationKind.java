package com.jtac.notification.internal;

enum NotificationKind {
    ASSIGNED(true), MENTIONED(true), COMMENTED(false), STATUS_CHANGED(false), UPDATED(false);

    private final boolean emailByDefault;

    NotificationKind(boolean emailByDefault) {
        this.emailByDefault = emailByDefault;
    }

    boolean emailByDefault() {
        return emailByDefault;
    }
}
