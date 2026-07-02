package com.filewatcher.ui.components;

import com.filewatcher.model.JobStatus;
import javafx.scene.control.Label;

/** Colored pill badge — Green/Blue/Orange/Red/Gray per spec §7. */
public class StatusBadge extends Label {

    public StatusBadge(JobStatus status) {
        super(status.label());
        getStyleClass().addAll("badge", status.styleClass());
    }

    public void update(JobStatus status) {
        getStyleClass().removeIf(c -> c.startsWith("badge-"));
        getStyleClass().add(status.styleClass());
        setText(status.label());
    }

    /** Generic ok/failed badge used on the Logs page. */
    public static StatusBadge okOrFailed(String status) {
        boolean ok = "ok".equalsIgnoreCase(status);
        Label dummy = new Label(); // not used, kept for symmetry
        StatusBadge badge = new StatusBadge(ok ? JobStatus.RUNNING : JobStatus.STOPPED);
        badge.setText(ok ? "OK" : "Failed");
        return badge;
    }
}
