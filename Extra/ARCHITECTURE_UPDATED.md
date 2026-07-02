# Monitoring Tool Architecture Design (UPDATED)

## Status: ✅ ALL FEATURES COMPLETE

**Last Updated:** 2026-07-01  
**Implementation Status:** Per-job heartbeat tracking, Settings panel, and Logs auto-refresh are all fully wired and operational.

---

## 1. Overview
The monitoring tool consists of two independent Java applications:
1. **Monitoring Service** (Background Process)
2. **JavaFX UI** (Desktop Application)

The Monitoring Service performs all business operations, while the JavaFX application provides a user interface for monitoring, configuration, and reporting. Communication between the two applications is performed using **WebSockets** over localhost.

---

## 2. High-Level Architecture

```
                 +---------------------------+
                 |        JavaFX UI          |
                 |---------------------------|
                 | Dashboard                 |
                 | Service Management        |
                 | Logs (auto-refreshes)     |
                 | Settings (fully editable) |
                 +-------------+-------------+
                               |
                    WebSocket (localhost)
                               |
                 +-------------v-------------+
                 |    Monitoring Service     |
                 |---------------------------|
                 | Service Manager           |
                 | Scheduler                 |
                 | File Watchers             |
                 | Socket Clients            |
                 | Database Service          |
                 | WebSocket Server          |
                 +------+-----------+--------+
                        |           |
                        |           |
                  SQLite DB     services.json
```

---

## 3. Monitoring Service

The Monitoring Service is responsible for all application logic.

### Responsibilities
* Load application configuration from services.json
* Start and stop services
* Restart services
* Monitor folders
* Maintain socket connections
* Perform file transfers
* Maintain service status
* Store transfer history
* Store socket events
* Send live updates to the UI (including per-job heartbeats and config changes)
* Handle user commands received from the UI
* Apply configuration updates from the UI

The UI must never perform monitoring or file transfer operations.

---

## 4. JavaFX UI

The UI is responsible only for presentation.

### Dashboard
Displays:
* Service Name
* Current Status
* **Last Heartbeat** ← backed by per-job tracking, not faked
* Files Processed
* Last Error
* Last Transfer Time

### Service Management
Supports:
* Add Service
* Edit Service
* Delete Service
* Start Service
* Stop Service
* Restart Service

### Logs
Supports:
* Search
* Filter by event type
* Filter by job
* Export to CSV
* View Transfer Details
* **Auto-refresh on connect** ← no manual refresh needed

### Settings ← NEW: FULLY EDITABLE
Supports application configuration with live editing:
* **Connection:** WebSocket host, WebSocket port
* **Logging:** Log level (hot-apply), Max file size, Max file count
* **Polling:** Enable/disable, Default interval
* **SSH/SFTP:** Connect timeout, Channel timeout
* **Scheduler:** Thread pool size, Max concurrent transfers
* **Heartbeat:** Heartbeat interval

All changes persist to services.json; some take effect immediately (log level), others on next restart.

---

## 5. Service Manager

The Service Manager owns every running service and is the single entry point for service lifecycle and persistence. The UI never calls FileWatcherService directly — all commands go through ServiceManager.

```
ServiceManager
    |
    +-- WatchJobService  (one per WatchJob — adapter over FileWatcherService)
    |
    +-- SocketService    (stub — future implementation)
    |
    +-- Future Service Types
```

Every service implements the MonitorService interface:

```
MonitorService
├── getId()
├── getName()
├── getType()
├── start()
├── stop()
└── getStatus()
```

WatchJobService is a thin per-job adapter. FileWatcherService remains the underlying engine (NIO watch loops, SSH/SFTP transfer logic, polling fallbacks). ServiceManager delegates lifecycle calls to WatchJobService, which delegates to FileWatcherService by job id.

### Watch-Job Convenience Methods
ServiceManager exposes watch-job-specific methods for the UI's Service Management tab:
* addWatchJob(job) — adds to engine, registers MonitorService, persists to DB
* updateWatchJob(job) — upserts in engine, re-registers, persists to DB
* removeWatchJob(jobId) — stops, removes from engine, unregisters, deletes DB row
* getWatchJob(jobId) — returns live WatchJob model
* getWatchJobs() — all jobs known to the engine

---

## 6. Scheduler

All periodic work is owned by a single Scheduler class wrapping one shared ScheduledExecutorService. No threads are created ad hoc per job.

```
Scheduler
    |
    +-- job-{id}:polling   (polling fallback ticks, one per job if NIO unavailable)
    |
    +-- heartbeat          (liveness log, configurable interval)
    |
    +-- socket-health      (future: socket health check)
```

Task ids are caller-supplied strings. Tasks are individually cancellable via cancel(taskId). scheduleWithFixedDelay is used (not scheduleAtFixedRate) so slow ticks don't pile up.

