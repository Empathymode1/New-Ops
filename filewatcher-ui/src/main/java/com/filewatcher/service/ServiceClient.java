package com.filewatcher.service;

import com.filewatcher.model.CredentialConfig;
import com.filewatcher.model.WatchJobConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Integration seam between the UI and the Monitoring Service backend.
 *
 * This is the ONLY interface the UI code (EventDispatcher, ServicesView, etc.)
 * talks to. Swap {@link MockServiceClient} for a real implementation
 * (e.g. a WebSocketServiceClient backed by java.net.http.WebSocket, or a
 * gRPC/REST+SSE client) without touching any view code — see
 * WebSocketServiceClientSkeleton for a starting point.
 */
public interface ServiceClient {

    /** Opens the connection (WebSocket handshake, auth, etc.). */
    CompletableFuture<Void> connect();

    /** Closes the connection cleanly. */
    void disconnect();

    /** True while a live connection to the backend is established. */
    boolean isConnected();

    /**
     * Registers a listener for inbound events. Implementations must NOT
     * assume this is called on the JavaFX Application Thread — the
     * EventDispatcher is responsible for hopping onto it via Platform.runLater.
     */
    void addListener(Consumer<ServiceEvent> listener);

    void removeListener(Consumer<ServiceEvent> listener);

    /** Sends a control command (Start/Stop/Restart/Delete/Test Connection) for a job. */
    CompletableFuture<Void> sendCommand(String jobId, JobCommand command);

    /** Fetches the initial job list on startup (before live events start flowing). */
    CompletableFuture<Void> requestInitialSnapshot();

    /** Creates a new job (contract §2.3 ADD_JOB). Resolves with a JobSaveResult — never fails the future for validation errors, only for transport failures. */
    CompletableFuture<JobSaveResult> addJob(WatchJobConfig config);

    /** Edits an existing job (contract §2.4 UPDATE_JOB). Same success/failure convention as {@link #addJob}. */
    CompletableFuture<JobSaveResult> updateJob(String jobId, WatchJobConfig config);
    /** Fetches the credential list (contract §2.5 CREDENTIALS_REQUEST). */
    CompletableFuture<Void> requestCredentials();

    /** Creates a new credential (contract §2.6 ADD_CREDENTIAL). */
    CompletableFuture<JobSaveResult> addCredential(CredentialConfig config);

    /** Edits an existing credential (contract §2.7 UPDATE_CREDENTIAL). */
    CompletableFuture<JobSaveResult> updateCredential(String credentialId, CredentialConfig config);

    /** Deletes a credential (contract §2.8 DELETE_CREDENTIAL). Fire-and-forget, like job DELETE — the resulting CREDENTIALS_SNAPSHOT is what actually updates the UI. */
    void deleteCredential(String credentialId);

    /**
     * Queries historical transfer logs (contract §2.9 LOGS_REQUEST /
     * §1.7 LOGS_RESPONSE) — the DB-backed job run history behind the Logs
     * page, distinct from the live in-session EVENT feed. Resolves with
     * an empty list (never fails the future) if the query legitimately
     * had no matches or the request couldn't be sent.
     */
    CompletableFuture<java.util.List<com.filewatcher.model.TransferLogEntry>> requestLogs(LogsQuery query);

    /**
     * Tests raw connection details directly, without an existing job or
     * credential (contract §2.10 TEST_RAW_CONNECTION) — e.g. a "Test
     * Connection" button in the Add/Edit Credential form, tried against
     * whatever's currently typed before saving. {@code detectOs} requests
     * remote OS auto-detection too (only worth setting true for an INBOUND
     * job's Remote OS auto-fill — see ConnectionTestResult).
     */
    CompletableFuture<ConnectionTestResult> testRawConnection(String host, int port, String username, String password, boolean detectOs);

    /**
     * Lists a remote directory (contract §2.11 BROWSE_REMOTE) — lets a
     * client browse a remote path by clicking through folders instead of
     * typing one blind. {@code path} null/blank lists the account's
     * default/home directory.
     */
    CompletableFuture<RemoteBrowseResult> browseRemote(String host, int port, String username, String password, String path);
}
