# Relay ↔ Monitoring Service — WebSocket Contract

This is the wire format the JavaFX app (`WebSocketServiceClient`) speaks.
It's plain JSON over a single WebSocket connection — language-agnostic, so
the backend can be built in anything (Java, Node, Python, Go...).

Endpoint (default, override via `RELAY_BACKEND_URL`): `ws://localhost:8765/ws`

---

## 1. Server → Client messages

Every message is a single JSON object with a `"type"` discriminator.

### 1.1 `SNAPSHOT` — full job list

Send this **once right after the connection opens**, and again any time
the client sends `SNAPSHOT_REQUEST` (e.g. on Refresh).

```json
{
  "type": "SNAPSHOT",
  "jobs": [
    {
      "id": "job-1",
      "name": "PAX-Manifest-Sync",
      "type": "SFTP Watch",
      "sourcePath": "/export/pax/manifests",
      "destPath": "sftp://dcs01/inbound",
      "pollingInterval": "15s",
      "credential": "dcs-svc-account",
      "status": "RUNNING",
      "filesToday": 342,
      "lastTransfer": "12s ago",
      "currentActivity": "Polling source directory"
    }
  ]
}
```

`status` must be one of: `RUNNING`, `STARTING`, `RESTARTING`, `STOPPED`, `DISABLED`
(these map 1:1 to the status badges in the UI spec).

### 1.2 `EVENT` — real-time event

Sent as things happen. One event = one UI update (a table row, an
activity feed entry, a toast, or a stat counter).

```json
{
  "type": "EVENT",
  "eventType": "TRANSFER_COMPLETED",
  "jobId": "job-1",
  "jobName": "PAX-Manifest-Sync",
  "filename": "manifest_8841.xml",
  "message": "Completed successfully",
  "newStatus": null,
  "timestamp": "2026-07-02T14:32:08"
}
```

`eventType` is one of:

| eventType | Required fields | UI effect |
|---|---|---|
| `TRANSFER_COMPLETED` | jobId, jobName, filename | +1 Transfers Today, activity feed entry, log row, toast |
| `TRANSFER_FAILED` | jobId, jobName, filename, message | +1 Failed Transfers, activity feed entry, log row, error toast |
| `SERVICE_STARTED` | jobId, jobName | status → RUNNING, activity feed entry |
| `SERVICE_STOPPED` | jobId, jobName | status → STOPPED, activity feed entry |
| `SERVICE_STATUS_CHANGED` | jobId, jobName, newStatus | status badge updates to `newStatus` |
| `HEARTBEAT` | jobId, jobName | "Last Heartbeat" / "Last Transfer" refreshed |
| `CONNECTION_LOST` | *(none)* | WebSocket status chip → "Reconnecting…" |
| `CONNECTION_RESTORED` | *(none)* | WebSocket status chip → "Connected" |

Fields not required for a given `eventType` may be omitted or `null`.
`timestamp` is ISO-8601 local time; if omitted, the client stamps it with
the time the message was received.

---

## 2. Client → Server messages

### 2.1 `SNAPSHOT_REQUEST`

```json
{ "type": "SNAPSHOT_REQUEST" }
```
Backend should reply with a `SNAPSHOT` message.

### 2.2 `COMMAND`

Sent when the operator clicks Start / Stop / Restart / Delete / Test
Connection in Service Management.

```json
{
  "type": "COMMAND",
  "jobId": "job-1",
  "command": "START"
}
```

`command` is one of: `START`, `STOP`, `RESTART`, `DELETE`, `TEST_CONNECTION`.

The backend should perform the action and then push the resulting state
change back as a `SERVICE_STATUS_CHANGED` (or `TRANSFER_*`, for
`TEST_CONNECTION`) `EVENT` message — the client does not assume success
just because the command was sent.

---

## 3. Connection lifecycle

- Client connects, backend sends an initial `SNAPSHOT`.
- Backend streams `EVENT` messages as they occur — no polling from the client.
- If the socket drops, the client retries with exponential backoff
  (2s, 4s, 8s, 16s, capped at 30s) and requests a fresh `SNAPSHOT` once
  reconnected, since state may have changed while disconnected.

## 4. Reference implementation

`filewatcher-service/src/main/java/com/relay/devserver/SampleBackendServer.java`
in this repo implements this exact contract (in Java, using the
lightweight `Java-WebSocket` library) and emits the same demo
data/cadence as the JavaFX app's `MockServiceClient`. Run it and point
the app at it to test the full real-time path before your real backend
exists — see `filewatcher-ui/README.md` for how to run both.