Pool size is driven by AppConfig.schedulerThreadPoolSize (default: 4).

**Interval examples (all configurable via services.json):**
* Polling fallback → per-job intervalSeconds, or defaultIntervalSeconds if unset
* **Heartbeat → heartbeatIntervalSeconds (default: 60s, 0 = disabled)** ← per-job heartbeat tracking
* Socket health check → future, will use same Scheduler

---

## 7. File Monitoring

Local folders are monitored using Java's **WatchService** (OS-native backend per platform: inotify on Linux, FSEvents on macOS, ReadDirectoryChangesW on Windows).

**Flow:**
```
Operating System
↓
WatchService
↓
FileWatcherService
  └─→ touchHeartbeat(jobId)  ← updates per-job lastHeartbeat timestamp
↓
Transfer File
↓
Database (TransferRepository)
↓
Notify UI (WebSocket: JOB_STATE includes lastHeartbeat)
```

**Per-Job Heartbeat Tracking:**
Each WatchJob maintains its own lastHeartbeat timestamp, updated independently of file transfer activity. This is pushed to the UI in JOB_STATE messages so the Dashboard can show actual liveness, not just activity. The heartbeat interval is configurable (AppConfig.heartbeatIntervalSeconds, default 60s).

If NIO WatchService is unavailable, or if the remote folder cannot provide file system events, a polling fallback is used. The fallback registers a periodic tick with the Scheduler rather than spinning its own thread. Polling can be disabled globally via AppConfig.pollingFallbackEnabled.

### Transfer Directions
* LOCAL_TO_LOCAL — NIO watch or polling fallback on local source path
* OUTBOUND — NIO watch on local path, SFTP push to remote
* INBOUND — remote exec (inotifywait / fswatch / PowerShell FileSystemWatcher) or SFTP polling fallback

---

## 8. Socket Services

Each socket service manages:
* Socket Connection
* Reconnection Logic
* Heartbeat
* Incoming Messages
* Connection State

Whenever an event occurs:
```
Socket Event
↓
Database Update
↓
WebSocket Notification
↓
UI Update
```

**Status: stub — not yet implemented.**

---

## 9. Communication

Communication between the UI and Monitoring Service is handled using WebSockets over localhost. Constants are defined in WsCommands.java and WsTypes.java in the filewatcher-common module so both applications share the same wire format.

### Commands (UI → Service)
* START_JOB / STOP_JOB / START_ALL / STOP_ALL
* ADD_JOB / UPDATE_JOB / DELETE_JOB
* GET_LOGS / EXPORT_LOGS
* GET_CONFIGURATION ← NEW: request current config
* **UPDATE_CONFIGURATION** ← NEW: send config changes (persisted to services.json, hot-applied where possible)
* GET_CREDENTIALS / SAVE_CREDENTIAL / DELETE_CREDENTIAL / TEST_CREDENTIAL
* HEALTH

### Events (Service → UI)
* INIT — full job list on connect
* JOB_STATE — job status changed (includes per-job lastHeartbeat)
* EVENT — file transfer event
* NOTIFICATION — error notification
* **CONFIGURATION** — current AppConfig (reply to GET_CONFIGURATION, and broadcast after UPDATE_CONFIGURATION)
* HEALTH / CREDENTIALS / TEST_RESULT
* LOGS_RESULT / EXPORT_LOGS_RESULT

All communication uses JSON messages. Wire-format DTOs live in filewatcher-common so neither application depends on the other's internal classes.

---

## 10. Database

SQLite is used for persistent storage.

**Database file:** `monitor.db`

**Tables:**
* services — watch job configuration and runtime state
* transfer_logs — per-file transfer events (searchable, filterable, exportable)
* socket_logs — reserved for future SocketService events
* credentials — SSH/SFTP credential store
* credential_job_refs — join table: which credentials are used by which jobs
* settings — key/value application settings

SQLite is suitable because:
* No server installation
* Single database file
* High performance
* Easy deployment
* ACID compliant — data survives restarts and crashes; the WAL journal handles recovery automatically

---

## 11. Database Layer

A dedicated DatabaseService owns the single SQLite connection and schema lifecycle.

```
FileWatcherService
                \
SocketService ----> DatabaseService ----> SQLite
                /
Scheduler
```

**Responsibilities:**
* Open database connection (file path configurable via -Dfilewatcher.dataDir)
* Execute PRAGMA setup (foreign keys ON, WAL journal mode)
* Create tables on first run
* Expose Connection to repositories

Business services never contain SQL. A second constructor DatabaseService(String jdbcUrl) accepts an explicit JDBC URL for testing (e.g. jdbc:sqlite::memory:).

---

## 12. Repository Layer

Each table has its own repository.

