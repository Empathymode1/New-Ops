# Relay Monitoring Console - Modular Architecture Implementation

## Overview

This document describes the refactored JavaFX architecture for the Relay Monitoring Console, implementing the specification from `UI/UX Design Specification §1-15`.

---

## Package Structure

The UI is organized into focused, maintainable packages:

```
com.filewatcherui/
├── app/                    # Application entry point
├── navigation/             # Navigation state management
│   └── NavigationController.java
├── dashboard/              # Dashboard page & view model
├── services/               # Service management page
├── logs/                   # Logs & transfer history page
├── settings/               # Settings & configuration pages
├── components/             # Reusable UI components
│   ├── ToggleSwitch.java
│   ├── EnhancedConnectionIndicator.java
│   ├── StatusBadge.java (existing)
│   ├── InfoCard.java (existing)
│   └── ...
├── dialogs/                # Modal dialogs & wizard steps
├── theme/                  # Theme management
│   ├── ThemeManager.java
│   ├── dark.css
│   └── light.css
├── websocket/              # WebSocket event handling
│   └── EventDispatcher.java
├── model/                  # View models & observable data
│   ├── DashboardViewModel.java
│   ├── ServicesViewModel.java
│   └── ...
├── util/                   # Utilities & formatters
│   ├── DateUtils.java
│   └── Formatters.java
└── ui/                     # Legacy UI panels (being refactored)
```

---

## Core Architecture Patterns

### 1. Observable Model Pattern

Every page has a **ViewModel** class that aggregates observable properties. UI controls bind to these properties for automatic updates.

```java
// Example: DashboardViewModel
public class DashboardViewModel {
    private final IntegerProperty runningJobs = new SimpleIntegerProperty(0);
    
    public void setRunningJobs(int count) {
        runningJobs.set(count);
    }
    
    public IntegerProperty runningJobsProperty() {
        return runningJobs;
    }
}

// In a Controller or View class:
Label jobCountLabel = new Label();
jobCountLabel.textProperty().bind(
    viewModel.runningJobsProperty().asString()
);
```

**Benefits:**
- UI automatically updates when model changes
- No manual refresh code needed
- Separation of concerns: Model knows nothing of UI

### 2. Event Dispatcher (Thread-Safe Event Bus)

The `EventDispatcher` routes WebSocket events safely to the JavaFX Application Thread. **CRITICAL**: JavaFX UI can ONLY be modified from the Application Thread.

```java
// In ServiceClient (WebSocket receiver thread):
webSocket.onMessage(message -> {
    TransferEvent event = parse(message);
    eventDispatcher.dispatch(event);  // Thread-safe, defers to App Thread
});

// In UI code (App Thread):
eventDispatcher.subscribe(TransferEvent.class, event -> {
    viewModel.prependActivity(event);  // Safe! Already on App Thread
});
```

**Key Rule:** All event handlers are wrapped with `Platform.runLater()` internally.

### 3. Theme Manager (Runtime Theme Switching)

The `ThemeManager` swaps CSS stylesheets at runtime without restart (spec §13).

```java
// Switch theme
ThemeManager.switchTheme(scene, ThemeManager.Theme.DARK);

// In dark.css and light.css, define the same variables with different values:
.root {
    -bg: #0B0F14;  /* dark */
    -accent: #42E0CE;
}

// Other stylesheets reference the variables:
.button {
    -fx-background-color: -accent;
}
```

### 4. Navigation Controller

The `NavigationController` manages page state and transitions. Sidebar and keyboard shortcuts both use this.

```java
// Register navigation handlers in MainWindow:
navigationController.onPage(Page.DASHBOARD, () -> {
    // Show dashboard, load data, update sidebar highlight
});

// Navigate from anywhere:
navigationController.navigateTo(Page.SERVICES);

// Keyboard shortcut can also navigate:
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN),
    () -> navigationController.navigateTo(Page.SERVICES)
);
```

---

## Component Library

### Custom Components (in `com.filewatcherui.components`)

