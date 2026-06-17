# LAN Sync

Synchronize watch progress across devices on your local network — no server, no account, no internet required.

## How it works

Every device runs a lightweight HTTP server (NanoHTTPD) on port 8765. Devices find each other via UDP broadcast on port 8766, then exchange watch data over HTTP.

```
┌─────────────┐         UDP broadcast (port 8766)         ┌─────────────┐
│  TV in       │ ──── "STREAMFLIX_DISCOVER" ──────────→   │  TV in       │
│  living room │ ←─── "STREAMFLIX_HERE:SM-S928B" ─────── │  bedroom     │
└─────────────┘                                           └─────────────┘
       │                                                       │
       │         HTTP (port 8765)                              │
       │──── GET  /sync/data ──────────────────────────────→   │
       │←─── 200  {watchData JSON} ────────────────────────────│
       │                                                       │
       │──── POST /sync/data ──────────────────────────────→   │
       │←─── 200  {"status":"ok"} ──────────────────────────── │
```

## Protocol

### Discovery

| Direction | Message |
|-----------|---------|
| Broadcast | `STREAMFLIX_DISCOVER` |
| Response  | `STREAMFLIX_HERE:{deviceName}` |

### HTTP endpoints (port 8765)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/sync/ping` | Health check, returns `{"deviceName","version","localIp"}` |
| GET | `/sync/data` | Export full watch data as JSON |
| POST | `/sync/data` | Import watch data from JSON body |

### Sync triggers

- **On startup** (`StreamFlixApp.onCreate`): pull from all known peers, merge newest data
- **On data change** (`UserDataNotifier`): debounced push to all peers (300ms debounce)
- **Manual**: "Sync now" button in settings

### Conflict resolution

When pulling, the device with the most recent `exportedAt` timestamp wins. All data from that device replaces local state. No per-item merge — simpler and prevents partial sync bugs.

### Echo prevention

The `@Volatile isSyncing` flag prevents re-importing data that this device just received from a peer and then re-pushed.

## Setup

1. Go to **Settings → LAN Sync**
2. Enable LAN Sync on all devices
3. Tap **Discover devices** to auto-find peers (devices must be on the same subnet)
4. Or manually enter IP addresses via **Add device manually**
5. Progress syncs automatically

## Requirements

- All devices must be on the same local network / subnet (UDP broadcast won't cross routers)
- No internet connection required
- No accounts or registration
- Firewall must allow TCP 8765 and UDP 8766
