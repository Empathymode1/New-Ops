package com.filewatcher;

import com.filewatcher.service.ServiceMain;
import com.filewatcher.ui.MainWindow;

import javax.swing.*;

/**
 * FileWatcher — OS-level file watching GUI for Ops teams.
 *
 * Architecture overview:
 *   FileWatcherService   — manages WatchJob lifecycle; uses Java NIO WatchService
 *                          for LOCAL_TO_LOCAL jobs (OS-level inotify / FSEvents / ReadDirectoryChangesW).
 *                          Polling-based scheduler for INBOUND / OUTBOUND remote jobs.
 *
 *   WatchJob             — immutable-ID model; holds source/dest config + runtime stats.
 *   TransferEvent        — timestamped log entry emitted by the service.
 *
 *   MainWindow           — root Swing frame (dark theme).
 *   JobTablePanel        — left pane; sortable job list + quick-action buttons.
 *   JobDetailPanel       — right pane; live stats + config for selected job.
 *   EventLogPanel        — center pane; StyledDocument log with per-job filtering.
 *   StatusBarPanel       — bottom; global counters + real-time clock.
 *   JobEditDialog        — modal for creating / editing jobs.
 *
 * To add real SFTP support:
 *   1. Add JSch to pom.xml:
 *        <dependency>
 *          <groupId>com.jcraft</groupId>
 *          <artifactId>jsch</artifactId>
 *          <version>0.1.55</version>
 *        </dependency>
 *   2. In FileWatcherService#pollRemote, replace simulateRemoteTransfer() with
 *      JSch session + ChannelSftp logic.
 */
public class Main {

    public static void main(String[] args) {
        // Enable HiDPI on modern JVMs
        System.setProperty("sun.java2d.uiScale", "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        boolean serviceMode = args.length > 0
                && args[0].equalsIgnoreCase("--service");

        if (serviceMode) {
            // Runs as Windows Service via WinSW — no UI, just watchers + WS server
            ServiceMain.start();
        } else {
            // Opens UI — connects to running service via WebSocket
            SwingUtilities.invokeLater(() -> {
                MainWindow window = new MainWindow();
                window.setVisible(true);
            });
        }
    }
}
