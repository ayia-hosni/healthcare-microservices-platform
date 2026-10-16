package com.healthplatform.identity.repository;

import com.healthplatform.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Lookups go through emailHash: email is encrypted at rest and can't be matched by equality.
 * emailHash is a deterministic blind index (see User) that AuthService computes before calling.
 */
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailHash(String emailHash);
    boolean existsByEmailHash(String emailHash);
}
