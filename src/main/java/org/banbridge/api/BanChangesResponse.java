package org.banbridge.api;

import java.util.List;

public record BanChangesResponse(
        String serverTime,
        List<BanChange> changes
) {
    public record BanChange(
            String type,      // BAN_UPSERT / BAN_REVOKE
            long banId,
            String xuid,
            String reason,
            String createdAt,
            String expiresAt,
            String revokedAt,
            String updatedAt
    ) {}
}
