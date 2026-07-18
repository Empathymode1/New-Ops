package com.filewatcher.service;

import com.filewatcher.model.RemoteEntryInfo;

import java.util.List;

/** Result of {@link ServiceClient#browseRemote} — mirrors BROWSE_REMOTE_RESPONSE (contract §1.9). */
public record RemoteBrowseResult(String path, List<RemoteEntryInfo> entries, String error) {

    public boolean success() {
        return error == null;
    }
}
