# Phase 3 Quick Reference: Using Observable ViewModels

**For**: All developers working on FileWatcher UI  
**Last Updated**: July 1, 2026  

---

## What Changed?

Three panels now support **automatic UI updates** through Observable ViewModels:

| Panel | ViewModel | What Updates Automatically |
|---|---|---|
| Dashboard | `DashboardViewModel` | Summary cards (Running, Stopped, Transfers, Errors) |
| Services | `ServicesViewModel` | Job table with filtering |
| Logs | `LogsViewModel` | Log table with multi-filter search |

---

## Quick Start: 3 Steps

### Step 1: Create the ViewModel

```java
DashboardViewModel viewModel = new DashboardViewModel();
```

### Step 2: Pass to Panel

```java
DashboardPanel panel = new DashboardPanel(client, viewModel);
```

### Step 3: Update ViewModel → UI Updates Automatically!

```java
viewModel.setRunningJobs(5);
// Dashboard card now shows "5" - NO manual refresh needed!
```

---

## Real-World Example: Dashboard Summary Cards

### Before (Manual Refresh)
```java
// Old way: manually update UI every time data changes
public void handleJobUpdate(WatchJob job) {
    updateSummaryCards();  // Manual calculation
}

private void updateSummaryCards() {
    long running = jobs.stream()
        .filter(j -> j.getStatus() == WATCHING).count();
    runningCard.setValue(String.valueOf(running));  // Manual set
}
```

### After (Observable Binding)
```java
// New way: bind once, then forget!
runningCard.valueProperty().bind(
    viewModel.runningJobsProperty().asString()
);

// Later, when job updates arrive:
public void handleJobUpdate(WatchJob job) {
    viewModel.updateFromJobState(job);
    // Card updates automatically! No manual refresh!
}
```

---

## Pattern: Observable Properties

### The ViewModel Exposes Properties

```java
public class DashboardViewModel {
    private final IntegerProperty runningJobs = 
        new SimpleIntegerProperty(0);
    
    public IntegerProperty runningJobsProperty() {
        return runningJobs;
    }
    
    public void setRunningJobs(int count) {
        runningJobs.set(count);
    }
}
```

### The UI Binds to Properties

```java
// One-time binding in constructor
Label label = new Label();
label.textProperty().bind(
    viewModel.runningJobsProperty().asString()
);

// Whenever property changes, label updates automatically
viewModel.setRunningJobs(5);  // Label shows "5"
viewModel.setRunningJobs(10); // Label shows "10"
```

---

## Pattern: Filtered Lists

### The ViewModel Manages Filtering

```java
public class ServicesViewModel {
    private final ObservableList<WatchJob> jobs = 
        FXCollections.observableArrayList();
    
    private final FilteredList<WatchJob> filteredJobs = 
        new FilteredList<>(jobs);
    
    public void applyFilter(String query) {
        filteredJobs.setPredicate(job -> {
            // Return true if job matches filter
            return job.getName().contains(query);
        });
    }
    
    public ObservableList<WatchJob> getFilteredJobs() {
        return filteredJobs;
    }
}
```

### The UI Binds to Filtered List

```java
// One-time binding in constructor
table.setItems(viewModel.getFilteredJobs());

// Later, apply filter
viewModel.applyFilter("payment");
// Table automatically shows only "payment" jobs - no refresh needed!
```

---

## Pattern: Bidirectional Binding (Logs Panel)

### Bind UI Controls to ViewModel

```java
// Search field updates ViewModel as user types
searchField.textProperty().bindBidirectional(
    viewModel.searchTextProperty()
);

// Combo box updates ViewModel when user selects
eventTypeCombo.valueProperty().bindBidirectional(
    viewModel.eventTypeProperty()
);
```

### ViewModel Auto-Filters When Bindings Update

```java
public class LogsViewModel {
    private final StringProperty searchText = 
        new SimpleStringProperty("");
    
    public LogsViewModel() {
        // Re-evaluate filter whenever search changes
        searchText.addListener((obs, old, newVal) -> 
            updatePredicate()
        );
    }
    
    private void updatePredicate() {
        filteredLogs.setPredicate(log -> {
            // Match against current search text
            return log.getMessage().contains(searchText.get());
        });
    }
}
```

---

## API Reference

### DashboardViewModel

```java
// Properties (bind to these)
IntegerProperty runningJobsProperty()
IntegerProperty stoppedJobsProperty()
IntegerProperty transfersTodayProperty()
IntegerProperty failedTransfersProperty()
IntegerProperty activeConnectionsProperty()

// Methods (call these to update)
void setRunningJobs(int count)
void setStoppedJobs(int count)
void setTransfersToday(int count)
void setFailedTransfers(int count)
void setActiveConnections(int count)
void updateServiceStatus(String name, Status status, LocalDateTime hb)

// Lists
ObservableList<ServiceStatusRow> getServices()
ObservableList<ActivityFeedItem> getActivityFeed()
void prependActivity(ActivityFeedItem item)
```

