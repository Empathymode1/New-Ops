package com.filewatcherui.ui;

import com.filewatchercommon.model.TransferEvent;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventLogPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LINES = 2000;

    private final TextFlow  logFlow   = new TextFlow();
    private final ScrollPane scroll;
    private final BorderPane root     = new BorderPane();
    private final List<TransferEvent> events = new CopyOnWriteArrayList<>();
    private String  filterJobId = null;
    private boolean autoScroll  = true;
    private int     lineCount   = 0;

    public EventLogPanel() {
        root.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");
        root.setTop(buildHeader());

        logFlow.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-padding: 8 12 8 12;");
        logFlow.setLineSpacing(2);

        scroll = new ScrollPane(logFlow);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_CARD + ";" +
                "-fx-background: " + Theme.BG_CARD + ";");
        root.setCenter(scroll);
        root.setBottom(buildLegend());
    }

    public Region getRoot() { return root; }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 12, 12, 16));
        header.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 1 0;");

        Label title = new Label("Activity Log");
        title.setStyle("-fx-font-size: 15; -fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        CheckBox autoScrollBox = new CheckBox("Auto-scroll");
        autoScrollBox.setSelected(true);
        autoScrollBox.setStyle("-fx-text-fill: " + Theme.TEXT_SECONDARY +
                "; -fx-font-size: 11;");
        autoScrollBox.setOnAction(e -> autoScroll = autoScrollBox.isSelected());

        Button clearBtn  = smallBtn("Clear");
        Button exportBtn = smallBtn("Export");
        clearBtn.setOnAction(e  -> clearLog());
        exportBtn.setOnAction(e -> exportLog());

        header.getChildren().addAll(title, spacer, autoScrollBox,
                clearBtn, exportBtn);
        return header;
    }

    // ── Legend ────────────────────────────────────────────────────────────

    private HBox buildLegend() {
        HBox p = new HBox(16);
        p.setPadding(new Insets(6, 16, 6, 16));
        p.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 1 0 0 0;");

        legendDot(p, "TRANSFERRED", Theme.SUCCESS);
        legendDot(p, "DETECTED",    Theme.ACCENT);
        legendDot(p, "CONNECTED",   Theme.SUCCESS);
        legendDot(p, "ERROR",       Theme.DANGER);
        legendDot(p, "SKIPPED",     Theme.TEXT_MUTED);
        return p;
    }

    private void legendDot(HBox p, String label, String color) {
        Label dot = new Label("*");
        dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 11;");
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + "; -fx-font-size: 11;");
        HBox pair = new HBox(3, dot, lbl);
        pair.setAlignment(Pos.CENTER_LEFT);
        p.getChildren().add(pair);
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void appendEvent(TransferEvent event) {
        events.add(event);
        if (filterJobId == null || filterJobId.equals(event.getJobId()))
            Platform.runLater(() -> renderEvent(event));
    }

    public void setFilter(String jobId) {
        this.filterJobId = jobId;
        Platform.runLater(this::rebuildLog);
    }

    public void clearFilter() { setFilter(null); }

    // ── Rendering ─────────────────────────────────────────────────────────

    private void renderEvent(TransferEvent e) {
        String ts    = "[" + e.getTimestamp().format(FMT) + "] ";
        String badge = "[" + e.getType().name() + "] ";
        String job   = e.getJobName() != null ? e.getJobName() + " - " : "";
        String msg   = e.getMessage() + "\n";

        Text tsText = styledText(ts, Theme.TEXT_MUTED, false);
        Text badgeText = styledText(badge, styleForType(e.getType()),
                isBold(e.getType()));
        Text jobText  = styledText(job,  Theme.TEXT_SECONDARY, false);
        Text msgText  = styledText(msg,  Theme.TEXT_PRIMARY,   false);

        logFlow.getChildren().addAll(tsText, badgeText, jobText, msgText);
        lineCount++;

        if (lineCount > MAX_LINES) {
            logFlow.getChildren().remove(0, 4);
            lineCount--;
        }

        if (autoScroll) {
            scroll.setVvalue(1.0);
        }
    }

    private void rebuildLog() {
        logFlow.getChildren().clear();
        lineCount = 0;
        events.stream()
                .filter(e -> filterJobId == null
                        || filterJobId.equals(e.getJobId()))
                .forEach(this::renderEvent);
    }

    private void clearLog() {
        events.clear();
        logFlow.getChildren().clear();
        lineCount = 0;
    }

    private void exportLog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export log");
        fc.setInitialFileName("filewatcher-log.txt");
        File chosen = fc.showSaveDialog(root.getScene().getWindow());
        if (chosen != null) {
            try (PrintWriter pw = new PrintWriter(chosen)) {
                for (TransferEvent e : events) {
                    pw.printf("[%s] [%s] %s - %s%n",
                            e.getTimestamp().format(FMT),
                            e.getType(), e.getJobName(), e.getMessage());
                }
            } catch (Exception ex) {
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "Export failed: " + ex.getMessage(), ButtonType.OK);
                a.setHeaderText(null);
                a.showAndWait();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Text styledText(String content, String colorHex, boolean bold) {
        Text t = new Text(content);
        t.setFill(Color.web(colorHex));
        t.setFont(bold
                ? javafx.scene.text.Font.font("Consolas",
                FontWeight.BOLD, 12)
                : javafx.scene.text.Font.font("Consolas",
                FontWeight.NORMAL, 12));
        return t;
    }

    private String styleForType(TransferEvent.EventType type) {
        return switch (type) {
            case TRANSFERRED, CONNECTED -> Theme.SUCCESS;
            case DETECTED,   STARTED   -> Theme.ACCENT;
            case DISCONNECTED, STOPPED -> Theme.WARNING;
            case ERROR                 -> Theme.DANGER;
            default                    -> Theme.TEXT_MUTED;
        };
    }

    private boolean isBold(TransferEvent.EventType type) {
        return switch (type) {
            case TRANSFERRED, CONNECTED, ERROR, STARTED -> true;
            default -> false;
        };
    }

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 3 10 3 10; -fx-font-size: 11;" +
                        "-fx-cursor: hand;");
        return b;
    }
}