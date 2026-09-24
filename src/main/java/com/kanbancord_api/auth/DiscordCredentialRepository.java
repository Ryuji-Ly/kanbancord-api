package com.kanbancord_api.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DiscordCredentialRepository extends JpaRepository<DiscordCredential, Long> {

    /** Locked, so concurrent requests do not both spend the same single-use refresh token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DiscordCredential c where c.userId = :userId")
    Optional<DiscordCredential> findForUpdate(@Param("userId") Long userId);
}
