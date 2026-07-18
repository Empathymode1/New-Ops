package com.filewatcher.model;

/** A single entry in a remote directory listing (contract §1.9 BROWSE_REMOTE_RESPONSE). */
public record RemoteEntryInfo(String name, boolean directory) {
}
