package com.filewatcherservice.service;

import com.filewatchercommon.model.WatchJob;

/**
 * Per-job MonitorService adapter. One instance wraps one WatchJob and
 * delegates start()/stop()/getStatus() to the existing FileWatcherService
 * engine (NIO watch loops, SSH/SFTP transfer logic, polling fallbacks —
 * all unchanged).
 *
 * This exists so ServiceManager can treat each watch job as an independent
 * managed service (per the architecture doc's section 5 diagram), without
 * requiring a rewrite of FileWatcherService's internals, which already key
 * everything by job id internally.
 */
public class WatchJobService implements MonitorService {

    private final WatchJob job;
    private final FileWatcherService engine;

    public WatchJobService(WatchJob job, FileWatcherService engine) {
        this.job = job;
        this.engine = engine;
    }

    @Override
    public String getId() {
        return job.getId();
    }

    @Override
    public String getName() {
        return job.getName();
    }

    @Override
    public String getType() {
        return "File Transfer";
    }

    @Override
    public void start() {
        engine.startJob(job.getId());
    }

    @Override
    public void stop() {
        engine.stopJob(job.getId());
    }

    @Override
    public String getStatus() {
        WatchJob current = engine.getJob(job.getId());
        return current != null && current.getStatus() != null
                ? current.getStatus().name()
                : WatchJob.Status.IDLE.name();
    }

    /** Underlying job model — used where callers need full WatchJob detail (UI push, persistence). */
    public WatchJob getJob() {
        return engine.getJob(job.getId()) != null ? engine.getJob(job.getId()) : job;
    }
}
