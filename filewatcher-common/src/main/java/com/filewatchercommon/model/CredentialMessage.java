package com.filewatchercommon.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight credential model shared between service and UI.
 * Replaces CredentialStore.Credential as the wire type.
 * Passwords are NOT obfuscated here — obfuscation stays in CredentialStore.
 */
public class CredentialMessage {

    private String        id;
    private String        host;
    private int           port;
    private String        username;
    private String        password;
    private String        protocol;
    private LocalDateTime lastUsed;
    private List<String>  usedByJobIds = new ArrayList<>();

    public String        getId()           { return id; }
    public void          setId(String id)  { this.id = id; }

    public String        getHost()         { return host; }
    public void          setHost(String h) { this.host = h; }

    public int           getPort()         { return port; }
    public void          setPort(int p)    { this.port = p; }

    public String        getUsername()              { return username; }
    public void          setUsername(String u)      { this.username = u; }

    public String        getPassword()              { return password; }
    public void          setPassword(String p)      { this.password = p; }

    public String        getProtocol()              { return protocol; }
    public void          setProtocol(String p)      { this.protocol = p; }

    public LocalDateTime getLastUsed()              { return lastUsed; }
    public void          setLastUsed(LocalDateTime t){ this.lastUsed = t; }

    public List<String>  getUsedByJobIds()          { return usedByJobIds; }
    public void          setUsedByJobIds(List<String> ids) { this.usedByJobIds = ids; }

    public String key() { return username + "@" + host; }
}
