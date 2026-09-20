package com.pms.auth.core.token;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashFetchUser(@Param("tokenHash") String tokenHash);

    /**
     * Atomically claims a token for rotation or logout: only a row that is still unrevoked and
     * unexpired is stamped. Zero rows updated means the token was unknown, expired, or already
     * claimed by another request, so the caller must treat that as invalid rather than retry.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.tokenHash = :tokenHash "
            + "AND rt.revokedAt IS NULL AND rt.expiresAt > :now")
    int revokeIfValid(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}