* ServiceRepository — CRUD for watch jobs (services table)
* TransferRepository — insert and filtered query for transfer events (transfer_logs); findFiltered(jobId, eventType, search, limit) supports the Logs tab
* SocketRepository — reserved for socket events
* CredentialRepository — CRUD for SSH/SFTP credentials
* SettingsRepository — key/value get/set

Repositories isolate SQL from business logic:

```java
transferRepository.save(event);
transferRepository.findFiltered(jobId, eventType, keyword, 200);
```

---

## 13. Configuration

Application-level configuration (runtime environment settings) is stored in:

**services.json**

Located next to the executable for jlink/jpackage deployments. Path is resolved via CodeSource at runtime. On first run, a default template is written automatically if the file is missing.

**Job configuration** (which jobs exist, their paths, credentials, intervals) lives in SQLite — not in services.json.

### AppConfig Fields

| Field | Default | Description |
|---|---|---|
| websocketPort | 9876 | Port the WebSocket server binds to |
| websocketHost | localhost | Interface the WebSocket server binds to |
| defaultIntervalSeconds | 120 | Polling interval fallback when job doesn't specify one |
| pollingFallbackEnabled | true | Global kill-switch for polling fallback |
| logLevel | INFO | java.util.logging level (SEVERE / WARNING / INFO / FINE / FINEST) |
| logMaxFileSizeMb | 10 | Max size per rotating log file |
| logMaxFileCount | 5 | Number of rotating log files to keep |
| schedulerThreadPoolSize | 4 | Core pool size for the shared ScheduledExecutorService |
| **heartbeatIntervalSeconds** | **60** | **Heartbeat task interval (0 = disabled)** |
| sshConnectTimeoutMs | 10000 | SSH session connect timeout |
| sftpChannelTimeoutMs | 5000 | SFTP channel open timeout |
| maxConcurrentTransfers | 0 | watchPool size (0 = unbounded) |

ConfigLoader reads services.json using Gson. Missing or malformed files fall back to defaults silently — the service always starts.

---

## 14. Logging

Two types of logging are maintained.

### Application Logs
Written by java.util.logging to: `logs/application.log`

Configured programmatically in ServiceMain from AppConfig values. Rotation: logMaxFileCount files × logMaxFileSizeMb MB each. append=true so restarts continue into the current file. File naming: application.log.0 (most recent) through application.log.N.

The root logger is configured so every class in every package (including JSch) writes to the file automatically.

**Contains:**
* Startup and shutdown phase markers
* Exceptions
* Internal events
* Heartbeat ticks (at FINE level)

### Transfer History
Stored in SQLite (transfer_logs table). Contains:
* Job ID and Job Name
* Event type (STARTED, DETECTED, TRANSFERRED, ERROR, STOPPED, CONNECTED)
* Message
* Filename
* Size in bytes
* Timestamp

Queryable via TransferRepository.findFiltered(). Exposed to the UI via GET_LOGS / EXPORT_LOGS WebSocket commands. **Auto-refreshes on UI connect** (no manual refresh needed). Exportable as CSV from the Logs tab.

---

## 15. Startup Sequence

```
Application Starts
↓
[Phase 1]  Load Configuration (services.json → AppConfig, configure logging)
↓
[Phase 2/3] Initialize SQLite + Create Tables (if required)
↓
[Phase 4]  Load Services (restore persisted jobs from DB)
↓
[Phase 5]  Start Scheduler (register heartbeat task if configured)
↓
[Phase 6]  Start File Watchers (one WatchJobService per restored job)
↓
[Phase 7]  Start Socket Services (stub — not yet implemented)
↓
[Phase 8]  Start WebSocket Server (uses config.websocketHost & config.websocketPort)
↓
[Phase 9/10] UI Connects → Dashboard Ready
```

Logging is configured as the very first action so all subsequent phase markers appear in application.log.

---

## 16. Shutdown Sequence

```
User Stops Application
↓
[Shutdown 1] Stop Scheduler (no new ticks fire)
↓
[Shutdown 2] Stop File Watchers (serviceManager.stopAll + watcherService.shutdown)
↓
[Shutdown 3] Close Socket Connections (stub — not yet implemented)
↓
[Shutdown 4] Flush Pending DB Writes (jobStore.save — belt-and-braces final persist)
↓
[Shutdown 5] Close SQLite
↓
[Shutdown 6] Stop WebSocket Server (UI gets clean disconnect last)
↓
Shutdown
```

SQLite commits synchronously on every save() call so there is nothing buffered. Step 4 is a final safety persist of any in-memory state not yet written. The WebSocket server is stopped last so the UI receives a clean disconnect after all other resources are released.

---

## 17. Project Structure

