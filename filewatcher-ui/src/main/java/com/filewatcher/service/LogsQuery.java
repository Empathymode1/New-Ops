package com.filewatcher.service;

/**
 * Filter parameters for a LOGS_REQUEST (contract §2.9). All fields
 * optional/nullable — null means "don't filter on this dimension".
 */
public record LogsQuery(String jobId, String eventType, String search, Long sinceEpochSeconds, int limit) {

    public static LogsQuery all(int limit) {
        return new LogsQuery(null, null, null, null, limit);
    }
}
