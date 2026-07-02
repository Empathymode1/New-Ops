# Quick Start Guide: Using the New Architecture

**For**: Developers working on FileWatcher UI  
**Time to read**: 10 minutes  
**What you'll learn**: How to add features using the new modular architecture

---

## TL;DR

The UI now uses **Observable ViewModels** + **Thread-Safe EventDispatcher** + **ThemeManager** + **NavigationController**. Here's how to build a new page:

```java
// 1. Create observable model
public class MyViewModel {
    private final IntegerProperty count = new SimpleIntegerProperty(0);
    public IntegerProperty countProperty() { return count; }
}

// 2. Create view that binds to model
public class MyPanel {
    private final MyViewModel model = new MyViewModel();
    public MyPanel(ServiceClient client) {
        Label label = new Label();
        label.textProperty().bind(model.countProperty().asString());
        // UI auto-updates when model.setCount() is called
    }
}

// 3. Register in MainWindow
navigationController.onPage(Page.MY_PAGE, () -> {
    // Show panel when navigated to MY_PAGE
});
```

---

## The Four Core Patterns

### 1️⃣ Observable Properties (Reactive Binding)

**Problem**: UI is out of sync with data; manual refresh code is messy.

**Solution**: Bind UI controls to observable properties.

```java
// ViewModel
public class DashboardViewModel {
    private final IntegerProperty runningJobs = new SimpleIntegerProperty(0);
    
    public IntegerProperty runningJobsProperty() { return runningJobs; }
    public void setRunningJobs(int count) { runningJobs.set(count); }
}

// View (automatic updates!)
Label jobsLabel = new Label();
jobsLabel.textProperty().bind(
    viewModel.runningJobsProperty().asString()
);

// Model changes → UI updates automatically
viewModel.setRunningJobs(5);  // Label now shows "5"
```

**Benefits**:
- ✅ No manual refresh code
- ✅ Type-safe
- ✅ Testable in isolation
- ✅ Works with multiple UI controls

**Common Properties**:
```java
IntegerProperty count = new SimpleIntegerProperty(0);
StringProperty name = new SimpleStringProperty("—");
BooleanProperty connected = new SimpleBooleanProperty(false);
ObjectProperty<WatchJob.Status> status = new SimpleObjectProperty<>();
ObservableList<String> items = FXCollections.observableArrayList();
```

### 2️⃣ Event Dispatcher (Thread-Safe Events)

**Problem**: WebSocket events arrive on network thread; UI can only be updated from JavaFX thread. Mixing threads crashes the app.

**Solution**: EventDispatcher wraps all events with `Platform.runLater()`.

```java
// Network thread receives event:
webSocket.onMessage(rawMessage -> {
    TransferEvent event = parse(rawMessage);
    eventDispatcher.dispatch(event);  // Thread-safe!
});

// UI code (always on JavaFX thread):
eventDispatcher.subscribe(TransferEvent.class, event -> {
    viewModel.prependActivity(event);  // Safe to modify UI here
});
```

**Critical Rule**: Never touch JavaFX UI from a non-App thread. The EventDispatcher enforces this.

### 3️⃣ Theme Manager (Runtime Switching)

**Problem**: Changing theme requires restart.

**Solution**: ThemeManager swaps CSS files at runtime.

```java
// In a button's onClick or settings:
ThemeManager.switchTheme(scene, ThemeManager.Theme.DARK);

// All UI updates automatically (stylesheet swap only)
```

**How it works**:
1. `dark.css` and `light.css` define the same variables with different values:
   ```css
   /* dark.css */
   .root { -bg: #0B0F14; -accent: #42E0CE; }
   
   /* light.css */
   .root { -bg: #FFFFFF; -accent: #B87C2A; }
   ```

2. All other stylesheets reference variables:
   ```css
   .button { -fx-background-color: -accent; }
   ```

3. Theme switch removes old stylesheet, adds new one. Everything updates because all properties are variables!

### 4️⃣ Navigation Controller (Centralized Routing)

**Problem**: Page switching code is scattered (sidebar, shortcuts, hyperlinks all do it differently).

**Solution**: NavigationController is single entry point for all navigation.

```java
// Setup (in MainWindow):
navigationController.onPage(Page.DASHBOARD, () -> {
    // Show dashboard in contentStack
    // Load initial data
});

// Navigate from anywhere:
navigationController.navigateTo(Page.DASHBOARD);

// Keyboard shortcut:
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN),
    () -> navigationController.navigateTo(Page.DASHBOARD)
);

// Sidebar click:
dashboardButton.setOnAction(e -> navigationController.navigateTo(Page.DASHBOARD));
```

---

## Building a New Page (Step by Step)

### Step 1: Create a ViewModel

