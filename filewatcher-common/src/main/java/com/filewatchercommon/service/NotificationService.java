package com.filewatchercommon.service;

import com.filewatchercommon.model.NotificationMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Moved to filewatcher-common so both UI and service can use it.
 * Uses NotificationMessage instead of inner Notification class.
 */
public class NotificationService {

    private final List<NotificationMessage>                 notifications = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<NotificationMessage>>> listeners     = new CopyOnWriteArrayList<>();

    public void addListener(Consumer<List<NotificationMessage>> listener) {
        listeners.add(listener);
    }

    public void addError(String jobId, String jobName, String message) {
        notifications.add(0, new NotificationMessage(jobId, jobName, message));
        notifyListeners();
    }

    public List<NotificationMessage> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(notifications));
    }

    public long getUnreadCount() {
        return notifications.stream().filter(n -> !n.isRead()).count();
    }

    public void markAllRead() {
        notifications.forEach(NotificationMessage::markRead);
        notifyListeners();
    }

    public void clear() {
        notifications.clear();
        notifyListeners();
    }

    private void notifyListeners() {
        List<NotificationMessage> snapshot = getAll();
        listeners.forEach(l -> l.accept(snapshot));
    }
}