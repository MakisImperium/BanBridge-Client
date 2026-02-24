package org.banbridge.stats;

/**
 * Simple abstraction so the plugin can swap implementations per OS.
 */
public interface BandwidthMeter {
    Result sampleKbps();

    record Result(Double rxKbps, Double txKbps) {}
}
