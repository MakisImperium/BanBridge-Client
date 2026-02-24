package org.banbridge.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandsPollResponse(
        String serverKey, // backend may include this at root
        String serverTime,
        List<ServerCommand> commands
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerCommand(
            long id,
            String serverKey,
            String cmdType,      // SHUTDOWN / REFRESH_BANS
            String payloadJson,  // nullable
            String createdAt
    ) {}
}
