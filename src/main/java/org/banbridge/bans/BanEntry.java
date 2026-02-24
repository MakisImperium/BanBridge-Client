package org.banbridge.bans;

import java.time.Instant;

public record BanEntry(
        long banId,
        String xuid,
        String reason,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant updatedAt
) {
    public boolean isActiveAt(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt == null) return true;
        return expiresAt.isAfter(now);
    }
}
