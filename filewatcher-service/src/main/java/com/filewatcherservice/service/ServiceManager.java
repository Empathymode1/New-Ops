package com.filewatcherservice.service;

import com.filewatchercommon.model.WatchJob;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every running MonitorService instance, regardless of type — per
 * architecture doc section 5:
 *
 *   ServiceManager
 *       |
 *       +-- FileTransferService  (one WatchJobService per WatchJob)
 *       |
 *       +-- SocketService
 *       |
 *       +-- Future Service Types
 *
 * ServiceManager itself never knows about WatchJob, SFTP, sockets, or any
 * other service-specific detail — it only ever calls start()/stop()/
 * getStatus() through the MonitorService interface. This is what lets new
 * service types be added later (section 19: "Plugin-based Service
 * Architecture") without touching this class.
 *
 * For watch jobs specifically, FileWatcherService remains the underlying
 * engine (NIO watch loops, SSH/SFTP transfer logic) — ServiceManager holds
 * a thin WatchJobService adapter per job and delegates lifecycle calls to
 * it, which in turn delegates to FileWatcherService for that job's id.
 */
public class ServiceManager {

    private static final Logger LOG = Logger.getLogger(ServiceManager.class.getName());

    private final Map<String, MonitorService> services = new ConcurrentHashMap<>();
    private final FileWatcherService watcherEngine;
    private final JobStore jobStore;

    public ServiceManager(FileWatcherService watcherEngine, JobStore jobStore) {
        this.watcherEngine = watcherEngine;
        this.jobStore = jobStore;
    }

    // ── Generic registration (works for any MonitorService type) ───────────

    public void register(MonitorService service) {
        services.put(service.getId(), service);
    }

    public void unregister(String id) {
        MonitorService service = services.remove(id);
        if (service != null) {
            try { service.stop(); } catch (Exception ignored) {}
        }
    }

    public MonitorService get(String id) {
        return services.get(id);
    }

    public Collection<MonitorService> getAll() {
        return Collections.unmodifiableCollection(services.values());
    }

    public Collection<MonitorService> getByType(String type) {
        return services.values().stream()
                .filter(s -> s.getType().equals(type))
                .toList();
    }

    // ── Lifecycle (uniform across every service type) ──────────────────────

    public void start(String id) {
        MonitorService service = services.get(id);
        if (service == null) {
            LOG.warning("ServiceManager: start() — unknown service id " + id);
            return;
        }
        service.start();
    }

    public void stop(String id) {
        MonitorService service = services.get(id);
        if (service == null) {
            LOG.warning("ServiceManager: stop() — unknown service id " + id);
            return;
        }
        service.stop();
    }

    public void startAll() {
        services.values().forEach(s -> {
            try { s.start(); } catch (Exception e) {
                LOG.warning("ServiceManager: startAll() — failed to start " + s.getId() + " — " + e.getMessage());
            }
        });
    }

    public void stopAll() {
        services.values().forEach(s -> {
            try { s.stop(); } catch (Exception e) {
                LOG.warning("ServiceManager: stopAll() — failed to stop " + s.getId() + " — " + e.getMessage());
            }
        });
    }

    // ── Watch-job-specific convenience methods ──────────────────────────────
    //
    // These exist because WatchJob CRUD (add/remove/edit) needs more than the
    // generic MonitorService surface provides — the UI's Service Management
    // tab needs full WatchJob detail (host, path, transfer mode, etc.), not
    // just id/name/status. ServiceWebSocketServer and ServiceMain use these
    // instead of reaching into FileWatcherService directly, so ServiceManager
    // stays the single entry point per the doc's "UI must never perform
    // monitoring or file transfer operations" principle (section 3) — the UI
    // talks to the manager, the manager talks to the engine.

    /** Registers a new watch job: adds it to the underlying engine, persists it, and wraps it as a MonitorService. */
    public void addWatchJob(WatchJob job) {
        watcherEngine.addJob(job);
        register(new WatchJobService(job, watcherEngine));
        jobStore.save(java.util.List.of(job));
    }

    /**
     * Updates an existing watch job (same id). Re-adding through the engine
     * overwrites the in-memory job (FileWatcherService.addJob upserts by id),
     * and the MonitorService wrapper is re-registered in case the WatchJob
     * instance itself changed.
     */
    public void updateWatchJob(WatchJob job) {
        watcherEngine.addJob(job);
        register(new WatchJobService(job, watcherEngine));
        jobStore.save(java.util.List.of(job));
    }

    /** Removes a watch job entirely: stops it, removes from the engine, unregisters the service, deletes its row. */
    public void removeWatchJob(String jobId) {
        watcherEngine.removeJob(jobId); // also stops it internally
        services.remove(jobId);
        jobStore.delete(jobId);
    }

    /** Returns the live WatchJob model for a given id, or null if not a watch job (or not found). */
    public WatchJob getWatchJob(String jobId) {
        return watcherEngine.getJob(jobId);
    }

    /** All watch jobs currently known to the engine — used for INIT push and persistence. */
    public Collection<WatchJob> getWatchJobs() {
        return watcherEngine.getJobs();
    }

    public FileWatcherService getWatcherEngine() {
        return watcherEngine;
    }
}