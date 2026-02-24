package org.banbridge.api;

import java.util.List;

/**
 * Backend contract:
 * POST /api/server/stats/batch
 * Body: { "players": [ ... ] }
 *
 * serverKey is provided via header X-Server-Key
 */
public record StatsBatchRequest(
        List<PlayerDelta> players
) {
    public record PlayerDelta(
            String xuid,
            String name,
            long playtimeDeltaSeconds,
            long killsDelta,
            long deathsDelta
    ) {}
}