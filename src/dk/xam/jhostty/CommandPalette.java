package dk.xam.jhostty;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;

/**
 * Command palette overlay — fuzzy-searchable list of actions.
 */
final class CommandPalette {

    record PaletteItem(String label, String shortcut, String category, String keywords, Runnable action) {
        /** Convenience constructor for items with no extra search keywords. */
        PaletteItem(String label, String shortcut, String category, Runnable action) {
            this(label, shortcut, category, "", action);
        }
        @Override public String toString() { return label; }
    }

    private static StackPane paletteOverlay;

    private CommandPalette() {}

    static void dismiss() {
        if (paletteOverlay != null && paletteOverlay.getParent() instanceof Pane p) {
            p.getChildren().remove(paletteOverlay);
        }
        paletteOverlay = null;
    }

    static void show(StackPane rootStack, List<PaletteItem> allItems) {
        if (paletteOverlay != null && paletteOverlay.getParent() == rootStack) {
            dismiss();
            return;
        }

        var searchField = new TextField();
        searchField.setPromptText("Type a command, session, tab, or theme\u2026");
        searchField.getStyleClass().add("jhostty-palette-search");

        var resultList = new ListView<PaletteItem>();
        resultList.getItems().addAll(allItems);
        resultList.setPrefHeight(350);
        resultList.getStyleClass().add("jhostty-palette-list");
        resultList.setFixedCellSize(32);
        resultList.setCellFactory(_ -> new ListCell<>() {
            @Override protected void updateItem(PaletteItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                var lbl = new Label(item.label());
                lbl.setStyle("-fx-font-size: 15;");
                lbl.getStyleClass().add("jhostty-palette-label");
                var svgPath = switch (item.category()) {
                    case "Shell"  -> "M2 3l6 5-6 5";
                    case "View"   -> "M1 4.5a3.5 3.5 0 017 0 3.5 3.5 0 01-7 0M4.5 4.5a0 0 0 010 0";
                    case "Pane"   -> "M0 0h10v10H0zM5 0v10M0 5h10";
                    case "Help"   -> "M3.5 1A2.5 2.5 0 016 3.5c0 1.5-2.5 1.5-2.5 3M3.5 8.5h0";
                    case "Tabs"   -> "M0 2h10M0 5h10M0 8h10";
                    case "Sessions" -> "M0 1h10v8H0zM0 3h10M2 5l1.5 1.5L2 8";
                    case "Themes" -> "M5 0A5 5 0 000 5a5 5 0 005 5 5 5 0 005-5A5 5 0 005 0";
                    default       -> "M4 5a1 1 0 110 0 1 1 0 01-1 0";
                };
                var icon = new Region();
                icon.setStyle("-fx-shape: '" + svgPath + "'; -fx-min-width: 12; -fx-min-height: 12; -fx-max-width: 12; -fx-max-height: 12; -fx-background-color: -fx-text-fill;");
                var cat = new StackPane(icon);
                cat.setMinWidth(20); cat.setMaxWidth(20);
                cat.setAlignment(javafx.geometry.Pos.CENTER);
                cat.getStyleClass().add("jhostty-palette-cat");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                var sc2 = new Label(item.shortcut());
                sc2.setStyle("-fx-font-size: 14;");
                sc2.getStyleClass().add("jhostty-palette-shortcut");
                var row = new HBox(6, lbl, cat, spacer, sc2);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });

        // Filter
        searchField.textProperty().addListener((_, _, text) -> {
            resultList.getItems().clear();
            var lower = text == null ? "" : text.toLowerCase();
            allItems.stream()
                .filter(item -> lower.isEmpty() || item.label().toLowerCase().contains(lower)
                    || item.category().toLowerCase().contains(lower)
                    || (item.keywords() != null && item.keywords().toLowerCase().contains(lower)))
                .forEach(item -> resultList.getItems().add(item));
            if (!resultList.getItems().isEmpty()) resultList.getSelectionModel().select(0);
        });

        final Runnable[] execRef = new Runnable[1];
        execRef[0] = () -> {
            var sel = resultList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                dismiss();
                Platform.runLater(sel.action());
            }
        };

        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    var si = resultList.getSelectionModel().getSelectedIndex();
                    resultList.requestFocus();
                    resultList.getSelectionModel().select(si < 0 ? 0 : Math.min(si + 1, resultList.getItems().size() - 1));
                    resultList.scrollTo(resultList.getSelectionModel().getSelectedIndex());
                    e.consume();
                }
                case UP -> {
                    var sz = resultList.getItems().size();
                    if (sz > 0) { resultList.requestFocus(); resultList.getSelectionModel().select(sz - 1); resultList.scrollTo(sz - 1); }
                    e.consume();
                }
                case ENTER -> { execRef[0].run(); e.consume(); }
                case ESCAPE -> { dismiss(); e.consume(); }
                case TAB -> e.consume();
                default -> {}
            }
        });

        resultList.setOnMouseClicked(_ -> execRef[0].run());

        var paletteBox = new VBox(8, searchField, resultList);
        paletteBox.getStyleClass().add("jhostty-palette-box");
        paletteBox.setMaxWidth(550);
        paletteBox.setMaxHeight(500);

        resultList.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ENTER -> { execRef[0].run(); e.consume(); }
                case ESCAPE -> { dismiss(); e.consume(); }
                case UP -> {
                    if (resultList.getSelectionModel().getSelectedIndex() == 0) {
                        searchField.requestFocus(); e.consume();
                    }
                }
                case TAB -> e.consume();
                default -> {
                    if (e.getCode().isLetterKey() || e.getCode().isDigitKey() || e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.SPACE) {
                        searchField.requestFocus();
                        if (e.getCode() == KeyCode.BACK_SPACE) searchField.deletePreviousChar();
                        else if (e.getText() != null && !e.getText().isEmpty()) searchField.appendText(e.getText());
                        e.consume();
                    }
                }
            }
        });

        paletteOverlay = new StackPane(paletteBox);
        paletteOverlay.getStyleClass().add("jhostty-palette-overlay");
        StackPane.setAlignment(paletteBox, javafx.geometry.Pos.TOP_CENTER);
        StackPane.setMargin(paletteBox, new javafx.geometry.Insets(60, 0, 0, 0));

        rootStack.getChildren().add(paletteOverlay);
        searchField.requestFocus();
        if (!resultList.getItems().isEmpty()) resultList.getSelectionModel().select(0);
    }
}
