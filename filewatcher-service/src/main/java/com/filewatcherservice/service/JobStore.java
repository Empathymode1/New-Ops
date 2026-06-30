package com.filewatcherservice.service;

import com.filewatchercommon.model.WatchJob;
import com.filewatcherservice.database.DatabaseService;
import com.filewatcherservice.database.ServiceRepository;

import java.util.Collection;
import java.util.List;

/**
 * Persists watch jobs via SQLite (ServiceRepository), replacing the old
 * XML-based version. Public API unchanged — save(Collection) and load()
 * keep the same signatures so ServiceMain and ServiceWebSocketServer don't
 * need to change.
 */
public class JobStore {

    private final ServiceRepository repository;

    public JobStore(DatabaseService db) {
        this.repository = new ServiceRepository(db);
    }

    /** Saves/updates every job in the collection. */
    public void save(Collection<WatchJob> jobs) {
        repository.saveAll(jobs);
    }

    /** Loads all persisted jobs. */
    public List<WatchJob> load() {
        return repository.findAll();
    }

    /** Deletes a single job's row directly — call when a job is removed,
     *  instead of relying on save(Collection) to implicitly clean it up
     *  (unlike the old XML store, SQLite rows must be explicitly deleted). */
    public void delete(String jobId) {
        repository.delete(jobId);
    }
}