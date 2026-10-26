package com.jtac.identity.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /**
     * Case-insensitive lookup. The input is normalized by PostgreSQL with exactly the
     * expression that maintains the generated column {@code email_normalized}
     * ({@code lower(btrim(email))}), so Java and SQL case mapping can never disagree.
     */
    @Query("select u from UserAccount u where u.emailNormalized = lower(trim(:email))")
    Optional<UserAccount> findByEmail(@Param("email") String email);

    boolean existsByIdAndStatus(UUID id, UserAccount.Status status);

    boolean existsByHandle(String handle);

    List<UserAccount> findByHandleIn(Collection<String> handles);

    /**
     * Records a sign-in without touching the optimistic-lock version, so concurrent
     * sign-ins of the same user (e.g. two tabs) cannot fail each other.
     */
    @Modifying
    @Query("update UserAccount u set u.lastLoginAt = :at, u.updatedAt = :at where u.id = :id")
    int recordLogin(@Param("id") UUID id, @Param("at") Instant at);

    /** Rehashes a password with the current encoder after a successful sign-in. */
    @Modifying
    @Query("update UserAccount u set u.passwordHash = :hash where u.emailNormalized = lower(trim(:email))")
    int upgradePasswordHash(@Param("email") String email, @Param("hash") String hash);
}
