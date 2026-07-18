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

By default the app is wired to `WebSocketServiceClient`, a real
implementation of the backend contract (see "Wiring up the real backend"
below) — pass `-DuseMockBackend=true` to use the in-memory mock instead.

## Project layout

```
src/main/java/com/relay/
├── MainApp.java, Main.java        → application entry point
├── model/                          → Job, JobStatus, TransferEvent, ActivityEvent, DashboardStats
├── service/                        → ServiceClient (integration interface), ServiceEvent,
│                                      MockServiceClient (demo data), WebSocketServiceClient
│                                      (real backend, see below), EventDispatcher,
│                                      WebSocketServiceClientSkeleton (bare starting point
│                                      for a different transport)
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

## Wiring up the real backend

Every view in this app talks only to the `ServiceClient` interface —
never to a concrete networking class. The app is now wired against a real
implementation, `service/WebSocketServiceClient.java`, which speaks the
plain-JSON contract in `docs/relay-monitoring-ws-contract.md`:

- **Server → Client**: `SNAPSHOT` (full job list, applied straight onto
  `AppState`) and `EVENT` (converted to a `ServiceEvent` and dispatched
  through the existing `EventDispatcher`/`Platform.runLater` path).
- **Client → Server**: `SNAPSHOT_REQUEST` and `COMMAND`
  (`sendCommand(jobId, JobCommand)` — the single seam the UI already calls
  for Start/Stop/Restart/Delete/Test Connection).
- Auto-reconnects with exponential backoff (2s/4s/8s/16s, capped at 30s)
  and re-requests a fresh snapshot once reconnected, per the contract's
  connection-lifecycle section.

By default it connects to `ws://localhost:8765/ws`. Override with:

```bash
RELAY_BACKEND_URL=ws://your-host:port/ws mvn javafx:run
```

To fall back to the old in-memory `MockServiceClient` (no backend needed
at all), run with `-DuseMockBackend=true` or `USE_MOCK_BACKEND=true`.

### Connecting to the real backend

`filewatcher-service`'s real engine (SFTP/FTP/SCP watchers, scheduler,
SQLite-backed job store) speaks this contract via `RelayWebSocketServer`,
started automatically by `ServiceMain` — there's no separate mock/demo
backend to run. Run the full service in one terminal:

```bash
mvn -pl filewatcher-service exec:java -Dexec.mainClass=com.filewatcherservice.Main
```

It binds to `services.json`'s `websocketHost`/`websocketPort`, which
default to `localhost:9876` — **not** 8765. Point the UI at it explicitly
in another terminal:

```bash
RELAY_BACKEND_URL=ws://localhost:9876/ws mvn -pl filewatcher-ui javafx:run
```

(or edit `websocketPort` in `services.json` to `8765` to match the UI's
zero-config default). Start/Stop/Restart/Delete/Test Connection in
Service Management drive real `WatchJob`s end to end — add a `LOCAL`
watch job pointed at two folders on disk if you want to see live
transfer events without configuring a real SFTP/FTP endpoint.

Kill and restart the service to see the UI's reconnect/backoff behavior
and the "Reconnecting…" status chip.

`WebSocketServiceClientSkeleton.java` is left in place as a bare-bones
reference for anyone building a client for a *different* transport (REST
+ SSE, gRPC, etc.) from scratch.

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
