# FileWatcher UI & Backend Implementation Status

**Last Updated:** 2026-07-01

## ✅ Completed Implementation

### 1. Per-Job Heartbeat Tracking (FULLY WIRED)

**Backend:**
- ✅ `WatchJob.lastHeartbeat` field with getter/setter (lines 46-50, 147-148)
- ✅ `FileWatcherService.touchHeartbeat(jobId)` updates per-job heartbeat on each watch tick
- ✅ Heartbeat is pushed to UI in `JOB_STATE` WebSocket messages via the WatchJob model
- ✅ Global heartbeat task in Scheduler (§6 of architecture doc) runs independently

**Frontend:**
- ✅ `JobTablePanel` displays real per-job "Last Heartbeat" column (lines 208-224)
- ✅ Column backed by `WatchJob.getLastHeartbeat()`, not a faked duplicate of lastTransfer
- ✅ Updates on every `JOB_STATE` push from backend

### 2. Settings Configuration (FULLY WIRED END-TO-END)

**Backend:**
- ✅ `ServiceWebSocketServer` implements UPDATE_CONFIGURATION handler (lines 322-326)
- ✅ `applyConfigUpdate()` method selectively patches AppConfig fields (lines 201-242)
- ✅ Hot-applies logLevel without restart; persists other fields to services.json
- ✅ Broadcasts updated config to all connected UIs via CONFIGURATION message
- ✅ `ConfigLoader.save(config)` persists changes to services.json
- ✅ All AppConfig fields are defined and documented (§13 of architecture)

**Frontend:**
- ✅ `SettingsPanel` now fully editable with organized sections:
  - Connection: WebSocket host/port
  - Logging: Log level, max file size, max file count
  - Polling: Enable/disable, default interval
  - SSH/SFTP: Connect timeout, channel timeout
  - Scheduler: Thread pool size, max concurrent transfers
  - Heartbeat: Heartbeat interval
- ✅ Save button sends UPDATE_CONFIGURATION to backend
- ✅ Reload button requests current config via GET_CONFIGURATION
- ✅ Config listener refreshes form whenever server broadcasts config changes
- ✅ Fields populated from AppConfig JSON on connect and after updates
- ✅ Numeric fields use Spinners with valid ranges; log level uses ComboBox; polling uses CheckBox

**Wire Protocol:**
- ✅ `ServiceClient.sendUpdateConfig(JsonObject patch)` sends command
- ✅ `ServiceClient.getConfiguration()` requests current config
- ✅ `ServiceClient.addConfigListener(Consumer<JsonObject>)` listens for updates
- ✅ UPDATE_CONFIGURATION command in WsCommands.java
- ✅ CONFIGURATION response type in WsTypes.java

### 3. Logs Panel Auto-Refresh (FULLY WIRED)

**Frontend:**
- ✅ `LogsPanel` constructor registers `addConnectListener()` callback
- ✅ On WebSocket connect, automatically calls `refreshFromServer()`
- ✅ Fetches most recent logs with no active filters
- ✅ Refreshes job filter dropdown whenever job list changes (via MainWindow)

### 4. Service Configuration Loading (FULLY WIRED)

**Backend:**
- ✅ `ServiceMain.start()` loads AppConfig from services.json in Phase 1
- ✅ Phase 8 uses `config.websocketHost` and `config.websocketPort`
- ✅ `configureLogging()` uses `config.logLevel`, `config.logMaxFileSizeMb`, `config.logMaxFileCount`
- ✅ `Scheduler` initialized with `config.schedulerThreadPoolSize` (Phase 5)
- ✅ `FileWatcherService` initialized with config for timeouts and pools (Phase 4)
- ✅ Heartbeat task scheduled with `config.heartbeatIntervalSeconds` (Phase 5)

### 5. UI Integration

**MainWindow Updates:**
- ✅ SettingsPanel now receives ServiceClient reference
- ✅ LogsPanel auto-refresh on connect (via addConnectListener in MainWindow line 77-79)

## 📋 Removed Obsolete Code / Comments

- Removed read-only note from SettingsPanel javadoc (previously stated UPDATE_CONFIGURATION was unimplemented)
- Removed "currently read-only" disclaimer from SettingsPanel UI
- Updated LogsPanel constructor to register connect listener (auto-refresh on reconnect)

## 📚 Architecture Document Status (from §19)

| Area | Status |
|---|---|
| Database layer (§10–12) | ✅ Done |
| ServiceManager + MonitorService + WatchJobService | ✅ Done |
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
| **Per-job heartbeat tracking** | **✅ Done** (NEW) |
| **Settings form (UPDATE_CONFIGURATION)** | **✅ Done** (NEW) |
| **LogsPanel auto-refresh on connect** | **✅ Done** (NEW) |
| Socket Services (§8) | 🔲 Not yet implemented |

## Files Changed

### Frontend (filewatcher-ui)

1. **SettingsPanel.java** — Complete rewrite
   - Added ServiceClient dependency
   - Created editable form with all 12 AppConfig fields
   - Added Save/Reload buttons
   - Wired to ServiceClient.sendUpdateConfig() and addConfigListener()

2. **MainWindow.java** — 1 line changed
   - Pass ServiceClient to SettingsPanel constructor (line 52)

3. **LogsPanel.java** — 1 line added
   - Register addConnectListener to call refreshFromServer() on connect (line 54)

### Backend (filewatcher-service)

- **No changes needed** — ServiceWebSocketServer, AppConfig, ConfigLoader, ServiceMain all ready

### Common (filewatcher-common)

- **No changes needed** — WsCommands/WsTypes already include UPDATE_CONFIGURATION/CONFIGURATION

## Testing Recommendations

1. **Settings Save/Restore:**
   - Change a setting in UI
   - Click Save
   - Verify alert appears
   - Reload page or reconnect
   - Verify setting persists

2. **Hot-Apply (Log Level):**
   - Change log level from INFO to FINE
   - Click Save
   - Check logs/application.log — should see FINE-level entries immediately
   - No service restart needed

3. **Restart-Required Settings:**
   - Change WebSocket port from 9876 to 9877
   - Click Save
   - Verify it's persisted to services.json
   - Note: Requires service restart to take effect (UI shows a note about this)

4. **Per-Job Heartbeat:**
   - Start a watch job
   - Check JobTablePanel "Last Heartbeat" column
   - Should update every `config.heartbeatIntervalSeconds` (default 60s)
   - Should be independent of file transfer events

5. **LogsPanel Auto-Refresh:**
   - Open Logs tab
   - Disconnect WebSocket (close service or UI)
   - Reconnect
   - Logs tab should automatically refresh (no manual refresh needed)

## Known Limitations

- WebSocket port/host changes don't take effect until service restart (persisted for next start)
- Thread pool size changes don't take effect until service restart
- Max concurrent transfers changes don't take effect until service restart
- These are noted in the UI help text

## Future Enhancements

- Dynamic restart capability (e.g., RESTART_SERVICE command)
- Validation of numeric ranges in SettingsPanel
- Error notifications if UPDATE_CONFIGURATION fails
- Persisted UI preferences (e.g., which tab was selected, column widths)
