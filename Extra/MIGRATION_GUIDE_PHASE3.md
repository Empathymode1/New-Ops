# Migration Guide: Refactoring Existing Panels to Use New Architecture

**Target**: Convert legacy UI panels to use Observable ViewModels + EventDispatcher  
**Complexity**: Medium (1-2 panels per day)  
**Start with**: `DashboardPanel` → `JobTablePanel` → `LogsPanel`

---

## Why Migrate?

| Current (Legacy) | New (Architecture) |
|---|---|
| Direct listener subscriptions | Centralized EventDispatcher |
| Manual UI refresh code | Observable binding (automatic) |
| Mixed concerns (data + UI) | ViewModel separation |
| Hard to test | Testable in isolation |
| Theme hardcoded to inline styles | CSS variable-based themes |
| Scattered navigation logic | NavigationController |

---

## Migration Checklist Template

```
Panel: XXXPanel
├── [ ] Create XXXViewModel
├── [ ] Move observable properties to ViewModel
├── [ ] Add ViewModel as field in Panel
├── [ ] Replace manual updates with property binding
├── [ ] Move event listener setup to ViewModel
├── [ ] Remove inline theme colors (use CSS)
├── [ ] Test with mock ServiceClient
├── [ ] Performance check (listener cleanup)
└── [ ] Mark old code for deletion
```

---

## Case Study 1: DashboardPanel → DashboardViewModel

### Before (Current)

```java
// File: DashboardPanel.java (lines 26-79)

public class DashboardPanel {
    private final ObservableList<WatchJob> jobs = FXCollections.observableArrayList();
    private final TableView<WatchJob> table = new TableView<>(jobs);
    private final BorderPane root = new BorderPane();
    
    // Summary cards store state directly
    private final InfoCard runningCard;
    private final InfoCard stoppedCard;
    private final InfoCard transfersCard;
    private final InfoCard failedCard;
    private final InfoCard connectionsCard;

    public DashboardPanel(ServiceClient client) {
        root.getStyleClass().add("panel-root");
        
        // Initialize cards with hardcoded values
        runningCard = new InfoCard("Running Jobs", "0", "");
        runningCard.getStyleClass().add("accent-green");
        
        stoppedCard = new InfoCard("Stopped Jobs", "0", "");
        
        transfersCard = new InfoCard("Transfers Today", "0", "");
        transfersCard.getStyleClass().add("accent-cyan");
        
        failedCard = new InfoCard("Failed Transfers", "0", "");
        failedCard.getStyleClass().add("accent-red");
        
        connectionsCard = new InfoCard("Active Connections", "0", "");
        connectionsCard.getStyleClass().add("accent-blue");
        
        root.setTop(buildHeaderWithCards());
        root.setCenter(buildContent());

        // Manual listener updates
        client.addJobListListener(list -> Platform.runLater(() -> {
            jobs.setAll(list);
            updateSummaryCards();  // Manual refresh!
        }));
        
        client.addJobStateListener(updated -> Platform.runLater(() -> {
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).getId().equals(updated.getId())) {
                    jobs.set(i, updated);
                    updateSummaryCards();  // Manual refresh!
                    return;
                }
            }
            jobs.add(updated);
            updateSummaryCards();  // Manual refresh!
        }));
    }
    
    private void updateSummaryCards() {
        // Manual calculation and update
        long running = jobs.stream().filter(j -> j.getStatus() == WatchJob.Status.WATCHING).count();
        long stopped = jobs.stream().filter(j -> j.getStatus() == WatchJob.Status.IDLE).count();
        long totalTransfers = jobs.stream().mapToLong(WatchJob::getFilesTransferred).sum();
        long errors = jobs.stream().filter(j -> j.getLastError() != null && !j.getLastError().isBlank()).count();

        runningCard.setValue(String.valueOf(running));  // Manual UI update
        stoppedCard.setValue(String.valueOf(stopped));
        transfersCard.setValue(String.valueOf(totalTransfers));
        failedCard.setValue(String.valueOf(errors));
        connectionsCard.setValue(String.valueOf(jobs.size()));
    }
}
```

**Problems**:
- Cards store state as `InfoCard` objects (UI concerns)
- Manual `updateSummaryCards()` refresh logic
- Listener logic mixed into constructor
- No testable separation

### After (New Architecture)

