package org.banbridge.stats;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.util.List;
import java.util.Locale;

/**
 * Cross-platform bandwidth meter using OSHI.
 *
 * Compatible with OSHI versions where NetworkIF exposes getName() (not getIfName()).
 */
public final class OshiBandwidthMeter implements BandwidthMeter {

    private final HardwareAbstractionLayer hal;
    private Snapshot prev;

    public OshiBandwidthMeter() {
        this.hal = new SystemInfo().getHardware();
    }

    @Override
    public synchronized Result sampleKbps() {
        Snapshot now = readSnapshot();
        if (now == null) return new Result(null, null);

        if (prev == null) {
            prev = now;
            return new Result(0.0, 0.0);
        }

        double seconds = Math.max(0.001, (now.nanoTime - prev.nanoTime) / 1_000_000_000.0);

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

    private Snapshot readSnapshot() {
        try {
            List<NetworkIF> nifs = hal.getNetworkIFs();
            if (nifs == null || nifs.isEmpty()) return null;

            Totals filtered = sumNics(nifs, true);
            if (!filtered.any) {
                Totals all = sumNics(nifs, false);
                return new Snapshot(System.nanoTime(), all.rxBytes, all.txBytes);
            }

            return new Snapshot(System.nanoTime(), filtered.rxBytes, filtered.txBytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Totals sumNics(List<NetworkIF> nifs, boolean applyFilter) {
        long rx = 0L;
        long tx = 0L;
        boolean any = false;

        for (NetworkIF nif : nifs) {
            if (nif == null) continue;

            try {
                nif.updateAttributes();
            } catch (Throwable ignored) {
                // ignore
            }

            String name = safeLower(nif.getName()); // <-- compatible replacement for getIfName()
            String display = safeLower(nif.getDisplayName());

            if (applyFilter && shouldIgnore(name, display)) continue;

            long r = nif.getBytesRecv();
            long t = nif.getBytesSent();
            if (r < 0 || t < 0) continue;

            rx += r;
            tx += t;
            any = true;
        }

        return new Totals(rx, tx, any);
    }

    private static boolean shouldIgnore(String nameLower, String displayLower) {
        String s = (nameLower + " " + displayLower).trim();
        if (s.isEmpty()) return true;

        if (s.equals("lo") || s.startsWith("lo ") || s.contains("loopback")) return true;

        if (s.contains("docker") || s.startsWith("br-") || s.contains("veth")) return true;
        if (s.contains("vmware") || s.contains("virtualbox") || s.contains("hyper-v") || s.contains("vmswitch")) return true;
        if (s.contains("tunnel") || s.contains("isatap") || s.contains("teredo")) return true;
        if (s.contains("wireguard") || s.contains("tailscale") || s.contains("hamachi")) return true;
        if (s.contains("npcap") || s.contains("loopback adapter")) return true;

        return false;
    }

    private static String safeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private record Snapshot(long nanoTime, long rxBytes, long txBytes) {}

    private record Totals(long rxBytes, long txBytes, boolean any) {}
}