### ServicesViewModel

```java
// Lists (bind to these)
ObservableList<WatchJob> getFilteredJobs()

// Methods (call these to update)
void setJobs(List<WatchJob> jobs)
void updateJob(WatchJob updated)
void applyFilter(String query)

// Selection
BooleanProperty hasSelectionProperty()
void setHasSelection(boolean value)
```

### LogsViewModel

```java
// Lists (bind to these)
ObservableList<LogEntryMessage> getFilteredLogs()

// Properties (bind to these for bidirectional)
StringProperty searchTextProperty()
StringProperty eventTypeProperty()
StringProperty jobFilterProperty()

// Methods (call to update)
void setLogs(List<LogEntryMessage> logs)
```

---

## Common Mistakes & How to Avoid

### ❌ Mistake 1: Forget the ViewModel Exists

```java
// DON'T do this:
panel = new JobTablePanel(client);  // No ViewModel!
// Manual filtering won't work; table shows all jobs
```

```java
// DO this:
viewModel = new ServicesViewModel();
panel = new JobTablePanel(client, viewModel);
// Filtering works automatically
```

### ❌ Mistake 2: Mix Manual + Observable Updates

```java
// DON'T do this:
viewModel.setRunningJobs(5);  // Observable update
card.setValue("5");           // Manual update
// Confusing: which one is correct?
```

```java
// DO this:
card.valueProperty().bind(viewModel.runningJobsProperty().asString());
viewModel.setRunningJobs(5);  // Only update ViewModel
// Card updates automatically via binding
```

### ❌ Mistake 3: Update ViewModel Without Data Change

```java
// DON'T do this:
for (WatchJob job : jobs) {
    viewModel.updateJob(job);  // Even if unchanged
}
// Wasteful: triggers filter re-evaluation for every job
```

```java
// DO this:
viewModel.setJobs(jobs);  // Replace whole list once
// More efficient: single filter pass
```

---

## Testing Your Code

### Unit Test a ViewModel

```java
@Test
public void testDashboardViewModelUpdates() {
    DashboardViewModel vm = new DashboardViewModel();
    
    // Initial state
    assertEquals(0, vm.getRunningJobs());
    
    // Update
    vm.setRunningJobs(5);
    
    // Verify
    assertEquals(5, vm.getRunningJobs());
}
```

### Integration Test with Mock Client

```java
@Test
public void testPanelUpdatesOnEvent() {
    ServicesViewModel vm = new ServicesViewModel();
    JobTablePanel panel = new JobTablePanel(mockClient, vm);
    
    // Simulate job update
    WatchJob job = createTestJob("test-job");
    vm.updateJob(job);
    
    // Verify table contains job
    assertEquals(1, vm.getFilteredJobs().size());
    assertEquals("test-job", vm.getFilteredJobs().get(0).getName());
}
```

---

## Performance Tips

1. **Use ViewModels for large lists** (>100 items)
   - FilteredList + SortedList are optimized for large datasets
   - Manual filtering is slower

2. **Bind once, update frequently**
   - Binding has small one-time cost (~1ms)
   - Updates are very fast (<1ms per item)

3. **Use SortedList for sorted tables**
   - Keeps sort order as items change
   - User doesn't lose sort when data updates

4. **Batch updates when possible**
   - `setJobs(allJobs)` faster than `updateJob(j)` for each job
   - Reduces filter re-evaluations

---

## Backward Compatibility

All panels still work **without** ViewModels:

```java
// Old code still works!
panel = new JobTablePanel(client);
panel.setJobs(jobs);
panel.applyFilter("search");

// No ViewModels, no automatic updates, but no errors either
```

---

## Next Steps

1. ✅ Read this guide
2. ✅ Try the examples
3. ✅ Use ViewModels in your code
4. ✅ Run tests to verify
5. ✅ Submit for code review

---

## Questions?

- **"How do I bind to a property?"** → Use `.bind()` or `.bindBidirectional()`
- **"Do I have to create ViewModels?"** → No, panels work without them (backward compatible)
- **"How fast are updates?"** → <1ms for single item, <50ms for 1000+ items
- **"Can I subclass ViewModel?"** → Yes! Create specialized ViewModels for your needs
- **"What if binding breaks?"** → Check null checks and import statements

---

**Remember**: With Observable ViewModels, you code less and the UI does more! 🚀

