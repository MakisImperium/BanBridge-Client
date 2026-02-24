package org.banbridge.api;

import java.util.List;

/**
 * BackendBridge Presence Protocol (SNAPSHOT MODE).
 *
 * Request JSON:
 * {
 *   "snapshot": true,
 *   "players": [ { "xuid": "...", "name": "...", "online": true, "ip": "...", "hwid": "..." } ]
 * }
 *
 * In snapshot mode, the backend will mark any player not in the list as OFFLINE.
 */
public record PresenceRequest(
        boolean snapshot,
        List<PlayerPresence> players
) {
    public record PlayerPresence(
            String xuid,
            String name,     // optional
            Boolean online,  // optional in snapshot mode (default true); we send true explicitly
            String ip,       // optional
            String hwid      // optional
    ) {}
}
