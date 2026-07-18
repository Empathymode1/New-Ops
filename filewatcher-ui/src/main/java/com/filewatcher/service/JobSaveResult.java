package com.filewatcher.service;

/**
 * Result of an addJob/updateJob call — mirrors the contract's JOB_SAVED /
 * JOB_SAVE_FAILED reply (§1.3). {@code jobId} is populated on success
 * (useful after an add, when the caller didn't know the id yet); {@code
 * error} is populated on failure with a user-facing message suitable for
 * showing directly in the form (e.g. "Source path is required").
 */
public record JobSaveResult(boolean success, String jobId, String error) {

    public static JobSaveResult ok(String jobId) {
        return new JobSaveResult(true, jobId, null);
    }

    public static JobSaveResult failed(String error) {
        return new JobSaveResult(false, null, error);
    }
}
