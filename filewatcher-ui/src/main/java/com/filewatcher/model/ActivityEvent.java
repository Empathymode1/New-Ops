package com.filewatcher.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** One row in the Dashboard "Recent Activity" feed (spec §7). Immutable — new events replace old ones. */
public record ActivityEvent(LocalTime time, String jobName, String eventLabel, String description, String colorKey) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public String timeLabel() {
        return time.format(FMT);
    }
}
