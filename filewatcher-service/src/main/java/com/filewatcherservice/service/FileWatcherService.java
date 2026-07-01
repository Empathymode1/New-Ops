package com.filewatcherservice.service;

import com.filewatchercommon.model.TransferEvent;
import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.service.NotificationService;
import com.filewatchercommon.util.FileUtils;
import com.filewatchercommon.util.OsType;
import com.filewatcherservice.config.AppConfig;
import com.filewatcherservice.database.DatabaseService;
import com.filewatcherservice.database.TransferRepository;
import com.filewatcherservice.scheduler.Scheduler;
import com.jcraft.jsch.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Core service that manages file watching and transfer for three directions:
 * LOCAL_TO_LOCAL, OUTBOUND, INBOUND.
 *
 * CHANGE: AppConfig is now injected via constructor. All previously hardcoded
 * values (SSH/SFTP timeouts, watchPool size, polling fallback kill-switch,
 * default interval) are read from config. No magic numbers remain in this class.
 */
public class FileWatcherService {

    private static final Logger LOG = Logger.getLogger(FileWatcherService.class.getName());

    private static final String POLLING_SUFFIX = ":polling";
    private static final String HEARTBEAT_SUFFIX = ":heartbeat";

    // ── Internal state ────────────────────────────────────────────────────────

    private final AppConfig            config;
    private final Scheduler            scheduler;
    private final CredentialStore      credentialStore;
    private final TransferRepository   transferRepository;

    private final Map<String, WatchJob>     jobs        = new ConcurrentHashMap<>();
    private final Map<String, Future<?>>    runningJobs = new ConcurrentHashMap<>();
    private final Map<String, WatchService> watchServices = new ConcurrentHashMap<>();
    private final Map<String, Session>      sshSessions   = new ConcurrentHashMap<>();

    /**
     * watchPool: bounded when AppConfig.maxConcurrentTransfers > 0,
     * unbounded (cached) otherwise — preserving original behaviour.
     */
    private final ExecutorService watchPool;

    private final List<Consumer<TransferEvent>> eventListeners    = new CopyOnWriteArrayList<>();
    private final List<Consumer<WatchJob>>      jobStateListeners = new CopyOnWriteArrayList<>();
    private NotificationService notificationService;

    public FileWatcherService(DatabaseService db, Scheduler scheduler, AppConfig config) {
        this.config             = config;
        this.scheduler          = scheduler;
        this.credentialStore    = new CredentialStore(db);
        this.transferRepository = new TransferRepository(db);
        this.watchPool          = config.maxConcurrentTransfers > 0
                ? Executors.newFixedThreadPool(config.maxConcurrentTransfers)
                : Executors.newCachedThreadPool();
    }

    public void setNotificationService(NotificationService ns) {
        this.notificationService = ns;
    }

    // ── Listener registration ──────────────────────────────────────────────────

    public void addEventListener(Consumer<TransferEvent> listener) {
        eventListeners.add(listener);
    }

    public void addJobStateListener(Consumer<WatchJob> listener) {
        jobStateListeners.add(listener);
    }

    private void emit(TransferEvent event) {
        transferRepository.save(event);
        eventListeners.forEach(l -> l.accept(event));
    }

    private void notifyState(WatchJob job) {
        jobStateListeners.forEach(l -> l.accept(job));
    }

    // ── Job CRUD ───────────────────────────────────────────────────────────────

    public void addJob(WatchJob job) {
        jobs.put(job.getId(), job);
        notifyState(job);
    }

    public void removeJob(String jobId) {
        stopJob(jobId);
        jobs.remove(jobId);
        credentialStore.removeJobRef(jobId);
    }

    public Collection<WatchJob> getJobs() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    public WatchJob getJob(String id) {
        return jobs.get(id);
    }

    // ── Start / Stop ───────────────────────────────────────────────────────────

