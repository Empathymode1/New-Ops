# 🚀 Phase 4 Migration Guide for UI Panels

**Target Audience:** Developers updating panels to use EventBus  
**Difficulty Level:** Beginner  
**Estimated Time per Panel:** 10-15 minutes  

---

## Table of Contents

1. [Overview](#overview)
2. [Step-by-Step Migration](#step-by-step-migration)
3. [Common Patterns](#common-patterns)
4. [Before/After Checklists](#beforeafter-checklists)
5. [Troubleshooting](#troubleshooting)
6. [Testing Your Changes](#testing-your-changes)

---

## Overview

### What You're Changing

You're replacing direct `client.addXxxListener()` calls with `eventBus.subscribe()` calls.

**Key Differences:**

| Aspect | Old Way | New Way |
|--------|---------|---------|
| **Registration** | `client.addJobStateListener(job -> {})` | `eventBus.subscribe(JobStateEvent.class, event -> {})` |
| **Type** | Generic `Consumer<WatchJob>` | Specific `JobStateEvent` |
| **Threading** | Manual `Platform.runLater()` | Automatic (done by EventBus) |
| **Error Handling** | Your responsibility | EventBus handles silently |
| **Debugging** | Scattered across methods | Centralized via EventBus |

### Why You Should Migrate

✅ **Type Safety** — Compiler catches mistakes  
✅ **Consistency** — All panels follow same pattern  
✅ **Maintainability** — Easier to understand data flow  
✅ **Testing** — Mock EventBus in tests  
✅ **Performance** — Same efficiency, better clarity  

---

## Step-by-Step Migration

### Step 1: Identify All Listener Registrations

Open your panel and find all `client.add*Listener()` calls:

```java
public class MyPanel extends JPanel {
    public MyPanel(ServiceClient client) {
        // These are the ones to migrate:
        client.addJobListListener(this::setJobs);           // ← migrate
        client.addJobStateListener(this::updateJob);        // ← migrate
        client.addConnectListener(this::onConnected);       // ← migrate
        // ... other initializations
    }
}
```

### Step 2: Get EventBus Reference

Add this line in your constructor:

```java
public MyPanel(ServiceClient client) {
    EventBus eventBus = client.getEventBus();  // ← NEW
    
    // Now you can use eventBus for subscriptions
}
```

### Step 3: Migrate Each Listener

**Pattern 1: Method Reference (Recommended)**

```java
// Old way:
client.addJobStateListener(this::updateJob);

// New way:
eventBus.subscribe(JobStateEvent.class, event ->
    updateJob(event.getJob())
);
```

**Pattern 2: Lambda with Event Handling**

```java
// Old way:
client.addJobListListener(jobs -> {
    resetTable();
    jobs.forEach(this::addJobRow);
});

// New way:
eventBus.subscribe(JobListEvent.class, event -> {
    resetTable();
    event.getJobs().forEach(this::addJobRow);
});
```

**Pattern 3: Connection Status**

```java
// Old way (Runnable):
client.addConnectListener(() -> {
    LOG.info("Connected!");
    refreshData();
});

// New way (ConnectionEvent):
eventBus.subscribe(ConnectionEvent.class, event -> {
    if (event.isConnected()) {
        LOG.info("Connected! Reason: " + event.getReason());
        refreshData();
    }
});
```

### Step 4: Remove Old Listener Registrations

After migrating all listeners, delete the old `client.add*Listener()` lines:

```java
// DELETE these lines after migrating:
// client.addJobListListener(...);
// client.addJobStateListener(...);
// client.addConnectListener(...);
```

### Step 5: Test

Run the panel and verify:
- ✅ Data displays correctly
- ✅ Updates happen automatically
- ✅ No console errors
- ✅ Thread safety maintained

---

## Common Patterns

### Pattern A: Update Table on Job State Change

```java
// Old way:
public MyPanel(ServiceClient client) {
    client.addJobStateListener(job -> {
        int rowIndex = findJobRow(job.getId());
        if (rowIndex >= 0) {
            tableModel.setValueAt(job.getStatus(), rowIndex, 2);
        }
    });
}

// New way:
public MyPanel(ServiceClient client) {
    EventBus eventBus = client.getEventBus();
    
    eventBus.subscribe(JobStateEvent.class, event -> {
        WatchJob job = event.getJob();
        int rowIndex = findJobRow(job.getId());
        if (rowIndex >= 0) {
            tableModel.setValueAt(job.getStatus(), rowIndex, 2);
        }
    });
}
```

### Pattern B: Refresh Data on Connect

```java
// Old way:
public MyPanel(ServiceClient client) {
    this.client = client;
    client.addConnectListener(() -> {
        Platform.runLater(() -> refreshDataFromServer());
    });
}

// New way (Platform.runLater automatic!):
public MyPanel(ServiceClient client) {
    this.client = client;
    EventBus eventBus = client.getEventBus();
    
    eventBus.subscribe(ConnectionEvent.class, event -> {
        if (event.isConnected()) {
            refreshDataFromServer();  // Already on JavaFX thread!
        }
    });
}
```

### Pattern C: Display Notifications on Event

```java
// Old way:
public MyPanel(ServiceClient client) {
    client.addNotificationListener(notif -> {
        String message = notif.getLevel() + ": " + notif.getMessage();
        showNotificationDialog(message);
    });
}

// New way:
public MyPanel(ServiceClient client) {
    EventBus eventBus = client.getEventBus();
    
    eventBus.subscribe(NotificationEvent.class, event -> {
        NotificationMessage notif = event.getNotification();
        String message = notif.getLevel() + ": " + notif.getMessage();
        showNotificationDialog(message);
    });
}
```

### Pattern D: Multiple Event Types

```java
// Old way:
public MyPanel(ServiceClient client) {
    client.addJobListListener(this::setJobs);
    client.addJobStateListener(this::updateJob);
    client.addConnectListener(() -> setStatusLabel("Connected"));
    client.addEventListener(event -> addEventRow(event));
}

// New way:
public MyPanel(ServiceClient client) {
    EventBus eventBus = client.getEventBus();
    
    eventBus.subscribe(JobListEvent.class, event ->
        setJobs(event.getJobs())
    );
    
    eventBus.subscribe(JobStateEvent.class, event ->
        updateJob(event.getJob())
    );
    
    eventBus.subscribe(ConnectionEvent.class, event -> {
        if (event.isConnected()) {
            setStatusLabel("Connected");
        }
    });
    
    eventBus.subscribe(TransferEventFired.class, event ->
        addEventRow(event.getEvent())
    );
}
```

---

## Before/After Checklists

### Before Migration

- [ ] Panel has ServiceClient reference
- [ ] All listener registrations identified
- [ ] Handler methods work correctly
- [ ] Unit tests pass

### During Migration

- [ ] Get EventBus from client
- [ ] Replace each listener registration
- [ ] Delete old listener calls
- [ ] Verify correct event type used
- [ ] Check handler method signatures

### After Migration

- [ ] No compilation errors
- [ ] Panel displays data correctly
- [ ] Events trigger handler methods
- [ ] Auto-updates work smoothly
- [ ] No manual `Platform.runLater()` calls
- [ ] Unit tests still pass
- [ ] No console warnings/errors

---

## Troubleshooting

### Problem: "Handler never called"

**Cause:** Wrong event type

**Solution:** Verify event type matches:
```java
// WRONG:
eventBus.subscribe(JobListEvent.class, event -> {
    WatchJob job = ((JobStateEvent) event).getJob();  // ClassCastException!
});

// RIGHT:
eventBus.subscribe(JobStateEvent.class, event -> {
    WatchJob job = event.getJob();  // Correct!
});
```

### Problem: "CompletableFuture returned from on Background thread"

**Cause:** Calling UI code from background thread

**Solution:** EventBus handles this automatically. Make sure:
```java
// OLD (manual Platform.runLater):
client.addJobStateListener(job -> {
    Platform.runLater(() -> updateTable(job));  // Now unnecessary!
});

// NEW (automatic):
eventBus.subscribe(JobStateEvent.class, event -> {
    updateTable(event.getJob());  // Already on JavaFX thread!
});
```

### Problem: "Multiple listeners registered"

**Cause:** Panel created multiple times

**Solution:** Ensure panel is created once and reused, or unsubscribe when panel destroyed:
```java
public class MyPanel extends JPanel {
    private Consumer<JobStateEvent> jobStateHandler;
    
    public MyPanel(ServiceClient client) {
        EventBus eventBus = client.getEventBus();
        
        jobStateHandler = event -> updateJob(event.getJob());
        eventBus.subscribe(JobStateEvent.class, jobStateHandler);
    }
    
    public void cleanup() {
        eventBus.unsubscribe(JobStateEvent.class, jobStateHandler);
    }
}
```

### Problem: "NullPointerException in EventBus"

**Cause:** Dispatching null event or null listener

**Solution:** EventBus checks for nulls automatically. If this happens:
1. Check your event creation code
2. Look for null fields in event
3. Verify listener isn't null

**Example:**
```java
// WRONG:
eventBus.dispatch(null);  // EventBus returns early, but confusing

// RIGHT:
if (job != null) {
    eventBus.dispatch(new JobStateEvent(job));
}
```

---

## Testing Your Changes

### Quick Visual Test

1. **Start service:** `java -jar filewatcher-service.jar --service`
2. **Start UI:** `java -jar filewatcher-ui.jar`
3. **Create job:** Via UI
4. **Start job:** Via UI
5. **Verify update:** Table/panel updates in real-time

### Unit Test Example

```java
public class MyPanelTest {
    private EventBus eventBus;
    private MyPanel panel;
    
    @Before
    public void setup() {
        ServiceClient mockClient = mock(ServiceClient.class);
        eventBus = new EventBus();
        when(mockClient.getEventBus()).thenReturn(eventBus);
        
        panel = new MyPanel(mockClient);
    }
    
    @Test
    public void testJobStateEventUpdatesTable() {
        // Arrange
        WatchJob job = new WatchJob();
        job.setId("job-1");
        job.setStatus("RUNNING");
        
        // Act
        eventBus.dispatch(new JobStateEvent(job));
        
        // Assert - verify panel updated (check table data, labels, etc.)
        // You'll need to expose getters or use Spy for verification
    }
}
```

---

## Migration Order Recommendation

**Phase 4A → Complete (Infrastructure)**  
**Phase 4B → Start Here (UI Panels, in this order):**

1. **MainWindow** (central registration point)
2. **JobTablePanel** (most common use case)
3. **LogsPanel** (auto-refresh pattern)
4. **DashboardPanel** (multiple event types)
5. **SettingsPanel** (configuration pattern)
6. **Other panels** (CredentialsPanel, etc.)

**Time Estimate:** 1-2 hours total

---

## Quick Reference

### Event Type → Handler Pattern

| Event | Extract | Handler Pattern |
|-------|---------|-----------------|
| **JobListEvent** | `event.getJobs()` | `eventBus.subscribe(JobListEvent.class, e -> handle(e.getJobs()))` |
| **JobStateEvent** | `event.getJob()` | `eventBus.subscribe(JobStateEvent.class, e -> handle(e.getJob()))` |
| **TransferEventFired** | `event.getEvent()` | `eventBus.subscribe(TransferEventFired.class, e -> handle(e.getEvent()))` |
| **NotificationEvent** | `event.getNotification()` | `eventBus.subscribe(NotificationEvent.class, e -> handle(e.getNotification()))` |
| **ConnectionEvent** | `event.isConnected()` | `eventBus.subscribe(ConnectionEvent.class, e -> handle(e.isConnected()))` |
| **HealthEvent** | `event.getStats()` | `eventBus.subscribe(HealthEvent.class, e -> handle(e.getStats()))` |
| **CredentialEvent** | `event.getCredentials()` | `eventBus.subscribe(CredentialEvent.class, e -> handle(e.getCredentials()))` |
| **TestCredentialEvent** | `event.getCredentialId()` | `eventBus.subscribe(TestCredentialEvent.class, e -> handle(e))` |
| **RemoteDirEvent** | `event.getEntries()` | `eventBus.subscribe(RemoteDirEvent.class, e -> handle(e.getEntries()))` |
| **LogsEvent** | `event.getLogs()` | `eventBus.subscribe(LogsEvent.class, e -> handle(e.getLogs()))` |
| **LogsExportEvent** | `event.getCsv()` | `eventBus.subscribe(LogsExportEvent.class, e -> handle(e.getCsv()))` |
| **ConfigurationEvent** | `event.getConfig()` | `eventBus.subscribe(ConfigurationEvent.class, e -> handle(e.getConfig()))` |

---

## Summary

✅ **Identify** old listener registrations  
✅ **Get** EventBus from client  
✅ **Replace** each listener with subscribe()  
✅ **Remove** old listener calls  
✅ **Test** functionality  
✅ **Commit** changes  

**Each panel typically takes 10-15 minutes to migrate.**

---

**Status:** Migration guide ready for Phase 4B implementation  
**Next Step:** Update MainWindow to demonstrate both patterns  
**Review Date:** July 8, 2026