#### ToggleSwitch
Modern on/off toggle with smooth animation.

```java
ToggleSwitch toggle = new ToggleSwitch(() -> {
    System.out.println("Toggled to: " + toggle.getState());
});
toggle.setState(true);

// In FXML or Scene:
scene.getRoot().getChildren().add(toggle);
```

#### EnhancedConnectionIndicator
Real-time connection status with pulsing animation when connected.

```java
EnhancedConnectionIndicator indicator = new EnhancedConnectionIndicator();
indicator.setConnected(true);  // Show green pulsing dot
```

#### StatusBadge (existing, enhanced)
Displays job/transfer status with color-coded background.

```java
Label badge = new StatusBadge("Running");
// Renders as green pill with "Running" text
```

#### InfoCard (existing, integrated)
Summary metric card with title, value, and optional description.

```java
InfoCard card = new InfoCard("Running Jobs", "12", "Active transfers");
// Bound to DashboardViewModel:
viewModel.runningJobsProperty()
    .addListener((obs, old, newVal) -> 
        card.setValue(newVal.toString())
    );
```

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                  Monitoring Service                             │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼ WebSocket (org.java-websocket)
┌─────────────────────────────────────────────────────────────────┐
│            ServiceClient (Listener Pattern)                     │
│   Receives: JOB_STATE, EVENT, NOTIFICATION, HEARTBEAT, etc.    │
└──────────────────────┬──────────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        │                             │
        ▼                             ▼
┌──────────────────────┐    ┌─────────────────────────┐
│  EventDispatcher     │    │  ServiceClient Listeners│
│  (Thread-safe bus)   │    │  (Direct subscriptions) │
└──────────────────────┘    └─────────────────────────┘
        │
        ├─► Platform.runLater()  (Defers to App Thread)
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│              Observable View Models                              │
│  DashboardViewModel, ServicesViewModel, etc.                    │
│  Update properties when events arrive                           │
└──────────────────────┬───────────────────────────────────────────┘
        │
        ├──► Property bindings
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│                  JavaFX UI Controls                              │
│  Label, TableView, InfoCard, etc. (automatic refresh)           │
└──────────────────────────────────────────────────────────────────┘
```

---

## Building a New Page

Follow this checklist to add a new page following the architecture:

### 1. Create ViewModel

```java
package com.filewatcherui.model;

public class MyPageViewModel {
    private final IntegerProperty metric = new SimpleIntegerProperty(0);
    private final ObservableList<MyItem> items = FXCollections.observableArrayList();
    
    public IntegerProperty metricProperty() { return metric; }
    public ObservableList<MyItem> getItems() { return items; }
}
```

### 2. Create View/Controller

```java
package com.filewatcherui.pages;  // or use 'services', 'dashboard', etc.

public class MyPagePanel {
    private final MyPageViewModel viewModel;
    private final BorderPane root = new BorderPane();
    
    public MyPagePanel(ServiceClient client) {
        this.viewModel = new MyPageViewModel();
        root.setTop(buildHeader());
        root.setCenter(buildContent());
        
        // Bind to ViewModel for automatic updates
        client.addMyListener(data -> {
            viewModel.update(data);
        });
    }
    
    private VBox buildContent() {
        Label metricLabel = new Label();
        metricLabel.textProperty().bind(
            viewModel.metricProperty().asString()
        );
        
        TableView<MyItem> table = new TableView<>(viewModel.getItems());
        // ... build columns
        
        VBox content = new VBox(metricLabel, table);
        return content;
    }
    
    public Region getRoot() { return root; }
}
```

### 3. Register in MainWindow

```java
// In MainWindow constructor:
MyPagePanel myPage = new MyPagePanel(client);

// Add to content stack:
contentStack.getChildren().add(myPage.getRoot());

