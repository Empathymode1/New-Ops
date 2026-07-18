package com.filewatcherservice.scheduler;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Central scheduler for all periodic work in the Monitoring Service.
 *
 * Architecture doc §6: "A single ScheduledExecutorService-backed Scheduler
 * class should own all periodic work, so threads aren't created ad hoc per job."
 *
 * Design notes:
 * - One shared ScheduledExecutorService with a bounded core pool. Periodic
 *   tasks are short-lived (one scan tick), so a small pool is sufficient.
 * - Tasks are keyed by a caller-supplied taskId so they can be cancelled
 *   individually (e.g. "job-123:polling", "heartbeat", "socket-health").
 * - scheduleRepeating uses scheduleWithFixedDelay rather than
 *   scheduleAtFixedRate: if a tick runs long (e.g. slow SFTP ls), the next
 *   tick is delayed by intervalSeconds *after* completion rather than
 *   firing on top of it.
 * - cancel() does not interrupt a running tick — polling ticks are short and
 *   non-blocking, so a clean "don't reschedule" is sufficient. The underlying
 *   NIO/SSH blocking work is cancelled separately via Future.cancel(true) on
 *   the watchPool threads.
 */
public class Scheduler {

    private static final Logger LOG = Logger.getLogger(Scheduler.class.getName());

    /** Core pool size. Periodic ticks are brief; 4 threads handles plenty of jobs. */
    private final ScheduledExecutorService executor;

    public Scheduler(int corePoolSize) {
        this.executor = Executors.newScheduledThreadPool(corePoolSize);
    }

    /** Active scheduled futures, keyed by taskId. */
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /**
     * Schedule {@code task} to run repeatedly, with {@code intervalSeconds} delay
     * between the end of one execution and the start of the next.
     *
     * If a task with the same {@code taskId} is already registered it is
     * cancelled first, so callers can safely re-register without leaking tasks.
     *
     * @param taskId          unique identifier (e.g. "job-abc:polling")
     * @param task            the work to run each tick
     * @param intervalSeconds delay between ticks in seconds
     */
    public void scheduleRepeating(String taskId, Runnable task, long intervalSeconds) {
        // Cancel any existing registration for this id before replacing it.
        cancel(taskId);

        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        // Log but don't propagate — an uncaught exception would
                        // silently kill the recurring task inside the executor.
                        LOG.warning("Scheduled task [" + taskId + "] threw: " + e.getMessage());
                    }
                },
                intervalSeconds,   // initial delay — first tick after one full interval
                intervalSeconds,
                TimeUnit.SECONDS
        );

        tasks.put(taskId, future);
        LOG.fine("Scheduled task [" + taskId + "] every " + intervalSeconds + "s");
    }

    /**
     * Cancel a previously scheduled task. Safe to call even if the taskId
     * was never registered or has already been cancelled.
     *
     * @param taskId the identifier passed to {@link #scheduleRepeating}
     */
    public void cancel(String taskId) {
        ScheduledFuture<?> existing = tasks.remove(taskId);
        if (existing != null) {
            existing.cancel(false); // don't interrupt a running tick
            LOG.fine("Cancelled task [" + taskId + "]");
        }
    }

    /**
     * Returns true if a task with this id is currently registered and has not
     * been cancelled or completed.
     */
    public boolean isScheduled(String taskId) {
        ScheduledFuture<?> f = tasks.get(taskId);
        return f != null && !f.isDone() && !f.isCancelled();
    }

    /**
     * True while the underlying executor is accepting/running tasks —
     * false once {@link #shutdown()} has been called. Used for the
     * dashboard's Health Overview (contract §1.4); not a deep health
     * check, just "is this thing alive".
     */
    public boolean isRunning() {
        return !executor.isShutdown();
    }

    /**
     * Cancel all registered tasks and shut down the executor.
     * Called once during application shutdown (§16 of architecture doc).
     */
    public void shutdown() {
        tasks.forEach((id, future) -> future.cancel(false));
        tasks.clear();
        executor.shutdownNow();
        LOG.info("Scheduler shut down");
    }
}