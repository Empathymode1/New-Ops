package com.filewatcher.model;

/**
 * A single historical transfer/event row, as returned by LOGS_RESPONSE
 * (contract §1.7) — the DB-backed job run history, distinct from the
 * live in-session {@link TransferEvent} feed AppState.getLogs() holds
 * (which only covers events that happened while this client was
 * connected). Plain DTO, not JavaFX-bound — LogsView converts these to
 * {@link TransferEvent} for display, reusing the existing table/dialog.
 */
public record TransferLogEntry(
        long id,
        String jobId,
        String jobName,
        String eventType,
        String message,
        String filename,
        long sizeBytes,
        String occurredAt
) {
}