```java
// File: com/filewatcherui/model/MyPageViewModel.java
package com.filewatcherui.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class MyPageViewModel {
    // Observable properties that UI will bind to
    private final IntegerProperty totalCount = new SimpleIntegerProperty(0);
    private final StringProperty status = new SimpleStringProperty("Idle");
    private final ObservableList<MyItem> items = FXCollections.observableArrayList();
    
    // Getters for properties (UI binds to these)
    public IntegerProperty totalCountProperty() { return totalCount; }
    public StringProperty statusProperty() { return status; }
    public ObservableList<MyItem> getItems() { return items; }
    
    // Setters for update logic (called when events arrive)
    public void setTotalCount(int count) { totalCount.set(count); }
    public void setStatus(String s) { status.set(s); }
    public void addItem(MyItem item) { items.add(item); }
    
    // Business logic methods
    public void reset() {
        totalCount.set(0);
        status.set("Idle");
        items.clear();
    }
}

// Simple data class for table/list
class MyItem {
    public String name;
    public int value;
    public MyItem(String name, int value) {
        this.name = name;
        this.value = value;
    }
}
```

### Step 2: Create the View (Panel)

```java
// File: com/filewatcherui/ui/MyPagePanel.java
package com.filewatcherui.ui;

import com.filewatcherui.model.MyPageViewModel;
import com.filewatcherui.service.ServiceClient;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class MyPagePanel {
    private final MyPageViewModel viewModel = new MyPageViewModel();
    private final ServiceClient client;
    private final BorderPane root = new BorderPane();
    
    public MyPagePanel(ServiceClient client) {
        this.client = client;
        
        // Build UI
        root.setTop(buildHeader());
        root.setCenter(buildContent());
        
        // Wire up listeners
        setupEventListeners();
    }
    
    private HBox buildHeader() {
        Label title = new Label("My Page");
        title.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        
        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> client.requestMyData());  // Example
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        return new HBox(12, title, spacer, refreshBtn);
    }
    
    private VBox buildContent() {
        // Bind label to observable property
        Label countLabel = new Label();
        countLabel.textProperty().bind(
            viewModel.totalCountProperty().asString("Total: %d")
        );
        
        // Bind status label
        Label statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.statusProperty());
        
        // Table bound to observable list
        TableView<MyItem> table = new TableView<>(viewModel.getItems());
        
        TableColumn<MyItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> 
            new javafx.beans.property.SimpleStringProperty(c.getValue().name)
        );
        
        TableColumn<MyItem, Integer> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(c -> 
            new javafx.beans.property.SimpleObjectProperty<>(c.getValue().value)
        );
        
        table.getColumns().addAll(nameCol, valueCol);
        
        VBox content = new VBox(12, countLabel, statusLabel, table);
        content.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        
        return content;
    }
    
    private void setupEventListeners() {
        // When service sends updates, view model updates automatically trigger UI refresh
        client.addMyListener(data -> {
            viewModel.setTotalCount(data.count);
            viewModel.setStatus(data.status);
            viewModel.addItem(new MyItem(data.name, data.value));
        });
    }
    
    public Region getRoot() { return root; }
    
    public void dispose() {
        // Clean up listeners to prevent memory leaks
        // client.removeMyListener(myListener);
    }
}
```

### Step 3: Register in MainWindow

```java
// In MainWindow constructor:

// Create the page
MyPagePanel myPage = new MyPagePanel(client);

// Add to navigation stack
contentStack.getChildren().add(myPage.getRoot());
int myPageIndex = contentStack.getChildren().size() - 1;

// Register navigation handler
navigationController.onPage(Page.MY_PAGE, () -> {
    // Show this page, hide others
    for (int i = 0; i < contentStack.getChildren().size(); i++) {
        contentStack.getChildren().get(i).setVisible(i == myPageIndex);
    }
    // Optionally load fresh data
    client.requestMyData();
});

// Optional: Add keyboard shortcut
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN),
    () -> navigationController.navigateTo(Page.MY_PAGE)
);
```

---

## Working with Events

### Pattern 1: Direct Listener (Current System)

```java
// ServiceClient already has listeners for each event type
client.addJobStateListener(job -> {
    // Called when job state changes
    viewModel.updateJob(job);
});
```

### Pattern 2: EventDispatcher (New, For Future)

```java
// Eventually, all events will flow through EventDispatcher
eventDispatcher.subscribe(JobStateChanged.class, event -> {
    viewModel.updateJob(event.getJob());
});
```

### Key: Always use `Platform.runLater()` if you're not already on App Thread

```java
// DON'T do this (network thread):
client.onWebSocketMessage(msg -> {
    viewModel.setData(data);  // ❌ CRASH! Not on App thread!
});

// DO this:
client.onWebSocketMessage(msg -> {
    Platform.runLater(() -> {
        viewModel.setData(data);  // ✅ Safe! Now on App thread
    });
});

// Or use EventDispatcher which does this automatically:
eventDispatcher.dispatch(event);  // Thread-safe by design
```

---

## Utility Classes

### DateUtils

```java
import com.filewatcherui.util.DateUtils;

LocalDateTime now = LocalDateTime.now();
LocalDateTime past = now.minusMinutes(5);

DateUtils.formatTime(now);           // "14:32:08"
DateUtils.formatDateTime(now);       // "2026-07-01 14:32:08"
DateUtils.formatRelative(past);      // "5m ago"
```

