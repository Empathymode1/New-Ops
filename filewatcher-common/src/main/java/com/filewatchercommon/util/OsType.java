package com.filewatchercommon.util;

public enum OsType {
    LINUX, MACOS, WINDOWS, UNKNOWN;

    public static OsType local() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("linux"))                          return LINUX;
        if (name.contains("mac") || name.contains("darwin")) return MACOS;
        if (name.contains("win"))                            return WINDOWS;
        return UNKNOWN;
    }
}