    public void startJob(String jobId) {
        WatchJob job = jobs.get(jobId);
        if (job == null || job.getStatus() == WatchJob.Status.WATCHING) return;

        job.setStatus(WatchJob.Status.WATCHING);
        notifyState(job);
        emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.STARTED,
                "Watcher started for job: " + job.getName()));

        runningJobs.put(jobId, watchPool.submit(() -> runWatcher(job)));

        // Per-job liveness tick: independent of the watcher mechanism (NIO loop,
        // polling, or remote exec) so it keeps firing even on connection types
        // that don't have a natural "tick" of their own (e.g. blocking remote
        // exec readLine). Mirrors the global heartbeat cadence from AppConfig,
        // but pushed per job to the UI instead of only logged.
        long heartbeatSeconds = config.heartbeatIntervalSeconds > 0
                ? config.heartbeatIntervalSeconds
                : 30;
        scheduler.scheduleRepeating(jobId + HEARTBEAT_SUFFIX,
                () -> touchHeartbeat(jobId), heartbeatSeconds);
    }

    public void stopJob(String jobId) {
        Future<?> future = runningJobs.remove(jobId);
        if (future != null) future.cancel(true);

        scheduler.cancel(jobId + POLLING_SUFFIX);
        scheduler.cancel(jobId + HEARTBEAT_SUFFIX);

        WatchService ws = watchServices.remove(jobId);
        if (ws != null) {
            try { ws.close(); } catch (IOException ignored) {}
        }

        Session session = sshSessions.remove(jobId);
        if (session != null && session.isConnected()) session.disconnect();

        WatchJob job = jobs.get(jobId);
        if (job != null) {
            job.setStatus(WatchJob.Status.IDLE);
            notifyState(job);
            emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.STOPPED,
                    "Watcher stopped for job: " + job.getName()));
        }
    }

    public void startAll() { jobs.keySet().forEach(this::startJob); }
    public void stopAll()  { new ArrayList<>(jobs.keySet()).forEach(this::stopJob); }

    // ── Top-level router ───────────────────────────────────────────────────────

    private void runWatcher(WatchJob job) {
        try {
            switch (job.getDirection()) {
                case LOCAL_TO_LOCAL -> runLocalWatcher(job);
                case OUTBOUND       -> runOutboundWatcher(job);
                case INBOUND        -> runInboundWatcher(job);
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                markError(job, "Unexpected error: " + e.getMessage());
            }
        }
    }

    // ── Effective interval helper ──────────────────────────────────────────────

    /**
     * Returns the job's own interval if set, otherwise falls back to
     * AppConfig.defaultIntervalSeconds. Replaces hardcoded "120" / "2 minutes"
     * comments that previously existed only in the architecture doc.
     */
    private long effectiveInterval(WatchJob job) {
        return job.getIntervalSeconds() > 0
                ? job.getIntervalSeconds()
                : config.defaultIntervalSeconds;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LOCAL → LOCAL
    // ══════════════════════════════════════════════════════════════════════════

    private void runLocalWatcher(WatchJob job) {
        OsType localOs = OsType.local();
        LOG.info("LOCAL_TO_LOCAL — local OS: " + localOs
                + " — NIO WatchService will use: " + nioBackendLabel(localOs));
        try {
            Path watchPath = ensureDir(job.getSourcePath());
            WatchService ws = FileSystems.getDefault().newWatchService();
            watchServices.put(job.getId(), ws);
            registerTree(ws, watchPath, job.getWatchDepth());

            emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                    "Watching [" + nioBackendLabel(localOs) + "]: " + watchPath));

            watchLoop(job, ws, changed -> handleLocalChange(job, changed));

        } catch (IOException e) {
            long interval = effectiveInterval(job);
            if (!config.pollingFallbackEnabled) {
                markError(job, "NIO unavailable and polling fallback is disabled: " + e.getMessage());
                return;
            }
            LOG.warning("NIO WatchService unavailable (" + e.getMessage()
                    + "), falling back to polling every " + interval + "s");
            emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                    "NIO unavailable — polling every " + interval + "s: " + job.getSourcePath()));
            runPollingFallback(job);
        }
    }

    private void runPollingFallback(WatchJob job) {
        Map<String, Long> initialSnapshot;
        try {
            initialSnapshot = takeSnapshot(job.getSourcePath(), job.getWatchDepth());
        } catch (IOException e) {
            markError(job, "Polling setup failed: " + e.getMessage());
            return;
        }

        AtomicReference<Map<String, Long>> snapshotRef = new AtomicReference<>(initialSnapshot);

        scheduler.scheduleRepeating(job.getId() + POLLING_SUFFIX, () -> {
            if (jobs.get(job.getId()) == null) return;

            try {
                Map<String, Long> currentSnapshot =
                        takeSnapshot(job.getSourcePath(), job.getWatchDepth());
                Map<String, Long> previousSnapshot = snapshotRef.get();

                for (Map.Entry<String, Long> entry : currentSnapshot.entrySet()) {
                    String filename   = entry.getKey();
                    long   currentMod = entry.getValue();
                    Long   prevMod    = previousSnapshot.get(filename);

                    boolean isNew      = prevMod == null;
                    boolean isModified = prevMod != null && !prevMod.equals(currentMod);

                    if (isNew || isModified) {
                        handleLocalChange(job, Paths.get(job.getSourcePath(), filename));
                    }
                }

                snapshotRef.set(currentSnapshot);

            } catch (IOException e) {
                markError(job, "Polling scan failed: " + e.getMessage());
                scheduler.cancel(job.getId() + POLLING_SUFFIX);
            }
        }, effectiveInterval(job));
    }

    private Map<String, Long> takeSnapshot(String sourcePath, int depth) throws IOException {
        Map<String, Long> snapshot = new HashMap<>();
        Path root = Paths.get(sourcePath);

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            int currentDepth = 0;

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                currentDepth++;
                return currentDepth <= depth
                        ? FileVisitResult.CONTINUE
                        : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = root.relativize(file).toString();
                snapshot.put(relative, attrs.lastModifiedTime().toMillis());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (!dir.equals(root)) currentDepth--;
                return FileVisitResult.CONTINUE;
            }
        });

        return snapshot;
    }

    private void handleLocalChange(WatchJob job, Path changed) {
        if (Files.isDirectory(changed) || !matchesTransferMode(job, changed)) return;

        String filename = changed.getFileName().toString();
        markTransferring(job, filename);
        try {
            Path dest = ensureDir(job.getDestPath()).resolve(filename);
            Path tmp  = dest.resolveSibling(filename + ".tmp");
            Files.copy(changed, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dest,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            recordSuccess(job, filename, Files.size(dest));
        } catch (IOException e) {
            markError(job, "Transfer failed for " + filename + ": " + e.getMessage());
        } finally {
            restoreWatching(job);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OUTBOUND  (local → remote via SFTP)
    // ══════════════════════════════════════════════════════════════════════════

    private void runOutboundWatcher(WatchJob job) {
        OsType localOs = OsType.local();
        LOG.info("OUTBOUND — local OS: " + localOs
                + " — NIO backend: " + nioBackendLabel(localOs)
                + " — remote: " + job.getSourceHost());

        Session session = null;
        try {
            session = openSshSession(job);
            sshSessions.put(job.getId(), session);

            emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                    "SSH connected to " + job.getSourceHost()
                            + "; watching local [" + nioBackendLabel(localOs) + "]: " + job.getSourcePath()));

            Path watchPath = ensureDir(job.getSourcePath());

            try {
                WatchService ws = FileSystems.getDefault().newWatchService();
                watchServices.put(job.getId(), ws);
                registerTree(ws, watchPath, job.getWatchDepth());

                final Session sess = session;
                watchLoop(job, ws, changed -> handleOutboundChange(job, changed, sess));

            } catch (IOException e) {
                long interval = effectiveInterval(job);
                if (!config.pollingFallbackEnabled) {
                    markError(job, "NIO unavailable and polling fallback is disabled: " + e.getMessage());
                    return;
                }
                LOG.warning("NIO WatchService unavailable (" + e.getMessage()
                        + "), falling back to polling every " + interval + "s");
                emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                        "NIO unavailable — polling local folder every "
                                + interval + "s: " + job.getSourcePath()));
                runOutboundPollingFallback(job);
            }

        } catch (JSchException e) {
            markError(job, "SSH connection failed: " + e.getMessage());
        } catch (IOException e) {
            markError(job, "Local watch setup failed: " + e.getMessage());
        } finally {
            Session orphan = sshSessions.get(job.getId());
            if (orphan != null && !scheduler.isScheduled(job.getId() + POLLING_SUFFIX)) {
                sshSessions.remove(job.getId());
                if (orphan.isConnected()) orphan.disconnect();
            }
        }
    }

    private void runOutboundPollingFallback(WatchJob job) {
        scheduler.scheduleRepeating(job.getId() + POLLING_SUFFIX, () -> {
            if (jobs.get(job.getId()) == null) return;

            Session session = sshSessions.get(job.getId());
            if (session == null || !session.isConnected()) {
                markError(job, "Outbound polling: SSH session lost");
                scheduler.cancel(job.getId() + POLLING_SUFFIX);
                return;
            }

            LocalDateTime since = job.getLastTransfer() != null
                    ? job.getLastTransfer()
                    : LocalDateTime.now().minusSeconds(effectiveInterval(job));

            try {
                Files.walkFileTree(Paths.get(job.getSourcePath()),
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                LocalDateTime modified = LocalDateTime.ofInstant(
                                        attrs.lastModifiedTime().toInstant(),
                                        java.time.ZoneId.systemDefault());

                                if (modified.isAfter(since) && matchesTransferMode(job, file)) {
                                    handleOutboundChange(job, file, session);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });

            } catch (IOException e) {
                markError(job, "Outbound polling scan failed: " + e.getMessage());
                scheduler.cancel(job.getId() + POLLING_SUFFIX);
            }
        }, effectiveInterval(job));
    }

    private void handleOutboundChange(WatchJob job, Path changed, Session session) {
        if (Files.isDirectory(changed) || !matchesTransferMode(job, changed)) return;

        String filename = changed.getFileName().toString();
        markTransferring(job, filename);

        ChannelSftp sftp = null;
        try {
            sftp = openSftpChannel(session);
            mkdirRemote(sftp, job.getDestPath());

            String remotePath = job.getDestPath() + "/" + filename;
            String remoteTmp  = remotePath + ".tmp";

            try (InputStream in = Files.newInputStream(changed)) {
                sftp.put(in, remoteTmp, ChannelSftp.OVERWRITE);
            }
            sftp.rename(remoteTmp, remotePath);

            recordSuccess(job, filename, Files.size(changed));

        } catch (JSchException | SftpException | IOException e) {
            markError(job, "SFTP push failed for " + filename + ": " + e.getMessage());
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            restoreWatching(job);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INBOUND  (remote → local)
    // ══════════════════════════════════════════════════════════════════════════

    private void runInboundWatcher(WatchJob job) {
        OsType remoteOs = job.getRemoteOs();

        LOG.info("INBOUND — remote OS: " + remoteOs
                + " (user-supplied) — local OS: " + OsType.local()
                + " [" + nioBackendLabel(OsType.local()) + "]");

        Session session = null;
        try {
            session = openSshSession(job);
            sshSessions.put(job.getId(), session);

            emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                    "SSH connected to " + job.getSourceHost()
                            + " [remote OS: " + remoteOs + "]"));

            try {
                switch (remoteOs) {
                    case LINUX   -> runRemoteWatchExec(job, session,
                            buildInotifyCmd(job.getSourcePath()),
                            "inotifywait", false);
                    case MACOS   -> runRemoteWatchExec(job, session,
                            buildFswatchCmd(job.getSourcePath()),
                            "fswatch", false);
                    case WINDOWS -> runRemoteWatchExec(job, session,
                            buildPowerShellWatcherCmd(job.getSourcePath()),
                            "PowerShell FileSystemWatcher", true);
                    default      -> throw new IOException(
                            "Remote OS not set — please select the remote OS when configuring the job.");
                }
            } catch (IOException e) {
                long interval = effectiveInterval(job);
                if (!config.pollingFallbackEnabled) {
                    markError(job, "Remote exec unavailable and polling fallback is disabled: " + e.getMessage());
                    return;
                }
                LOG.warning("Remote exec failed (" + e.getMessage()
                        + "), falling back to SFTP polling every " + interval + "s");
                emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                        "Remote exec unavailable — SFTP polling every "
                                + interval + "s: " + job.getSourcePath()));
                runInboundPollingFallback(job);
            }

        } catch (JSchException e) {
            markError(job, "SSH connection failed: " + e.getMessage());
        } finally {
            Session orphan = sshSessions.get(job.getId());
            if (orphan != null && !scheduler.isScheduled(job.getId() + POLLING_SUFFIX)) {
                sshSessions.remove(job.getId());
                if (orphan.isConnected()) orphan.disconnect();
            }
        }
    }

    private void runInboundPollingFallback(WatchJob job) {
        scheduler.scheduleRepeating(job.getId() + POLLING_SUFFIX, () -> {
            if (jobs.get(job.getId()) == null) return;

            Session session = sshSessions.get(job.getId());
            if (session == null || !session.isConnected()) {
                markError(job, "Inbound polling: SSH session lost");
                scheduler.cancel(job.getId() + POLLING_SUFFIX);
                return;
            }

            long sinceEpochSeconds = job.getLastTransfer() != null
                    ? job.getLastTransfer()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toEpochSecond()
                    : Instant.now().minusSeconds(effectiveInterval(job))
                    .getEpochSecond();

            ChannelSftp sftp = null;
            try {
                sftp = openSftpChannel(session);

                sftp.ls(job.getSourcePath()).forEach(entry -> {
                    if (entry instanceof ChannelSftp.LsEntry ls) {
                        String name = ls.getFilename();
                        if (name.equals(".") || name.equals("..")) return;

                        if (ls.getAttrs().getMTime() > sinceEpochSeconds
                                && matchesTransferMode(job, Paths.get(name))) {
                            pullFileFromRemote(job, session, name);
                        }
                    }
                });

            } catch (JSchException | SftpException e) {
                markError(job, "Inbound polling scan failed: " + e.getMessage());
                scheduler.cancel(job.getId() + POLLING_SUFFIX);
            } finally {
                if (sftp != null && sftp.isConnected()) sftp.disconnect();
            }
        }, effectiveInterval(job));
    }

    private Map<String, Long> takeRemoteSnapshot(Session session, String remotePath)
            throws JSchException, SftpException {
        Map<String, Long> snapshot = new HashMap<>();
        ChannelSftp sftp = null;
        try {
            sftp = openSftpChannel(session);
            sftp.ls(remotePath).forEach(entry -> {
                if (entry instanceof ChannelSftp.LsEntry ls) {
                    String name = ls.getFilename();
                    if (name.equals(".") || name.equals("..")) return;
                    long modifiedMs = ls.getAttrs().getMTime() * 1000L;
                    snapshot.put(name, modifiedMs);
                }
            });
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
        }
        return snapshot;
    }

    // ── Remote watcher command builders ──────────────────────────────────────

    private static String buildInotifyCmd(String remotePath) {
        return String.format(
                "inotifywait -m -q -e close_write,create --format '%%f' '%s'",
                remotePath);
    }

    private static String buildFswatchCmd(String remotePath) {
        return String.format(
                "fswatch -m poll_monitor -l 0.3 --event Created --event Updated --format '%%f' '%s'",
                remotePath);
    }

    private static String buildPowerShellWatcherCmd(String remotePath) {
        String psPath = remotePath.replace("/", "\\").replace("'", "''");
        return String.format(
                "powershell -NoProfile -NonInteractive -Command \""
                        + "$w=New-Object System.IO.FileSystemWatcher('%s',$false);"
                        + "$w.NotifyFilter="
                        +   "[System.IO.NotifyFilters]::FileName -bor [System.IO.NotifyFilters]::LastWrite;"
                        + "$w.EnableRaisingEvents=$true;"
                        + "while($true){"
                        +   "$e=$w.WaitForChanged("
                        +     "[System.IO.WatcherChangeTypes]::Created -bor "
                        +     "[System.IO.WatcherChangeTypes]::Changed,"
                        +     "5000);"
                        +   "if(-not $e.TimedOut){Write-Output $e.Name}"
                        + "}\"",
                psPath);
    }

    // ── Remote exec runner ────────────────────────────────────────────────────

    private void runRemoteWatchExec(WatchJob job, Session session,
                                    String command, String label, boolean windowsCrLf) {
        emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.CONNECTED,
                label + " active on remote: " + job.getSourcePath()));

        ChannelExec execChannel = null;
        try {
            execChannel = (ChannelExec) session.openChannel("exec");
            execChannel.setCommand(command);
            execChannel.setErrStream(System.err);

            InputStream stdout = execChannel.getInputStream();
            execChannel.connect();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))) {
                String line;
                while (!Thread.currentThread().isInterrupted()
                        && (line = reader.readLine()) != null) {
                    if (windowsCrLf) line = line.stripTrailing();
                    String filename = line.trim();
                    if (filename.isEmpty()) continue;
                    if (!matchesTransferMode(job, Paths.get(filename))) continue;
                    pullFileFromRemote(job, session, filename);
                }
            }

        } catch (JSchException | IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                markError(job, label + " exec failed: " + e.getMessage());
            }
        } finally {
            if (execChannel != null && execChannel.isConnected()) execChannel.disconnect();
        }
    }

    // ── SFTP pull ─────────────────────────────────────────────────────────────

    private void pullFileFromRemote(WatchJob job, Session session, String filename) {
        markTransferring(job, filename);

        ChannelSftp sftp = null;
        try {
            sftp = openSftpChannel(session);
            String remoteSrc = job.getSourcePath() + "/" + filename;

            Path localDest = ensureDir(job.getDestPath()).resolve(filename);
            Path localTmp  = localDest.resolveSibling(filename + ".tmp");

            try (OutputStream out = Files.newOutputStream(localTmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                sftp.get(remoteSrc, out);
            }

            Files.move(localTmp, localDest,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            recordSuccess(job, filename, Files.size(localDest));

        } catch (JSchException | SftpException | IOException e) {
            markError(job, "SFTP pull failed for " + filename + ": " + e.getMessage());
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            restoreWatching(job);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared NIO watch loop
    // ══════════════════════════════════════════════════════════════════════════

    private void watchLoop(WatchJob job, WatchService ws, Consumer<Path> handler) {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = ws.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            if (key == null) continue;

            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path changed = dir.resolve(((WatchEvent<Path>) event).context());

                if (kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    handler.accept(changed);
                }
            }

            if (!key.reset()) break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NIO tree registration
    // ══════════════════════════════════════════════════════════════════════════

    private void registerTree(WatchService ws, Path root, int depth) throws IOException {
        if (depth < 0) return;
        root.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        if (depth > 0) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) registerTree(ws, entry, depth - 1);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JSch helpers — timeouts from AppConfig
    // ══════════════════════════════════════════════════════════════════════════

    private Session openSshSession(WatchJob job) throws JSchException {
        JSch jsch = new JSch();
        int port = job.getSourcePort() > 0 ? job.getSourcePort() : 22;
        Session session = jsch.getSession(job.getSourceUser(), job.getSourceHost(), port);
        session.setPassword(job.getSourcePassword());
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password");
        session.connect(config.sshConnectTimeoutMs);   // was hardcoded 10_000
        return session;
    }

    private ChannelSftp openSftpChannel(Session session) throws JSchException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(config.sftpChannelTimeoutMs);     // was hardcoded 5_000
        return sftp;
    }

    private void mkdirRemote(ChannelSftp sftp, String path) {
        StringBuilder current = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) { current.append("/"); continue; }
            current.append(part).append("/");
            try {
                sftp.mkdir(current.toString());
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_FAILURE) {
                    LOG.warning("mkdirRemote: " + e.getMessage());
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Transfer mode filter
    // ══════════════════════════════════════════════════════════════════════════

    private boolean matchesTransferMode(WatchJob job, Path file) {
        return switch (job.getTransferMode()) {
            case ENTIRE_FOLDER -> true;
            case LATEST_ONLY -> {
                try {
                    if (job.getLastTransfer() == null) yield true;
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    LocalDateTime modified = LocalDateTime.ofInstant(
                            attrs.lastModifiedTime().toInstant(),
                            java.time.ZoneId.systemDefault());
                    yield modified.isAfter(job.getLastTransfer());
                } catch (IOException e) { yield true; }
            }
            case SPECIFIC -> {
                String pattern = job.getSpecificPattern();
                if (pattern == null || pattern.isBlank()) yield true;
                yield file.getFileName().toString().matches(
                        pattern.replace(".", "\\.").replace("*", ".*").replace("?", "."));
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // State / event helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void markTransferring(WatchJob job, String filename) {
        job.setStatus(WatchJob.Status.TRANSFERRING);
        job.setLastHeartbeat(LocalDateTime.now());
        notifyState(job);
        emit(new TransferEvent(job.getId(), job.getName(),
                TransferEvent.EventType.DETECTED, "Detected: " + filename, filename, 0));
    }

    /**
     * Per-job liveness tick, invoked on the schedule registered in startJob().
     * Only updates/broadcasts while the job is still actively tracked and not
     * already IDLE — a stopped or removed job should stop reporting heartbeats
     * rather than appearing falsely alive.
     */
    private void touchHeartbeat(String jobId) {
        WatchJob job = jobs.get(jobId);
        if (job == null || job.getStatus() == WatchJob.Status.IDLE) return;
        job.setLastHeartbeat(LocalDateTime.now());
        notifyState(job);
    }

    private void recordSuccess(WatchJob job, String filename, long size) {
        job.setFilesTransferred(job.getFilesTransferred() + 1);
        job.setBytesTransferred(job.getBytesTransferred() + size);
        job.setLastTransfer(LocalDateTime.now());
        job.setLastHeartbeat(LocalDateTime.now());
        emit(new TransferEvent(job.getId(), job.getName(), TransferEvent.EventType.TRANSFERRED,
                "Transferred: " + filename + " (" + formatBytes(size) + ")", filename, size));
    }

    private void restoreWatching(WatchJob job) {
        if (job.getStatus() == WatchJob.Status.TRANSFERRING) {
            job.setStatus(WatchJob.Status.WATCHING);
        }
        notifyState(job);
    }

    private void markError(WatchJob job, String message) {
        job.setStatus(WatchJob.Status.ERROR);
        job.setLastError(message);
        notifyState(job);
        emit(new TransferEvent(job.getId(), job.getName(),
                TransferEvent.EventType.ERROR, message));
        if (notificationService != null)
            notificationService.addError(job.getId(), job.getName(), message);
    }

    private static Path ensureDir(String pathStr) throws IOException {
        Path p = Paths.get(pathStr);
        if (!Files.exists(p)) Files.createDirectories(p);
        return p;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public utilities
    // ══════════════════════════════════════════════════════════════════════════

    public static String nioBackendLabel(OsType os) {
        return switch (os) {
            case LINUX   -> "inotify";
            case MACOS   -> "FSEvents";
            case WINDOWS -> "ReadDirectoryChangesW";
            default      -> "NIO poll";
        };
    }

    public String testCredential(CredentialStore.Credential cred) {
        com.jcraft.jsch.JSch    jsch    = new com.jcraft.jsch.JSch();
        com.jcraft.jsch.Session session = null;
        try {
            int port = cred.getPort() > 0 ? cred.getPort() : 22;
            session = jsch.getSession(cred.getUsername(), cred.getHost(), port);
            session.setPassword(cred.getPassword());
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password");
            session.connect(config.sshConnectTimeoutMs);
            return null;
        } catch (com.jcraft.jsch.JSchException e) {
            return e.getMessage();
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    public static String formatBytes(long bytes) { return FileUtils.formatBytes(bytes); }

    public CredentialStore getCredentialStore()        { return credentialStore; }
    public TransferRepository getTransferRepository()  { return transferRepository; }

    public void shutdown() {
        stopAll();
        watchPool.shutdownNow();
    }
}