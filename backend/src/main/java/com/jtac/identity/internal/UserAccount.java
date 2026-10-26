package com.jtac.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account")
class UserAccount {

    enum Status { ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    /** Maintained by the database (generated column); used for case-insensitive lookup. */
    @Column(name = "email_normalized", insertable = false, updatable = false)
    private String emailNormalized;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String handle;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** May create organizations (server-wide administration). */
    @Column(name = "server_admin", nullable = false)
    private boolean serverAdmin;

    @Version
    private long version;

    protected UserAccount() {
        // for JPA
    }

    UserAccount(UUID id, String email, String displayName, String handle, String passwordHash, Instant now,
            boolean verified) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.handle = handle;
        this.passwordHash = passwordHash;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.emailVerifiedAt = verified ? now : null;
    }

    void rename(String displayName, String handle, Instant now) {
        this.displayName = displayName;
        this.handle = handle;
        this.updatedAt = now;
    }

    void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    void setServerAdmin(boolean serverAdmin, Instant now) {
        if (this.serverAdmin != serverAdmin) {
            this.serverAdmin = serverAdmin;
            this.updatedAt = now;
        }
    }

    void markVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            updatedAt = now;
        }
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String displayName() {
        return displayName;
    }

    String handle() {
        return handle;
    }

    String passwordHash() {
        return passwordHash;
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    boolean isServerAdmin() {
        return serverAdmin;
    }

    boolean isVerified() {
        return emailVerifiedAt != null;
    }

    long version() {
        return version;
    }
}
