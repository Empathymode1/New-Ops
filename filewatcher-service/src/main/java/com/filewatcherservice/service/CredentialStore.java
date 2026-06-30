package com.filewatcherservice.service;

import com.filewatcherservice.database.CredentialRepository;
import com.filewatcherservice.database.DatabaseService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory cache of credentials, backed by SQLite via {@link CredentialRepository}.
 *
 * Public API is unchanged from the old XML-based version — FileWatcherService,
 * ServiceWebSocketServer, and CredentialsPanel/CredentialEditDialog on the UI
 * side all keep working without modification. Only the persistence mechanism
 * underneath changed (XML file → SQLite, per the architecture doc's section 10).
 *
 * Passwords are stored as-is in the password column for now — same trust
 * level as the old XOR-obfuscated XML version, since the obfuscation step
 * was never actually wired in there either (see the original class's
 * trailing "Obfuscation" section, which was empty).
 */
public class CredentialStore {

    // ── Credential model (unchanged) ─────────────────────────────────────────

    public static final class Credential {
        private String       id;
        private String       host;
        private int          port;
        private String       username;
        private String       passwordObfuscated;
        private String       protocol;
        private LocalDateTime lastUsed;
        private List<String> usedByJobIds = new ArrayList<>();

        public String        getId()       { return id; }
        public void          setId(String id) { this.id = id; }

        public String        getHost()     { return host; }
        public void          setHost(String h) { this.host = h; }

        public int           getPort()     { return port; }
        public void          setPort(int p) { this.port = p; }

        public String        getUsername() { return username; }
        public void          setUsername(String u) { this.username = u; }

        public String        getProtocol() { return protocol; }
        public void          setProtocol(String p) { this.protocol = p; }

        public LocalDateTime getLastUsed() { return lastUsed; }
        public void          setLastUsed(LocalDateTime t) { this.lastUsed = t; }

        public List<String>  getUsedByJobIds() { return usedByJobIds; }
        public void          setUsedByJobIds(List<String> ids) { this.usedByJobIds = ids; }

        /** Returns the plaintext password. */
        public String getPassword() {
            return passwordObfuscated == null ? "" : passwordObfuscated;
        }

        /** Stores the password. */
        public void setPassword(String plaintext) {
            this.passwordObfuscated = (plaintext == null) ? "" : plaintext;
        }

        /** Convenience key: "username@host" */
        public String key() { return username + "@" + host; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final CredentialRepository repository;
    private final Map<String, Credential>          store     = new LinkedHashMap<>();
    private final List<Consumer<List<Credential>>> listeners = new CopyOnWriteArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public CredentialStore(DatabaseService db) {
        this.repository = new CredentialRepository(db);
        load();
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public void addChangeListener(Consumer<List<Credential>> listener) {
        listeners.add(listener);
    }

    private void notifyChange() {
        List<Credential> snapshot = new ArrayList<>(store.values());
        listeners.forEach(l -> l.accept(snapshot));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<Credential> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(store.values()));
    }

    public Optional<Credential> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Credential> findByKey(String username, String host) {
        return store.values().stream()
                .filter(c -> c.getUsername().equalsIgnoreCase(username)
                        && c.getHost().equalsIgnoreCase(host))
                .findFirst();
    }

    /** Adds or overwrites (matched by id). Assigns a random id if none set. */
    public void save(Credential cred) {
        if (cred.getId() == null || cred.getId().isBlank())
            cred.setId(UUID.randomUUID().toString());
        store.put(cred.getId(), cred);
        repository.save(cred);
        notifyChange();
    }

    public void delete(String id) {
        store.remove(id);
        repository.delete(id);
        notifyChange();
    }

    /** Called by FileWatcherService each time a job uses this credential. */
    public void touchLastUsed(String credId, String jobId) {
        Credential c = store.get(credId);
        if (c == null) return;
        c.setLastUsed(LocalDateTime.now());
        if (!c.getUsedByJobIds().contains(jobId))
            c.getUsedByJobIds().add(jobId);
        repository.touchLastUsed(credId, jobId);
    }

    /** Removes a job reference from every credential (call on job delete). */
    public void removeJobRef(String jobId) {
        store.values().forEach(c -> c.getUsedByJobIds().remove(jobId));
        repository.removeJobRef(jobId);
        notifyChange();
    }

    // ── Load from DB into memory ────────────────────────────────────────────

    private void load() {
        for (Credential c : repository.findAll()) {
            store.put(c.getId(), c);
        }
    }
}