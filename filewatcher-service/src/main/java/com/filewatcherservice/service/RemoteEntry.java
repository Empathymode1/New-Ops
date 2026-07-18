package com.filewatcherservice.service;

/** A single entry in a {@link RemoteListing} — a file or directory name. */
public record RemoteEntry(String name, boolean directory) {
}
