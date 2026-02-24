# 🎮 BanBridge Client Plugin

> **NukkitX Plugin for Synchronized Ban Management & Player Stats Tracking**

[![Java 17](https://img.shields.io/badge/Java-17-ED8936?logo=openjdk&logoColor=white)](https://openjdk.java.net/)
[![NukkitX](https://img.shields.io/badge/NukkitX-1.0-E74C3C?logo=minecraft&logoColor=white)](https://nukkitx.com/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Proprietary-FF6B6B)](LICENSE)
[![Backend]([https://img.shields.io/badge/License-Proprietary-FF6B6B](https://github.com/MakisImperium/BanBridge-Client))](Backend)

## ⚠️ CRITICAL: Backend Dependency

**This plugin REQUIRES a separate BanBridge Backend instance running independently!**

The backend is **NOT** part of the NukkitX server. It must:
- 🖥️ Run on a **separate machine or different port** (not localhost:25565)
- 🗄️ Have its own **MySQL database**
- 🌐 Listen on **HTTP (typically port 8080)**
- 📡 Be **accessible from the game server** via network

**Architecture:**
```
┌──────────────────────┐
│    MySQL Database    │
│   (Backend only)     │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────────────┐
│   BanBridge Backend          │ ← SEPARATE Java process
│   (Host: backend.local:8080) │
│   (or separate port 9000)    │
└──────────┬───────────────────┘
           │ HTTP REST
           ▼
┌──────────────────────┐
│  NukkitX Game Server │
│ + BanBridge Plugin   │ ← Plugin syncs via HTTP
│  (Host: 0.0.0.0:25565)      │
└──────────────────────┘
```

**Setup Order:**
1. ✅ Start BanBridge Backend first (see Backend `README.md`)
2. ✅ Install BanBridge Client Plugin in plugins folder
3. ✅ Configure `config.yml` with backend URL
4. ✅ Start NukkitX server

---

## 🎯 Overview

BanBridge is a powerful **NukkitX game server plugin** that synchronizes player bans and tracks statistics across your Minecraft network. It maintains a local ban cache for offline protection and communicates with a centralized backend server.

### Key Features

🚫 **Synchronized Bans** - Ban players across multiple servers instantly  
📊 **Player Stats Tracking** - Monitor playtime, kills, deaths  
🟢 **Presence Tracking** - Real-time online/offline status  
💾 **Local Ban Cache** - Offline-safe ban enforcement (works without internet)  
🔐 **Token-Based Auth** - Secure backend communication  
⚡ **Resilient API** - Automatic retry with exponential backoff  
🔌 **Event-Driven** - Efficient NukkitX event hooks  

---

## 🚀 Installation & Setup

### Prerequisites
- **Java 17+** (OpenJDK or Oracle JDK)
- **Maven 3.8+**
- **NukkitX Server** (1.0+)
- **BanBridge Backend** (running separately with MySQL)

### Step 1: Build Plugin

```bash
cd /path/to/BanBridgeProjekt/Client
mvn clean package
```

Output: `target/BanBridge-1.0.0.jar`

### Step 2: Install JAR

```bash
# Copy to NukkitX plugins directory
cp target/BanBridge-1.0.0.jar /path/to/nukkit/plugins/
```

### Step 3: Start Server (First Run)

```bash
cd /path/to/nukkit
./nukkit.sh
```

This generates: `plugins/BanBridge/config.yml`

### Step 4: Configure Backend Connection

Edit `plugins/BanBridge/config.yml`:

```yaml
# Backend Connection Settings
api:
  baseUrl: "http://backend-host:8080"    # ← IMPORTANT: Point to Backend Server!
  serverKey: "survival-1"                 # Unique identifier for this server
  serverToken: "your-secret-token"        # Token from backend DB (servers table)

# Sync Intervals (seconds)
sync:
  bansPollSeconds: 10                     # Check for new bans every 10 seconds
  statsFlushSeconds: 60                   # Upload stats every 60 seconds
  metricsSeconds: 15                      # Report metrics every 15 seconds
  presenceSeconds: 10                     # Update player presence every 10 seconds
  commandsPollSeconds: 3                  # Poll for commands every 3 seconds

  # HTTP Retry Configuration
  httpMaxAttempts: 4                      # Retry failed requests 4 times
  httpBaseBackoffMillis: 250              # Initial backoff: 250ms
  httpMaxBackoffMillis: 5000              # Max backoff: 5 seconds

# Local Cache
cache:
  bansFile: "bans-cache.json"             # Local ban cache filename

# Debug Web Server (for development only)
web:
  enabled: false                          # Leave disabled in production
  bind: "127.0.0.1"
  port: 8090
```

### Step 5: Verify Backend Connection

Before starting the plugin, verify the backend is running:

```bash
# From game server machine
curl http://backend-host:8080/api/server/health

# Expected response:
# {"status":"ok","serverTime":"2026-02-25T...","dbOk":true}
```

### Step 6: Restart Server

```bash
./nukkit.sh
```

Check console for:
```
[BanBridge] Backend health check OK
[BanBridge] Ban cache loaded: 0 entries
[BanBridge] Plugin enabled - syncing with backend
```

🎉 **Done!** Plugin is now syncing with backend.

---

## 📚 Plugin Architecture

### Component Overview

```
org.banbridge
├── BanBridgePlugin          🎮 Main plugin entry point
├── api/
│   ├── BackendClient        📡 REST client for backend communication
│   ├── BanChangesResponse   📥 Parses ban change responses
│   ├── StatsBatchRequest    📤 Player stats upload payload
│   ├── PresenceRequest      🟢 Player presence updates
│   ├── ServerMetricsRequest 📊 Server metrics reporting
│   ├── CommandAckRequest    💬 Command acknowledgment
│   └── CommandsPollResponse 📋 Polls for backend commands
├── bans/
│   ├── BanCache             💾 Persistent local ban cache
│   └── BanEntry             🚫 Individual ban data
└── stats/
    ├── StatsAccumulator     📈 Accumulates player stats
    ├── BandwidthMeter       🌐 Network metrics (abstract)
    ├── LinuxBandwidthMeter  🐧 Linux-specific metrics
    └── OshiBandwidthMeter   💻 Cross-platform metrics
```

### Key Classes

#### 🎮 BanBridgePlugin
Main plugin class - initializes all components and hooks NukkitX events.

**Listens to:**
- `PlayerLoginEvent` - Check ban cache
- `PlayerQuitEvent` - Record playtime
- `PlayerDeathEvent` - Track kills/deaths
- Periodic timers - Sync bans, flush stats, update presence

#### 📡 BackendClient
HTTP REST client for all backend communication.

```java
// Health check
HttpResponse<String> health = backendClient.health();

// Fetch new bans
String changes = backendClient.fetchBanChanges(sinceInstant);

// Upload stats
backendClient.postStatsBatch(playerList);

// Update presence (who's online)
backendClient.postPresence(onlinePlayers);

// Report metrics (CPU, memory, bandwidth)
backendClient.postServerMetrics(metrics);

// Poll for commands (future feature)
backendClient.pollCommands();
```

#### 💾 BanCache
Persistent local ban cache stored as JSON file.

**Features:**
- Loaded on startup from `bans-cache.json`
- Updated periodically from backend
- Used at login to kick banned players (works offline!)
- Survives server restarts

```java
// Check if player is banned
if (banCache.isBanned(playerXuid)) {
    player.kick("§cYou are banned!");
}

// Add/update ban from backend
banCache.addOrUpdateBan(banEntry);

// Revoke ban
banCache.revokeBan(banId);
```

#### 📈 StatsAccumulator
Tracks player statistics in memory, flushes to backend periodically.

**Tracked Metrics:**
- **Playtime** - Incremented every minute per online player
- **Kills** - On PlayerDeathEvent (killer)
- **Deaths** - On PlayerDeathEvent (victim)

```java
// Record +60 seconds playtime
statsAccumulator.recordPlaytime(xuid, 60);

// Record kill
statsAccumulator.recordKill(xuid);

// Record death
statsAccumulator.recordDeath(xuid);

// Get accumulated stats
PlayerStats stats = statsAccumulator.getStats(xuid);
```

#### 🌐 BandwidthMeter
Monitors network I/O and system resources.

- `OshiBandwidthMeter` - Cross-platform (Windows, Linux, macOS)
- `LinuxBandwidthMeter` - Linux-specific optimization

---

## 🔌 Backend API Integration

### Architecture Flow

```
Plugin ──HTTP──> Backend ──SQL──> MySQL Database
```

### Request Authentication

All requests include:
```
X-Server-Key: <serverKey>
X-Server-Token: <serverToken>
```

### Endpoints Used

#### 1. Health Check
```http
GET /api/server/health
```

**Response:**
```json
{
  "status": "ok",
  "serverTime": "2026-02-25T10:30:00.000Z",
  "dbOk": true
}
```

**Used by:** Initial connection test, periodic health verification

#### 2. Ban Changes Sync
```http
GET /api/server/bans/changes?since=2026-02-25T09:00:00Z
X-Server-Key: survival-1
X-Server-Token: secret123
```

**Response:**
```json
{
  "serverTime": "2026-02-25T10:30:00.000Z",
  "changes": [
    {
      "type": "BAN_UPSERT",
      "banId": 123,
      "xuid": "2533274790299905",
      "reason": "Hacking detected",
      "createdAt": "2026-02-25T10:00:00.000Z",
      "expiresAt": "2026-02-26T10:00:00.000Z",
      "revokedAt": null,
      "updatedAt": "2026-02-25T10:15:00.000Z"
    }
  ]
}
```

**Ban Status Logic:**
- If `revokedAt != null` → UNBAN
- If `expiresAt` in past → UNBAN (expired)
- Otherwise → BAN active

**Used by:** Ban cache synchronization every `bansPollSeconds`

#### 3. Stats Upload
```http
POST /api/server/stats/batch
Content-Type: application/json
X-Server-Key: survival-1
X-Server-Token: secret123
```

**Request Body:**
```json
{
  "players": [
    {
      "xuid": "2533274790299905",
      "name": "PlayerName",
      "playtimeDeltaSeconds": 3600,
      "killsDelta": 15,
      "deathsDelta": 3
    }
  ]
}
```

**Response:** 200 OK (empty)

**Used by:** Periodic stats flush every `statsFlushSeconds`

#### 4. Presence Update
```http
POST /api/server/presence/batch
Content-Type: application/json
X-Server-Key: survival-1
X-Server-Token: secret123
```

**Snapshot Mode (Recommended):**
```json
{
  "snapshot": true,
  "players": [
    {
      "xuid": "2533274790299905",
      "name": "PlayerName",
      "ip": "192.168.1.100",
      "hwid": "device_hash"
    }
  ]
}
```

**Effect:** All listed players = online, all others = offline

**Event Mode:**
```json
{
  "players": [
    {"xuid": "...", "online": true},
    {"xuid": "...", "online": false}
  ]
}
```

**Effect:** Update only specified players

**Response:** 200 OK (empty)

**Used by:** Every `presenceSeconds` to keep backend in sync

#### 5. Metrics Report
```http
POST /api/server/metrics
Content-Type: application/json
X-Server-Key: survival-1
X-Server-Token: secret123
```

**Request Body:**
```json
{
  "cpuUsagePercent": 45.5,
  "memoryUsageMB": 2048,
  "memoryMaxMB": 4096,
  "playerCount": 25,
  "uploadBandwidthKbps": 100.5,
  "downloadBandwidthKbps": 200.3
}
```

**Response:** 200 OK (empty)

**Used by:** Every `metricsSeconds` for monitoring

---

## ⚙️ Configuration Reference

### Complete config.yml

```yaml
# Backend API Connection
api:
  baseUrl: "http://backend-host:8080"
  serverKey: "survival-1"
  serverToken: "secret_token_abc123"

# Synchronization Intervals (seconds)
sync:
  bansPollSeconds: 10                    # ← How often to check for new bans
  statsFlushSeconds: 60                  # ← How often to upload stats
  metricsSeconds: 15                     # ← How often to report metrics
  presenceSeconds: 10                    # ← How often to update who's online
  commandsPollSeconds: 3                 # ← How often to check for commands

  # HTTP Retry Logic
  httpMaxAttempts: 4                     # ← Retry failed requests this many times
  httpBaseBackoffMillis: 250             # ← Initial wait before first retry (ms)
  httpMaxBackoffMillis: 5000             # ← Never wait longer than this (ms)

# Local Cache
cache:
  bansFile: "bans-cache.json"            # ← Where to store ban cache

# Debug Web Server (development only)
web:
  enabled: false                         # ← Set to true for local testing
  bind: "127.0.0.1"
  port: 8090
```

### Configuration Best Practices

**For Production:**
```yaml
sync:
  bansPollSeconds: 30        # Don't hammer backend
  statsFlushSeconds: 300     # Reduce network traffic
  metricsSeconds: 60
  presenceSeconds: 30
  httpMaxAttempts: 3         # Balance reliability vs speed
```

**For Testing/Development:**
```yaml
sync:
  bansPollSeconds: 5         # Faster testing
  statsFlushSeconds: 10      # See changes immediately
  metricsSeconds: 5
  presenceSeconds: 5
  httpMaxAttempts: 1         # Fail fast

web:
  enabled: true              # Debug endpoints
```

---

## 📊 Player Ban & Stats Flow

### Ban Synchronization

```
┌──────────────────────────────────────────┐
│ Backend Admin UI                         │
│ (Admin clicks "Ban Player")              │
└──────────────────┬───────────────────────┘
                   │ (Ban stored)
                   ▼
         ┌─────────────────┐
         │ Backend MySQL   │
         │ bans table      │
         └────────┬────────┘
                  │ (Plugin polls every 10s)
                  ▼
      ┌───────────────────────┐
      │ GET /bans/changes     │
      │ since=...             │
      └───────────┬───────────┘
                  │ (New ban in response)
                  ▼
      ┌──────────────────────┐
      │ BanCache.json        │
      │ (Local cache updated)│
      └──────────┬───────────┘
                 │
         ┌───────┴──────────┐
         ▼                  ▼
    Player Online      Player Offline
    (Next login)       (Already offline)
         │                  │
         ▼                  ▼
   Check cache         When they login:
   Kick immediately    Check cache
                       Kick immediately
```

### Stats Recording

```
Player Action              Recorded By         Flushed
─────────────────────────────────────────────────────
Join server         →  PlayerLoginEvent   →  Every 60s
Playtime (per min)  →  Timer tick         →  Every 60s
Kill other player   →  PlayerDeathEvent   →  Every 60s
Die                 →  PlayerDeathEvent   →  Every 60s
Leave server        →  PlayerQuitEvent    →  Immediate
```

### Presence Tracking

```
Real-time Events              Update Backend
─────────────────────────────────────────
PlayerLoginEvent       →  Add to online list
PlayerQuitEvent        →  Remove from online list
Every 10 seconds       →  POST /presence/batch
                       →  Backend shows who's online
```

---

## 🔐 Security

### Token Authentication

All API requests require:
```yaml
api:
  serverKey: "survival-1"                # Server identifier
  serverToken: "abc123xyz..."            # Secret token
```

**Important:**
- Tokens are server-side only (sent in HTTP headers)
- Never expose tokens in public logs
- Never commit tokens to version control
- Regenerate if compromised

### Ban Cache Security

Ban cache (`bans-cache.json`) is:
- ✅ Stored locally on game server
- ✅ Only readable by NukkitX process
- ✅ Used for **offline protection** (no internet needed to ban)
- ✅ Automatically synced with backend

---

## ⚡ Resilience & Error Handling

### Automatic Retry Logic

If a request fails:
```
Request fails
    ↓
Wait 250ms (httpBaseBackoffMillis)
    ↓
Retry (attempt 2/4)
    ↓
Fails again
    ↓
Wait 500ms (250 * 2)
    ↓
Retry (attempt 3/4)
    ↓
Fails again
    ↓
Wait 1000ms (250 * 4)
    ↓
Retry (attempt 4/4)
    ↓
Fails again
    ↓
Wait 5000ms (capped at max)
    ↓
Give up, log error, continue
```

### Offline Mode

Plugin continues working even if backend is down:

- ✅ Local ban cache still enforced
- ✅ Stats accumulated in memory
- ✅ Player can join/leave normally
- ✅ When backend recovers → Auto-resync
- ✅ No data lost

### Console Logging

```
[BanBridge] Backend health check OK
[BanBridge] Ban cache updated: 5 new bans, 0 unbans
[BanBridge] Stats flushed: 42 players updated
[BanBridge] Presence updated: 25 players online
[BanBridge] Server metrics: CPU 45%, Mem 2048MB/4096MB

[WARN] [BanBridge] Backend health check FAILED (attempt 1/4)
[WARN] [BanBridge] Retrying in 250ms...
[ERROR] [BanBridge] Ban sync FAILED after 4 attempts
```

---

## 🎮 NukkitX Event Hooks

### Hooked Events

```java
PlayerLoginEvent
├─ Load player stats from accumulator
├─ Check BanCache.isBanned()
└─ If banned: player.kick("§cYou are banned!")

PlayerQuitEvent
├─ Save current playtime
└─ Flush stats if needed

PlayerDeathEvent
├─ Check if killer is known
├─ Record kill for killer (if possible)
└─ Record death for victim
```

### Periodic Tasks

```java
Timer Task (every bansPollSeconds)
└─ GET /api/server/bans/changes
   └─ Update BanCache

Timer Task (every statsFlushSeconds)
└─ POST /api/server/stats/batch
   └─ Upload accumulated stats

Timer Task (every presenceSeconds)
└─ POST /api/server/presence/batch
   └─ Sync online player list

Timer Task (every metricsSeconds)
└─ POST /api/server/metrics
   └─ Report CPU, memory, bandwidth

Timer Task (every commandsPollSeconds)
└─ GET /api/server/commands
   └─ Check for backend commands
```

---

## 🧪 Testing & Debugging

### Build Plugin
```bash
mvn clean package
```

JAR: `target/BanBridge-1.0.0.jar`

### Enable Debug Web Server

In `config.yml`:
```yaml
web:
  enabled: true
  bind: "127.0.0.1"
  port: 8090

api:
  baseUrl: "http://127.0.0.1:8090"  # Point to debug server
```

Then visit: `http://127.0.0.1:8090/admin/players`

### Console Commands

```bash
# Check if backend is reachable
/banbridge health

# Force ban sync
/banbridge sync-bans

# Force stats flush
/banbridge flush-stats

# Show ban cache info
/banbridge cache-info
```

### Testing API Connection

From game server:
```bash
curl -H "X-Server-Key: survival-1" \
     -H "X-Server-Token: token123" \
     http://backend-host:8080/api/server/health
```

Expected:
```json
{"status":"ok","serverTime":"...","dbOk":true}
```

---

## 🐛 Troubleshooting

### "Backend connection refused"
```
[ERROR] Backend health check FAILED: Connection refused
```

**Checklist:**
1. Is backend running? → `ps aux | grep BackendBridge` (on backend host)
2. Is port 8080 correct? → Check backend config
3. Is URL reachable? → `curl http://backend-host:8080/api/server/health`
4. Firewall blocking? → `telnet backend-host 8080`

**Fix:** Start backend, verify connectivity, restart plugin

### "Invalid token / 401 Unauthorized"
```
[WARN] Backend auth failed (401): Check serverToken
```

**Checklist:**
1. Backend DB has servers table?
2. Server entry exists? → `SELECT * FROM servers WHERE server_key='survival-1';`
3. Token matches? → Copy exact token from DB

**Fix:** Update token in config.yml, restart plugin

### "Ban cache not updating"
```
[WARN] Ban changes fetch returned 0 changes
```

**Checklist:**
1. Are there actual bans in backend? → Check admin UI
2. `bansPollSeconds` reasonable? (10-30 is good)
3. Token correct? → Test health check

**Fix:** Manually trigger: `/banbridge sync-bans`

### "Stats not appearing on backend"
```
[WARN] Stats batch POST returned empty
```

**Checklist:**
1. Have active players on server?
2. `statsFlushSeconds` configured? (60 is default)
3. Backend receiving requests? → Check backend logs

**Fix:** Wait for next flush, check backend connectivity

### "Can't kick banned player at login"
- First run → Ban cache empty, wait for first sync
- Manually trigger → `/banbridge sync-bans`
- Check that ban is active (not expired/revoked)

### "Plugin won't start"
```
[ERROR] Failed to enable BanBridge: NullPointerException
```

**Fix:** Check `config.yml` syntax (YAML format)

### "Memory usage growing"

Ban cache and stats accumulator might be large on long-running servers.

**Solutions:**
- Increase JVM memory: `java -Xmx2G -jar nukkit.jar`
- Reduce sync intervals slightly
- Implement cache cleanup (future enhancement)

---

## 📈 Multi-Server Network Setup

### One Backend for Many Servers

```
┌──────────────────────┐
│ BanBridge Backend    │
│ (Single instance)    │
└──────────┬───────────┘
           │
    ┌──────┼──────┬────────┬────────┐
    │      │      │        │        │
    ▼      ▼      ▼        ▼        ▼
  Surv.  Creat.  PvP    Hard.   Skyw.
  (Port  (Port   (Port  (Port   (Port
   25565) 25566) 25567) 25568)  25569)
```

Configure each server's plugin:

**Server 1 - Survival:**
```yaml
api:
  baseUrl: "http://backend-host:8080"
  serverKey: "survival"
  serverToken: "token_abc123"
```

**Server 2 - Creative:**
```yaml
api:
  baseUrl: "http://backend-host:8080"
  serverKey: "creative"
  serverToken: "token_def456"
```

**Server 3 - PvP:**
```yaml
api:
  baseUrl: "http://backend-host:8080"
  serverKey: "pvp"
  serverToken: "token_ghi789"
```

**Result:** Ban one player → All servers enforce immediately! ✅

---

## 📦 Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jackson Databind | 2.17.2 | JSON processing |
| NukkitX | 1.0 | Game server API |
| OSHI | 6.6.5 | System metrics |

---

## 🤝 Contributing

1. Fork repository
2. Create feature branch: `git checkout -b feature/name`
3. Commit changes: `git commit -m 'Add feature'`
4. Push: `git push origin feature/name`
5. Open pull request

### Code Style
- Java 17+ features (records, text blocks)
- Jackson for JSON
- Try-with-resources for resources
- Defensive null-checking

---

## 📄 License

Proprietary - All rights reserved.

---

## 🆘 Support & Documentation

**GitHub Issues:** Report bugs here  
**Setup Guide:** See `BanBridgePluginSetupGuide.txt`  
**Backend Integration:** See Backend `README.md`  
**Team:** BanBridge Development Team

---

<div align="center">

### ⭐ Enjoying BanBridge? Give it a star! ⭐

**Made with ❤️ for Minecraft Servers**

[🎮 Plugin](#) • [🖥️ Backend](#) • [📚 Docs](#) • [🐛 Issues](#)

</div>

