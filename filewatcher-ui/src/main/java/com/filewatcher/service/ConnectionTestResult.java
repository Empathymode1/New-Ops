package com.filewatcher.service;

/**
 * Result of {@link ServiceClient#testRawConnection} — mirrors
 * TEST_RAW_CONNECTION_RESULT (contract §1.8). {@code detectedOs} is only
 * populated when the request asked for it (detectOs=true) and the test
 * succeeded — null otherwise, same "didn't ask" vs "asked, nothing came
 * back" ambiguity the contract doc calls out; callers that only care about
 * success/failure can ignore it entirely.
 */
public record ConnectionTestResult(boolean success, String error, String detectedOs) {

    public static ConnectionTestResult ok(String detectedOs) {
        return new ConnectionTestResult(true, null, detectedOs);
    }

    public static ConnectionTestResult failed(String error) {
        return new ConnectionTestResult(false, error, null);
    }
}
