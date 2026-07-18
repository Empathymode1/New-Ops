package com.filewatcher.model;

/**
 * Mirrors the backend's OsType exactly (LINUX/MACOS/WINDOWS/UNKNOWN) --
 * only meaningful for INBOUND jobs, where it picks which OS-specific
 * remote-watch mechanism the backend uses (contract §2.3's note on
 * "remoteOs"). null/unset falls back to plain SFTP polling.
 */
public enum RemoteOs {
    LINUX, MACOS, WINDOWS, UNKNOWN
}
