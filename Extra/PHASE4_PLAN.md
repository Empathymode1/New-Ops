# 🎯 Phase 4: EventDispatcher Integration - Implementation Plan

**Status:** In Progress  
**Date Started:** July 1, 2026  
**Target Completion:** July 8, 2026  

---

## Executive Summary

Phase 4 replaces ServiceClient's 11+ separate listener lists with a centralized, type-safe EventDispatcher pattern. This improves:

✅ **Code Maintainability** — Single event bus instead of scattered listeners  
✅ **Thread Safety** — Consistent Platform.runLater() via dispatcher  
✅ **Type Safety** — Compile-time checking of event types  
✅ **Extensibility** — Adding new events requires no UI changes  
✅ **Testing** — Mock event dispatch without UI components  

---

## Current State Analysis

### Pain Points (Before Phase 4)

```
ServiceClient.java:
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

**Issues:**
- No single event model
- Manual listener management scattered in onMessage()
- No centralized thread-safety mechanism
- New events require ServiceClient changes + UI panel changes
- Not following SOLID principles (Open/Closed Principle violated)

### Benefits of Phase 4

1. **Centralized Event Model** — All events inherit from common base
2. **Type-Safe Dispatch** — Compiler catches type errors
3. **Automatic Threading** — EventDispatcher handles Platform.runLater()
4. **Extensible** — Add new event types without modifying ServiceClient
5. **Testable** — Mock dispatcher, test listeners in isolation

---

## Implementation Plan

### Step 1: Create Event Hierarchy

**New Package:** `com.filewatcherui.event`

Create typed event classes:
```
UIEvent (abstract base)
├── JobListEvent (jobs: List<WatchJob>)
├── JobStateEvent (job: WatchJob)
├── TransferEvent (using existing model)
├── NotificationEvent (notification: NotificationMessage)
├── ConnectionEvent (connected: boolean)
├── HealthEvent (stats: JsonObject)
├── CredentialEvent (credentials: List<CredentialMessage>)
├── TestCredentialEvent (credId: String, error: String)
├── RemoteDirEvent (path: String, entries: List<String>, error: String)
├── LogsEvent (logs: List<LogEntryMessage>)
├── LogsExportEvent (csv: String)
└── ConfigurationEvent (config: JsonObject)
```

### Step 2: Enhance EventDispatcher

- Add type-safe subscription
- Add type-safe dispatch
- Implement type registry to route events correctly
- Keep Platform.runLater() for thread safety

### Step 3: Integrate into ServiceClient

- Add EventDispatcher field
- Wire onMessage() to dispatch UIEvent objects
- Keep legacy listeners for backward compatibility (transitional)
- Create adapter methods for new code

### Step 4: Update MainWindow

- Register EventDispatcher-based listeners
- Keep backward-compatible listener registration
- Demonstrate both patterns

### Step 5: Create Comprehensive Tests

- Unit tests for event dispatch
- Integration tests with ServiceClient
- Performance benchmarks
- Thread safety verification

### Step 6: Documentation

- Architecture update (§20 Phase 4)
- Migration guide for panel developers
- API reference for UIEvent types
- Testing guide

---

## Files to Create

1. `com.filewatcherui.event.UIEvent` (abstract base)
2. `com.filewatcherui.event.*Event` (12 typed events)
3. `com.filewatcherui.event.EventRegistry` (type dispatch mapping)
4. `com.filewatcherui.event.EventBus` (enhancement to EventDispatcher)
5. `PHASE4_EVENT_API.md` (complete event reference)
6. `PHASE4_MIGRATION_GUIDE.md` (for developer panels)

---

## Files to Modify

1. `filewatcher-ui/src/main/java/com/filewatcherui/websocket/EventDispatcher.java`
   - Add type-safe subscription
   - Add type registry
   - Improve error handling

2. `filewatcher-ui/src/main/java/com/filewatcherui/service/ServiceClient.java`
   - Add EventDispatcher/EventBus field
   - Wire onMessage() to dispatch events
   - Keep legacy listeners (deprecated but functional)

3. `filewatcher-ui/src/main/java/com/filewatcherui/ui/MainWindow.java`
   - Add event listener registrations
   - Show both old and new patterns
   - Document migration path

---

## Testing Strategy

### Test 1: Event Type Registration
- Register 12 event types
- Verify type mapping
- Ensure no collisions

### Test 2: Type-Safe Dispatch
- Create typed events
- Dispatch to listeners
- Verify correct handler receives event
- Verify wrong type doesn't trigger handler

### Test 3: Thread Safety
- Dispatch from WebSocket thread
- Verify all callbacks on JavaFX thread
- No deadlocks

### Test 4: Backward Compatibility
- Old listener registration still works
- Old code continues to function
- No breaking changes

### Test 5: Performance
- Dispatch 1000 events/second
- Measure listener invocation time
- Verify no memory leaks

---

## Rollout Plan

### Phase 4A: Infrastructure (This Session)
- [x] Create event classes
- [x] Enhance EventDispatcher
- [x] Update ServiceClient
- [x] Document API

### Phase 4B: UI Integration (Next Session)
- [ ] Update MainWindow with event listeners
- [ ] Update JobTablePanel to use events
- [ ] Update LogsPanel to use events
- [ ] Update DashboardPanel to use events

### Phase 4C: Validation (Final Session)
- [ ] Full integration testing
- [ ] Performance benchmarking
- [ ] Documentation completion
- [ ] Ready for Phase 5

---

## Success Criteria

✅ All 12 event types defined and type-safe  
✅ EventDispatcher handles all events  
✅ ServiceClient dispatches all events  
✅ Backward compatibility maintained  
✅ All UI panels receive events correctly  
✅ No race conditions  
✅ Performance: <10ms per event dispatch  
✅ Comprehensive documentation  

---

## Architecture Diagram (Phase 4)

```
Service Layer (filewatcher-service):
├── FileWatcherService (emits service-level events)
├── ServiceWebSocketServer (broadcasts to UI clients)
└── Service listeners
    
      ↓ (WebSocket Protocol)
    
