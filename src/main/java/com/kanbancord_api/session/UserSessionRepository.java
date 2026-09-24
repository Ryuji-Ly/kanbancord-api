package com.kanbancord_api.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** Locked, so two refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserSession> findByPreviousRefreshTokenHash(String previousRefreshTokenHash);

    @Query("select s from UserSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now "
            + "order by s.lastUsedAt desc")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("select count(s) > 0 from UserSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now")
    boolean existsActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from UserSession s where s.expiresAt < :cutoff or s.revokedAt < :cutoff")
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}
