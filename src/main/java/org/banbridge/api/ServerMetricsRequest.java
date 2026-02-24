package org.banbridge.api;

/**
 * Backend contract:
 * POST /api/server/metrics
 *
 * Rules:
 * - serverKey is required and must never be empty.
 * - Unknown/unavailable values must be null (NOT -1).
 * - Never send negative numbers.
 */
public record ServerMetricsRequest(
        String serverKey,       // required, never empty
        Integer ramUsedMb,      // >= 0 or null
        Integer ramMaxMb,       // > 0 or null
        Double cpuLoad,         // >= 0, typically 0..1 (slightly above ok); else null
        Integer playersOnline,  // >= 0 (0 if none)
        Integer playersMax,     // >= 0 or null
        Double tps,             // >= 0 (clamped); null if not measurable
        Double rxKbps,          // >= 0; null if not measurable
        Double txKbps           // >= 0; null if not measurable
) {}