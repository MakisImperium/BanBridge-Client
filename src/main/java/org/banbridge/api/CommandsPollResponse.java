package org.banbridge.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Backend contract for command polling.
 *
 * Canonical expected response shape:
 * {
 *   "serverKey": "survival-1",
 *   "serverTime": "2026-03-13T12:34:56Z",
 *   "commands": [
 *     {
 *       "id": 123,
 *       "serverKey": "survival-1",
 *       "cmdType": "KICK",
 *       "payloadJson": "{\"xuid\":\"1234567890\",\"reason\":\"Test\"}",
 *       "createdAt": "2026-03-13T12:34:00Z"
 *     }
 *   ]
 * }
 *
 * Compatibility:
 * - The client also accepts legacy backend responses that send `type` instead of `cmdType`.
 *
 * Supported command types on the client:
 * - SHUTDOWN
 * - REFRESH_BANS
 * - KICK
 * - MESSAGE
 * - BROADCAST
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandsPollResponse(
        String serverKey,
        String serverTime,
        List<ServerCommand> commands
) {
    /**
     * A single backend-issued server command.
     *
     * payloadJson by type:
     *
     * SHUTDOWN:
     * - payloadJson may be null/empty
     *
     * REFRESH_BANS:
     * - payloadJson may be null/empty
     *
     * KICK:
     * {
     *   "xuid": "player-xuid",
     *   "reason": "kick reason"
     * }
     *
     * MESSAGE:
     * {
     *   "xuid": "player-xuid",
     *   "message": "message text"
     * }
     *
     * BROADCAST:
     * {
     *   "message": "message text"
     * }
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerCommand(
            long id,
            String serverKey,
            @JsonAlias("type") String cmdType,
            String payloadJson,
            String createdAt
    ) {
    }
}