// Register navigation handler:
navigationController.onPage(Page.MY_PAGE, () -> {
    // Show this page
    for (int i = 0; i < contentStack.getChildren().size(); i++) {
        contentStack.getChildren().get(i).setVisible(i == myPageIndex);
    }
    client.requestMyData();
});
```

### 4. Add Keyboard Shortcut (Optional)

```java
// In MainWindow after scene creation:
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN),
    () -> navigationController.navigateTo(Page.MY_PAGE)
);
```

---

## Utility Classes

### DateUtils
Format times/dates consistently:

```java
LocalDateTime now = LocalDateTime.now();

DateUtils.formatTime(now);         // "14:32:08"
DateUtils.formatDateTime(now);     // "2026-07-01 14:32:08"
DateUtils.formatRelative(now);     // "5s ago", "2m ago", etc.
```

### Formatters
Format numbers, bytes, enums:

```java
Formatters.formatBytes(1024 * 1024);           // "1.0 MB"
Formatters.formatNumber(1234567);              // "1,234,567"
Formatters.formatStatus(Status.WATCHING);      // "Watching"
```

---

## CSS & Theming

### Variable-Based Theme System

`theme/variables.css` (loaded on `.root`):

```css
.root {
    -bg: #0B0F14;
    -panel: #121822;
    -border: #26303F;
    -text: #E7EDF5;
    -accent: #42E0CE;
    -success: #3FB950;
    -error: #F85149;
}
```

`theme/dark.css`:

```css
.root {
    -bg: #0B0F14;
    -panel: #121822;
    ...
}
```

`theme/light.css`:

```css
.root {
    -bg: #FFFFFF;
    -panel: #F5F5F5;
    ...
}
```

All component stylesheets reference variables, so theme switching affects everything:

```css
.button {
    -fx-background-color: -accent;
    -fx-text-fill: -text;
    -fx-border-color: -border;
}
```

---

## Testing & Simulation

### Simulating WebSocket Events (Before Backend Ready)

Per spec §9, validate UI design before real networking:

```java
// In a test setup or demo mode:
Timeline eventSimulator = new Timeline(
    new KeyFrame(Duration.seconds(2), e -> {
        TransferEvent fakeEvent = new TransferEvent(...);
        eventDispatcher.dispatch(fakeEvent);
    })
);
eventSimulator.setCycleCount(Timeline.INDEFINITE);
eventSimulator.play();

// The UI updates as if real WebSocket data arrived
```

This lets you test the entire visual/interaction design before the Monitoring Service is ready.

---

## Performance Considerations

1. **Listener Cleanup**: Remove listeners when panels close to prevent memory leaks.
   ```java
   public void dispose() {
       client.removeJobListListener(myListener);
   }
   ```

2. **Observable List Size**: Keep activity feeds/logs bounded (e.g., last 100 items).
   ```java
   public void prependActivity(Item item) {
       items.add(0, item);
       if (items.size() > 100) items.remove(items.size() - 1);
   }
   ```

3. **Thread Safety**: Always use `Platform.runLater()` for cross-thread updates.

4. **CSS Reuse**: Use style classes extensively; avoid inline styles for properties that change per theme.

---

## Migration Path (Existing → New Architecture)

1. ✅ **Phase 1**: Create package structure & core infrastructure (ThemeManager, EventDispatcher, NavigationController)
2. ✅ **Phase 2**: Create ViewModels and binding utilities
3. ⏳ **Phase 3**: Refactor existing panels (DashboardPanel, JobTablePanel, LogsPanel) to use ViewModels
4. ⏳ **Phase 4**: Create new components (ToggleSwitch, enhanced ConnectionIndicator, etc.)
5. ⏳ **Phase 5**: Wire EventDispatcher into ServiceClient
6. ⏳ **Phase 6**: Add remaining utility classes (validators, preferences, etc.)
7. ⏳ **Phase 7**: Comprehensive testing & performance tuning

---

## References

- **Spec**: `UI/UX Design Specification §1-15`
- **Build Order**: Spec §9
- **Event Flow**: Spec §14
- **Keyboard Shortcuts**: Spec §15
- **Themes**: Spec §3, §13

---

**Last Updated**: July 1, 2026
**Version**: 1.0 (Initial architecture)

