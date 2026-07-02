# FileWatcher UI ↔ Service Integration Summary

## Overview

The FileWatcher UI has been successfully integrated with the FileWatcher Service backend. The UI no longer relies exclusively on mock data and can now receive real job information and transfer events from the running backend service.

## What Changed

### 1. New WebSocket Client Implementation
**File**: `filewatcher-ui/src/main/java/com/filewatcher/service/WebSocketServiceClient.java`

- Full `java_websocket` (org.java-websocket) implementation replacing the skeletal starting point
- Handles connection lifecycle (connect, disconnect, reconnection with exponential backoff)
- Parses incoming JSON messages and converts backend models to UI models:
  - `WatchJob` → `Job`
  - `TransferEvent` → `ServiceEvent`
- Sends commands (START, STOP, RESTART, DELETE) as JSON to the backend
- Automatically reconnects on connection loss

**Key Features**:
- JSON deserialization using Gson (matching backend serialization)
- Reconnection strategy: starts with 1s delay, doubles up to 32s max
- Thread-safe, concurrent command handling
- Separate handler for initial jobs list (INIT message)

### 2. Updated MainApp Startup Flow
**File**: `filewatcher-ui/src/main/java/com/filewatcher/MainApp.java`

**Before**:
```java
AppState state = AppState.seedDemoData();
ServiceClient client = new MockServiceClient(state);
```

**After**:
```java
WebSocketServiceClient client = new WebSocketServiceClient("localhost", 9876);
client.connect()
    .thenCompose(v -> client.getInitialJobs())
    .thenAccept(backendJobs -> {
        // Convert to UI jobs and populate AppState
        // Then show main UI
    })
    .exceptionally(ex -> {
        // Fall back to demo mode if backend is unavailable
    });
```

**Startup Sequence**:
1. Show "Connecting..." dialog
2. Connect to backend WebSocket server
3. Receive INIT message with all jobs
4. Convert backend WatchJob objects to UI Job objects
5. Populate AppState with real jobs
6. Show main UI and start listening for real-time events
7. If backend unavailable, fall back to demo mode

### 3. Job Model Conversion
**File**: `filewatcher-ui/src/main/java/com/filewatcher/MainApp.java` (helper methods)

Conversion logic maps backend WatchJob fields to UI Job fields:

| Backend | UI |
|---------|-----|
| `id` | `id` |
| `name` | `name` |
| `protocol` + `direction` | `type` (e.g., "SFTP Watch") |
| `sourcePath`/`sourceHost` | `sourcePath` |
| `destPath`/`destHost` | `destPath` |
| `intervalSeconds` | `pollingInterval` (formatted as "15s") |
| `sourceUser` | `credential` |
| `status` (WATCHING→RUNNING, etc.) | `status` |
| `filesTransferred` | `filesToday` |
| `lastTransfer` | `lastTransfer` (human-readable: "2m ago") |

### 4. Dependencies Added
**File**: `filewatcher-ui/pom.xml`

New dependencies:
```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
</dependency>
<dependency>
    <groupId>org.java-websocket</groupId>
    <artifactId>Java-WebSocket</artifactId>
</dependency>
<dependency>
    <groupId>com.filewatcher</groupId>
    <artifactId>filewatcher-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

Also updated to inherit from parent pom for managed dependency versions.

### 5. Event Mapping
Backend transfer events are mapped to UI ServiceEvents:

| Backend EventType | → | UI ServiceEventType |
|-----------------|---|---|
| `TRANSFERRED` | → | `TRANSFER_COMPLETED` |
| `ERROR` | → | `TRANSFER_FAILED` |
| `DETECTED` | → | `HEARTBEAT` |
| Other | → | `HEARTBEAT` |

Job status mapping:
- Backend `WATCHING` → UI `RUNNING`
- Backend `TRANSFERRING` → UI `RUNNING`
- Backend `IDLE`/`PAUSED`/`ERROR` → UI `STOPPED`

## How to Use

### Starting the Backend
```bash
cd filewatcher-service
mvn clean package
java -jar target/filewatcher-service-1.0.0.jar
```

Default: Listens on `localhost:9876`

### Starting the UI
```bash
cd filewatcher-ui
mvn javafx:run
```

The UI will:
1. Attempt to connect to `localhost:9876`
2. Load real jobs from the backend
3. Display the main window with live job data
4. If backend is unavailable, fall back to demo mode automatically

### Customizing the Backend URL
Edit `MainApp.java`:
```java
WebSocketServiceClient client = new WebSocketServiceClient("your-host", 9876);
```

Or make it configurable via environment variables/config file:
```java
String host = System.getProperty("filewatcher.backend.host", "localhost");
int port = Integer.parseInt(System.getProperty("filewatcher.backend.port", "9876"));
WebSocketServiceClient client = new WebSocketServiceClient(host, port);
```

## Message Protocol

### UI → Service (Commands)
```json
{
  "cmd": "START_JOB",
  "id": "job-123"
}
```

Commands: `GET_JOBS`, `START_JOB`, `STOP_JOB`, `DELETE_JOB`, etc.
(See `WsCommands` constants in `filewatcher-common`)

### Service → UI (Events)
```json
{
  "type": "INIT",
  "jobs": [
    {
      "id": "job-1",
      "name": "PAX-Manifest-Sync",
      "status": "WATCHING",
      ...
    }
  ]
}
```

Message types: `INIT`, `JOB_STATE`, `EVENT`, `NOTIFICATION`, etc.
(See `WsTypes` constants in `filewatcher-common`)

## Fallback to Demo Mode

If the backend is unreachable:
- UI title changes to "Demo Mode"
- MockServiceClient takes over
- Simulated events fire every ~4 seconds
- Full functionality works without real backend
- Allows UI testing without running service

## Testing the Integration

### 1. Full Integration Test
```bash
# Terminal 1: Start backend
cd filewatcher-service && mvn clean package && java -jar target/filewatcher-service-*.jar

