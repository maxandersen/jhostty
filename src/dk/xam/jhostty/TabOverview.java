package dk.xam.jhostty;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Tab overview overlay — shows a grid of tab snapshots with animations.
 */
final class TabOverview {

    private static StackPane tabOverlayPane;
    private static boolean animating = false;

    private TabOverview() {}

    static boolean isShowing(StackPane root) {
        return tabOverlayPane != null && tabOverlayPane.getParent() == root;
    }

    static void toggle(TabPane tabs, StackPane root, Runnable newTabAction) {
        if (animating) return;
        if (isShowing(root)) {
            animateOut(root);
            return;
        }

        var grid = new FlowPane(16, 16);
        grid.setAlignment(javafx.geometry.Pos.CENTER);
        grid.setPadding(new javafx.geometry.Insets(40));

        var selectedTab = tabs.getSelectionModel().getSelectedItem();

        for (var tab : tabs.getTabs()) {
            var content = tab.getContent();
            if (content == null) continue;

            var snapshot = content.snapshot(null, null);
            var imgView = new ImageView(snapshot);
            imgView.setFitWidth(220);
            imgView.setPreserveRatio(true);
            imgView.setSmooth(true);

            var title = tab.getText() != null && !tab.getText().isBlank() ? tab.getText() : "Terminal";
            var titleLbl = new Label(title);
            titleLbl.getStyleClass().add("tab-overview-title");
            titleLbl.setMaxWidth(220);
            titleLbl.setAlignment(javafx.geometry.Pos.CENTER);

            var card = new VBox(4, titleLbl, imgView);
            card.setAlignment(javafx.geometry.Pos.CENTER);
            card.setPadding(new javafx.geometry.Insets(8));
            card.getStyleClass().add("tab-overview-card");

            if (tab == selectedTab) {
                card.getStyleClass().add("tab-overview-card-selected");
            }

            final var cardRef = card;
            card.setOnMouseClicked(_ -> {
                tabs.getSelectionModel().select(tab);
                animateSelect(root, cardRef, grid);
            });

            grid.getChildren().add(card);
        }

        // "+" card
        var plusLbl = new Label("+");
        plusLbl.getStyleClass().add("tab-overview-plus-label");
        var plusCard = new VBox(plusLbl);
        plusCard.setAlignment(javafx.geometry.Pos.CENTER);
        plusCard.setPrefSize(220, 140);
        plusCard.getStyleClass().add("tab-overview-plus-card");
        plusCard.setOnMouseClicked(_ -> {
            animateOut(root);
            Platform.runLater(newTabAction);
        });
        grid.getChildren().add(plusCard);

        var scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("tab-overview-scroll");

        tabOverlayPane = new StackPane(scroll);
        tabOverlayPane.getStyleClass().add("tab-overview-overlay");

        tabOverlayPane.setOnMouseClicked(e -> {
            if (e.getTarget() == tabOverlayPane || e.getTarget() == scroll) animateOut(root);
        });

        tabOverlayPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) { animateOut(root); e.consume(); }
        });

        // Animate in
        root.getChildren().add(tabOverlayPane);
        tabOverlayPane.requestFocus();
        tabOverlayPane.setOpacity(0);
        for (var node : grid.getChildren()) { node.setOpacity(0); node.setScaleX(2.5); node.setScaleY(2.5); }
        animating = true;

        var bgFade = new FadeTransition(Duration.millis(250), tabOverlayPane);
        bgFade.setToValue(1.0);

        var cardAnims = new ParallelTransition();
        int ci = 0;
        for (var node : grid.getChildren()) {
            var delay = Duration.millis(ci * 30);
            var fade = new FadeTransition(Duration.millis(200), node);
            fade.setDelay(delay);
            fade.setToValue(1.0);
            var scale = new ScaleTransition(Duration.millis(300), node);
            scale.setDelay(delay);
            scale.setToX(1.0); scale.setToY(1.0);
            scale.setInterpolator(Interpolator.EASE_OUT);
            cardAnims.getChildren().addAll(fade, scale);
            ci++;
        }
        var par = new ParallelTransition(bgFade, cardAnims);
        par.setOnFinished(_ -> animating = false);
        par.play();
    }

    static void animateOut(Pane root) {
        if (tabOverlayPane == null || animating) return;
        animating = true;
        var content = tabOverlayPane.getChildren().isEmpty() ? tabOverlayPane : tabOverlayPane.getChildren().getFirst();
        var gridNode = (content instanceof ScrollPane sp) ? sp.getContent() : content;
        var cards = (gridNode instanceof Pane p) ? p.getChildren() : FXCollections.<Node>observableArrayList();

        var cardAnims = new ParallelTransition();
        int ci = 0;
        for (var node : cards) {
            var delay = Duration.millis(ci * 20);
            var fade = new FadeTransition(Duration.millis(150), node);
            fade.setDelay(delay);
            fade.setToValue(0);
            var scale = new ScaleTransition(Duration.millis(200), node);
            scale.setDelay(delay);
            scale.setToX(2.5); scale.setToY(2.5);
            scale.setInterpolator(Interpolator.EASE_IN);
            cardAnims.getChildren().addAll(fade, scale);
            ci++;
        }
        var bgFade = new FadeTransition(Duration.millis(200), tabOverlayPane);
        bgFade.setDelay(Duration.millis(100));
        bgFade.setToValue(0);
        var par = new ParallelTransition(cardAnims, bgFade);
        par.setOnFinished(_ -> {
            root.getChildren().remove(tabOverlayPane);
            tabOverlayPane = null;
            animating = false;
        });
        par.play();
    }

    private static void animateSelect(Pane root, Node selectedCard, Pane grid) {
        if (tabOverlayPane == null || animating) return;
        animating = true;

        var anims = new ParallelTransition();

        var selScale = new ScaleTransition(Duration.millis(300), selectedCard);
        selScale.setToX(4.0); selScale.setToY(4.0);
        selScale.setInterpolator(Interpolator.EASE_IN);
        var selFade = new FadeTransition(Duration.millis(250), selectedCard);
        selFade.setDelay(Duration.millis(100));
        selFade.setToValue(0);
        anims.getChildren().addAll(selScale, selFade);

        for (var node : grid.getChildren()) {
            if (node == selectedCard) continue;
            var fade = new FadeTransition(Duration.millis(150), node);
            fade.setToValue(0);
            var scale = new ScaleTransition(Duration.millis(200), node);
            scale.setToX(0.8); scale.setToY(0.8);
            scale.setInterpolator(Interpolator.EASE_IN);
            anims.getChildren().addAll(fade, scale);
        }

        var bgFade = new FadeTransition(Duration.millis(200), tabOverlayPane);
        bgFade.setDelay(Duration.millis(150));
        bgFade.setToValue(0);
        anims.getChildren().add(bgFade);

        anims.setOnFinished(_ -> {
            root.getChildren().remove(tabOverlayPane);
            tabOverlayPane = null;
            animating = false;
        });
        anims.play();
    }
}