```java
// Step 1: Create DashboardViewModel (ALREADY DONE!)
// File: com/filewatcherui/model/DashboardViewModel.java

public class DashboardViewModel {
    private final IntegerProperty runningJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty stoppedJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty transfersToday = new SimpleIntegerProperty(0);
    private final IntegerProperty failedTransfers = new SimpleIntegerProperty(0);
    private final IntegerProperty activeConnections = new SimpleIntegerProperty(0);
    private final ObservableList<WatchJob> jobs = FXCollections.observableArrayList();
    
    public void updateFromJobList(List<WatchJob> list) {
        jobs.setAll(list);
        recalculateSummaries();
    }
    
    public void updateFromJobState(WatchJob updated) {
        // Update or add job
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).getId().equals(updated.getId())) {
                jobs.set(i, updated);
                recalculateSummaries();
                return;
            }
        }
        jobs.add(updated);
        recalculateSummaries();
    }
    
    private void recalculateSummaries() {
        long running = jobs.stream().filter(j -> j.getStatus() == WatchJob.Status.WATCHING).count();
        long stopped = jobs.stream().filter(j -> j.getStatus() == WatchJob.Status.IDLE).count();
        long totalTransfers = jobs.stream().mapToLong(WatchJob::getFilesTransferred).sum();
        long errors = jobs.stream().filter(j -> j.getLastError() != null && !j.getLastError().isBlank()).count();
        
        runningJobs.set((int) running);
        stoppedJobs.set((int) stopped);
        transfersToday.set((int) totalTransfers);
        failedTransfers.set((int) errors);
        activeConnections.set(jobs.size());
    }
    
    // Getters for UI binding
    public IntegerProperty runningJobsProperty() { return runningJobs; }
    public IntegerProperty stoppedJobsProperty() { return stoppedJobs; }
    public IntegerProperty transfersTodayProperty() { return transfersToday; }
    public IntegerProperty failedTransfersProperty() { return failedTransfers; }
    public IntegerProperty activeConnectionsProperty() { return activeConnections; }
    public ObservableList<WatchJob> getJobs() { return jobs; }
}

// Step 2: Refactor DashboardPanel to use ViewModel
// File: DashboardPanel.java

public class DashboardPanel {
    private final DashboardViewModel viewModel = new DashboardViewModel();
    private final BorderPane root = new BorderPane();
    private final InfoCard runningCard;
    private final InfoCard stoppedCard;
    private final InfoCard transfersCard;
    private final InfoCard failedCard;
    private final InfoCard connectionsCard;

    public DashboardPanel(ServiceClient client) {
        root.getStyleClass().add("panel-root");
        
        // Initialize cards (same as before, but will be auto-bound)
        runningCard = new InfoCard("Running Jobs", "0", "");
        runningCard.getStyleClass().add("accent-green");
        
        stoppedCard = new InfoCard("Stopped Jobs", "0", "");
        
        transfersCard = new InfoCard("Transfers Today", "0", "");
        transfersCard.getStyleClass().add("accent-cyan");
        
        failedCard = new InfoCard("Failed Transfers", "0", "");
        failedCard.getStyleClass().add("accent-red");
        
        connectionsCard = new InfoCard("Active Connections", "0", "");
        connectionsCard.getStyleClass().add("accent-blue");
        
        // Bind cards to observable properties (AUTOMATIC UPDATE!)
        runningCard.valueProperty().bind(viewModel.runningJobsProperty().asString());
        stoppedCard.valueProperty().bind(viewModel.stoppedJobsProperty().asString());
        transfersCard.valueProperty().bind(viewModel.transfersTodayProperty().asString());
        failedCard.valueProperty().bind(viewModel.failedTransfersProperty().asString());
        connectionsCard.valueProperty().bind(viewModel.activeConnectionsProperty().asString());
        
        root.setTop(buildHeaderWithCards());
        root.setCenter(buildContent()); // Uses viewModel.getJobs() instead of local list
        
        // Wire up listeners (simplified!)
        client.addJobListListener(list -> Platform.runLater(() -> {
            viewModel.updateFromJobList(list);  // ViewModel handles the update
        }));
        
        client.addJobStateListener(updated -> Platform.runLater(() -> {
            viewModel.updateFromJobState(updated);  // ViewModel handles the update
        }));
        
        // No more updateSummaryCards() calls needed!
        // UI updates automatically when properties change
    }
    
    private VBox buildContent() {
        VBox content = new VBox();
        content.getStyleClass().add("panel-root");
        
        // Use viewModel's job list instead of local observable
        TableView<WatchJob> table = buildTable(viewModel.getJobs());
        
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(table);
        return content;
    }
    
    private TableView<WatchJob> buildTable(ObservableList<WatchJob> jobs) {
        // Same as before, just now receives jobs from viewModel
        TableView<WatchJob> table = new TableView<>(jobs);
        // ... build columns ...
        return table;
    }
    
    // Now testable!
    public DashboardViewModel getViewModel() {
        return viewModel;
    }
}
```

**Benefits**:
- ✅ UI updates automatically (no manual refresh)
- ✅ Card values bound to observable properties
- ✅ Testable: can instantiate `DashboardPanel` without `ServiceClient`
- ✅ ViewModel is data-only (pure logic, easy to test)
- ✅ No scattered update logic

