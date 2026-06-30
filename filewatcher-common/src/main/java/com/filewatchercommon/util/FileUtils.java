package com.filewatchercommon.util;

/**
 * Shared utility methods used by both service and UI modules.
 */
public final class FileUtils {

    private FileUtils() {}

    public static String formatBytes(long bytes) {
        if (bytes < 1024)          return bytes + " B";
        if (bytes < 1_048_576)     return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824) return String.format("%.1f MB", bytes / 1_048_576.0);
        return                            String.format("%.1f GB", bytes / 1_073_741_824.0);
    }
}