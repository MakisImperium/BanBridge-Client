package org.banbridge.api;

import java.util.List;

/**
 * BackendBridge Presence Protocol (SNAPSHOT MODE).
 *
 * Request JSON:
 * {
 *   "serverKey": "survival-1",
 *   "snapshot": true,
 *   "players": [ { "xuid": "...", "name": "...", "online": true, "ip": "...", "hwid": "..." } ]
 * }
 *
 * In snapshot mode, the backend will mark any player not in the list as OFFLINE.
 */
public record PresenceRequest(
        String serverKey,
        boolean snapshot,
        List<PlayerPresence> players
) {
    public record PlayerPresence(
            String xuid,
            String name,
            Boolean online,
            String ip,
            String hwid
    ) {}
}
