# Relay — Transfer Monitoring Console (JavaFX)

A full JavaFX + CSS implementation of the Monitoring Tool UI/UX Design
Specification, structured for a real backend to be plugged in later.

## Requirements

- JDK 21+ (the code uses records, switch expressions, and `var`)
- Maven 3.9+
- Internet access the first time you build, to pull JavaFX 21 artifacts from Maven Central

> This project was written and organized in an offline sandbox, so it has
> **not** been compiled here — there was no network access to download the
> JavaFX SDK/Maven dependencies. Run `mvn -q compile` once you have network
> access to catch anything that needs a tweak for your exact JDK/OS combo
> (JavaFX ships OS-specific native artifacts).

## Run it

```bash
mvn javafx:run
```

This launches the app with a `MockServiceClient` that fires simulated
transfer/status events every ~4 seconds — the same cadence as the HTML
preview — so you can see the full UI live without a backend.

## Project layout

```
src/main/java/com/relay/
├── MainApp.java, Main.java        → application entry point
├── model/                          → Job, JobStatus, TransferEvent, ActivityEvent, DashboardStats
├── service/                        → ServiceClient (integration interface), ServiceEvent,
│                                      MockServiceClient (demo data), EventDispatcher,
│                                      WebSocketServiceClientSkeleton (real-backend starting point)
├── state/                          → AppState (single shared source of truth)
├── theme/                          → ThemeManager (dark/light runtime switch)
└── ui/
    ├── shell/                      → TopToolbar, SidebarNav, StatusBar, MainShell (BorderPane root)
    ├── dashboard/                  → DashboardView
    ├── services/                   → ServicesView, JobDetailsPanel
    ├── logs/                       → LogsView, TransferDetailsDialog (slide-over)
    ├── settings/                   → SettingsView
    └── components/                 → InfoCard, StatusBadge, ToggleSwitch, ToastNotification, ConnectionIndicator

src/main/resources/css/
├── variables-dark.css              → dark theme tokens (-fx-* custom properties)
├── variables-light.css             → light theme tokens, same variable names
└── app.css                         → every component style, references variables only
```

## Wiring up your real backend

Every view in this app talks only to the `ServiceClient` interface —
never to a concrete networking class. To go live:

1. Open `service/WebSocketServiceClientSkeleton.java`. It already has the
   full `java.net.http.WebSocket` connection lifecycle stubbed out
   (`connect`, `onText`, `onClose`, `onError`, `sendCommand`,
   `requestInitialSnapshot`) with `TODO` markers showing exactly where to:
   - point it at your real WebSocket URL,
   - deserialize inbound JSON into `ServiceEvent` (Jackson/Gson — add
     whichever you prefer to `pom.xml`),
   - serialize outbound `JobCommand`s in the shape your backend expects.
2. In `MainApp.start(...)`, change:
   ```java
   ServiceClient client = new MockServiceClient(state);
   ```
   to:
   ```java
   ServiceClient client = new WebSocketServiceClientSkeleton();
   ```
3. That's it — `EventDispatcher` already applies every `ServiceEvent` to
   `AppState` on the JavaFX Application Thread via `Platform.runLater`,
   and every table/card/list in the UI is bound to `AppState`'s
   observable properties, so real events will flow straight through to
   the screen with no other code changes.

If your backend also exposes a REST API for job CRUD (Add/Edit/Delete),
implement those calls in the same `ServiceClient` implementation —
`sendCommand(jobId, JobCommand)` is the single seam the UI already calls
for Start/Stop/Restart/Delete/Test Connection.

## What's stubbed / simplified

To keep this a focused, readable scaffold rather than a 10,000-line
drop-in, a few pieces from the spec are intentionally left as TODOs
rather than fully built:

- **Add/Edit Job Wizard** (spec §8, 5 steps) — the "+ Add" button currently
  shows a toast instead of opening the wizard. Build it as a `Stage`
  (modal) containing a `StackPane` of step panes with Back/Next buttons,
  or use ControlsFX's `Wizard` control.
- **CSV export** on the Logs page — button is present but not wired to a
  file writer yet.
- **High Contrast** theme — enum value exists in `ThemeManager`, currently
  points at the dark variables as a placeholder; give it its own
  `variables-hc.css` when you design the palette.
- **Search filtering** — search fields exist on Services/Logs/toolbar but
  aren't yet wired to `FilteredList` predicates.

Everything else from the spec (Dashboard, Service Management table +
details panel + toolbar state, Logs + slide-over details, Settings
categories, theme switching, keyboard shortcuts, status bar, toasts,
real-time event flow) is fully implemented.
