package com.healthplatform.identity.domain;

import com.healthplatform.common.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Identity record. Deliberately thin: this service owns credentials and roles only.
 * Clinical/demographic data lives in patient-service / doctor-service and is linked
 * by this same UUID (shared identity across bounded contexts).
 *
 * email is encrypted at rest (EncryptedStringConverter, AES-256-GCM). The ciphertext is
 * nondeterministic, so email can't be queried by equality; emailHash is a deterministic
 * HMAC-SHA256 blind index of the lowercased address (BlindIndexHasher, its own key) and is
 * what AuthService queries on for the registration uniqueness check and for login. The unique
 * constraint moved from email to email_hash in the V2 migration.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String email;

    @Column(name = "email_hash", nullable = false, unique = true)
    private String emailHash;

    @Column(nullable = false)
    private String passwordHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean accountLocked = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected User() {}

    public User(String email, String emailHash, String passwordHash, Set<Role> roles) {
        this.email = email;
        this.emailHash = emailHash;
        this.passwordHash = passwordHash;
        this.roles = roles;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getEmailHash() { return emailHash; }
    public String getPasswordHash() { return passwordHash; }
    public Set<Role> getRoles() { return roles; }
    public boolean isEnabled() { return enabled; }
    public boolean isAccountLocked() { return accountLocked; }
    public Instant getCreatedAt() { return createdAt; }

    public void lock() { this.accountLocked = true; }
    public void unlock() { this.accountLocked = false; }
    public void disable() { this.enabled = false; }
}