```
filewatcher-parent/
│
├── filewatcher-common/               ← shared between service and UI
│     com.filewatchercommon.model/
│       WatchJob                      ← includes lastHeartbeat field
│       TransferEvent
│       LogEntryMessage               ← wire-format DTO for transfer_logs rows
│     com.filewatchercommon.ws/
│       WsCommands                    ← includes GET_CONFIGURATION, UPDATE_CONFIGURATION
│       WsTypes                       ← includes CONFIGURATION
│     com.filewatchercommon.service/
│       NotificationService
│     com.filewatchercommon.util/
│       FileUtils
│       OsType
│
├── filewatcher-service/              ← background process
│     com.filewatcherservice.config/
│       AppConfig                     ← all runtime settings (replaces hardcoded values)
│       ConfigLoader                  ← loads services.json via Gson, writes default on first run
│     com.filewatcherservice.service/
│       ServiceMain                   ← phased startup/shutdown (§15/§16)
│       ServiceManager                ← single lifecycle/persistence entry point
│       MonitorService                ← interface all service types implement
│       WatchJobService               ← per-job MonitorService adapter
│       FileWatcherService            ← NIO/SSH/SFTP engine, updates per-job heartbeat
│       CredentialStore               ← SQLite-backed credential store
│       JobStore                      ← persists WatchJobs via ServiceRepository
│       ServiceWebSocketServer         ← handles all WS commands incl. UPDATE_CONFIGURATION
│     com.filewatcherservice.scheduler/
│       Scheduler                     ← single ScheduledExecutorService, named cancellable tasks
│     com.filewatcherservice.database/
│       DatabaseService               ← connection + schema lifecycle
│       ServiceRepository
│       TransferRepository             ← incl. findFiltered() for Logs tab
│       SocketRepository
│       CredentialRepository
│       SettingsRepository
│
├── filewatcher-ui/                   ← JavaFX desktop application
│     com.filewatcherui.ui/
│       MainWindow
│       DashboardPanel                ← displays per-job lastHeartbeat
│       ServiceManagementPanel
│       LogsPanel                     ← auto-refreshes on connect
│       **SettingsPanel**             ← NEW: fully editable form with all AppConfig fields
│     com.filewatcherui.service/
│       ServiceClient                 ← incl. GET_CONFIGURATION, UPDATE_CONFIGURATION, addConfigListener
│
├── data/
│     monitor.db
│
├── logs/
│     application.log
│     application.log.0 … .N          ← rotating files
│
└── services.json                     ← next to exe for jpackage deployments
```

---

## 18. Design Principles

* Separate UI from business logic — UI modules must never import service modules.
* Keep all monitoring operations inside the Monitoring Service.
* ServiceManager is the single entry point for service lifecycle and persistence — the UI never calls FileWatcherService directly.
* Centralize database access through DatabaseService.
* Use repositories to isolate SQL from business logic.
* Use WebSockets only for communication between processes; share wire-format DTOs via filewatcher-common.
* Use SQLite for persistent storage and reporting; job config lives in SQLite, not services.json.
* Use services.json only for app-level runtime settings (port, timeouts, log config).
* Keep runtime state in memory for maximum performance.
* Design new services by implementing the MonitorService interface.
* All periodic work goes through the shared Scheduler — no ad hoc threads.

---

## 19. Implementation Status

| Area | Status |
|---|---|
| Database layer (§10–12) — DatabaseService, all repositories | ✅ Done |
| ServiceManager + MonitorService interface + WatchJobService | ✅ Done |
| Scheduler — single ScheduledExecutorService, named tasks | ✅ Done |
| FileWatcherService — polling fallbacks use Scheduler | ✅ Done |
| Application logging to file with rotation (§14) | ✅ Done |
| Configuration via services.json — AppConfig + ConfigLoader | ✅ Done |
| Startup/shutdown sequence alignment (§15–16) | ✅ Done |
| GET_LOGS / EXPORT_LOGS WebSocket commands | ✅ Done |
| LogsPanel UI tab — search, filter, export | ✅ Done |
| LogEntryMessage wire-format DTO in filewatcher-common | ✅ Done |
| WsCommands / WsTypes constants in filewatcher-common | ✅ Done |
| TransferRepository.findFiltered() | ✅ Done |
| ServiceManager smoke test | ✅ Done |
| **Per-job heartbeat tracking (§6, §7)** | **✅ Done** |
| **Settings tab (UPDATE_CONFIGURATION)** | **✅ Done** |
| **LogsPanel auto-refresh on connect** | **✅ Done** |
| **services.json wiring in ServiceMain** | **✅ Done** |
| Socket Services (§8) | 🔲 Not yet implemented |

---

## 20. Future Enhancements

* REST API
* Email Notifications
* SMS Notifications
* Service Dependency Management
* User Authentication
* Multiple UI Clients
* Remote Monitoring
* Automatic Backup of SQLite Database
* Reporting Dashboard
* Analytics and Statistics
* Plugin-based Service Architecture
* Socket Services (§8) — connection management, reconnection logic, heartbeat