# Terminal 2: Start UI
cd filewatcher-ui && mvn javafx:run
```

Expected behavior:
- UI shows "Connecting..." briefly
- UI loads and displays real jobs from backend
- Jobs show real status (WATCHING, IDLE, etc.)
- Transfer events appear in real-time
- Add/Start/Stop/Delete operations work end-to-end

### 2. Demo Mode Fallback Test
```bash
# Start UI WITHOUT backend running
cd filewatcher-ui && mvn javafx:run
```

Expected behavior:
- UI shows "Connecting..." for ~10 seconds
- Falls back to demo mode
- UI title shows "Demo Mode"
- Simulated events continue every ~4 seconds
- No errors in console

### 3. Reconnection Test
```bash
# Start UI
cd filewatcher-ui && mvn javafx:run

# Wait for connection
# Kill backend (Ctrl+C)
# Observe: UI attempts reconnect (check logs)
# Restart backend
# Observe: UI reconnects and updates
```

Expected behavior:
- See "Reconnecting..." messages in logs
- UI attempts to reconnect with increasing delays
- When backend restarts, UI reconnects automatically
- Job list updates with any changes

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        MainApp                              │
│  - Manages startup, connection, and error handling          │
│  - Converts backend jobs to UI jobs                         │
└────────────┬────────────────────────────────────────────────┘
             │
             v
┌─────────────────────────────────────────────────────────────┐
│              WebSocketServiceClient                         │
│  - Implements ServiceClient interface                       │
│  - Handles WebSocket lifecycle                              │
│  - Parses JSON messages from backend                        │
│  - Sends commands as JSON                                   │
│  - Auto-reconnects on failure                               │
└────────────┬────────────────────────────────────────────────┘
             │
    ┌────────v────────┐
    │  EventDispatcher│
    │  - Listens to   │
    │    ServiceEvents│
    │  - Updates      │
    │    AppState     │
    └────────┬────────┘
             │
             v
    ┌────────────────┐
    │   AppState     │
    │  (Observable   │
    │   Collections) │
    └────────┬────────┘
             │
             v
    ┌────────────────┐
    │  JavaFX Views  │
    │  (Bound to     │
    │   AppState)    │
    └────────────────┘
```

## Known Limitations

1. **Credential handling**: Uses `sourceUser` as credential label; full credential management is separate
2. **Port numbers**: Remote URLs don't include non-standard ports (always uses protocol defaults)
3. **Error messages**: Limited error details in UI; full details available in service logs
4. **Auto-refresh**: No periodic refresh; only real-time events trigger updates

## Files Modified

- ✅ `filewatcher-ui/src/main/java/com/filewatcher/MainApp.java` - Startup flow
- ✅ `filewatcher-ui/src/main/java/com/filewatcher/service/WebSocketServiceClient.java` - New implementation
- ✅ `filewatcher-ui/pom.xml` - Dependencies and parent reference
- ✅ `filewatcher-ui/README.md` - Updated documentation

## Backward Compatibility

- ✅ All existing views work unchanged (still bound to AppState)
- ✅ EventDispatcher unchanged
- ✅ ServiceClient interface unchanged
- ✅ MockServiceClient still available for demo/testing
- ✅ No breaking changes to public APIs

## Next Steps

1. Test the full integration with a running backend service
2. Add configuration file support (config.properties or YAML)
3. Add UI indicators for WebSocket connection status
4. Implement credential management UI
5. Add job CRUD wizard (currently stubbed in ServiceManagementView)
6. Add CSV export for logs
