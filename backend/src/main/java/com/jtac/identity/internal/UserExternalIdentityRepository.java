package com.jtac.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserExternalIdentityRepository extends JpaRepository<UserExternalIdentity, UUID> {

    Optional<UserExternalIdentity> findByProviderAndSubjectId(String provider, String subjectId);

    List<UserExternalIdentity> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}

