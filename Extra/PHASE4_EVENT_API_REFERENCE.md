# 📘 Phase 4 Event API Reference

**Status:** ✅ Implementation Complete  
**Date:** July 1, 2026  
**Architecture Version:** §20

---

## Table of Contents

1. [Overview](#overview)
2. [Event Hierarchy](#event-hierarchy)
3. [EventBus API](#eventbus-api)
4. [Integration Guide](#integration-guide)
5. [Migration Path](#migration-path)
6. [Examples](#examples)
7. [Testing](#testing)

---

## Overview

### What is Phase 4?

Phase 4 replaces ServiceClient's 11+ scattered listener lists with a centralized, type-safe **EventBus** system.

**Before Phase 4:**
```
ServiceClient with 11+ listener lists:
├── jobListListeners (List<Consumer<List<WatchJob>>>)
├── jobStateListeners (List<Consumer<WatchJob>>)
├── eventListeners (List<Consumer<TransferEvent>>)
├── notifListeners (List<Consumer<NotificationMessage>>)
├── connectListeners (List<Runnable>)
├── disconnectListeners (List<Runnable>)
├── healthListeners (List<Consumer<JsonObject>>)
├── credentialListeners (List<Consumer<List<CredentialMessage>>>)
├── testCredentialListeners (List<BiConsumer<String, String>>)
├── remoteDirListeners (List<RemoteDirListener>)
├── logsListeners (List<Consumer<List<LogEntryMessage>>>)
├── logsExportListeners (List<Consumer<String>>)
└── configListeners (List<Consumer<JsonObject>>)
```

**After Phase 4:**
```
ServiceClient with centralized EventBus:
├── eventBus (EventBus)
│   ├── jobListListeners → dispatch JobListEvent
│   ├── jobStateListeners → dispatch JobStateEvent
│   ├── eventListeners → dispatch TransferEventFired
│   ├── ... (all 12 types) ...
│   └── configListeners → dispatch ConfigurationEvent
└── Legacy listener methods (backward compatible)
```

### Benefits

| Aspect | Before | After |
|--------|--------|-------|
| Type Safety | No | ✅ Compile-time checking |
| Thread Safety | Manual | ✅ Automatic Platform.runLater() |
| Extensibility | Requires ServiceClient edit | ✅ Just create new UIEvent subclass |
| Code Clarity | Scattered lists | ✅ Centralized pattern |
| Testing | Difficult | ✅ Mock EventBus easily |

---

## Event Hierarchy

All events inherit from the base `UIEvent` class:

```
UIEvent (abstract base)
├── timestamp: LocalDateTime
├── source: String
├── getTimestamp()
├── getSource()
└── toString()
```

### 12 Event Types

**1. JobListEvent**
```java
class JobListEvent extends UIEvent {
    List<WatchJob> jobs
    List<WatchJob> getJobs()
}
```
Fired: When UI connects (INIT message)

**2. JobStateEvent**
```java
class JobStateEvent extends UIEvent {
    WatchJob job
    WatchJob getJob()
}
```
Fired: When any job's state changes

**3. TransferEventFired**
```java
class TransferEventFired extends UIEvent {
    TransferEvent event
    TransferEvent getEvent()
}
```
Fired: When a file transfer occurs

**4. NotificationEvent**
```java
class NotificationEvent extends UIEvent {
    NotificationMessage notification
    NotificationMessage getNotification()
}
```
Fired: When an error/warning is reported

**5. ConnectionEvent**
```java
class ConnectionEvent extends UIEvent {
    boolean connected
    String reason
    boolean isConnected()
    String getReason()
}
```
Fired: On WebSocket connect/disconnect

**6. HealthEvent**
```java
class HealthEvent extends UIEvent {
    JsonObject stats
    JsonObject getStats()
}
```
Fired: When health statistics arrive

**7. CredentialEvent**
```java
class CredentialEvent extends UIEvent {
    List<CredentialMessage> credentials
    List<CredentialMessage> getCredentials()
}
```
Fired: When credential list is received

**8. TestCredentialEvent**
```java
class TestCredentialEvent extends UIEvent {
    String credentialId
    String error
    boolean success
    String getCredentialId()
    String getError()
    boolean isSuccess()
}
```
Fired: When credential test completes

**9. RemoteDirEvent**
```java
class RemoteDirEvent extends UIEvent {
    String path
    List<String> entries
    String error
    String getPath()
    List<String> getEntries()
    String getError()
    boolean isError()
}
```
Fired: When remote directory listing completes (one-shot)

**10. LogsEvent**
```java
class LogsEvent extends UIEvent {
    List<LogEntryMessage> logs
    List<LogEntryMessage> getLogs()
}
```
Fired: When logs are received

**11. LogsExportEvent**
```java
class LogsExportEvent extends UIEvent {
    String csv
    String getCsv()
}
```
Fired: When log export completes

**12. ConfigurationEvent**
```java
class ConfigurationEvent extends UIEvent {
    JsonObject config
    JsonObject getConfig()
}
```
Fired: When configuration is received or updated

---

## EventBus API

### Core Methods

#### subscribe(Class<T>, Consumer<T>)
```java
/**
 * Subscribe to events of a specific type.
 * Listener will be called on the JavaFX Application Thread.
 *
 * @param eventType Type of event to listen for
 * @param listener  Callback to receive typed events
 */
public <T extends UIEvent> void subscribe(Class<T> eventType, Consumer<T> listener)
```

**Example:**
```java
eventBus.subscribe(JobStateEvent.class, event -> {
    WatchJob job = event.getJob();
    System.out.println("Job " + job.getId() + " state changed");
});
```

#### dispatch(UIEvent)
```java
/**
 * Dispatch an event to all listeners subscribed to its type.
 * Thread-safe, can be called from any thread.
 * Listener invocations deferred to JavaFX Application Thread.
 *
 * @param event The event to dispatch
 */
public void dispatch(UIEvent event)
```

**Note:** Called internally by ServiceClient when WebSocket messages arrive. UI code doesn't typically call this.

#### unsubscribe(Class<T>, Consumer<T>)
```java
/**
 * Unsubscribe a listener from an event type.
 *
 * @param eventType Type of event
 * @param listener  Listener to remove
 */
public <T extends UIEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener)
```

#### getListenerCount(Class<T>)
```java
/**
 * Get count of listeners for a specific event type (debugging/testing).
 *
 * @param eventType Type of event
 * @return Number of listeners
 */
public int getListenerCount(Class<? extends UIEvent> eventType)
```

#### clear()
```java
/**
 * Clear all listeners (useful for testing or cleanup).
 */
public void clear()
```

#### getDiagnostics()
```java
/**
 * Get diagnostic info about registered listeners.
 * Example output:
 *   EventBus Diagnostics:
 *   JobStateEvent: 3 listener(s)
 *   ConnectionEvent: 1 listener(s)
 *
 * @return String representation of registry state
 */
public String getDiagnostics()
```

---

## Integration Guide

### How EventBus Fits In

```
┌─────────────────────────────────────────────────┐
│ Service (filewatcher-service)                   │
│ - FileWatcherService (emits service events)     │
│ - ServiceWebSocketServer (broadcasts to UI)     │
└────────────────────┬────────────────────────────┘
                     │
                     │ WebSocket JSON message
                     ↓
┌─────────────────────────────────────────────────┐
│ ServiceClient (filewatcher-ui)                  │
│ - onMessage() parses JSON                       │
│ - Creates typed UIEvent objects                 │
│ - Calls eventBus.dispatch(event)                │
│ - Also calls legacy listeners (for backward     │
│   compatibility)                                │
└────────────────────┬────────────────────────────┘
                     │
                     ↓
         ┌───────────────────────┐
         │ EventBus              │
         │ ├─ Type Registry      │
         │ ├─ Thread Safety      │
         │ │  (Platform.runLater)│
         │ └─ Listener Dispatch  │
         └────────┬──────────────┘
                  │
         ┌────────┴─────────┬──────────────┐
         ↓                  ↓              ↓
    ┌─────────┐         ┌────────┐    ┌─────────┐
    │ Panel A │         │Panel B │    │ Panel C │
    │(listens │         │(listens│    │(listens │
    │ for X)  │         │ for Y) │    │ for Z)  │
    └─────────┘         └────────┘    └─────────┘
```

### Getting the EventBus

```java
ServiceClient client = new ServiceClient();
EventBus eventBus = client.getEventBus();
```

### Subscribing to Events

```java
// Example 1: Listen for job state changes
eventBus.subscribe(JobStateEvent.class, event -> {
    WatchJob job = event.getJob();
    System.out.println("Job " + job.getId() + " is now " + job.getStatus());
    updateJobTablePanel(job);
});

// Example 2: Listen for connection status
eventBus.subscribe(ConnectionEvent.class, event -> {
    if (event.isConnected()) {
        System.out.println("Connected! Reason: " + event.getReason());
        refreshAllPanels();
    } else {
        System.out.println("Disconnected! Reason: " + event.getReason());
        showErrorNotification("Lost connection to service");
    }
});

// Example 3: Listen for configuration changes
eventBus.subscribe(ConfigurationEvent.class, event -> {
    JsonObject config = event.getConfig();
    updateSettingsPanelWithConfig(config);
});
```

---

## Migration Path

### Stage 1: Coexistence (Current - Phase 4A)
- Old listener methods still work (backward compatible)
- New code can use EventBus
- Both run simultaneously

```java
// Old way (still works):
client.addJobStateListener(job -> {
    System.out.println("Job: " + job.getId());
});

// New way (preferred):
client.getEventBus().subscribe(JobStateEvent.class, event -> {
    System.out.println("Job: " + event.getJob().getId());
});
```

### Stage 2: Migration (Phase 4B)
- Update MainWindow to use EventBus
- Update each UI panel to listen for typed events
- Keep old listeners for backward compatibility

### Stage 3: Cleanup (Phase 4C+)
- Remove old listener lists from ServiceClient (optional)
- All panels using EventBus
- Deprecate old methods

**Current Status:** Stage 1 (Coexistence) ✅

---

## Examples

### Example 1: JobTablePanel using EventBus

**Before Phase 4:**
```java
class JobTablePanel {
    private final ServiceClient client;
    
    public JobTablePanel(ServiceClient client) {
        this.client = client;
        client.addJobListListener(this::setJobs);
        client.addJobStateListener(this::updateJob);
    }
    
    private void setJobs(List<WatchJob> jobs) {
        // update table...
    }
    
    private void updateJob(WatchJob job) {
        // update row...
    }
}
```

**After Phase 4:**
```java
class JobTablePanel {
    private final ServiceClient client;
    
    public JobTablePanel(ServiceClient client) {
        this.client = client;
        EventBus eventBus = client.getEventBus();
        
        // Type-safe event subscription
        eventBus.subscribe(JobListEvent.class, event ->
            setJobs(event.getJobs())
        );
        
        eventBus.subscribe(JobStateEvent.class, event ->
            updateJob(event.getJob())
        );
    }
    
    private void setJobs(List<WatchJob> jobs) {
        // update table...
    }
    
    private void updateJob(WatchJob job) {
        // update row...
    }
}
```

**Benefits:**
- ✅ Type-safe (compiler checks event types)
- ✅ Clear intent (event type is explicit)
- ✅ Thread-safe (Platform.runLater handled automatically)
- ✅ Scalable (add new events without ServiceClient changes)

### Example 2: LogsPanel auto-refresh on reconnect

**Before Phase 4:**
```java
class LogsPanel {
    public LogsPanel(ServiceClient client) {
        client.addConnectListener(this::refreshFromServer);
    }
    
    private void refreshFromServer() {
        client.getLogs();
    }
}
```

**After Phase 4:**
```java
class LogsPanel {
    public LogsPanel(ServiceClient client) {
        EventBus eventBus = client.getEventBus();
        
        // React to connection events
        eventBus.subscribe(ConnectionEvent.class, event -> {
            if (event.isConnected()) {
                refreshFromServer();
            }
        });
        
        // React to log events
        eventBus.subscribe(LogsEvent.class, event -> {
            displayLogs(event.getLogs());
        });
    }
    
    private void refreshFromServer() {
        client.getLogs();
    }
    
    private void displayLogs(List<LogEntryMessage> logs) {
        // update UI...
    }
}
```

### Example 3: Settings panel listening for config changes

```java
class SettingsPanel {
    private final ServiceClient client;
    private final EventBus eventBus;
    
    public SettingsPanel(ServiceClient client) {
        this.client = client;
        this.eventBus = client.getEventBus();
        
        // Listen for configuration events
        eventBus.subscribe(ConfigurationEvent.class, this::onConfigurationUpdate);
    }
    
    private void onConfigurationUpdate(ConfigurationEvent event) {
        JsonObject config = event.getConfig();
        
        // Update form fields from config
        if (config.has("logging")) {
            JsonObject logging = config.getAsJsonObject("logging");
            updateLoggingFields(logging);
        }
        
        if (config.has("polling")) {
            JsonObject polling = config.getAsJsonObject("polling");
            updatePollingFields(polling);
        }
        
        showMessage("Settings updated from service");
    }
}
```

---

## Testing

### Unit Test Example

```java
public class JobTablePanelTest {
    private ServiceClient mockClient;
    private EventBus eventBus;
    private JobTablePanel panel;
    
    @Before
    public void setup() {
        mockClient = mock(ServiceClient.class);
        eventBus = new EventBus();
        when(mockClient.getEventBus()).thenReturn(eventBus);
        
        panel = new JobTablePanel(mockClient);
    }
    
    @Test
    public void testJobStateEventUpdatesTable() {
        // Arrange
        WatchJob job = new WatchJob();
        job.setId("job-1");
        job.setStatus("RUNNING");
        
        JobStateEvent event = new JobStateEvent(job);
        
        // Act
        eventBus.dispatch(event);
        
        // Assert - verify panel updated
        verify(panel).updateJob(job);
    }
    
    @Test
    public void testConnectionEventRefreshesData() {
        // Arrange
        ConnectionEvent event = new ConnectionEvent(true, "Connected");
        
        // Act
        eventBus.dispatch(event);
        
        // Assert - verify panel refreshed
        verify(mockClient).getJobs();
    }
}
```

### Integration Test Example

```java
public class ServiceClientEventDispatchTest {
    private ServiceClient client;
    private EventBus eventBus;
    
    @Before
    public void setup() throws Exception {
        client = new ServiceClient();
        eventBus = client.getEventBus();
    }
    
    @Test
    public void testJobStateEventDispatched() {
        // Arrange
        List<WatchJob> receivedJobs = new ArrayList<>();
        eventBus.subscribe(JobListEvent.class, event -> {
            receivedJobs.addAll(event.getJobs());
        });
        
        // Simulate WebSocket message
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "INIT");
        msg.add("jobs", /* ... job JSON ... */);
        
        // Act
        client.onMessage(msg.toString());
        
        // Assert
        assertEquals(1, receivedJobs.size());
    }
}
```

---

## Summary

**Phase 4 delivers:**

✅ **12 typed event classes** — JobListEvent through ConfigurationEvent  
✅ **EventBus** — Centralized, type-safe event dispatch  
✅ **ServiceClient integration** — All 12 event types dispatched  
✅ **Backward compatibility** — Old listeners still work  
✅ **Thread safety** — Automatic Platform.runLater()  
✅ **Extensibility** — Adding new events is now trivial  
✅ **Comprehensive documentation** — This guide + examples  

**Next Steps:** Phase 4B will update MainWindow and UI panels to use EventBus subscribers.

---

**Status:** ✅ Phase 4A Implementation Complete  
**Review Date:** July 8, 2026  
**Next Milestone:** Phase 4B UI Panel Updates

