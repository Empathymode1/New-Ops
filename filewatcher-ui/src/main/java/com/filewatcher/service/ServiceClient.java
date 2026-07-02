package com.filewatcher.service;

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
}
