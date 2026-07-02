package com.filewatcher.model;

/** Mirrors the status badge states from the UI/UX spec (Dashboard §7, Service Mgmt §8). */
public enum JobStatus {
    RUNNING, STARTING, RESTARTING, STOPPED, DISABLED;

    public String label() {
        return switch (this) {
            case RUNNING -> "Running";
            case STARTING -> "Starting";
            case RESTARTING -> "Restarting";
            case STOPPED -> "Stopped";
            case DISABLED -> "Disabled";
        };
    }

    /** CSS style class suffix, e.g. "badge-running". Backing colors live in app.css. */
    public String styleClass() {
        return "badge-" + name().toLowerCase();
    }
}