### Testing the Refactor

```java
// Test ViewModel independently
@Test
public void testDashboardViewModelUpdates() {
    DashboardViewModel vm = new DashboardViewModel();
    
    assertEquals(0, vm.getRunningJobs());
    
    // Simulate data update
    List<WatchJob> jobs = /* create 3 jobs, 2 running */;
    vm.updateFromJobList(jobs);
    
    assertEquals(2, vm.getRunningJobs());
    assertEquals(1, vm.getStoppedJobs());
}

// Test UI binding
@Test
public void testPanelBindsToViewModel() {
    DashboardPanel panel = new DashboardPanel(null);  // No client needed!
    Label label = panel.runningCard.valueProperty();  // Get label from card
    
    assertEquals("0", label.getText());
    
    // Update viewModel
    panel.getViewModel().setRunningJobs(5);
    
    assertEquals("5", label.getText());  // Automatic update!
}
```

---

## Case Study 2: JobTablePanel → ServicesViewModel

### Key Changes

```java
// Create ServicesViewModel.java
public class ServicesViewModel {
    private final ObservableList<WatchJob> jobs = FXCollections.observableArrayList();
    private final ObservableList<WatchJob> filteredJobs = new FilteredList<>(jobs);
    private final SortedList<WatchJob> sortedJobs = new SortedList<>(filteredJobs);
    
    // Observable properties for toolbar state
    private final BooleanProperty hasSelection = new SimpleBooleanProperty(false);
    private final ObjectProperty<WatchJob> selectedJob = new SimpleObjectProperty<>();
    
    public void setJobs(List<WatchJob> list) {
        jobs.setAll(list);
    }
    
    public void applyFilter(String query) {
        filteredJobs.setPredicate(job -> {
            if (query == null || query.isEmpty()) return true;
            String q = query.toLowerCase();
            return job.getName().toLowerCase().contains(q) ||
                   (job.getSourcePath() != null && job.getSourcePath().toLowerCase().contains(q)) ||
                   (job.getDestPath() != null && job.getDestPath().toLowerCase().contains(q));
        });
    }
    
    public ObservableList<WatchJob> getFilteredJobs() {
        return sortedJobs;  // Return sorted, filtered list
    }
}

// Refactor JobTablePanel
public class JobTablePanel {
    private final ServicesViewModel viewModel = new ServicesViewModel();
    
    public JobTablePanel(ServiceClient client) {
        // ... build UI ...
        
        // Bind table to filtered/sorted list
        TableView<WatchJob> table = new TableView<>(viewModel.getFilteredJobs());
        
        // Search field updates filter
        searchField.textProperty().addListener((obs, old, newVal) -> {
            viewModel.applyFilter(newVal);
        });
        
        // Toolbar buttons bind to selection state
        editBtn.disableProperty().bind(viewModel.hasSelection().not());
        deleteBtn.disableProperty().bind(viewModel.hasSelection().not());
        
        // ... same listener pattern ...
    }
}
```

---

## Case Study 3: LogsPanel → LogsViewModel

### Key Changes

```java
public class LogsViewModel {
    private final ObservableList<LogEntryMessage> allLogs = FXCollections.observableArrayList();
    private final FilteredList<LogEntryMessage> filteredLogs = new FilteredList<>(allLogs);
    
    // Filter state
    private final StringProperty searchText = new SimpleStringProperty("");
    private final StringProperty eventTypeFilter = new SimpleStringProperty("All types");
    private final StringProperty jobFilter = new SimpleStringProperty("All jobs");
    
    public LogsViewModel() {
        // Update filter predicate when any filter changes
        searchText.addListener((obs, old, newVal) -> updateFilter());
        eventTypeFilter.addListener((obs, old, newVal) -> updateFilter());
        jobFilter.addListener((obs, old, newVal) -> updateFilter());
    }
    
    private void updateFilter() {
        filteredLogs.setPredicate(log -> {
            // Apply all filters
            if (!searchText.get().isEmpty() && 
                !log.getMessage().toLowerCase().contains(searchText.get())) {
                return false;
            }
            if (!"All types".equals(eventTypeFilter.get()) && 
                !log.getEventType().equals(eventTypeFilter.get())) {
                return false;
            }
            // ... check jobFilter ...
            return true;
        });
    }
    
    public void setLogs(List<LogEntryMessage> logs) {
        allLogs.setAll(logs);
    }
    
    public ObservableList<LogEntryMessage> getFilteredLogs() {
        return filteredLogs;
    }
}

// In LogsPanel:
public class LogsPanel {
    private final LogsViewModel viewModel = new LogsViewModel();
    
    public LogsPanel(ServiceClient client) {
        // Bind filter inputs to viewModel properties
        searchField.textProperty().bindBidirectional(viewModel.searchTextProperty());
        eventTypeCombo.valueProperty().bindBidirectional(viewModel.eventTypeFilterProperty());
        jobCombo.valueProperty().bindBidirectional(viewModel.jobFilterProperty());
        
        // Table automatically shows filtered data
        TableView<LogEntryMessage> table = new TableView<>(viewModel.getFilteredLogs());
        
        // ... same listener pattern ...
    }
}
```

