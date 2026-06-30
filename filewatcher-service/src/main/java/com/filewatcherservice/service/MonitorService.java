package com.filewatcherservice.service;

/**
 * Common interface every managed service implements — per architecture doc
 * section 5:
 *
 *   ServiceManager
 *       |
 *       +-- FileTransferService
 *       |
 *       +-- SocketService
 *       |
 *       +-- Future Service Types
 *
 *   MonitorService
 *     start()
 *     stop()
 *     getStatus()
 *
 * This lets ServiceManager add new service types without changing its own
 * code — it only ever talks to MonitorService.
 *
 * In this codebase, each WatchJob becomes its own MonitorService instance
 * (see WatchJobService), rather than the whole FileWatcherService being a
 * single service. FileWatcherService remains the underlying engine that
 * actually does the watching/transferring; WatchJobService is a thin
 * per-job adapter over it.
 */
public interface MonitorService {

    /** Unique id for this service instance (matches WatchJob.getId() for watch jobs). */
    String getId();

    /** Human-readable name, shown in the UI. */
    String getName();

    /** The service type — used by the UI to group/label entries (e.g. "File Transfer", "Socket"). */
    String getType();

    void start();

    void stop();

    /**
     * Current status as a simple string (e.g. WatchJob.Status.name(), or
     * a SocketService's own connection state). Kept as String rather than
     * a shared enum so different service types aren't forced into the
     * same status vocabulary.
     */
    String getStatus();
}