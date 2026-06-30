package com.filewatchercommon.model;

import com.filewatchercommon.util.OsType;

import java.time.LocalDateTime;
import java.util.UUID;

public class WatchJob {

    public enum Direction { INBOUND, OUTBOUND, LOCAL_TO_LOCAL }
    public enum TransferMode { ENTIRE_FOLDER, LATEST_ONLY, SPECIFIC }
    public enum Status { IDLE, WATCHING, TRANSFERRING, ERROR, PAUSED }
    public enum Protocol { SFTP, FTP, SCP, LOCAL }

    private final String id;
    private String name;
    private Direction direction;
    private TransferMode transferMode;
    private Status status;
    private Protocol protocol;

    // Source
    private String sourceHost;
    private int sourcePort;
    private String sourceUser;
    private String sourcePassword;
    private String sourcePath;

    // Destination
    private String destHost;
    private int destPort;
    private String destUser;
    private String destPassword;
    private String destPath;

    // For SPECIFIC mode
    private String specificPattern;

    // Polling interval in seconds
    private int intervalSeconds;

    // Stats
    private long filesTransferred;
    private long bytesTransferred;
    private LocalDateTime lastTransfer;
    private LocalDateTime createdAt;
    private String lastError;
    private int watchDepth;
    private OsType remoteOs; // set by user: LINUX | MACOS | WINDOWS
    public OsType getRemoteOs() { return remoteOs; }
    public void setRemoteOs(OsType remoteOs) { this.remoteOs = remoteOs; }

    public WatchJob() {
        this.id = UUID.randomUUID().toString();
        this.status = Status.IDLE;
        this.intervalSeconds = 30;
        this.sourcePort = 22;
        this.destPort = 22;
        this.createdAt = LocalDateTime.now();
        this.watchDepth = 1;
        this.protocol = Protocol.SFTP;
    }

    // for Smoke Test
    public WatchJob(String id) {
        this.id = id;
        this.status = Status.IDLE;
        this.intervalSeconds = 30;
        this.sourcePort = 22;
        this.destPort = 22;
        this.createdAt = LocalDateTime.now();
        this.watchDepth = 1;
        this.protocol = Protocol.SFTP;
    }



    // ---- Getters & Setters ----

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }

    public TransferMode getTransferMode() { return transferMode; }
    public void setTransferMode(TransferMode transferMode) { this.transferMode = transferMode; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Protocol getProtocol() { return protocol; }
    public void setProtocol(Protocol protocol) { this.protocol = protocol; }

    public String getSourceHost() { return sourceHost; }
    public void setSourceHost(String sourceHost) { this.sourceHost = sourceHost; }

    public int getSourcePort() { return sourcePort; }
    public void setSourcePort(int sourcePort) { this.sourcePort = sourcePort; }

    public String getSourceUser() { return sourceUser; }
    public void setSourceUser(String sourceUser) { this.sourceUser = sourceUser; }

    public String getSourcePassword() { return sourcePassword; }
    public void setSourcePassword(String sourcePassword) { this.sourcePassword = sourcePassword; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getDestHost() { return destHost; }
    public void setDestHost(String destHost) { this.destHost = destHost; }

    public int getDestPort() { return destPort; }
    public void setDestPort(int destPort) { this.destPort = destPort; }

    public String getDestUser() { return destUser; }
    public void setDestUser(String destUser) { this.destUser = destUser; }

    public String getDestPassword() { return destPassword; }
    public void setDestPassword(String destPassword) { this.destPassword = destPassword; }

    public String getDestPath() { return destPath; }
    public void setDestPath(String destPath) { this.destPath = destPath; }

    public String getSpecificPattern() { return specificPattern; }
    public void setSpecificPattern(String specificPattern) { this.specificPattern = specificPattern; }

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    public long getFilesTransferred() { return filesTransferred; }
    public void setFilesTransferred(long filesTransferred) { this.filesTransferred = filesTransferred; }

    public long getBytesTransferred() { return bytesTransferred; }
    public void setBytesTransferred(long bytesTransferred) { this.bytesTransferred = bytesTransferred; }

    public LocalDateTime getLastTransfer() { return lastTransfer; }
    public void setLastTransfer(LocalDateTime lastTransfer) { this.lastTransfer = lastTransfer; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public int getWatchDepth() { return watchDepth; }
    public void setWatchDepth(int watchDepth) { this.watchDepth = watchDepth; }


    @Override
    public String toString() { return name != null ? name : id; }
}
