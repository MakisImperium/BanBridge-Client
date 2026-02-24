package org.banbridge.stats;

import cn.nukkit.utils.Logger;
import org.banbridge.api.StatsBatchRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects per-player deltas and can flush them as a batch to the backend.
 *
 * Thread-safety:
 * - Uses concurrent maps + LongAdder.
 * - drainBatch() is safe to call periodically; it uses sumThenReset().
 */
public final class StatsAccumulator {

    private static final String UNKNOWN_NAME = "Unknown";

    private final Logger log;

    /**
     * Last known name for a given XUID (used for stats attribution and optional presence/offline naming).
     */
    private final Map<String, String> nameByXuid = new ConcurrentHashMap<>();

    private final Map<String, LongAdder> playtime = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> kills = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> deaths = new ConcurrentHashMap<>();

    public StatsAccumulator(Logger log) {
        this.log = log;
    }

    public void markOnline(String xuid, String name) {
        putNameIfPresent(xuid, name);
    }

    public void markOffline(String xuid) {
        // reserved for future per-session logic (currently no-op)
    }

    public void addPlaytimeDelta(String xuid, String name, long seconds) {
        if (xuid == null || seconds <= 0) return;
        putNameIfPresent(xuid, name);
        playtime.computeIfAbsent(xuid, k -> new LongAdder()).add(seconds);
    }

    public void addKillDelta(String xuid, String name, long delta) {
        if (xuid == null || delta <= 0) return;
        putNameIfPresent(xuid, name);
        kills.computeIfAbsent(xuid, k -> new LongAdder()).add(delta);
    }

    public void addDeathDelta(String xuid, String name, long delta) {
        if (xuid == null || delta <= 0) return;
        putNameIfPresent(xuid, name);
        deaths.computeIfAbsent(xuid, k -> new LongAdder()).add(delta);
    }

    /**
     * Best-effort last known name; returns "Unknown" if none exists.
     */
    public String getLastKnownNameOrUnknown(String xuid) {
        if (xuid == null) return UNKNOWN_NAME;
        String name = nameByXuid.get(xuid);
        if (name == null) return UNKNOWN_NAME;
        String t = name.trim();
        return t.isEmpty() ? UNKNOWN_NAME : t;
    }

    public StatsBatchRequest drainBatch() {
        List<StatsBatchRequest.PlayerDelta> players = new ArrayList<>();

        Set<String> xuids = new HashSet<>();
        xuids.addAll(playtime.keySet());
        xuids.addAll(kills.keySet());
        xuids.addAll(deaths.keySet());

        for (String xuid : xuids) {
            long pt = sumThenReset(playtime.get(xuid));
            long k = sumThenReset(kills.get(xuid));
            long d = sumThenReset(deaths.get(xuid));

            if (pt == 0 && k == 0 && d == 0) continue;

            String name = getLastKnownNameOrUnknown(xuid);
            players.add(new StatsBatchRequest.PlayerDelta(xuid, name, pt, k, d));
        }

        return new StatsBatchRequest(players);
    }

    public void requeue(StatsBatchRequest batch) {
        try {
            if (batch == null || batch.players() == null) return;

            for (StatsBatchRequest.PlayerDelta p : batch.players()) {
                if (p == null) continue;
                addPlaytimeDelta(p.xuid(), p.name(), p.playtimeDeltaSeconds());
                addKillDelta(p.xuid(), p.name(), p.killsDelta());
                addDeathDelta(p.xuid(), p.name(), p.deathsDelta());
            }
        } catch (Exception e) {
            log.warning("Failed to requeue stats batch: " + e.getMessage());
        }
    }

    private void putNameIfPresent(String xuid, String name) {
        if (xuid == null) return;
        if (name == null) return;

        String t = name.trim();
        if (t.isEmpty()) return;

        nameByXuid.put(xuid, t);
    }

    private static long sumThenReset(LongAdder adder) {
        if (adder == null) return 0;
        return adder.sumThenReset();
    }
}