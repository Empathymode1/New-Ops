package com.filewatcherui.ui;

import com.filewatchercommon.model.WatchJob;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class Theme {

    private Theme() {}

    // ── CSS colour strings (use in -fx-background-color etc.) ────────────
    public static final String BG_BASE      = "#F5F3EF";
    public static final String BG_SURFACE   = "#FAFAF8";
    public static final String BG_CARD      = "#FFFFFF";
    public static final String BG_HOVER     = "#F5EFE5";

    public static final String BORDER       = "#E2DDD9";
    public static final String BORDER_STRONG= "#C8C1BB";

    public static final String TEXT_PRIMARY  = "#1A1714";
    public static final String TEXT_SECONDARY= "#4A423C";
    public static final String TEXT_MUTED    = "#8A7E74";

    public static final String ACCENT   = "#B87C2A";
    public static final String SUCCESS  = "#2E7D52";
    public static final String WARNING  = "#9A5A1A";
    public static final String DANGER   = "#B54040";
    public static final String IDLE_CLR = "#6A5E56";

    public static final String BTN_PRIMARY_BG   = "#B87C2A";
    public static final String BTN_PRIMARY_FG   = "#FFFFFF";
    public static final String BTN_PRIMARY_HOVER= "#A06A1F";

    public static final String PILL_WATCH_BG   = "#FEF3DA";
    public static final String PILL_SUCCESS_BG = "#E8F7EE";
    public static final String PILL_ERROR_BG   = "#FEEAEA";
    public static final String PILL_WARNING_BG = "#FEF0E3";
    public static final String PILL_IDLE_BG    = "#EDEAE6";

    public static final String INBOUND_CLR        = "#2D7A4F";
    public static final String OUTBOUND_CLR       = "#2A5498";
    public static final String LOCAL_TO_LOCAL_CLR = "#7A5B30";

    // ── JavaFX Color objects (for code that needs Color not String) ───────
    public static final Color COLOR_ACCENT   = Color.web(ACCENT);
    public static final Color COLOR_SUCCESS  = Color.web(SUCCESS);
    public static final Color COLOR_DANGER   = Color.web(DANGER);
    public static final Color COLOR_WARNING  = Color.web(WARNING);
    public static final Color COLOR_PRIMARY  = Color.web(TEXT_PRIMARY);
    public static final Color COLOR_MUTED    = Color.web(TEXT_MUTED);

    // ── Fonts ─────────────────────────────────────────────────────────────
    public static final Font FONT_TITLE = Font.font("Segoe UI", FontWeight.NORMAL, 22);
    public static final Font FONT_H2    = Font.font("Segoe UI", FontWeight.NORMAL, 15);
    public static final Font FONT_BODY  = Font.font("Segoe UI", FontWeight.NORMAL, 13);
    public static final Font FONT_SMALL = Font.font("Segoe UI", FontWeight.NORMAL, 11);
    public static final Font FONT_BADGE = Font.font("Segoe UI", FontWeight.BOLD,   10);
    public static final Font FONT_MONO  = Font.font("Consolas", FontWeight.NORMAL, 12);

    // ── Inline CSS helpers ────────────────────────────────────────────────
    public static String statusBgColor(WatchJob.Status s) {
        return switch (s) {
            case WATCHING     -> PILL_WATCH_BG;
            case TRANSFERRING -> PILL_SUCCESS_BG;
            case ERROR        -> PILL_ERROR_BG;
            case PAUSED       -> PILL_WARNING_BG;
            case IDLE         -> PILL_IDLE_BG;
        };
    }

    public static String statusFgColor(WatchJob.Status s) {
        return switch (s) {
            case WATCHING     -> ACCENT;
            case TRANSFERRING -> SUCCESS;
            case ERROR        -> DANGER;
            case PAUSED       -> WARNING;
            case IDLE         -> IDLE_CLR;
        };
    }

    public static String directionBgColor(WatchJob.Direction d) {
        return switch (d) {
            case INBOUND        -> "#EAF1FA";
            case OUTBOUND       -> "#E6EEF8";
            case LOCAL_TO_LOCAL -> "#F4EFE5";
        };
    }

    public static String directionFgColor(WatchJob.Direction d) {
        return switch (d) {
            case INBOUND        -> INBOUND_CLR;
            case OUTBOUND       -> OUTBOUND_CLR;
            case LOCAL_TO_LOCAL -> LOCAL_TO_LOCAL_CLR;
        };
    }

    // ── Reusable inline style builders ────────────────────────────────────
    public static String cardStyle() {
        return "-fx-background-color: " + BG_CARD + ";" +
                "-fx-border-color: " + BORDER + ";" +
                "-fx-border-radius: 6;" +
                "-fx-background-radius: 6;" +
                "-fx-border-width: 1;";
    }

    public static String pillStyle(String bg, String fg) {
        return "-fx-background-color: " + bg + ";" +
                "-fx-text-fill: " + fg + ";" +
                "-fx-background-radius: 20;" +
                "-fx-padding: 3 8 3 8;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 10;";
    }
}