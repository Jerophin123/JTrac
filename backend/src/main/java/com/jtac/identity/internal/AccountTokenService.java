package com.jtac.identity.internal;

import com.jtac.shared.Ids;
import com.jtac.shared.Tokens;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.ProblemTypes;
import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use, expiring account tokens. Only the SHA-256 hash is stored; issuing a new
 * token of a purpose revokes the user's previous unused tokens of that purpose.
 */
@Component
class AccountTokenService {

    enum Purpose { PASSWORD_RESET, EMAIL_VERIFICATION }

    private final JdbcClient jdbc;
    private final Clock clock;

    AccountTokenService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    String issue(UUID userId, Purpose purpose, Duration ttl) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        jdbc.sql("UPDATE account_token SET consumed_at = :now WHERE user_id = :user AND purpose = :purpose AND consumed_at IS NULL")
                .param("now", now).param("user", userId).param("purpose", purpose.name()).update();
        String token = Tokens.newToken();
        jdbc.sql("""
                INSERT INTO account_token (id, user_id, purpose, token_hash, expires_at, created_at)
                VALUES (:id, :user, :purpose, :hash, :expires, :now)
                """)
                .param("id", Ids.newId()).param("user", userId).param("purpose", purpose.name())
                .param("hash", Tokens.sha256Hex(token)).param("expires", now.plus(ttl)).param("now", now)
                .update();
        return token;
    }

    /** Consumes a valid token atomically and returns its user, or fails with {@code invalid-token}. */
    @Transactional
    UUID consume(String token, Purpose purpose) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new InvalidToken();
        }
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        return jdbc.sql("""
                UPDATE account_token SET consumed_at = :now
                 WHERE token_hash = :hash AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
                RETURNING user_id
                """)
                .param("now", now).param("hash", Tokens.sha256Hex(token)).param("purpose", purpose.name())
                .query(UUID.class).optional()
                .orElseThrow(InvalidToken::new);
    }

    static final class InvalidToken extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        InvalidToken() {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_TOKEN, "Invalid or expired link",
                    "This link is invalid, has expired, or has already been used. Request a new one.");
        }
    }
}
