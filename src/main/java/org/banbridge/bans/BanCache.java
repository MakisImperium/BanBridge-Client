package org.banbridge.bans;

import cn.nukkit.utils.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.banbridge.api.BanChangesResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BanCache {

    private final Path file;
    private final Logger log;
    private final ObjectMapper om = new ObjectMapper();

    private final Map<String, BanEntry> activeByXuid = new ConcurrentHashMap<>();
    private volatile String sinceCursor = "1970-01-01T00:00:00Z";

    public BanCache(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public String getSinceCursor() {
        return sinceCursor;
    }

    public Optional<BanEntry> findActiveBan(String xuid) {
        BanEntry e = activeByXuid.get(xuid);
        if (e == null) return Optional.empty();
        if (!e.isActiveAt(Instant.now())) {
            activeByXuid.remove(xuid);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public String buildKickMessage(BanEntry ban) {
        String base = "You are banned.\nReason: " + safe(ban.reason());
        if (ban.expiresAt() != null) {
            base += "\nExpires: " + ban.expiresAt();
        } else {
            base += "\nDuration: Permanent";
        }
        return base;
    }

    public void loadFromDisk() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) return;

            CacheFile cf = om.readValue(Files.readString(file), CacheFile.class);
            this.sinceCursor = (cf.sinceCursor != null && !cf.sinceCursor.isBlank()) ? cf.sinceCursor : this.sinceCursor;

            activeByXuid.clear();
            Instant now = Instant.now();
            if (cf.entries != null) {
                for (BanEntry e : cf.entries) {
                    if (e != null && e.xuid() != null && e.isActiveAt(now)) {
                        activeByXuid.put(e.xuid(), e);
                    }
                }
            }
            log.info("Loaded ban cache: " + activeByXuid.size() + " active bans");
        } catch (Exception e) {
            log.warning("Failed to load ban cache: " + e.getMessage());
        }
    }

    public void saveToDiskAtomic() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");

            CacheFile cf = new CacheFile();
            cf.sinceCursor = this.sinceCursor;
            cf.entries = new ArrayList<>(activeByXuid.values());

            String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(cf);
            Files.writeString(tmp, json);

            try {
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warning("Failed to save ban cache: " + e.getMessage());
        }
    }

    /**
     * Backend contract says:
     * after processing changes set since = max(updatedAt) and persist it.
     */
    public ApplyResult applyChanges(BanChangesResponse resp) {
        if (resp == null) return new ApplyResult(false, List.of());

        boolean changed = false;
        List<BanEntry> newlyBanned = new ArrayList<>();
        Instant now = Instant.now();

        Instant maxUpdatedAt = null;

        if (resp.changes() != null) {
            for (BanChangesResponse.BanChange c : resp.changes()) {
                if (c == null || c.xuid() == null) continue;

                Instant updatedAt = parseInstant(c.updatedAt());
                if (updatedAt != null && (maxUpdatedAt == null || updatedAt.isAfter(maxUpdatedAt))) {
                    maxUpdatedAt = updatedAt;
                }

                BanEntry entry = new BanEntry(
                        c.banId(),
                        c.xuid(),
                        c.reason(),
                        parseInstant(c.createdAt()),
                        parseInstant(c.expiresAt()),
                        parseInstant(c.revokedAt()),
                        updatedAt
                );

                boolean isActive = entry.isActiveAt(now);

                if (isActive) {
                    BanEntry prev = activeByXuid.put(entry.xuid(), entry);
                    if (prev == null) newlyBanned.add(entry);
                    changed = true;
                } else {
                    if (activeByXuid.remove(entry.xuid()) != null) {
                        changed = true;
                    }
                }
            }
        }

        // local cleanup of expired bans
        for (Iterator<Map.Entry<String, BanEntry>> it = activeByXuid.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, BanEntry> e = it.next();
            if (!e.getValue().isActiveAt(now)) {
                it.remove();
                changed = true;
            }
        }

        if (maxUpdatedAt != null) {
            sinceCursor = maxUpdatedAt.toString();
        }

        return new ApplyResult(changed, newlyBanned);
    }

    private static Instant parseInstant(String s) {
        try {
            if (s == null || s.isBlank()) return null;
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "N/A" : s;
    }

    public record ApplyResult(boolean changed, List<BanEntry> newlyBanned) {}

    public static final class CacheFile {
        public String sinceCursor;
        public List<BanEntry> entries;
    }
}