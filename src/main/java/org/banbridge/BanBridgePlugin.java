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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.List;
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
    private final AtomicBoolean commandsProcessing = new AtomicBoolean(false);

    /**
     * Commands cursor: only advance after successful ACK to avoid losing commands.
     */
    private final AtomicLong commandsSinceId = new AtomicLong(0);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ----------------------------
    // Lifecycle
    // ----------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String baseUrl = getConfig().getString("api.baseUrl");
        this.serverKey = getConfig().getString("api.serverKey");
        String serverToken = getConfig().getString("api.serverToken");

        int bansPollSeconds = Math.max(3, getConfig().getInt("sync.bansPollSeconds", 10));
        int statsFlushSeconds = Math.max(10, getConfig().getInt("sync.statsFlushSeconds", 60));
        int metricsSeconds = Math.max(5, getConfig().getInt("sync.metricsSeconds", 15));

        int presenceSecondsCfg = getConfig().getInt("sync.presenceSeconds", 15);
        int presenceSeconds = clampInt(presenceSecondsCfg, 10, 30);

        int commandsPollSeconds = Math.max(2, getConfig().getInt("sync.commandsPollSeconds", 3));

        int httpMaxAttempts = Math.max(1, getConfig().getInt("sync.httpMaxAttempts", 4));
        long httpBaseBackoffMillis = Math.max(50L, getConfig().getLong("sync.httpBaseBackoffMillis", 250L));
        long httpMaxBackoffMillis = Math.max(httpBaseBackoffMillis, getConfig().getLong("sync.httpMaxBackoffMillis", 5000L));

        String bansFileName = getConfig().getString("cache.bansFile", "bans-cache.json");
        this.banCachePath = getDataFolder().toPath().resolve(bansFileName);

        this.backendClient = new BackendClient(
                baseUrl,
                serverKey,
                serverToken,
                Duration.ofSeconds(10),
                httpMaxAttempts,
                httpBaseBackoffMillis,
                httpMaxBackoffMillis
        );

        this.banCache = new BanCache(banCachePath, getLogger());
        this.stats = new StatsAccumulator(getLogger());
        banCache.loadFromDisk();

        getServer().getPluginManager().registerEvents(this, this);

        this.bandwidthMeter = createBandwidthMeter();
        if (bandwidthMeter == null) {
            logInfo("Metrics", "Bandwidth meter disabled. " + INFO + "rxKbps/txKbps will be " + ACCENT + "null" + INFO + ".");
        } else {
            logOk("Metrics", "Bandwidth meter enabled: " + ACCENT + bandwidthMeter.getClass().getSimpleName());
        }

        if (baseUrl != null && (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost"))) {
            logWarn("Config", "api.baseUrl points to localhost " + DIM + "(" + baseUrl + ")" + WARN + ". "
                    + INFO + "If the backend runs on another machine, set it to " + ACCENT + "http://<BACKEND_HOST>:<PORT>");
        }

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

        // 1) Ban changes poll
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

                        logWarn("BanSync", "NEW BAN " + DIM + "→ " + WARN
                                + "banId=" + ACCENT + b.banId() + WARN
                                + ", xuid=" + ACCENT + b.xuid() + WARN
                                + ", playerName=" + ACCENT + (playerName == null ? "n/a" : playerName) + WARN
                                + ", reason=" + ACCENT + safeInline(b.reason()) + WARN
                                + ", createdAt=" + ACCENT + b.createdAt() + WARN
                                + ", expiresAt=" + ACCENT + b.expiresAt() + WARN
                                + ", revokedAt=" + ACCENT + b.revokedAt() + WARN
                                + ", updatedAt=" + ACCENT + b.updatedAt());
                    }
                }

                if (apply.newlyBanned() != null) {
                    for (BanEntry newlyBanned : apply.newlyBanned()) {
                        if (newlyBanned == null || newlyBanned.xuid() == null) continue;
                        Player p = findOnlineByXuid(newlyBanned.xuid());
                        if (p != null) {
                            String kickMessage = banCache.buildKickMessage(newlyBanned);
                            getServer().getScheduler().scheduleTask(this, () -> kickPlayer(p, kickMessage));
                        }
                    }
                }
            });
        }, bansPollSeconds * 20, true);

        // 2) Presence push
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

            PresenceRequest presence = new PresenceRequest(
                    normalizedServerKey(),
                    true,
                    players
            );

            backendClient.postPresenceAsync(presence, ok -> {
                if (!ok) {
                    logWarn("Presence", "POST /api/server/presence failed " + DIM + "→ " + WARN
                            + "serverKey=" + ACCENT + safeInline(normalizedServerKey()) + WARN
                            + ", snapshot=true players=" + ACCENT + players.size());
                }
            });
        }, presenceSeconds * 20, true);

        // 3) Playtime tick
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

        // 5) Metrics push
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;

            String sk = normalizedServerKey();
            if (sk.isEmpty()) {
                if (warnedMissingServerKey.compareAndSet(false, true)) {
                    logErr("Metrics", "Disabled: " + ERR + "api.serverKey is missing/empty" + INFO
                            + " (must be set and unique per instance).");
                }
                return;
            }

            ServerMetricsRequest metrics = collectMetrics();
            backendClient.postMetricsAsync(metrics, result -> {
                if (result != null && result.ok()) {
                    return;
                }

                String sc = (result == null || result.statusCode() == null) ? "n/a" : result.statusCode().toString();
                logWarn("Metrics", "POST /api/server/metrics failed " + DIM + "→ " + WARN
                        + "status=" + ACCENT + sc + WARN
                        + ", serverKey=" + ACCENT + metrics.serverKey());
            });
        }, metricsSeconds * 20, true);

        // 6) Commands poll
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            if (shuttingDown.get()) return;
            if (!commandsProcessing.compareAndSet(false, true)) return;

            String sinceId = Long.toString(commandsSinceId.get());
            backendClient.pollCommandsAsync(sinceId, resOpt -> {
                if (resOpt.isEmpty()) {
                    commandsProcessing.set(false);
                    return;
                }

                CommandsPollResponse res = resOpt.get();
                List<CommandsPollResponse.ServerCommand> incoming = res.commands();
                if (incoming == null || incoming.isEmpty()) {
                    commandsProcessing.set(false);
                    return;
                }

                List<CommandsPollResponse.ServerCommand> commands = incoming.stream()
                        .filter(cmd -> cmd != null && cmd.id() > commandsSinceId.get())
                        .sorted(Comparator.comparingLong(CommandsPollResponse.ServerCommand::id))
                        .toList();

                if (commands.isEmpty()) {
                    commandsProcessing.set(false);
                    return;
                }

                getServer().getScheduler().scheduleTask(this, () ->
                        processPolledCommandsSequentially(commands, 0)
                );
            });
        }, commandsPollSeconds * 20, true);

        logOk("Startup", "Enabled. backend=" + ACCENT + safeInline(baseUrl) + OK
                + " serverKey=" + ACCENT + safeInline(serverKey));
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);

        try {
            if (backendClient != null) {
                backendClient.postPresenceAsync(new PresenceRequest(
                        normalizedServerKey(),
                        true,
                        List.of()
                ), __ -> {
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
    // Commands: disable /ban
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
                    PREFIX + ERR + "Banning is disabled. " + INFO + "Please use the " + ACCENT + "website" + INFO + "."
            );

            logWarn("Commands", "Blocked /ban " + DIM + "→ " + WARN
                    + "player=" + ACCENT + event.getPlayer().getName() + WARN
                    + ", cmd=" + ACCENT + safeInline(trimmed));
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
            logWarn("Commands", "Blocked console ban " + DIM + "→ " + WARN
                    + "sender=" + ACCENT + sender + WARN
                    + ", cmd=" + ACCENT + safeInline(trimmed));
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

            logWarn("Ban", "Login blocked " + DIM + "→ " + WARN
                    + "player=" + ACCENT + p.getName() + WARN
                    + ", xuid=" + ACCENT + xuid + WARN
                    + ", banId=" + ACCENT + ban.get().banId());
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

    private void processPolledCommandsSequentially(List<CommandsPollResponse.ServerCommand> commands, int index) {
        if (shuttingDown.get()) {
            commandsProcessing.set(false);
            return;
        }

        if (commands == null || index >= commands.size()) {
            commandsProcessing.set(false);
            return;
        }

        CommandsPollResponse.ServerCommand cmd = commands.get(index);
        if (cmd == null) {
            processPolledCommandsSequentially(commands, index + 1);
            return;
        }

        long id = cmd.id();
        String type = normalizeCommandType(cmd.cmdType());

        logInfo("Commands", "Received command " + DIM + "→ " + INFO
                + "id=" + ACCENT + id + INFO
                + ", type=" + ACCENT + safeInline(type)
                + INFO + ", createdAt=" + ACCENT + safeInline(cmd.createdAt()));

        CommandExecutionOutcome outcome = executeBackendCommand(cmd);

        if (!outcome.acknowledge()) {
            logWarn("Commands", "Command not acknowledged " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", type=" + ACCENT + safeInline(type) + WARN
                    + ", reason=" + ACCENT + safeInline(outcome.logMessage()));
            commandsProcessing.set(false);
            return;
        }

        backendClient.ackCommandAsync(id, ok -> {
            if (!ok) {
                logWarn("Commands", "ACK failed " + DIM + "→ " + WARN
                        + "id=" + ACCENT + id + WARN
                        + ", type=" + ACCENT + safeInline(type) + WARN
                        + " (" + INFO + "will retry next poll" + WARN + ")");
                commandsProcessing.set(false);
                return;
            }

            commandsSinceId.updateAndGet(prev -> Math.max(prev, id));

            logOk("Commands", "ACK successful " + DIM + "→ " + OK
                    + "id=" + ACCENT + id + OK
                    + ", type=" + ACCENT + safeInline(type));

            if (outcome.shutdownAfterAck()) {
                logWarn("Commands", "Executing shutdown after ACK " + DIM + "→ " + WARN
                        + "id=" + ACCENT + id);
                getServer().getScheduler().scheduleTask(this, () -> getServer().shutdown());
                commandsProcessing.set(false);
                return;
            }

            getServer().getScheduler().scheduleTask(this, () ->
                    processPolledCommandsSequentially(commands, index + 1)
            );
        });
    }

    private CommandExecutionOutcome executeBackendCommand(CommandsPollResponse.ServerCommand cmd) {
        if (cmd == null) {
            return CommandExecutionOutcome.ack("command was null");
        }

        String type = normalizeCommandType(cmd.cmdType());
        long id = cmd.id();

        try {
            return switch (type) {
                case "SHUTDOWN" -> {
                    logWarn("Commands", "Executing command " + DIM + "→ " + WARN
                            + "id=" + ACCENT + id + WARN
                            + ", type=" + ACCENT + "SHUTDOWN");
                    yield CommandExecutionOutcome.ackShutdown("shutdown scheduled after ACK");
                }

                case "REFRESH_BANS" -> {
                    resetBanCacheToEpoch();
                    logOk("Commands", "Executed command " + DIM + "→ " + OK
                            + "id=" + ACCENT + id + OK
                            + ", type=" + ACCENT + "REFRESH_BANS");
                    yield CommandExecutionOutcome.ack("ban cache reset");
                }

                case "KICK" -> executeKickCommand(id, cmd.payloadJson());
                case "MESSAGE" -> executeMessageCommand(id, cmd.payloadJson());
                case "BROADCAST" -> executeBroadcastCommand(id, cmd.payloadJson());

                default -> {
                    logWarn("Commands", "Unknown command type " + DIM + "→ " + WARN
                            + "id=" + ACCENT + id + WARN
                            + ", type=" + ACCENT + safeInline(type) + WARN
                            + ", payload=" + ACCENT + clipPayload(cmd.payloadJson()));
                    yield CommandExecutionOutcome.ack("unknown command type ignored");
                }
            };
        } catch (Throwable t) {
            logErr("Commands", "Execution error " + DIM + "→ " + ERR
                    + "id=" + ACCENT + id + ERR
                    + ", type=" + ACCENT + safeInline(type) + ERR
                    + ", err=" + ACCENT + safeInline(t.getClass().getSimpleName() + ": " + t.getMessage()));
            return CommandExecutionOutcome.ack("execution error handled");
        }
    }

    private CommandExecutionOutcome executeKickCommand(long id, String payloadJson) {
        JsonNode payload = parsePayloadObject("KICK", id, payloadJson);
        if (payload == null) {
            return CommandExecutionOutcome.ack("invalid KICK payload");
        }

        String xuid = readTrimmedText(payload, "xuid");
        String reason = readTrimmedText(payload, "reason");

        if (xuid == null) {
            logWarn("Commands", "KICK ignored due to missing xuid " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", payload=" + ACCENT + clipPayload(payloadJson));
            return CommandExecutionOutcome.ack("missing xuid");
        }

        Player target = findOnlineByXuid(xuid);
        if (target == null) {
            logWarn("Commands", "KICK target not online " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", xuid=" + ACCENT + xuid);
            return CommandExecutionOutcome.ack("player not online");
        }

        String finalReason = (reason == null) ? "You were kicked by the backend." : reason;
        kickPlayer(target, finalReason);

        logOk("Commands", "KICK executed " + DIM + "→ " + OK
                + "id=" + ACCENT + id + OK
                + ", player=" + ACCENT + target.getName() + OK
                + ", xuid=" + ACCENT + xuid + OK
                + ", reason=" + ACCENT + safeInline(finalReason));

        return CommandExecutionOutcome.ack("kick executed");
    }

    private CommandExecutionOutcome executeMessageCommand(long id, String payloadJson) {
        JsonNode payload = parsePayloadObject("MESSAGE", id, payloadJson);
        if (payload == null) {
            return CommandExecutionOutcome.ack("invalid MESSAGE payload");
        }

        String xuid = readTrimmedText(payload, "xuid");
        String message = readTrimmedText(payload, "message");

        if (xuid == null) {
            logWarn("Commands", "MESSAGE ignored due to missing xuid " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", payload=" + ACCENT + clipPayload(payloadJson));
            return CommandExecutionOutcome.ack("missing xuid");
        }

        if (message == null) {
            logWarn("Commands", "MESSAGE ignored due to missing message " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", xuid=" + ACCENT + xuid + WARN
                    + ", payload=" + ACCENT + clipPayload(payloadJson));
            return CommandExecutionOutcome.ack("missing message");
        }

        Player target = findOnlineByXuid(xuid);
        if (target == null) {
            logWarn("Commands", "MESSAGE target not online " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", xuid=" + ACCENT + xuid);
            return CommandExecutionOutcome.ack("player not online");
        }

        sendMessageToPlayer(target, message);

        logOk("Commands", "MESSAGE executed " + DIM + "→ " + OK
                + "id=" + ACCENT + id + OK
                + ", player=" + ACCENT + target.getName() + OK
                + ", xuid=" + ACCENT + xuid + OK
                + ", message=" + ACCENT + safeInline(message));

        return CommandExecutionOutcome.ack("message executed");
    }

    private CommandExecutionOutcome executeBroadcastCommand(long id, String payloadJson) {
        JsonNode payload = parsePayloadObject("BROADCAST", id, payloadJson);
        if (payload == null) {
            return CommandExecutionOutcome.ack("invalid BROADCAST payload");
        }

        String message = readTrimmedText(payload, "message");
        if (message == null) {
            logWarn("Commands", "BROADCAST ignored due to missing message " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", payload=" + ACCENT + clipPayload(payloadJson));
            return CommandExecutionOutcome.ack("missing message");
        }

        int recipients = broadcastToAllPlayers(message);

        logOk("Commands", "BROADCAST executed " + DIM + "→ " + OK
                + "id=" + ACCENT + id + OK
                + ", recipients=" + ACCENT + recipients + OK
                + ", message=" + ACCENT + safeInline(message));

        return CommandExecutionOutcome.ack("broadcast executed");
    }

    private JsonNode parsePayloadObject(String type, long id, String payloadJson) {
        try {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                logWarn("Commands", "Empty payload_json " + DIM + "→ " + WARN
                        + "id=" + ACCENT + id + WARN
                        + ", type=" + ACCENT + type);
                return null;
            }

            JsonNode node = objectMapper.readTree(payloadJson);
            if (node == null || !node.isObject()) {
                logWarn("Commands", "payload_json is not an object " + DIM + "→ " + WARN
                        + "id=" + ACCENT + id + WARN
                        + ", type=" + ACCENT + type + WARN
                        + ", payload=" + ACCENT + clipPayload(payloadJson));
                return null;
            }

            return node;
        } catch (Throwable t) {
            logWarn("Commands", "Failed to parse payload_json " + DIM + "→ " + WARN
                    + "id=" + ACCENT + id + WARN
                    + ", type=" + ACCENT + type + WARN
                    + ", err=" + ACCENT + safeInline(t.getClass().getSimpleName() + ": " + t.getMessage()) + WARN
                    + ", payload=" + ACCENT + clipPayload(payloadJson));
            return null;
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
        String sk = normalizedServerKey();

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
    // Helpers
    // ----------------------------

    private String normalizedServerKey() {
        return serverKey == null ? "" : serverKey.trim();
    }

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

        double clamped = Math.min(v, 100_000_000.0);
        return Math.round(clamped * 100.0) / 100.0;
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

    private static String normalizeCommandType(String type) {
        if (type == null) return "";
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static String clipPayload(String payload) {
        if (payload == null) return "n/a";
        String flat = payload.replace("\n", " ").replace("\r", " ").trim();
        if (flat.isEmpty()) return "n/a";
        if (flat.length() <= 240) return flat;
        return flat.substring(0, 240) + "...";
    }

    private String readTrimmedText(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return null;
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) return null;

        String text = value.asText();
        if (text == null) return null;

        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Player findOnlineByXuid(String xuid) {
        if (xuid == null) return null;
        for (Player p : getServer().getOnlinePlayers().values()) {
            String px = safeXuid(p);
            if (xuid.equals(px)) return p;
        }
        return null;
    }

    private void kickPlayer(Player player, String reason) {
        if (player == null) return;
        String finalReason = (reason == null || reason.trim().isEmpty())
                ? "You were kicked by the backend."
                : reason.trim();
        player.kick(finalReason, false);
    }

    private void sendMessageToPlayer(Player player, String message) {
        if (player == null || message == null || message.trim().isEmpty()) return;
        player.sendMessage(message);
    }

    private int broadcastToAllPlayers(String message) {
        if (message == null || message.trim().isEmpty()) return 0;

        int count = 0;
        for (Player player : getServer().getOnlinePlayers().values()) {
            if (player == null) continue;
            player.sendMessage(message);
            count++;
        }
        return count;
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

    private record CommandExecutionOutcome(
            boolean acknowledge,
            boolean shutdownAfterAck,
            String logMessage
    ) {
        private static CommandExecutionOutcome ack(String logMessage) {
            return new CommandExecutionOutcome(true, false, logMessage);
        }

        private static CommandExecutionOutcome ackShutdown(String logMessage) {
            return new CommandExecutionOutcome(true, true, logMessage);
        }
    }
}