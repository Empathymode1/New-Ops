package com.filewatcherservice.database;

import com.filewatchercommon.model.TransferEvent;
import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.util.OsType;
import com.filewatcherservice.service.CredentialStore;

import java.io.File;
import java.util.List;

/**
 * Standalone smoke test for the SQLite-backed DB layer.
 *
 * Not a JUnit test — runs as a plain main() so it can be executed without
 * adding a test framework dependency. Run with:
 *
 *   mvn compile exec:java \
 *     -Dexec.mainClass=com.filewatcherservice.database.DbLayerSmokeTest \
 *     -Dfilewatcher.dataDir=./test-data
 *
 * (the -Dfilewatcher.dataDir override keeps this from touching your real
 * D:\Data directory and lets it run on non-Windows machines too)
 *
 * Each check prints PASS/FAIL; the process exits with code 1 if anything
 * failed, so it can also be wired into CI later.
 */
public class DbLayerSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // Use a throwaway dir if not explicitly overridden, so repeated runs
        // start from a clean slate and don't pollute a real data directory.
        if (System.getProperty("filewatcher.dataDir") == null) {
            String tmp = new File(System.getProperty("java.io.tmpdir"), "filewatcher-test-" + System.nanoTime())
                    .getAbsolutePath();
            System.setProperty("filewatcher.dataDir", tmp);
        }
        System.out.println("Using data dir: " + System.getProperty("filewatcher.dataDir"));

        DatabaseService db = new DatabaseService();

        try {
            testServiceRepository(db);
            testTransferRepository(db);
            testCredentialRepository(db);
            testSettingsRepository(db);
            testCredentialStoreIntegration(db);
        } finally {
            db.close();
        }

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ── ServiceRepository ─────────────────────────────────────────────────

    private static void testServiceRepository(DatabaseService db) {
        section("ServiceRepository");
        ServiceRepository repo = new ServiceRepository(db);

        WatchJob job = new WatchJob();
        job.setName("Test Job 1");
        job.setDirection(WatchJob.Direction.OUTBOUND);
        job.setTransferMode(WatchJob.TransferMode.ENTIRE_FOLDER);
        job.setProtocol(WatchJob.Protocol.SFTP);
        job.setSourceHost("192.168.1.50");
        job.setSourcePort(22);
        job.setSourceUser("opsuser");
        job.setSourcePassword("hunter2");
        job.setSourcePath("/local/in");
        job.setDestHost("remote.example.com");
        job.setDestPort(2222);
        job.setDestUser("remoteuser");
        job.setDestPassword("s3cr3t");
        job.setDestPath("/remote/out");
        job.setRemoteOs(OsType.LINUX);
        job.setSpecificPattern("*.csv");
        job.setIntervalSeconds(45);
        job.setWatchDepth(2);
        job.setFilesTransferred(7);
        job.setBytesTransferred(123456);
        job.setLastError("previous transient error");

        String originalId = job.getId();

        // Insert
        repo.save(job);
        check("save() does not throw", true);

        // Find by id — verify every field round-trips correctly
        WatchJob loaded = repo.findById(originalId);
        check("findById returns non-null", loaded != null);
        if (loaded == null) return;

        check("id preserved (reflection-set)", originalId.equals(loaded.getId()));
        check("name round-trips", "Test Job 1".equals(loaded.getName()));
        check("direction round-trips", loaded.getDirection() == WatchJob.Direction.OUTBOUND);
        check("transferMode round-trips", loaded.getTransferMode() == WatchJob.TransferMode.ENTIRE_FOLDER);
        check("protocol round-trips", loaded.getProtocol() == WatchJob.Protocol.SFTP);
        check("sourceHost round-trips", "192.168.1.50".equals(loaded.getSourceHost()));
        check("sourcePort round-trips", loaded.getSourcePort() == 22);
        check("sourcePassword round-trips", "hunter2".equals(loaded.getSourcePassword()));
        check("destPath round-trips", "/remote/out".equals(loaded.getDestPath()));
        check("remoteOs round-trips", loaded.getRemoteOs() == OsType.LINUX);
        check("specificPattern round-trips", "*.csv".equals(loaded.getSpecificPattern()));
        check("intervalSeconds round-trips", loaded.getIntervalSeconds() == 45);
        check("watchDepth round-trips", loaded.getWatchDepth() == 2);
        check("filesTransferred round-trips", loaded.getFilesTransferred() == 7);
        check("bytesTransferred round-trips", loaded.getBytesTransferred() == 123456);
        check("lastError round-trips", "previous transient error".equals(loaded.getLastError()));
        check("createdAt round-trips (non-null)", loaded.getCreatedAt() != null);
        check("status defaults to IDLE and round-trips", loaded.getStatus() == WatchJob.Status.IDLE);

        // Update (ON CONFLICT upsert path)
        loaded.setStatus(WatchJob.Status.WATCHING);
        loaded.setFilesTransferred(8);
        repo.save(loaded);
        WatchJob reloaded = repo.findById(originalId);
        check("upsert updates status", reloaded.getStatus() == WatchJob.Status.WATCHING);
        check("upsert updates counters", reloaded.getFilesTransferred() == 8);
        check("upsert does not duplicate row", repo.findAll().size() == 1);

        // Second job + findAll
        WatchJob job2 = new WatchJob();
        job2.setName("Test Job 2");
        job2.setDirection(WatchJob.Direction.LOCAL_TO_LOCAL);
        job2.setTransferMode(WatchJob.TransferMode.LATEST_ONLY);
        job2.setSourcePath("/a");
        job2.setDestPath("/b");
        repo.save(job2);

        List<WatchJob> all = repo.findAll();
        check("findAll returns both jobs", all.size() == 2);

        // Delete
        repo.delete(originalId);
        check("delete removes the row", repo.findById(originalId) == null);
        check("delete leaves the other job intact", repo.findAll().size() == 1);

        // Cleanup for next sections
        repo.delete(job2.getId());
    }

    // ── TransferRepository ────────────────────────────────────────────────

    private static void testTransferRepository(DatabaseService db) {
        section("TransferRepository");
        TransferRepository repo = new TransferRepository(db);

        String jobId = "job-abc-123";

        repo.save(new TransferEvent(jobId, "My Job", TransferEvent.EventType.STARTED, "Watcher started"));
        repo.save(new TransferEvent(jobId, "My Job", TransferEvent.EventType.DETECTED, "Detected: a.csv", "a.csv", 0));
        repo.save(new TransferEvent(jobId, "My Job", TransferEvent.EventType.TRANSFERRED, "Transferred: a.csv (1.2 KB)", "a.csv", 1234));
        repo.save(new TransferEvent(jobId, "My Job", TransferEvent.EventType.ERROR, "SFTP push failed for b.csv: timeout"));
        repo.save(new TransferEvent("other-job", "Other Job", TransferEvent.EventType.TRANSFERRED, "Transferred: z.txt", "z.txt", 99));

        List<TransferRepository.LogEntry> byJob = repo.findByJobId(jobId, 100);
        check("findByJobId returns only this job's 4 events", byJob.size() == 4);
        check("findByJobId most-recent-first ordering",
                byJob.get(0).eventType().equals("ERROR")); // last inserted for this job

        List<TransferRepository.LogEntry> errors = repo.findByEventType("ERROR", 100);
        check("findByEventType filters correctly", errors.size() == 1
                && errors.get(0).message().contains("timeout"));

        List<TransferRepository.LogEntry> searchResults = repo.search("a.csv", 100);
        check("search matches filename", searchResults.size() == 2); // DETECTED + TRANSFERRED for a.csv

        List<TransferRepository.LogEntry> searchByJobName = repo.search("Other Job", 100);
        check("search matches job_name", searchByJobName.size() == 1);

        List<TransferRepository.LogEntry> allLogs = repo.findAll(100);
        check("findAll returns all 5 events across jobs", allLogs.size() == 5);

        List<TransferRepository.LogEntry> limited = repo.findAll(2);
        check("limit is respected", limited.size() == 2);
    }

    // ── CredentialRepository ──────────────────────────────────────────────

    private static void testCredentialRepository(DatabaseService db) {
        section("CredentialRepository");
        CredentialRepository repo = new CredentialRepository(db);

        CredentialStore.Credential cred = new CredentialStore.Credential();
        cred.setId("cred-1");
        cred.setHost("10.0.0.5");
        cred.setPort(22);
        cred.setUsername("svc_account");
        cred.setPassword("p@ssw0rd");
        cred.setProtocol("SFTP");

        repo.save(cred);
        check("save() does not throw", true);

        CredentialStore.Credential loaded = repo.findById("cred-1");
        check("findById returns non-null", loaded != null);
        if (loaded == null) return;
        check("host round-trips", "10.0.0.5".equals(loaded.getHost()));
        check("port round-trips", loaded.getPort() == 22);
        check("username round-trips", "svc_account".equals(loaded.getUsername()));
        check("password round-trips", "p@ssw0rd".equals(loaded.getPassword()));
        check("protocol round-trips", "SFTP".equals(loaded.getProtocol()));
        check("usedByJobIds starts empty", loaded.getUsedByJobIds().isEmpty());

        // touchLastUsed should set lastUsed AND add a job ref
        repo.touchLastUsed("cred-1", "job-xyz");
        CredentialStore.Credential afterTouch = repo.findById("cred-1");
        check("touchLastUsed sets lastUsed", afterTouch.getLastUsed() != null);
        check("touchLastUsed adds job ref", afterTouch.getUsedByJobIds().contains("job-xyz"));

        // touch again with a second job — should accumulate, not replace
        repo.touchLastUsed("cred-1", "job-xyz-2");
        CredentialStore.Credential afterSecondTouch = repo.findById("cred-1");
        check("second touchLastUsed accumulates job refs",
                afterSecondTouch.getUsedByJobIds().size() == 2
                        && afterSecondTouch.getUsedByJobIds().contains("job-xyz")
                        && afterSecondTouch.getUsedByJobIds().contains("job-xyz-2"));

        // save() with explicit usedByJobIds should replace the full set (not accumulate)
        cred.setUsedByJobIds(new java.util.ArrayList<>(List.of("job-only-this-one")));
        repo.save(cred);
        CredentialStore.Credential afterReplace = repo.findById("cred-1");
        check("save() replaces job refs wholesale",
                afterReplace.getUsedByJobIds().size() == 1
                        && afterReplace.getUsedByJobIds().contains("job-only-this-one"));

        // removeJobRef should clear it from this credential
        repo.removeJobRef("job-only-this-one");
        CredentialStore.Credential afterRemove = repo.findById("cred-1");
        check("removeJobRef clears the ref", afterRemove.getUsedByJobIds().isEmpty());

        // findAll
        CredentialStore.Credential cred2 = new CredentialStore.Credential();
        cred2.setId("cred-2");
        cred2.setHost("10.0.0.6");
        cred2.setPort(21);
        cred2.setUsername("ftpuser");
        cred2.setPassword("pw2");
        cred2.setProtocol("FTP");
        repo.save(cred2);

        List<CredentialStore.Credential> all = repo.findAll();
        check("findAll returns both credentials", all.size() == 2);

        // delete
        repo.delete("cred-2");
        check("delete removes the row", repo.findById("cred-2") == null);
        check("delete leaves the other credential intact", repo.findAll().size() == 1);

        repo.delete("cred-1");
    }

    // ── SettingsRepository ────────────────────────────────────────────────

    private static void testSettingsRepository(DatabaseService db) {
        section("SettingsRepository");
        SettingsRepository repo = new SettingsRepository(db);

        check("get() returns default when key missing",
                "fallback".equals(repo.get("nonexistent.key", "fallback")));

        repo.set("theme", "dark");
        check("set/get round-trips", "dark".equals(repo.get("theme", null)));

        repo.set("theme", "light"); // upsert
        check("set() overwrites existing key", "light".equals(repo.get("theme", null)));

        repo.delete("theme");
        check("delete() removes the key",
                "gone".equals(repo.get("theme", "gone")));
    }

    // ── CredentialStore integration (the actual class FileWatcherService uses) ──

    private static void testCredentialStoreIntegration(DatabaseService db) {
        section("CredentialStore (integration)");
        CredentialStore store = new CredentialStore(db);

        CredentialStore.Credential cred = new CredentialStore.Credential();
        cred.setHost("integration.example.com");
        cred.setPort(22);
        cred.setUsername("integrationuser");
        cred.setPassword("ipassword");
        cred.setProtocol("SFTP");

        store.save(cred);
        check("save() assigns an id", cred.getId() != null && !cred.getId().isBlank());

        boolean[] listenerFired = {false};
        store.addChangeListener(list -> listenerFired[0] = true);

        store.touchLastUsed(cred.getId(), "job-integration-1");
        check("touchLastUsed updates in-memory cache",
                store.findById(cred.getId()).get().getUsedByJobIds().contains("job-integration-1"));

        // Reload from a fresh CredentialStore instance — proves it actually
        // persisted to SQLite and isn't just living in memory
        CredentialStore reloadedStore = new CredentialStore(db);
        Object reloadedCred = reloadedStore.findById(cred.getId()).orElse(null);
        check("credential survives across CredentialStore instances (real persistence)",
                reloadedCred != null);

        store.delete(cred.getId());
        check("delete reflected immediately in-memory", store.findById(cred.getId()).isEmpty());

        CredentialStore verifyStore = new CredentialStore(db);
        check("delete persisted to SQLite", verifyStore.findById(cred.getId()).isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void section(String name) {
        System.out.println();
        System.out.println("── " + name + " ──");
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + description);
        } else {
            failed++;
            System.out.println("  FAIL  " + description);
        }
    }
}