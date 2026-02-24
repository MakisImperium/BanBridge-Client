package org.banbridge.api;

public record BanReportRequest(
        String serverKey,
        BanPayload ban
) {
    public record BanPayload(
            String xuid,
            String reason,
            Long durationSeconds,   // nullable (permanent if null)
            String ip,              // nullable
            String hwid,            // nullable
            String executedAtIso    // nullable (Instant ISO-8601)
    ) {}
}