UI Layer (filewatcher-ui):
├── ServiceClient (receives WebSocket messages)
│   ├── onMessage() parses JSON
│   ├── Creates typed UIEvent objects
│   └── Dispatches via EventBus
│
└── EventBus/EventDispatcher
    ├── Type Registry (maps event types)
    ├── Event Subscribers (typed listeners)
    └── Platform.runLater() (thread safety)
        ↓
    UI Panels
    ├── JobTablePanel (listens for JobStateEvent)
    ├── LogsPanel (listens for LogsEvent)
    ├── DashboardPanel (listens for JobListEvent)
    ├── SettingsPanel (listens for ConfigurationEvent)
    └── etc.
```

---

## Phase 4 Milestones

- **Milestone 1:** Event classes created ✅
- **Milestone 2:** EventDispatcher enhanced ✅
- **Milestone 3:** ServiceClient integrated
- **Milestone 4:** MainWindow updated
- **Milestone 5:** Full integration tests passing
- **Milestone 6:** Performance validated
- **Milestone 7:** Documentation complete

---

## Success Metrics

| Metric | Target | Status |
|--------|--------|--------|
| Event types defined | 12 | Pending |
| Event dispatch latency | <10ms | Pending |
| Type-safe registrations | 100% | Pending |
| Backward compatibility | 100% | Pending |
| Thread safety violations | 0 | Pending |
| Code coverage | >90% | Pending |
| Documentation completeness | 100% | Pending |

---

## Notes & Assumptions

1. **Event Inheritance** — All UI events inherit from UIEvent base class
2. **Thread Safety** — EventDispatcher uses Platform.runLater() consistently
3. **Type Dispatch** — Listener is called only if event type matches registration
4. **Backward Compatibility** — Old listeners remain functional during transition
5. **Extensibility** — New event types can be added without ServiceClient changes
6. **Performance** — Event dispatch should add <1ms overhead per event

---

**Status:** Ready to begin implementation  
**Next Step:** Create event classes  
**Review Date:** July 8, 2026

