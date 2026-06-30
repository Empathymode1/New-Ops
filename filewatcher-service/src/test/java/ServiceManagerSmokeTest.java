import com.filewatchercommon.model.WatchJob;
import com.filewatcherservice.config.AppConfig;
import com.filewatcherservice.database.DatabaseService;
import com.filewatcherservice.scheduler.Scheduler;
import com.filewatcherservice.service.FileWatcherService;
import com.filewatcherservice.service.JobStore;
import com.filewatcherservice.service.MonitorService;
import com.filewatcherservice.service.ServiceManager;
import org.junit.jupiter.api.*;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for ServiceManager — architecture doc §5 / roadmap item:
 *
 *   addWatchJob → start → getStatus → stop → removeWatchJob
 *
 * Verifies both sides of the contract:
 *   (a) in-memory MonitorService registry (services map inside ServiceManager)
 *   (b) persistence (JobStore → ServiceRepository → SQLite)
 *
 * Uses a real in-memory SQLite database (":memory:") so ServiceRepository,
 * JobStore, and DatabaseService all run for real — no mocks, no disk I/O.
 * FileWatcherService is constructed normally; jobs won't launch real NIO
 * watchers (source paths don't exist) but the engine's in-memory job map
 * is exercised fully.
 *
 * Scheduler uses pool size 1 — sufficient for a smoke test, no periodic
 * ticks are expected to fire during the test duration.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceManagerSmokeTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    private static DatabaseService   db;
    private static JobStore jobStore;
    private static Scheduler         scheduler;
    private static FileWatcherService watcherEngine;
    private static ServiceManager manager;

    private static final String JOB_ID   = "smoke-test-job-1";
    private static final String JOB_NAME = "Smoke Test Job";

    @BeforeAll
    static void setUp() {
        db = new DatabaseService("jdbc:sqlite::memory:");  // in-memory SQLite — no disk file
        jobStore      = new JobStore(db);
        scheduler     = new Scheduler(1);
        watcherEngine = new FileWatcherService(db, scheduler, new AppConfig());
        manager       = new ServiceManager(watcherEngine, jobStore);
    }

    @AfterAll
    static void tearDown() {
        scheduler.shutdown();
        db.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static WatchJob makeJob() {
        WatchJob job = new WatchJob(JOB_ID);
//        job.setId(JOB_ID);
        job.setName(JOB_NAME);
        job.setDirection(WatchJob.Direction.LOCAL_TO_LOCAL);
        job.setSourcePath("/tmp/smoke-src");
        job.setDestPath("/tmp/smoke-dest");
        job.setTransferMode(WatchJob.TransferMode.ENTIRE_FOLDER);
        job.setWatchDepth(0);
        job.setIntervalSeconds(30);
        job.setStatus(WatchJob.Status.IDLE);
        return job;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests — ordered to form a single lifecycle walkthrough
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("addWatchJob — registers in ServiceManager and persists to DB")
    void addWatchJob_registersAndPersists() {
        WatchJob job = makeJob();

        manager.addWatchJob(job);

        // (a) in-memory registry
        MonitorService service = manager.get(JOB_ID);
        assertNotNull(service,              "ServiceManager should contain the registered service");
        assertEquals(JOB_ID,   service.getId(),   "Service id should match job id");
        assertEquals(JOB_NAME, service.getName(),  "Service name should match job name");
        assertEquals("File Transfer", service.getType(), "Type should be 'File Transfer'");

        // (b) engine in-memory map
        WatchJob fromEngine = manager.getWatchJob(JOB_ID);
        assertNotNull(fromEngine, "FileWatcherService should know about the job");
        assertEquals(JOB_ID, fromEngine.getId());

        // (c) persistence — reload from SQLite
        boolean foundInDb = jobStore.load().stream()
                .anyMatch(j -> JOB_ID.equals(j.getId()));
        assertTrue(foundInDb, "Job should be persisted in SQLite after addWatchJob");
    }

    @Test
    @Order(2)
    @DisplayName("start — status transitions to WATCHING")
    void start_statusBecomesWatching() {
        // start() calls FileWatcherService.startJob(), which sets WATCHING
        // and submits a watcher task. The task itself will fail (no real path)
        // but the status is set synchronously before the watchPool submits.
        manager.start(JOB_ID);

        MonitorService service = manager.get(JOB_ID);
        assertNotNull(service);

        // Give the watcher thread a brief moment to attempt startup and fail.
        // We only care that the service moved out of IDLE — it may settle at
        // WATCHING (if the thread hasn't run yet) or ERROR (if it ran and failed
        // immediately because /tmp/smoke-src doesn't exist). Both are valid
        // post-start states; neither is IDLE.
        sleepMs(100);

        String status = service.getStatus();
        assertNotEquals(WatchJob.Status.IDLE.name(), status,
                "Status should not be IDLE after start() — got: " + status);
    }

    @Test
    @Order(3)
    @DisplayName("getStatus — returns current engine status via WatchJobService")
    void getStatus_reflectsEngineState() {
        MonitorService service = manager.get(JOB_ID);
        assertNotNull(service);

        String status = service.getStatus();
        assertNotNull(status, "getStatus() should never return null");
        assertFalse(status.isBlank(), "getStatus() should never return a blank string");

        // Must be a valid WatchJob.Status name
        assertDoesNotThrow(() -> WatchJob.Status.valueOf(status),
                "getStatus() should return a valid WatchJob.Status name, got: " + status);
    }

    @Test
    @Order(4)
    @DisplayName("stop — status transitions back to IDLE")
    void stop_statusBecomesIdle() {
        manager.stop(JOB_ID);

        // stop() is synchronous — WatchJob.setStatus(IDLE) is called before stopJob() returns
        MonitorService service = manager.get(JOB_ID);
        assertNotNull(service);
        assertEquals(WatchJob.Status.IDLE.name(), service.getStatus(),
                "Status should be IDLE after stop()");
    }

    @Test
    @Order(5)
    @DisplayName("updateWatchJob — overwrites in-memory and persisted state")
    void updateWatchJob_overwritesState() {
        WatchJob updated = makeJob();
        updated.setName("Updated Smoke Job");
        updated.setIntervalSeconds(60);

        manager.updateWatchJob(updated);

        // in-memory
        assertEquals("Updated Smoke Job", manager.getWatchJob(JOB_ID).getName());

        // persisted — reload from SQLite
        WatchJob fromDb = jobStore.load().stream()
                .filter(j -> JOB_ID.equals(j.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(fromDb);
        assertEquals("Updated Smoke Job", fromDb.getName());
        assertEquals(60, fromDb.getIntervalSeconds());
    }

    @Test
    @Order(6)
    @DisplayName("getAll / getByType — collection views are consistent")
    void collectionViews_areConsistent() {
        Collection<MonitorService> all = manager.getAll();
        assertFalse(all.isEmpty(), "getAll() should contain at least the smoke job");
        assertTrue(all.stream().anyMatch(s -> JOB_ID.equals(s.getId())),
                "getAll() should include the smoke job");

        Collection<MonitorService> byType = manager.getByType("File Transfer");
        assertFalse(byType.isEmpty(), "getByType('File Transfer') should be non-empty");
        assertTrue(byType.stream().anyMatch(s -> JOB_ID.equals(s.getId())),
                "getByType('File Transfer') should include the smoke job");
    }

    @Test
    @Order(7)
    @DisplayName("removeWatchJob — unregisters from ServiceManager, engine, and DB")
    void removeWatchJob_cleansUpEverywhere() {
        manager.removeWatchJob(JOB_ID);

        // (a) ServiceManager registry
        assertNull(manager.get(JOB_ID),
                "ServiceManager should not contain the job after removeWatchJob");

        // (b) FileWatcherService engine
        assertNull(manager.getWatchJob(JOB_ID),
                "FileWatcherService should not know the job after removeWatchJob");

        // (c) SQLite — row must be deleted
        boolean stillInDb = jobStore.load().stream()
                .anyMatch(j -> JOB_ID.equals(j.getId()));
        assertFalse(stillInDb, "Job row should be deleted from SQLite after removeWatchJob");
    }

    @Test
    @Order(8)
    @DisplayName("start / stop on unknown id — logs warning, does not throw")
    void unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> manager.start("nonexistent-id"),
                "start() with unknown id should not throw");
        assertDoesNotThrow(() -> manager.stop("nonexistent-id"),
                "stop() with unknown id should not throw");
    }

    @Test
    @Order(9)
    @DisplayName("stopAll / startAll — operate over all registered services without throwing")
    void stopAllStartAll_doNotThrow() {
        // Re-add a job so there's something to operate on
        manager.addWatchJob(makeJob());

        assertDoesNotThrow(() -> manager.startAll(), "startAll() should not throw");
        sleepMs(50);
        assertDoesNotThrow(() -> manager.stopAll(),  "stopAll() should not throw");

        // Clean up
        manager.removeWatchJob(JOB_ID);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
