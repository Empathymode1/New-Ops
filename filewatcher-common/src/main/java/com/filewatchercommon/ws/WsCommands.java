package com.filewatchercommon.ws;

/**
 * WebSocket command strings sent from UI → Service.
 * Centralised here so both JARs use the same constants
 * and a typo in one place doesn't silently break the protocol.
 */
public final class WsCommands {
    private WsCommands() {}

    public static final String GET_JOBS           = "GET_JOBS";
    public static final String START_JOB          = "START_JOB";
    public static final String STOP_JOB           = "STOP_JOB";
    public static final String START_ALL          = "START_ALL";
    public static final String STOP_ALL           = "STOP_ALL";
    public static final String ADD_JOB            = "ADD_JOB";
    public static final String UPDATE_JOB         = "UPDATE_JOB";
    public static final String DELETE_JOB         = "DELETE_JOB";
    public static final String SAVE_CREDENTIAL    = "SAVE_CREDENTIAL";
    public static final String DELETE_CREDENTIAL  = "DELETE_CREDENTIAL";
    public static final String GET_CREDENTIALS    = "GET_CREDENTIALS";
    public static final String TEST_CREDENTIAL    = "TEST_CREDENTIAL";
    public static final String HEALTH             = "HEALTH";

    /** Fetches transfer_logs rows, filtered by optional jobId/eventType/searchText. */
    public static final String GET_LOGS           = "GET_LOGS";
    /** Same filters as GET_LOGS, but returns the result as a single CSV string for client-side save. */
    public static final String EXPORT_LOGS        = "EXPORT_LOGS";
}