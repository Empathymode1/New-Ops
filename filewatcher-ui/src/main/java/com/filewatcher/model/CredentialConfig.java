package com.filewatcher.model;

/**
 * Editable credential fields — mirrors the contract's ADD_CREDENTIAL /
 * UPDATE_CREDENTIAL "credential" object shape exactly (§2.6/§2.7), the same
 * pattern as {@link WatchJobConfig} for jobs. Plain mutable DTO, not
 * JavaFX-bound — only exists transiently as CredentialFormDialog's working
 * state and the outbound payload.
 *
 * password is deliberately never pre-filled from CREDENTIALS_SNAPSHOT (the
 * backend never sends one back — contract §1.5) — starts blank, and a blank
 * value on an update means "leave the stored password alone" (§2.7).
 */
public class CredentialConfig {
    public String host = "";
    public int port = 22;
    public String username = "";
    public String password = "";
    public Protocol protocol = Protocol.SFTP;

    public CredentialConfig() {
    }

    public CredentialConfig copy() {
        CredentialConfig c = new CredentialConfig();
        c.host = host;
        c.port = port;
        c.username = username;
        c.password = password;
        c.protocol = protocol;
        return c;
    }
}
