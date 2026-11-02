package com.jtac.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_external_identity")
class UserExternalIdentity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(nullable = false)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected UserExternalIdentity() {
    }

    UserExternalIdentity(UUID id, UUID userId, String provider, String subjectId, String email, Instant now) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subjectId = subjectId;
        this.email = email;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    /** Records a sign-in; the email follows the provider's current claim. */
    void recordLogin(String email, Instant now) {
        this.email = email;
        this.lastLoginAt = now;
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
    }

    String provider() {
        return provider;
    }

    String subjectId() {
        return subjectId;
    }

    String email() {
        return email;
    }

    Instant lastLoginAt() {
        return lastLoginAt;
    }
}

