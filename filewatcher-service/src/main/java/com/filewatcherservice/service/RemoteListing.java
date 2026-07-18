package com.filewatcherservice.service;

import java.util.List;

/**
 * Result of {@link FileWatcherService#listRemoteDirectory}, backing the
 * contract's BROWSE_REMOTE / BROWSE_REMOTE_RESPONSE (§2.11/§1.9) — lets a
 * client browse a remote SFTP path (e.g. to pick a job's source/dest path)
 * instead of typing it blind.
 */
public record RemoteListing(String path, List<RemoteEntry> entries, String error) {

    public static RemoteListing failed(String path, String error) {
        return new RemoteListing(path, List.of(), error);
    }
}
