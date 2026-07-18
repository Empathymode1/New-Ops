package com.filewatcher.model;

/**
 * The full set of fields needed to create or edit a watch job — mirrors
 * the backend's WatchJob editable fields (and the contract's ADD_JOB /
 * UPDATE_JOB "job" object shape) exactly, field name for field name, so it
 * serializes straight to JSON with no custom mapping.
 *
 * This is a plain mutable DTO, not a JavaFX-bound model like {@link Job} —
 * it only exists transiently while a job is being added or edited (as the
 * working state behind {@code JobFormDialog}, and as the outbound payload
 * sent to the backend). {@link Job} remains the read-only, property-backed
 * table/dashboard row.
 *
 * sourcePassword/destPassword are deliberately never populated from a
 * SNAPSHOT (the backend never sends them back — see contract §1.1) — when
 * used to pre-fill an Edit form they start blank, and per contract §2.4 a
 * blank password on UPDATE_JOB means "leave the stored password alone".
 */
public class WatchJobConfig {
    public String name = "";
    public Protocol protocol = Protocol.SFTP;
    public Direction direction = Direction.INBOUND;
    public TransferMode transferMode = TransferMode.ENTIRE_FOLDER;

    public String sourceHost = "";
    public int sourcePort = 22;
    public String sourceUser = "";
    public String sourcePassword = "";
    public String sourcePath = "";

    public String destHost = "";
    public int destPort = 22;
    public String destUser = "";
    public String destPassword = "";
    public String destPath = "";

    public String specificPattern = "";
    public int intervalSeconds = 30;
    public int watchDepth = 1;

    /** Only meaningful for INBOUND (see contract §2.3's note) -- null/unset means "use SFTP polling instead of a remote-exec watcher". */
    public RemoteOs remoteOs;

    public WatchJobConfig() {
    }

    /** A shallow copy — JobFormDialog mutates a working copy so Cancel leaves the original untouched. */
    public WatchJobConfig copy() {
        WatchJobConfig c = new WatchJobConfig();
        c.name = name;
        c.protocol = protocol;
        c.direction = direction;
        c.transferMode = transferMode;
        c.sourceHost = sourceHost;
        c.sourcePort = sourcePort;
        c.sourceUser = sourceUser;
        c.sourcePassword = sourcePassword;
        c.sourcePath = sourcePath;
        c.destHost = destHost;
        c.destPort = destPort;
        c.destUser = destUser;
        c.destPassword = destPassword;
        c.destPath = destPath;
        c.specificPattern = specificPattern;
        c.intervalSeconds = intervalSeconds;
        c.watchDepth = watchDepth;
        c.remoteOs = remoteOs;
        return c;
    }
}