---

## General Migration Steps

### 1. Create ViewModel

```java
public class MyPageViewModel {
    // Move all observable properties here
    // Move all business logic here (calculations, filtering, etc.)
    // Keep it pure: no UI references, no ServiceClient calls
}
```

### 2. Add ViewModel to Panel

```java
public class MyPanel {
    private final MyPageViewModel viewModel = new MyPageViewModel();
    
    public MyPanel(ServiceClient client) {
        // ... UI setup ...
        // ... listener setup (feed data to viewModel) ...
    }
}
```

### 3. Bind UI to ViewModel Properties

```java
// Replace:
// label.setText("5");  // Manual update

// With:
label.textProperty().bind(viewModel.countProperty().asString());
```

### 4. Move Event Handling to ViewModel

```java
// Old:
client.addListener(event -> {
    updateManually();  // Scattered logic
});

// New:
client.addListener(event -> {
    Platform.runLater(() -> {
        viewModel.handleEvent(event);  // Centralized in viewModel
    });
});
```

### 5. Remove Manual Refresh Code

```java
// Old:
private void updateSummaryCards() {
    // ... calculation ...
    card.setValue("5");  // Manual update
}

// New:
// Not needed! Properties auto-update UI
```

### 6. Add Dispose Method for Cleanup

```java
public void dispose() {
    // Remove listeners to prevent memory leaks
    // client.removeListener(myListener);
}
```

---

## Checklist for Each Migration

- [ ] Create ViewModel class
- [ ] Move observable properties to ViewModel
- [ ] Move calculation/business logic to ViewModel
- [ ] Add ViewModel field to Panel
- [ ] Replace manual UI updates with property binding
- [ ] Replace manual refresh code with event → viewModel → automatic UI
- [ ] Add getViewModel() method for testing
- [ ] Write unit test for ViewModel
- [ ] Write integration test with mock client
- [ ] Remove inline theme colors (use CSS classes)
- [ ] Verify listener cleanup (no memory leaks)
- [ ] Performance check (ensure responsiveness)
- [ ] Mark old code patterns for deletion in comments
- [ ] Code review with another developer

---

## Performance Checklist

After migration:

- [ ] No memory leaks: Add `dispose()` call when panel closes
- [ ] No UI freezes: All ServiceClient calls use `Platform.runLater()`
- [ ] Binding efficiency: Properties only update when values actually change
- [ ] Table performance: Use `FilteredList`/`SortedList` for large datasets
- [ ] CSS efficiency: Use style classes, not inline styles

---

## Rollback Plan

If something goes wrong:

1. ViewModel exists independently → can test in isolation
2. Panel binding is declarative → easy to revert
3. Event flow is explicit → easy to trace issues
4. Git history shows before/after → easy to compare

**Worst case**: Revert commit and try again. The architecture is designed to make this safe.

---

## Timeline

| Panel | Estimated Time | Dependencies |
|---|---|---|
| DashboardPanel | 2 hours | None (independent) |
| JobTablePanel | 2-3 hours | FilteredList/SortedList patterns |
| LogsPanel | 2 hours | Same as JobTablePanel |
| SettingsPanel | 1 hour | No model needed, mostly form state |
| CredentialsPanel | 1.5 hours | Similar to JobTablePanel |
| HealthPanel | 1 hour | Simple property binding |

**Total**: ~10 hours to migrate all panels

---

## Success Criteria

After migration, the panel should:

- ✅ Have a separate ViewModel with no UI references
- ✅ Bind all UI controls to ViewModel properties
- ✅ Have no manual refresh code
- ✅ Be testable without ServiceClient
- ✅ Auto-update UI when ViewModel changes
- ✅ Have a dispose() method for cleanup
- ✅ Pass all existing tests
- ✅ Have 100% backward compatibility

---

## Questions?

1. **"How do I test this?"** → See `DashboardPanel` example above
2. **"What if the ViewModel is complex?"** → Break it into smaller ViewModels or methods
3. **"Do I have to migrate all at once?"** → No! Migrate one panel at a time
4. **"What about the EventDispatcher?"** → Use it eventually, but listeners work fine for now
5. **"Will this break existing functionality?"** → No, it's 100% compatible

---

**Status**: Ready to start Phase 3 migrations! 🚀

