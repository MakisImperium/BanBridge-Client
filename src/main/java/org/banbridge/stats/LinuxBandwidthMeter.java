package org.banbridge.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux (Ubuntu/Debian/etc.) bandwidth meter based on /proc/net/dev.
 *
 * Produces rxKbps/txKbps (KBit/s):
 *   kbps = bytesPerSec * 8 / 1000
 *
 * Guarantee on Linux where /proc/net/dev is readable:
 * - Always returns non-negative numbers
 * - First successful sample returns 0.0 / 0.0 (no previous delta window)
 */
public final class LinuxBandwidthMeter implements BandwidthMeter {

    private final String preferredIface; // nullable => auto
    private Snapshot prev;

    public LinuxBandwidthMeter(String preferredIface) {
        this.preferredIface = (preferredIface == null || preferredIface.isBlank()) ? null : preferredIface.trim();
    }

    @Override
    public synchronized Result sampleKbps() {
        Snapshot now = readSnapshot(preferredIface);
        if (now == null) return new Result(null, null);

        if (prev == null) {
            prev = now;
            return new Result(0.0, 0.0);
        }

        long dtNanos = now.nanoTime - prev.nanoTime;
        double seconds = Math.max(0.001, dtNanos / 1_000_000_000.0);

        long dRx = now.rxBytes - prev.rxBytes;
        long dTx = now.txBytes - prev.txBytes;

        prev = now;

        if (dRx < 0 || dTx < 0) return new Result(0.0, 0.0);

        double rxKbps = (dRx / seconds) * 8.0 / 1000.0;
        double txKbps = (dTx / seconds) * 8.0 / 1000.0;

        if (rxKbps < 0) rxKbps = 0.0;
        if (txKbps < 0) txKbps = 0.0;

        return new Result(rxKbps, txKbps);
    }

    private static Snapshot readSnapshot(String preferredIface) {
        Path p = Path.of("/proc/net/dev");
        if (!Files.exists(p)) return null;

        try {
            for (String line : Files.readAllLines(p)) {
                if (!line.contains(":")) continue;

                String[] parts = line.trim().split(":");
                if (parts.length != 2) continue;

                String iface = parts[0].trim();
                if ("lo".equals(iface)) continue;

                if (preferredIface != null && !preferredIface.equals(iface)) continue;

                String[] cols = parts[1].trim().split("\\s+");
                if (cols.length < 16) continue;

                long rxBytes = parseLongSafe(cols[0]);
                long txBytes = parseLongSafe(cols[8]);
                if (rxBytes < 0 || txBytes < 0) continue;

                return new Snapshot(System.nanoTime(), rxBytes, txBytes);
            }

            if (preferredIface != null) return readSnapshot(null);
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private record Snapshot(long nanoTime, long rxBytes, long txBytes) {}
}