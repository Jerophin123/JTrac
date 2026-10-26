package com.jtac.identity.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Ends a user's persisted sessions, e.g. after a password change or reset. */
@Component
class SessionTerminator {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    SessionTerminator(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.sessions = sessions.getIfAvailable();
    }

    /**
     * Deletes every session of {@code email} except {@code keepSessionId} (may be null).
     *
     * @return the number of sessions ended
     */
    int endSessions(String email, String keepSessionId) {
        if (sessions == null) {
            return 0;
        }
        int ended = 0;
        for (String id : sessions.findByPrincipalName(email).keySet()) {
            if (!id.equals(keepSessionId)) {
                sessions.deleteById(id);
                ended++;
            }
        }
        return ended;
    }
}