### Formatters

```java
import com.filewatcherui.util.Formatters;

Formatters.formatBytes(1024 * 1024);         // "1.0 MB"
Formatters.formatNumber(1234567);            // "1,234,567"
Formatters.formatStatus(Status.WATCHING);    // "Watching"
```

---

## Common Pitfalls & Solutions

### Pitfall 1: UI doesn't update after model change

```java
// ❌ Wrong: Model change, no binding
viewModel.setCount(5);
label.setText("5");  // Manual, error-prone

// ✅ Right: Auto-binding
label.textProperty().bind(viewModel.countProperty().asString());
viewModel.setCount(5);  // Label updates automatically
```

### Pitfall 2: Thread error "Not on FX thread"

```java
// ❌ Wrong: Updating UI from network thread
webSocket.onMessage(msg -> {
    label.setText("Updated");  // CRASH!
});

// ✅ Right: Defer to App thread
webSocket.onMessage(msg -> {
    Platform.runLater(() -> {
        label.setText("Updated");  // Safe
    });
});
```

### Pitfall 3: Memory leak from uncleaned listeners

```java
// ❌ Wrong: No cleanup
public MyPanel(ServiceClient client) {
    client.addListener(e -> updateUI(e));  // Listener stays forever
}

// ✅ Right: Cleanup on dispose
public MyPanel(ServiceClient client) {
    Consumer<Event> listener = e -> updateUI(e);
    client.addListener(listener);
    
    // In dispose():
    // client.removeListener(listener);
}
```

### Pitfall 4: ObservableList modifications from wrong thread

```java
// ❌ Wrong: From network thread
webSocket.onMessage(msg -> {
    viewModel.getItems().add(newItem);  // Not thread-safe!
});

// ✅ Right: From App thread via Platform.runLater()
webSocket.onMessage(msg -> {
    Platform.runLater(() -> {
        viewModel.getItems().add(newItem);  // Safe
    });
});
```

---

## Debugging Tips

### Tip 1: Check if property is bound correctly

```java
Label label = new Label();
label.textProperty().bind(viewModel.countProperty().asString());

// These should print and update:
System.out.println(label.getText());  // "0"
viewModel.setCount(5);
System.out.println(label.getText());  // "5"
```

### Tip 2: Verify event dispatching

```java
// Add logging to see if events reach handlers
eventDispatcher.subscribe(MyEvent.class, event -> {
    System.out.println("Event received: " + event);  // Check console
    viewModel.update(event);
});
```

### Tip 3: Use `@Override` and IDE inspections

```java
// IDE will catch if you misname a listener method
@Override
public void onJobStateChanged(WatchJob job) {
    // IDE ensures this matches the interface
}
```

---

## Testing Your Page

### Unit Test ViewModel

```java
@Test
public void testCountProperty() {
    MyPageViewModel vm = new MyPageViewModel();
    assertEquals(0, vm.getTotalCount());
    
    vm.setTotalCount(5);
    assertEquals(5, vm.getTotalCount());
    
    vm.reset();
    assertEquals(0, vm.getTotalCount());
}
```

### Integration Test (With Simulated Events)

```java
@Test
public void testUIUpdatesOnEvent() {
    MyPagePanel panel = new MyPagePanel(mockClient);
    Label label = /* get label from panel */;
    
    // Simulate event
    viewModel.setTotalCount(10);
    
    // Check UI updated
    assertEquals("Total: 10", label.getText());
}
```

---

## Useful Links

| Resource | Location |
|---|---|
| Architecture Guide | `ARCHITECTURE_MODULAR.md` |
| Implementation Summary | `IMPLEMENTATION_SUMMARY.md` |
| DashboardViewModel Example | `com.filewatcherui.model.DashboardViewModel` |
| EventDispatcher Code | `com.filewatcherui.websocket.EventDispatcher` |
| Theme Manager | `com.filewatcherui.theme.ThemeManager` |
| Navigation Controller | `com.filewatcherui.navigation.NavigationController` |

---

## Next: Advanced Topics

- **Form Validation**: Use `BooleanBinding` for form state
- **Search/Filter**: Use `FilteredList` wrapper around `ObservableList`
- **Multi-Select**: Use `TableView.getSelectionModel().setSelectionMode(MULTIPLE)`
- **Async Operations**: Use `Task` for long-running operations off App thread
- **Custom Controls**: Extend `Region` or `HBox` for complex components

---

## Get Help

1. **Read the code**: Existing panels like `DashboardPanel`, `JobTablePanel` show the patterns
2. **Check javadoc**: All new classes have detailed comments
3. **Review spec**: `UI/UX Design Specification` has all requirements
4. **Ask in code review**: If you're unsure about a pattern, ask!

---

**Happy coding! The architecture is designed to make UI development fast, testable, and maintainable.** 🚀

