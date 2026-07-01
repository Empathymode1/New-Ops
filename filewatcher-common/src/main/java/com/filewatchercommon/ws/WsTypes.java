package com.filewatchercommon.ws;

/**
 * WebSocket message type strings pushed from Service → UI.
 */
public final class WsTypes {
    private WsTypes() {}

    public static final String INIT         = "INIT";
    public static final String JOB_STATE    = "JOB_STATE";
    public static final String EVENT        = "EVENT";
    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String CREDENTIALS  = "CREDENTIALS";
    public static final String TEST_RESULT  = "TEST_RESULT";
    public static final String HEALTH       = "HEALTH";

    /** Reply to GET_CONFIGURATION and confirmation push after UPDATE_CONFIGURATION — carries the current AppConfig. */
    public static final String CONFIGURATION = "CONFIGURATION";

    /** Reply to GET_LOGS — carries a list of LogEntryMessage. */
    public static final String LOGS         = "LOGS";
    /** Reply to EXPORT_LOGS — carries a single CSV string field. */
    public static final String LOGS_EXPORT  = "LOGS_EXPORT";
}