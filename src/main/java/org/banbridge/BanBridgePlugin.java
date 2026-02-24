package org.banbridge;

import cn.nukkit.Player;
import cn.nukkit.command.ConsoleCommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerLoginEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.ServerCommandEvent;
import cn.nukkit.plugin.PluginBase;
import org.banbridge.api.BackendClient;
import org.banbridge.api.BanReportRequest;
import org.banbridge.api.CommandsPollResponse;
import org.banbridge.api.PresenceRequest;
import org.banbridge.api.ServerMetricsRequest;
import org.banbridge.bans.BanCache;
import org.banbridge.bans.BanEntry;
import org.banbridge.stats.BandwidthMeter;
import org.banbridge.stats.LinuxBandwidthMeter;
import org.banbridge.stats.OshiBandwidthMeter;
import org.banbridge.stats.StatsAccumulator;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BanBridgePlugin extends PluginBase implements Listener {

    // ----------------------------
    // Styling / Prefix (Nukkit color codes)
    // ----------------------------

    private static final String PREFIX = "§8[§bBanBridge§8]§r ";
    private static final String INFO = "§7";
    private static final String OK = "§a";
    private static final String WARN = "§e";
    private static final String ERR = "§c";
    private static final String ACCENT = "§b";
    private static final String DIM = "§8";

    // ----------------------------
    // State
    // ----------------------------

    private BackendClient backendClient;
    private BanCache banCache;
    private StatsAccumulator stats;

    private String serverKey;
    private Path banCachePath;

    private BandwidthMeter bandwidthMeter;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean warnedMissingServerKey = new AtomicBoolean(false);

    /**
     * Commands cursor: only advance after successful ACK to avoid losing commands.
     */
    private final AtomicLong commandsSinceId = new AtomicLong(0);

    // ----------------------------
    // Lifecycle
    // ----------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ---- config
        String baseUrl = getConfig().getString("api.baseUrl");
        this.serverKey = getConfig().getString("api.serverKey");
        String serverToken = getConfig().getString("api.serverToken");

        int bansPollSeconds = Math.max(3, getConfig().getInt("sync.bansPollSeconds", 10));
        int statsFlushSeconds = Math.max(10, getConfig().getInt("sync.statsFlushSeconds", 60));
        int metricsSeconds = Math.max(5, getConfig().getInt("sync.metricsSeconds", 15));

        // Presence heartbeat: protocol says 10-30 seconds. Default 15.
        int presenceSecondsCfg = getConfig().getInt("sync.presenceSeconds", 15);
        int presenceSeconds = clampInt(presenceSecondsCfg, 10, 30);

        int commandsPollSeconds = Math.max(2, getConfig().getInt("sync.commandsPollSeconds", 3));

        int httpMaxAttempts = Math.max(1, getConfig().getInt("sync.httpMaxAttempts", 4));
        long httpBaseBackoffMillis = Math.max(50L, getConfig().getLong("sync.httpBaseBackoffMillis", 250L));
        long httpMaxBackoffMillis = Math.max(httpBaseBackoffMillis, getConfig().getLong("sync.httpMaxBackoffMillis", 5000L));

        String bansFileName = getConfig().getString("cache.bansFile", "bans-cache.json");
        this.banCachePath = getDataFolder().toPath().resolve(bansFileName);

        // ---- backend client
        this.backendClient = new BackendClient(
                baseUrl,
                serverKey,
                serverToken,
                Duration.ofSeconds(10),
                httpMaxAttempts,
                httpBaseBackoffMillis,
                httpMaxBackoffMillis
        );

        // ---- local stores
        this.banCache = new BanCache(banCachePath, getLogger());
        this.stats = new StatsAccumulator(getLogger());
        banCache.loadFromDisk();

        // ---- events
        getServer().getPluginManager().registerEvents(this, this);

        // ---- bandwidth meter
        this.bandwidthMeter = createBandwidthMeter();
        if (bandwidthMeter == null) {
            logInfo("Metrics", "Bandwidth meter disabled. " + INFO + "rxKbps/txKbps will be " + ACCENT + "null" + INFO + ".");
        } else {
            logOk("Metrics", "Bandwidth meter enabled: " + ACCENT + bandwidthMeter.getClass().getSimpleName());
        }

        // ---- helpful config warning
        if (baseUrl != null && (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost"))) {
            logWarn("Config", "api.baseUrl points to localhost " + DIM + "(" + baseUrl + ")" + WARN + ". " +
                    INFO + "If backend is on another machine, set it to " + ACCENT + "http://<BACKEND_HOST>:<PORT>");
        }

        // ---- health check
        backendClient.healthCheckAsync(resOpt -> {
            if (resOpt.isEmpty()) {
                logErr("Backend", "Health check " + ERR + "FAILED" + INFO + ". baseUrl=" + ACCENT + safeInline(baseUrl));
                return;
            }
            BackendClient.HealthResponse res = resOpt.get();
            String db = (res.dbOk() == null) ? (WARN + "unknown") : (res.dbOk() ? (OK + "OK") : (ERR + "FAIL"));
            logOk("Backend", "Reachable. status=" + ACCENT + res.status()
                    + OK + " serverTime=" + ACCENT + res.serverTime()
                    + OK + " db=" + db);
        });

        // ----------------------------
        // Scheduled tasks
        // ----------------------------

        // 1) Ban changes poll (+ debug newly banned)
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            backendClient.fetchBanChangesAsync(banCache.getSinceCursor(), result -> {
                if (result.isEmpty()) return;

                BanCache.ApplyResult apply = banCache.applyChanges(result.get());
                if (!apply.changed()) return;

                banCache.saveToDiskAtomic();

                if (apply.newlyBanned() != null && !apply.newlyBanned().isEmpty()) {
                    for (BanEntry b : apply.newlyBanned()) {
                        if (b == null) continue;

                        String playerName = null;
                        Player online = (b.xuid() == null) ? null : findOnlineByXuid(b.xuid());
                        if (online != null) playerName = online.getName();

                        logWarn("BanSync", "NEW BAN " + DIM + "→ " + WARN +
                                "banId=" + ACCENT + b.banId() + WARN +
                                ", xuid=" + ACCENT + b.xuid() + WARN +
                                ", playerName=" + ACCENT + (playerName == null ? "n/a" : playerName) + WARN +
                                ", reason=" + ACCENT + safeInline(b.reason()) + WARN +
                                ", createdAt=" + ACCENT + b.createdAt() + WARN +
                                ", expiresAt=" + ACCENT + b.expiresAt() + WARN +
                                ", revokedAt=" + ACCENT + b.revokedAt() + WARN +
                                ", updatedAt=" + ACCENT + b.updatedAt());
                    }
                }

                // kick newly banned players if online
                for (BanEntry newlyBanned : apply.newlyBanned()) {
                    if (newlyBanned == null || newlyBanned.xuid() == null) continue;
                    Player p = findOnlineByXuid(newlyBanned.xuid());
                    if (p != null) {
                        getServer().getScheduler().scheduleTask(this, () ->
                                p.kick(banCache.buildKickMessage(newlyBanned), false)
                        );
                    }
                }
            });
        }, bansPollSeconds * 20, true);

        // 2) Presence push (SNAPSHOT MODE: always send full online list, even if empty)
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            var players = new ArrayList<PresenceRequest.PlayerPresence>();
            for (Player p : getServer().getOnlinePlayers().values()) {
                String xuid = safeXuid(p);
                if (xuid == null) continue;

                players.add(new PresenceRequest.PlayerPresence(
                        xuid,
                        p.getName(),
                        true,
                        safeIp(p),
                        safeHwid(p)
                ));
            }

            backendClient.postPresenceAsync(new PresenceRequest(true, players), ok -> {
                if (!ok) {
                    logWarn("Presence", "POST /api/server/presence failed " + DIM + "→ " + WARN +
                            "snapshot=true players=" + ACCENT + players.size());
                }
            });
        }, presenceSeconds * 20, true);

        // 3) Playtime tick (1 min)
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            for (Player p : getServer().getOnlinePlayers().values()) {
                String xuid = safeXuid(p);
                if (xuid != null) {
                    stats.addPlaytimeDelta(xuid, p.getName(), 60);
                }
            }
        }, 60 * 20, false);

        // 4) Stats flush
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            var batch = stats.drainBatch();
            if (batch.players() == null || batch.players().isEmpty()) return;

            backendClient.postStatsBatchAsync(batch, ok -> {
                if (!ok) {
                    logWarn("Stats", "Flush failed; " + INFO + "will retry later.");
                    stats.requeue(batch);
                }
            });
        }, statsFlushSeconds * 20, true);

        // 5) Metrics push (MUST be periodic even if playersOnline=0)
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            String sk = (serverKey == null) ? "" : serverKey.trim();
            if (sk.isEmpty()) {
                if (warnedMissingServerKey.compareAndSet(false, true)) {
                    logErr("Metrics", "Disabled: " + ERR + "api.serverKey is missing/empty" + INFO +
                            " (must be set and unique per instance).");
                }
                return;
            }

            ServerMetricsRequest metrics = collectMetrics();
            backendClient.postMetricsAsync(metrics, result -> {
                if (result != null && result.ok()) {
                    logOk("Metrics", "POST /api/server/metrics ok " + DIM + "→ " + OK +
                            "serverKey=" + ACCENT + metrics.serverKey() + OK +
                            ", playersOnline=" + ACCENT + metrics.playersOnline() + OK +
                            ", tps=" + ACCENT + fmt(metrics.tps()) + OK +
                            ", rxKbps=" + ACCENT + fmt(metrics.rxKbps()) + OK +
                            ", txKbps=" + ACCENT + fmt(metrics.txKbps()));
                } else {
                    String sc = (result == null || result.statusCode() == null) ? "n/a" : result.statusCode().toString();
                    logWarn("Metrics", "POST /api/server/metrics failed " + DIM + "→ " + WARN +
                            "status=" + ACCENT + sc + WARN +
                            ", serverKey=" + ACCENT + metrics.serverKey());
                }
            });
        }, metricsSeconds * 20, true);

        // 6) Commands poll
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            String sinceId = Long.toString(commandsSinceId.get());
            backendClient.pollCommandsAsync(sinceId, resOpt -> {
                if (resOpt.isEmpty()) return;

                CommandsPollResponse res = resOpt.get();
                if (res.commands() == null || res.commands().isEmpty()) return;

                for (CommandsPollResponse.ServerCommand cmd : res.commands()) {
                    if (cmd == null) continue;

                    long id = cmd.id();
                    if (id <= commandsSinceId.get()) continue;

                    String type = (cmd.cmdType() == null) ? "" : cmd.cmdType().trim().toUpperCase(Locale.ROOT);
                    boolean executed = executeBackendCommand(type, cmd.payloadJson());
                    if (!executed) continue;

                    backendClient.ackCommandAsync(id, ok -> {
                        if (ok) {
                            commandsSinceId.updateAndGet(prev -> Math.max(prev, id));
                        } else {
                            logWarn("Commands", "ACK failed " + DIM + "→ " + WARN + "id=" + ACCENT + id +
                                    WARN + " (" + INFO + "will retry next poll" + WARN + ")");
                        }
                    });
                }
            });
        }, commandsPollSeconds * 20, true);

        logOk("Startup", "Enabled. backend=" + ACCENT + safeInline(baseUrl) + OK +
                " serverKey=" + ACCENT + safeInline(serverKey));
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);

        // Best-effort: send an empty snapshot so backend can mark everyone offline immediately.
        // (Backend must interpret snapshot=true as authoritative.)
        try {
            if (backendClient != null) {
                backendClient.postPresenceAsync(new PresenceRequest(true, java.util.List.of()), __ -> {
                    // no logs during shutdown
                });
            }
        } catch (Throwable ignored) {
            // ignore on shutdown
        }

        try {
            if (banCache != null) banCache.saveToDiskAtomic();
        } catch (Throwable ignored) {
            // ignore on shutdown
        }

        logInfo("Shutdown", "Disabled.");
    }

    // ----------------------------
    // Commands: disable /ban (website-only banning)
    // ----------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg == null) return;

        String trimmed = msg.trim();
        if (trimmed.isEmpty()) return;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("/ban") || lower.startsWith("/ban ")) {
            event.setCancelled(true);

            event.getPlayer().sendMessage(
                    PREFIX + ERR + "Bannen ist deaktiviert. " + INFO + "Bitte nutze die " + ACCENT + "Website" + INFO + "."
            );

            logWarn("Commands", "Blocked /ban " + DIM + "→ " + WARN +
                    "player=" + ACCENT + event.getPlayer().getName() + WARN +
                    ", cmd=" + ACCENT + safeInline(trimmed));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand();
        if (cmd == null) return;

        String trimmed = cmd.trim();
        if (trimmed.isEmpty()) return;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("ban") || lower.startsWith("ban ")) {
            event.setCancelled(true);

            String sender = (event.getSender() instanceof ConsoleCommandSender) ? "CONSOLE" : event.getSender().getName();
            logWarn("Commands", "Blocked console ban " + DIM + "→ " + WARN +
                    "sender=" + ACCENT + sender + WARN +
                    ", cmd=" + ACCENT + safeInline(trimmed));
        }
    }

    // ----------------------------
    // Ban enforcement at login
    // ----------------------------

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        Player p = event.getPlayer();
        String xuid = safeXuid(p);
        if (xuid == null) return;

        Optional<BanEntry> ban = banCache.findActiveBan(xuid);
        if (ban.isPresent()) {
            event.setKickMessage(banCache.buildKickMessage(ban.get()));
            event.setCancelled(true);

            logWarn("Ban", "Login blocked " + DIM + "→ " + WARN +
                    "player=" + ACCENT + p.getName() + WARN +
                    ", xuid=" + ACCENT + xuid + WARN +
                    ", banId=" + ACCENT + ban.get().banId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String xuid = safeXuid(p);
        if (xuid == null) return;

        stats.markOnline(xuid, p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        String xuid = safeXuid(p);
        if (xuid == null) return;

        stats.markOffline(xuid);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String victimXuid = safeXuid(victim);
        if (victimXuid != null) {
            stats.addDeathDelta(victimXuid, victim.getName(), 1);
        }

        Player killer = null;
        try {
            killer = (Player) victim.getKiller();
        } catch (Throwable ignored) {
            // keep null
        }

        if (killer != null) {
            String killerXuid = safeXuid(killer);
            if (killerXuid != null) {
                stats.addKillDelta(killerXuid, killer.getName(), 1);
            }
        }
    }

    // ----------------------------
    // Backend commands execution
    // ----------------------------

    private boolean executeBackendCommand(String type, String payloadJson) {
        try {
            return switch (type) {
                case "SHUTDOWN" -> {
                    logWarn("Commands", "Backend command " + DIM + "→ " + WARN + "SHUTDOWN");
                    getServer().shutdown();
                    yield true;
                }
                case "REFRESH_BANS" -> {
                    logInfo("Commands", "Backend command " + DIM + "→ " + INFO + "REFRESH_BANS");
                    resetBanCacheToEpoch();
                    yield true;
                }
                default -> {
                    logWarn("Commands", "Unknown command " + DIM + "→ " + WARN + safeInline(type));
                    yield false;
                }
            };
        } catch (Throwable t) {
            logErr("Commands", "Execution error: " + safeInline(t.getMessage()));
            return false;
        }
    }

    private void resetBanCacheToEpoch() {
        try {
            if (banCachePath != null) Files.deleteIfExists(banCachePath);
        } catch (Throwable t) {
            logWarn("BanCache", "Failed to delete cache file: " + safeInline(t.getMessage()));
        }

        try {
            this.banCache = new BanCache(banCachePath, getLogger());
            this.banCache.loadFromDisk();
        } catch (Throwable t) {
            logWarn("BanCache", "Failed to re-init: " + safeInline(t.getMessage()));
        }
    }

    // ----------------------------
    // Metrics collection
    // ----------------------------

    private ServerMetricsRequest collectMetrics() {
        String sk = (serverKey == null) ? "" : serverKey.trim();

        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        Integer ramUsedMb = toNonNegativeIntMb(usedBytes);
        Integer ramMaxMb = toPositiveIntMb(rt.maxMemory());

        Integer playersOnline = Math.max(0, getServer().getOnlinePlayers().size());
        int max = getServer().getMaxPlayers();
        Integer playersMax = (max >= 0) ? max : null;

        Double cpuLoad = null;
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                cpuLoad = sanitizeCpuLoad(sunOs.getSystemCpuLoad());
            }
        } catch (Throwable ignored) {
            cpuLoad = null;
        }

        Double tps = null;
        try {
            Object v = invokeNoArg(getServer(), "getTicksPerSecond");
            if (v instanceof Number n) {
                tps = sanitizeTps(n.doubleValue());
            }
        } catch (Throwable ignored) {
            tps = null;
        }

        Double rxKbps = null;
        Double txKbps = null;
        try {
            if (bandwidthMeter != null) {
                BandwidthMeter.Result r = bandwidthMeter.sampleKbps();
                rxKbps = normalizeKbps(r.rxKbps());
                txKbps = normalizeKbps(r.txKbps());
            }
        } catch (Throwable ignored) {
            rxKbps = null;
            txKbps = null;
        }

        return new ServerMetricsRequest(
                sk,
                ramUsedMb,
                ramMaxMb,
                cpuLoad,
                playersOnline,
                playersMax,
                tps,
                rxKbps,
                txKbps
        );
    }

    private BandwidthMeter createBandwidthMeter() {
        try {
            if (isLinux()) {
                String iface = getConfig().getString("metrics.netInterface");
                return new LinuxBandwidthMeter(iface);
            }
            return new OshiBandwidthMeter();
        } catch (Throwable t) {
            logWarn("Metrics", "Bandwidth meter init failed: " + safeInline(t.getMessage()));
            return null;
        }
    }

    // ----------------------------
    // Helpers: sanitization
    // ----------------------------

    private static Integer toNonNegativeIntMb(long bytes) {
        if (bytes < 0) return null;
        long mb = bytes / (1024L * 1024L);
        if (mb < 0) return null;
        if (mb > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) mb;
    }

    private static Integer toPositiveIntMb(long bytes) {
        if (bytes <= 0) return null;
        long mb = bytes / (1024L * 1024L);
        if (mb <= 0) return null;
        if (mb > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) mb;
    }

    private static Double sanitizeCpuLoad(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        if (v < 0.0) return null;
        return Math.min(v, 1.2);
    }

    private static Double sanitizeTps(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        if (v < 0.0) return null;
        return Math.min(v, 25.0);
    }

    private static Double normalizeKbps(Double v) {
        if (v == null) return null;
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        if (v < 0.0) return null;

        double clamped = Math.min(v, 100_000_000.0); // 100 Gbit/s in kbit/s
        return Math.round(clamped * 100.0) / 100.0;  // 2 decimals
    }

    private static String fmt(Double v) {
        return (v == null) ? "null" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("linux");
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String safeInline(String s) {
        if (s == null) return "n/a";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        return t.isEmpty() ? "n/a" : t;
    }

    // ----------------------------
    // Helpers: player lookups / reflection-safe extraction
    // ----------------------------

    private Player findOnlineByXuid(String xuid) {
        if (xuid == null) return null;
        for (Player p : getServer().getOnlinePlayers().values()) {
            String px = safeXuid(p);
            if (xuid.equals(px)) return p;
        }
        return null;
    }

    private String safeXuid(Player player) {
        try {
            Object lcd = player.getLoginChainData();
            if (lcd == null) return null;
            var m = lcd.getClass().getMethod("getXUID");
            Object x = m.invoke(lcd);
            String s = (x == null) ? null : x.toString().trim();
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeIp(Player player) {
        try {
            Object v = invokeNoArg(player, "getAddress");
            String ip = extractIpFromUnknown(v);
            if (ip != null) return ip;

            Object sa = invokeNoArg(player, "getSocketAddress");
            ip = extractIpFromUnknown(sa);
            if (ip != null) return ip;

            Object ca = invokeNoArg(player, "getClientAddress");
            return extractIpFromUnknown(ca);
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeHwid(Player player) {
        try {
            Object lcd = player.getLoginChainData();
            if (lcd == null) return null;

            for (String method : new String[]{"getDeviceId", "getDeviceID", "getClientId"}) {
                try {
                    var m = lcd.getClass().getMethod(method);
                    Object v = m.invoke(lcd);
                    if (v != null) {
                        String s = v.toString().trim();
                        if (!s.isEmpty()) return s;
                    }
                } catch (Throwable ignored) {
                    // try next
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            var m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String extractIpFromUnknown(Object v) {
        if (v == null) return null;

        if (v instanceof String s) {
            String ip = s.trim();
            return ip.isEmpty() ? null : ip;
        }

        if (v instanceof InetSocketAddress isa) {
            InetAddress ia = isa.getAddress();
            return (ia == null) ? null : ia.getHostAddress();
        }

        if (v instanceof InetAddress ia) {
            return ia.getHostAddress();
        }

        try {
            var m = v.getClass().getMethod("getAddress");
            Object addr = m.invoke(v);
            if (addr instanceof InetAddress ia2) return ia2.getHostAddress();
        } catch (Throwable ignored) {
            // ignore
        }

        return null;
    }

    // ----------------------------
    // Colored logger helpers
    // ----------------------------

    private void logInfo(String area, String msg) {
        getLogger().info(PREFIX + INFO + "[" + ACCENT + area + INFO + "] " + INFO + msg);
    }

    private void logOk(String area, String msg) {
        getLogger().info(PREFIX + OK + "[" + ACCENT + area + OK + "] " + OK + msg);
    }

    private void logWarn(String area, String msg) {
        getLogger().warning(PREFIX + WARN + "[" + ACCENT + area + WARN + "] " + WARN + msg);
    }

    private void logErr(String area, String msg) {
        getLogger().warning(PREFIX + ERR + "[" + ACCENT + area + ERR + "] " + ERR + msg);
    }

    // ----------------------------
    // Optional: local ban reporting hook (kept for future)
    // ----------------------------

    @SuppressWarnings("unused")
    private void reportLocalBanEnforced(Player target, String reason, Long durationSeconds) {
        String xuid = safeXuid(target);
        if (xuid == null) return;

        String ip = safeIp(target);
        String hwid = safeHwid(target);

        BanReportRequest req = new BanReportRequest(
                serverKey,
                new BanReportRequest.BanPayload(
                        xuid,
                        reason,
                        durationSeconds,
                        ip,
                        hwid,
                        Instant.now().toString()
                )
        );

        backendClient.reportBanEnforcedAsync(req, ok -> {
            if (!ok) logWarn("BanReport", "Failed to report enforced ban to backend.");
        });
    }
}