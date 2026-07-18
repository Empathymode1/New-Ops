package com.filewatcher.model;

import javafx.beans.property.*;

import java.util.List;

/**
 * Read-only row model for the Credentials table — mirrors CREDENTIALS_SNAPSHOT's
 * per-credential shape (contract §1.5) exactly, the same way {@link Job} mirrors
 * SNAPSHOT's per-job shape.
 *
 * Carries the actual password (contract §1.5 sends it in plain text, on
 * explicit request — see that section's note on the security tradeoff).
 * Treat this field carefully in UI code: don't log it, don't show it in
 * the clear by default (mask-with-reveal-toggle instead), don't put it
 * somewhere it'd end up in a screenshot or window title.
 */
public class CredentialInfo {
    private final StringProperty id = new SimpleStringProperty(this, "id");
    private final StringProperty host = new SimpleStringProperty(this, "host");
    private final IntegerProperty port = new SimpleIntegerProperty(this, "port");
    private final StringProperty username = new SimpleStringProperty(this, "username");
    private final StringProperty password = new SimpleStringProperty(this, "password", "");
    private final StringProperty protocol = new SimpleStringProperty(this, "protocol");
    private final StringProperty lastUsed = new SimpleStringProperty(this, "lastUsed", "—");
    private final IntegerProperty usedByCount = new SimpleIntegerProperty(this, "usedByCount", 0);

    private List<String> usedByJobIds = List.of();

    public CredentialInfo(String id, String host, int port, String username, String password, String protocol,
                           String lastUsed, List<String> usedByJobIds) {
        this.id.set(id);
        this.host.set(host);
        this.port.set(port);
        this.username.set(username);
        this.password.set(password == null ? "" : password);
        this.protocol.set(protocol);
        this.lastUsed.set(lastUsed == null ? "—" : lastUsed);
        this.usedByJobIds = usedByJobIds == null ? List.of() : usedByJobIds;
        this.usedByCount.set(this.usedByJobIds.size());
    }

    public StringProperty idProperty() { return id; }
    public StringProperty hostProperty() { return host; }
    public IntegerProperty portProperty() { return port; }
    public StringProperty usernameProperty() { return username; }
    public StringProperty protocolProperty() { return protocol; }
    public StringProperty lastUsedProperty() { return lastUsed; }
    public IntegerProperty usedByCountProperty() { return usedByCount; }

    public String getId() { return id.get(); }
    public String getHost() { return host.get(); }
    public int getPort() { return port.get(); }
    public String getUsername() { return username.get(); }
    public String getPassword() { return password.get(); }
    public String getProtocol() { return protocol.get(); }
    public List<String> getUsedByJobIds() { return usedByJobIds; }

    public void setHost(String h) { host.set(h); }
    public void setPort(int p) { port.set(p); }
    public void setUsername(String u) { username.set(u); }
    public void setPassword(String p) { password.set(p == null ? "" : p); }
    public void setProtocol(String p) { protocol.set(p); }
    public void setLastUsed(String s) { lastUsed.set(s == null ? "—" : s); }

    public void setUsedByJobIds(List<String> ids) {
        this.usedByJobIds = ids == null ? List.of() : ids;
        this.usedByCount.set(this.usedByJobIds.size());
    }
}
