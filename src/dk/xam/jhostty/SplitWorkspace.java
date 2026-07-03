package dk.xam.jhostty;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

import javafx.animation.*;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

// ─── Model ──────────────────────────────────────────────────────────────────────

/**
 * A tiling split-pane workspace component for JavaFX.
 * <p>
 * Instead of nesting SplitPane controls, this uses a single Region that owns
 * all leaf panes as direct children and computes their layout from an n-ary
 * weighted split tree. Supports focus ring, split/close, divider drag (including
 * corner drag at junctions), animated reflow, zoom/unzoom, and pastel tinting.
 */

record PaneId(String value) {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    static PaneId next() { return new PaneId("pane-" + COUNTER.incrementAndGet()); }
}

record Rect(double x, double y, double w, double h) {
    double right() { return x + w; }
    double bottom() { return y + h; }
    Rect inset(double inset) { return new Rect(x + inset, y + inset, w - 2 * inset, h - 2 * inset); }
}

sealed interface SplitNode permits Split, LeafPane {}

final class Split implements SplitNode {
    private Orientation orientation;
    private final List<SplitNode> children;
    private final List<Double> weights;

    Split(Orientation orientation, List<SplitNode> children, List<Double> weights) {
        if (children.size() != weights.size() || children.size() < 2)
            throw new IllegalArgumentException("Split needs >= 2 children with matching weights");
        this.orientation = orientation;
        this.children = new ArrayList<>(children);
        this.weights = new ArrayList<>(weights);
    }

    Orientation orientation() { return orientation; }
    List<SplitNode> children() { return children; }
    List<Double> weights() { return weights; }

    void normalizeWeights() {
        double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0 && Math.abs(sum - 1.0) > 0.001) {
            for (int i = 0; i < weights.size(); i++) weights.set(i, weights.get(i) / sum);
        }
    }
}

final class LeafPane implements SplitNode {
    private final PaneId id;
    private Node content;
    private String title;

    LeafPane(PaneId id, Node content, String title) {
        this.id = id;
        this.content = content;
        this.title = title;
    }

    PaneId id() { return id; }
    Node content() { return content; }
    String title() { return title; }
    void setContent(Node content) { this.content = content; }
    void setTitle(String title) { this.title = title; }
}

enum DropZone { CENTER_SWAP, LEFT, RIGHT, TOP, BOTTOM }

// ─── Workspace ──────────────────────────────────────────────────────────────────

class SplitWorkspace extends Region {

    // Registry of all live workspaces (for cross-window DnD)
    private static final List<SplitWorkspace> ALL_WORKSPACES = new ArrayList<>();

    // Geometry constants
    static double GUTTER = 2;
    static final double PADDING = 0;
    static double PANE_RADIUS = 8;
    static final double MIN_PANE_W = 120;
    static final double MIN_PANE_H = 80;
    static double FOCUS_RING_WIDTH = 1.5;
    private Color focusRingColor = Color.web("#3B82F6");
    static final double CORNER_HIT_SIZE = 14;
    static final double DIVIDER_OVERLAP = 3;
    static double HEADER_H = 22;
    static final double DRAG_THRESHOLD = 6;

    // Pastel palette from the design doc
    private static final Color[] PASTEL_PALETTE = {
        Color.web("#E3E7FB"), Color.web("#FDEBDD"), Color.web("#FBE7F3"),
        Color.web("#E5F3E8"), Color.web("#FBDFDF"), Color.web("#DFF1F7"),
        Color.web("#E4E9F7")
    };

    // Animation constants
    private Duration REFLOW_DURATION = Duration.millis(200);
    private Duration FOCUS_DURATION = Duration.millis(120);
    private Duration ZOOM_DURATION = Duration.millis(250);
    private Duration RESIZE_ANIM_DURATION = Duration.millis(300);
    private static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0);

    // ── State ──
    private SplitNode root;
    private final ObjectProperty<LeafPane> focusedPane = new SimpleObjectProperty<>();
    private final BooleanProperty pastelTinting = new SimpleBooleanProperty(true);
    private final BooleanProperty animationsEnabled = new SimpleBooleanProperty(true);
    private final BooleanProperty focusFollowsMouse = new SimpleBooleanProperty(false);
    private Supplier<Node> contentFactory; // creates new terminal content
    private Runnable onEmpty; // called when last pane is removed

    // Layout cache
    private final Map<PaneId, Rect> currentRects = new LinkedHashMap<>();
    private final Map<PaneId, LeafPane> leafIndex = new LinkedHashMap<>();

    // Visual layers
    private final Pane contentLayer = new Pane();
    private final Pane overlayLayer = new Pane();
    private final Map<PaneId, Rectangle> focusRings = new HashMap<>();
    private final Map<PaneId, Rectangle> pastelOverlays = new HashMap<>();
    private final Map<PaneId, Region> paneWrappers = new HashMap<>();

    // Divider state
    private record DividerSegment(double x1, double y1, double x2, double y2,
                                   Split split, int leftIndex, boolean horizontal) {}
    private record CornerJunction(double cx, double cy,
                                   Split hSplit, int hLeftIndex,
                                   Split vSplit, int vLeftIndex) {}
    private final List<DividerSegment> dividerSegments = new ArrayList<>();
    private final List<CornerJunction> cornerJunctions = new ArrayList<>();

    // Drag state
    private Map<PaneId, Rect> preDragRects; // saved at drag start for equalize animation
    private DividerSegment draggingDivider;
    private CornerJunction draggingCorner;
    private double dragStartX, dragStartY;
    private double dragStartWeightA, dragStartWeightB;
    private double dragStartWeightC, dragStartWeightD; // for corner drag (second axis)

    // DnD state
    private LeafPane dragSource;
    private double dragOriginX, dragOriginY;
    private boolean dragging;
    private Region dragChip;
    private LeafPane dropTarget;
    private DropZone dropZone;
    private Rectangle dropHighlight;
    private SplitWorkspace dragTargetWorkspace; // workspace receiving the drop (may differ from this)
    private final Map<PaneId, Region> paneHeaders = new HashMap<>();

    // Zoom state
    private boolean zoomed = false;
    private LeafPane zoomedPane;
    private Map<PaneId, Rect> preZoomRects;
    private Map<PaneId, Rect> zoomedRects; // layout to maintain while zoomed

    // Animation
    private Timeline currentAnimation;
    private final Map<PaneId, Rect> animatingFrom = new LinkedHashMap<>();
    private final Map<PaneId, Rect> animatingTo = new LinkedHashMap<>();

    public SplitWorkspace() {
        contentLayer.setMouseTransparent(false);
        overlayLayer.setMouseTransparent(true);
        getChildren().addAll(contentLayer, overlayLayer);
        setStyle("-fx-background-color: transparent;");
        ALL_WORKSPACES.add(this);
        // Unregister when removed from scene
        sceneProperty().addListener((_, _, newScene) -> {
            if (newScene == null) ALL_WORKSPACES.remove(this);
        });

        // Click-to-focus on the workspace
        contentLayer.setOnMousePressed(this::handleMousePressed);
        contentLayer.setOnMouseDragged(this::handleMouseDragged);
        contentLayer.setOnMouseReleased(this::handleMouseReleased);
        contentLayer.setOnMouseMoved(this::handleMouseMoved);

        // Focus-follows-mouse needs an event filter on the workspace itself
        // so it fires even when mouse is over child TerminalView nodes
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, this::handleFocusFollowsMouse);

        // Focus ring updates
        focusedPane.addListener((_, oldPane, newPane) -> updateFocusRings(oldPane, newPane));

        // Repaint pastels when toggled
        pastelTinting.addListener((_, _, _) -> updatePastelOverlays());

        // Esc cancels drag
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && dragging) {
                cancelPaneDrag();
                e.consume();
            }
        });
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    public void setContentFactory(Supplier<Node> factory) { this.contentFactory = factory; }
    public void setOnEmpty(Runnable handler) { this.onEmpty = handler; }
    public Supplier<Node> getContentFactory() { return contentFactory; }

    public ObjectProperty<LeafPane> focusedPaneProperty() { return focusedPane; }
    public LeafPane getFocusedPane() { return focusedPane.get(); }

    public BooleanProperty pastelTintingProperty() { return pastelTinting; }
    public BooleanProperty animationsEnabledProperty() { return animationsEnabled; }
    public BooleanProperty focusFollowsMouseProperty() { return focusFollowsMouse; }
    public boolean isDragging() { return dragging || dragSource != null; }

    /** Set gutter width between panes. */
    public void setGutter(double px) {
        GUTTER = px;
        requestLayout();
    }

    /** Set pane corner radius. */
    public void setPaneRadius(double px) {
        PANE_RADIUS = px;
        // Update clips and overlays
        for (var wrapper : paneWrappers.values()) {
            if (wrapper.getClip() instanceof Rectangle clip) {
                clip.setArcWidth(px * 2); clip.setArcHeight(px * 2);
            }
        }
        for (var ring : focusRings.values()) {
            ring.setArcWidth(px * 2 + FOCUS_RING_WIDTH); ring.setArcHeight(px * 2 + FOCUS_RING_WIDTH);
        }
        for (var pastel : pastelOverlays.values()) {
            pastel.setArcWidth(px * 2); pastel.setArcHeight(px * 2);
        }
        // Update header styles
        for (var header : paneHeaders.values()) {
            header.setStyle("-fx-background-color: rgba(0,0,0,0.15); -fx-background-radius: " + px + " " + px + " 0 0;");
        }
    }

    /** Set header bar height. */
    public void setHeaderHeight(double px) {
        HEADER_H = px;
        for (var header : paneHeaders.values()) {
            header.setPrefHeight(px); header.setMinHeight(px); header.setMaxHeight(px);
        }
        // Update content anchors
        for (var leaf : allLeaves()) {
            var wrapper = paneWrappers.get(leaf.id());
            if (wrapper instanceof AnchorPane ap) {
                AnchorPane.setTopAnchor(leaf.content(), px);
            }
        }
        requestLayout();
    }

    /** Set focus ring stroke width. */
    public void setFocusRingWidth(double px) {
        FOCUS_RING_WIDTH = px;
        for (var ring : focusRings.values()) {
            ring.setStrokeWidth(px);
            ring.setArcWidth(PANE_RADIUS * 2 + px); ring.setArcHeight(PANE_RADIUS * 2 + px);
        }
        requestLayout();
    }

    /** Set animation duration for reflow/equalize/zoom (in ms). */
    public void setAnimationDuration(double ms) {
        REFLOW_DURATION = Duration.millis(Math.max(50, ms * 0.67));
        RESIZE_ANIM_DURATION = Duration.millis(Math.max(50, ms));
        ZOOM_DURATION = Duration.millis(Math.max(50, ms * 0.83));
        FOCUS_DURATION = Duration.millis(Math.max(30, ms * 0.4));
    }

    /** Set pastel overlay opacity (0–1). Lower for dark themes, higher for light. */
    public void setPastelOpacity(double opacity) {
        this.pastelOpacity = opacity;
        updatePastelOverlays();
    }

    /** Set the background color for pane wrappers (covers fractional-cell gaps in terminals). */
    public void setPaneBackground(Color color) {
        var css = String.format("-fx-background-color: rgba(%d,%d,%d,%.2f);",
            (int)(color.getRed()*255), (int)(color.getGreen()*255),
            (int)(color.getBlue()*255), color.getOpacity());
        for (var wrapper : paneWrappers.values()) {
            wrapper.setStyle(css);
        }
        // Store for new panes
        this.paneBackgroundCss = css;
    }
    private String paneBackgroundCss = "-fx-background-color: #282828;"; // sensible dark default
    private double pastelOpacity = 0.12; // default for dark themes

    /** Set the focus ring color to match the current theme. */
    public void setFocusRingColor(Color color) {
        this.focusRingColor = color;
        for (var ring : focusRings.values()) {
            ring.setStroke(color);
        }
    }

    public SplitNode getRoot() { return root; }

    public void setRoot(SplitNode root) {
        this.root = root;
        rebuildAll();
    }

    /** Split the focused pane, inserting a new pane on the given side. */
    public void splitFocused(Side side) {
        var focused = focusedPane.get();
        if (focused == null || contentFactory == null) return;
        var newContent = contentFactory.get();
        if (newContent == null) return;
        var newLeaf = new LeafPane(PaneId.next(), newContent, "");
        insertRelative(focused, newLeaf, side);
    }

    /** Close the focused pane. */
    public void closeFocused() {
        var focused = focusedPane.get();
        if (focused == null) return;
        removeLeaf(focused);
    }

    /** Close a specific pane. */
    public void closePane(LeafPane pane) {
        removeLeaf(pane);
    }

    /** Toggle zoom on the focused pane. */
    public void toggleZoom() {
        if (zoomed) unzoom(); else zoom();
    }

    /** Get all leaf panes via depth-first traversal. */
    public List<LeafPane> allLeaves() {
        var result = new ArrayList<LeafPane>();
        collectLeaves(root, result);
        return result;
    }

    /** Find a leaf by content node. */
    public LeafPane findLeafByContent(Node content) {
        for (var leaf : leafIndex.values()) {
            if (leaf.content() == content) return leaf;
        }
        return null;
    }

    /** Focus a specific pane. */
    public void focusPane(LeafPane pane) {
        focusedPane.set(pane);
    }

    // ── Tree operations ─────────────────────────────────────────────────────────

    private void insertRelative(LeafPane target, LeafPane newLeaf, Side side) {
        insertRelative(target, newLeaf, side, true);
    }

    private void insertRelative(LeafPane target, LeafPane newLeaf, Side side, boolean animate) {
        var orientation = (side == Side.LEFT || side == Side.RIGHT)
            ? Orientation.HORIZONTAL : Orientation.VERTICAL;
        boolean insertBefore = (side == Side.LEFT || side == Side.TOP);

        var parent = findParent(root, target);
        if (parent != null && parent.orientation() == orientation) {
            // Same orientation — add as sibling
            int idx = indexOf(parent, target);
            int insertIdx = insertBefore ? idx : idx + 1;
            parent.children().add(insertIdx, newLeaf);
            // Redistribute weights equally
            int n = parent.children().size();
            parent.weights().clear();
            for (int i = 0; i < n; i++) parent.weights().add(1.0 / n);
        } else {
            // Different orientation or target is root — wrap in new Split
            var children = insertBefore
                ? List.<SplitNode>of(newLeaf, target)
                : List.<SplitNode>of(target, newLeaf);
            var newSplit = new Split(orientation, children, List.of(0.5, 0.5));

            if (parent != null) {
                int idx = indexOf(parent, target);
                parent.children().set(idx, newSplit);
            } else {
                root = newSplit;
            }
        }
        if (animate) {
            captureRectsForAnimation();
            rebuildAll();
            focusedPane.set(newLeaf);
            animateReflow(REFLOW_DURATION);
        }
    }

    private void removeLeaf(LeafPane target) {
        if (root == target) {
            // Last pane — clean up and notify
            root = null;
            var wrapper = paneWrappers.remove(target.id());
            if (wrapper != null) contentLayer.getChildren().remove(wrapper);
            paneHeaders.remove(target.id());
            var ring = focusRings.remove(target.id());
            if (ring != null) overlayLayer.getChildren().remove(ring);
            var pastel = pastelOverlays.remove(target.id());
            if (pastel != null) overlayLayer.getChildren().remove(pastel);
            leafIndex.remove(target.id());
            focusedPane.set(null);
            if (onEmpty != null) onEmpty.run();
            return;
        }
        var parent = findParent(root, target);
        if (parent == null) return;

        int idx = indexOf(parent, target);
        parent.children().remove(idx);
        parent.weights().remove(idx);
        parent.normalizeWeights();

        // If parent has one child left, collapse
        if (parent.children().size() == 1) {
            var remaining = parent.children().getFirst();
            var grandparent = findParent(root, parent);
            if (grandparent != null) {
                int pidx = indexOf(grandparent, parent);
                grandparent.children().set(pidx, remaining);
            } else {
                root = remaining;
            }
        }

        // Move focus to nearest sibling
        if (focusedPane.get() == target) {
            var leaves = allLeaves();
            focusedPane.set(leaves.isEmpty() ? null : leaves.getFirst());
        }

        captureRectsForAnimation();
        // Remove content and overlays from scene
        var wrapper = paneWrappers.remove(target.id());
        if (wrapper != null) contentLayer.getChildren().remove(wrapper);
        paneHeaders.remove(target.id());
        var ring = focusRings.remove(target.id());
        if (ring != null) overlayLayer.getChildren().remove(ring);
        var pastel = pastelOverlays.remove(target.id());
        if (pastel != null) overlayLayer.getChildren().remove(pastel);
        leafIndex.remove(target.id());

        rebuildLayout();
        animateReflow(REFLOW_DURATION);
    }

    // ── Tree queries ────────────────────────────────────────────────────────────

    private Split findParent(SplitNode tree, SplitNode target) {
        if (tree instanceof Split split) {
            for (var child : split.children()) {
                if (child == target) return split;
                var found = findParent(child, target);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int indexOf(Split parent, SplitNode child) {
        return parent.children().indexOf(child);
    }

    private void collectLeaves(SplitNode node, List<LeafPane> result) {
        if (node == null) return;
        if (node instanceof LeafPane leaf) result.add(leaf);
        else if (node instanceof Split split) {
            for (var child : split.children()) collectLeaves(child, result);
        }
    }

    private LeafPane leafAt(double x, double y) {
        for (var entry : currentRects.entrySet()) {
            var r = entry.getValue();
            if (x >= r.x() && x <= r.right() && y >= r.y() && y <= r.bottom()) {
                return leafIndex.get(entry.getKey());
            }
        }
        return null;
    }

    // ── Layout ──────────────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        var w = getWidth();
        var h = getHeight();
        contentLayer.resizeRelocate(0, 0, w, h);
        overlayLayer.resizeRelocate(0, 0, w, h);

        if (root == null) return;
        if (currentAnimation != null && currentAnimation.getStatus() == Animation.Status.RUNNING) return;

        if (zoomed && zoomedRects != null) {
            // Recompute zoomed rect for current window size
            var contentBounds = new Rect(PADDING, PADDING, w - 2 * PADDING, h - 2 * PADDING);
            zoomedRects.put(zoomedPane.id(), contentBounds);
            applyRects(zoomedRects);
            return;
        }

        rebuildLayout();
        applyRects(currentRects);
    }

    private void rebuildLayout() {
        var w = getWidth();
        var h = getHeight();
        if (w <= 0 || h <= 0) return;

        currentRects.clear();
        leafIndex.clear();
        var bounds = new Rect(PADDING, PADDING, w - 2 * PADDING, h - 2 * PADDING);
        computeRects(root, bounds);
        computeDividers(bounds);
    }

    private void computeRects(SplitNode node, Rect bounds) {
        if (node instanceof LeafPane leaf) {
            currentRects.put(leaf.id(), bounds);
            leafIndex.put(leaf.id(), leaf);
        } else if (node instanceof Split split) {
            int n = split.children().size();
            double totalGutter = GUTTER * (n - 1);
            boolean horizontal = split.orientation() == Orientation.HORIZONTAL;
            double available = (horizontal ? bounds.w() : bounds.h()) - totalGutter;

            double offset = 0;
            for (int i = 0; i < n; i++) {
                double extent = available * split.weights().get(i);
                Rect childBounds;
                if (horizontal) {
                    childBounds = new Rect(bounds.x() + offset, bounds.y(), extent, bounds.h());
                } else {
                    childBounds = new Rect(bounds.x(), bounds.y() + offset, bounds.w(), extent);
                }
                computeRects(split.children().get(i), childBounds);
                offset += extent + GUTTER;
            }
        }
    }

    private void computeDividers(Rect bounds) {
        dividerSegments.clear();
        cornerJunctions.clear();
        collectDividers(root, bounds);
        // Find corner junctions where H and V dividers intersect
        for (var h : dividerSegments) {
            if (!h.horizontal()) continue;
            for (var v : dividerSegments) {
                if (v.horizontal()) continue;
                // H segment is horizontal (y range), V segment is vertical (x range)
                double hMidY = (h.y1() + h.y2()) / 2;
                double vMidX = (v.x1() + v.x2()) / 2;
                // Check if they cross
                if (vMidX >= h.x1() && vMidX <= h.x2() && hMidY >= v.y1() && hMidY <= v.y2()) {
                    cornerJunctions.add(new CornerJunction(vMidX, hMidY,
                        h.split(), h.leftIndex(), v.split(), v.leftIndex()));
                }
            }
        }
    }

    private void collectDividers(SplitNode node, Rect bounds) {
        if (!(node instanceof Split split)) return;
        int n = split.children().size();
        boolean horizontal = split.orientation() == Orientation.HORIZONTAL;
        double totalGutter = GUTTER * (n - 1);
        double available = (horizontal ? bounds.w() : bounds.h()) - totalGutter;

        double offset = 0;
        for (int i = 0; i < n; i++) {
            double extent = available * split.weights().get(i);
            Rect childBounds;
            if (horizontal) {
                childBounds = new Rect(bounds.x() + offset, bounds.y(), extent, bounds.h());
            } else {
                childBounds = new Rect(bounds.x(), bounds.y() + offset, bounds.w(), extent);
            }

            if (i < n - 1) {
                // Divider between child i and i+1
                if (horizontal) {
                    double dx = bounds.x() + offset + extent;
                    dividerSegments.add(new DividerSegment(
                        dx, bounds.y(), dx + GUTTER, bounds.bottom(),
                        split, i, false)); // vertical divider → horizontal=false
                } else {
                    double dy = bounds.y() + offset + extent;
                    dividerSegments.add(new DividerSegment(
                        bounds.x(), dy, bounds.right(), dy + GUTTER,
                        split, i, true)); // horizontal divider → horizontal=true
                }
            }

            collectDividers(split.children().get(i), childBounds);
            offset += extent + GUTTER;
        }
    }

    // ── Visual rebuild ──────────────────────────────────────────────────────────

    private void rebuildAll() {
        rebuildLayout();
        syncContentNodes();
        updatePastelOverlays();
        applyRects(currentRects);
    }

    private void syncContentNodes() {
        var leaves = allLeaves();
        var activeIds = new HashSet<PaneId>();

        int index = 0;
        for (var leaf : leaves) {
            activeIds.add(leaf.id());
            if (!paneWrappers.containsKey(leaf.id())) {
                var content = leaf.content();

                // Header bar — drag grip + title
                var titleLabel = new javafx.scene.control.Label();
                titleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.6); -fx-font-size: 11;");
                titleLabel.setMouseTransparent(true);
                if (content instanceof io.github.vlaaad.ghosttyfx.TerminalView tv) {
                    titleLabel.textProperty().bind(tv.titleProperty());
                } else {
                    titleLabel.setText(leaf.title());
                }
                var grip = new javafx.scene.control.Label("\u2261"); // ≡
                grip.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-size: 13;");
                grip.setMouseTransparent(true);
                var headerSpacer = new Region();
                HBox.setHgrow(headerSpacer, Priority.ALWAYS);
                var header = new HBox(4, titleLabel, headerSpacer, grip);
                header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                header.setPadding(new Insets(0, 6, 0, 6));
                header.setPrefHeight(HEADER_H);
                header.setMinHeight(HEADER_H);
                header.setMaxHeight(HEADER_H);
                header.setStyle("-fx-background-color: rgba(0,0,0,0.15); -fx-background-radius: " + PANE_RADIUS + " " + PANE_RADIUS + " 0 0;");
                header.setCursor(Cursor.OPEN_HAND);
                paneHeaders.put(leaf.id(), header);

                // Wire DnD on header
                final var leafRef = leaf;
                header.setOnMousePressed(e -> { startPaneDrag(leafRef, e); e.consume(); });
                header.setOnMouseDragged(e -> { doPaneDrag(e); e.consume(); });
                header.setOnMouseReleased(e -> { endPaneDrag(e); e.consume(); });
                header.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) { focusedPane.set(leafRef); toggleZoom(); e.consume(); }
                });

                // Layout: header on top, content fills rest
                var paneNode = new AnchorPane(header, content);
                AnchorPane.setTopAnchor(header, 0.0);
                AnchorPane.setLeftAnchor(header, 0.0);
                AnchorPane.setRightAnchor(header, 0.0);
                AnchorPane.setTopAnchor(content, HEADER_H);
                AnchorPane.setLeftAnchor(content, 0.0);
                AnchorPane.setRightAnchor(content, 0.0);
                AnchorPane.setBottomAnchor(content, 0.0);
                paneNode.setMinSize(0, 0);
                paneNode.setManaged(false);
                paneNode.setStyle(paneBackgroundCss);
                var clip = new Rectangle();
                clip.setArcWidth(PANE_RADIUS * 2);
                clip.setArcHeight(PANE_RADIUS * 2);
                paneNode.setClip(clip);

                // Click-to-focus
                paneNode.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                    focusedPane.set(leafRef);
                });

                paneWrappers.put(leaf.id(), paneNode);
                contentLayer.getChildren().add(paneNode);

                // Pastel overlay
                var pastel = new Rectangle();
                pastel.setArcWidth(PANE_RADIUS * 2);
                pastel.setArcHeight(PANE_RADIUS * 2);
                pastel.setMouseTransparent(true);
                pastelOverlays.put(leaf.id(), pastel);
                overlayLayer.getChildren().add(pastel);

                // Focus ring
                var ring = new Rectangle();
                ring.setFill(Color.TRANSPARENT);
                ring.setStroke(focusRingColor);
                ring.setStrokeWidth(FOCUS_RING_WIDTH);
                ring.setArcWidth(PANE_RADIUS * 2 + FOCUS_RING_WIDTH);
                ring.setArcHeight(PANE_RADIUS * 2 + FOCUS_RING_WIDTH);
                ring.setMouseTransparent(true);
                ring.setOpacity(0);
                focusRings.put(leaf.id(), ring);
                overlayLayer.getChildren().add(ring);
            }
            index++;
        }

        // Remove stale entries
        var staleIds = new HashSet<>(paneWrappers.keySet());
        staleIds.removeAll(activeIds);
        for (var id : staleIds) {
            var wrapper = paneWrappers.remove(id);
            if (wrapper != null) contentLayer.getChildren().remove(wrapper);
            paneHeaders.remove(id);
            var ring = focusRings.remove(id);
            if (ring != null) overlayLayer.getChildren().remove(ring);
            var pastel = pastelOverlays.remove(id);
            if (pastel != null) overlayLayer.getChildren().remove(pastel);
        }

        // Set initial focus if none
        if (focusedPane.get() == null && !leaves.isEmpty()) {
            focusedPane.set(leaves.getFirst());
        }
    }

    private void updatePastelOverlays() {
        if (!pastelTinting.get()) {
            pastelOverlays.values().forEach(r -> r.setFill(Color.TRANSPARENT));
            return;
        }
        var leaves = allLeaves();
        for (int i = 0; i < leaves.size(); i++) {
            var pastel = pastelOverlays.get(leaves.get(i).id());
            if (pastel != null) {
                var color = PASTEL_PALETTE[i % PASTEL_PALETTE.length];
                pastel.setFill(color.deriveColor(0, 1, 1, pastelOpacity));
            }
        }
    }

    private void applyRects(Map<PaneId, Rect> rects) {
        for (var entry : rects.entrySet()) {
            var id = entry.getKey();
            var r = entry.getValue();
            var wrapper = paneWrappers.get(id);
            if (wrapper != null) {
                wrapper.resizeRelocate(r.x(), r.y(), r.w(), r.h());
                // Update clip to match new size
                if (wrapper.getClip() instanceof Rectangle clip) {
                    clip.setWidth(r.w());
                    clip.setHeight(r.h());
                }
            }
            var ring = focusRings.get(id);
            if (ring != null) {
                ring.setX(r.x() - FOCUS_RING_WIDTH);
                ring.setY(r.y() - FOCUS_RING_WIDTH);
                ring.setWidth(r.w() + 2 * FOCUS_RING_WIDTH);
                ring.setHeight(r.h() + 2 * FOCUS_RING_WIDTH);
            }
            var pastel = pastelOverlays.get(id);
            if (pastel != null) {
                pastel.setX(r.x());
                pastel.setY(r.y());
                pastel.setWidth(r.w());
                pastel.setHeight(r.h());
            }
        }
    }

    // ── Focus ring ──────────────────────────────────────────────────────────────

    private void updateFocusRings(LeafPane oldPane, LeafPane newPane) {
        if (oldPane != null) {
            var ring = focusRings.get(oldPane.id());
            if (ring != null) fadeRing(ring, false);
        }
        if (newPane != null) {
            var ring = focusRings.get(newPane.id());
            if (ring != null) fadeRing(ring, true);
        }
    }

    private void fadeRing(Rectangle ring, boolean in) {
        if (!animationsEnabled.get()) {
            ring.setOpacity(in ? 1.0 : 0.0);
            return;
        }
        var ft = new FadeTransition(FOCUS_DURATION, ring);
        ft.setToValue(in ? 1.0 : 0.0);
        ft.play();
    }

    // ── Mouse handling (divider & corner drag) ──────────────────────────────────

    private void handleMouseMoved(MouseEvent e) {
        if (zoomed) { setCursor(Cursor.DEFAULT); return; }
        double mx = e.getX(), my = e.getY();

        // Check corner junctions first
        for (var cj : cornerJunctions) {
            if (Math.abs(mx - cj.cx()) < CORNER_HIT_SIZE / 2 &&
                Math.abs(my - cj.cy()) < CORNER_HIT_SIZE / 2) {
                setCursor(Cursor.MOVE);
                return;
            }
        }
        // Check divider segments
        for (var seg : dividerSegments) {
            if (hitTestDivider(seg, mx, my)) {
                setCursor(seg.horizontal() ? Cursor.V_RESIZE : Cursor.H_RESIZE);
                return;
            }
        }
        setCursor(Cursor.DEFAULT);
    }

    private void handleFocusFollowsMouse(MouseEvent e) {
        if (!focusFollowsMouse.get() || zoomed) return;
        double mx = e.getX(), my = e.getY();
        var leaf = leafAt(mx, my);
        if (leaf != null && leaf != focusedPane.get()) {
            focusedPane.set(leaf);
            if (leaf.content() != null) leaf.content().requestFocus();
        }
    }

    private void handleMousePressed(MouseEvent e) {
        if (zoomed) return;
        double mx = e.getX(), my = e.getY();

        // Check if we're on a divider or corner
        DividerSegment hitSeg = null;
        CornerJunction hitCorner = null;
        for (var cj : cornerJunctions) {
            if (Math.abs(mx - cj.cx()) < CORNER_HIT_SIZE / 2 &&
                Math.abs(my - cj.cy()) < CORNER_HIT_SIZE / 2) {
                hitCorner = cj;
                break;
            }
        }
        if (hitCorner == null) {
            for (var seg : dividerSegments) {
                if (hitTestDivider(seg, mx, my)) { hitSeg = seg; break; }
            }
        }

        // Double-click on divider → equalize (skip drag)
        if (e.getClickCount() == 2 && hitSeg != null) {
            draggingDivider = null; // cancel any drag from the first click
            equalize(hitSeg.split());
            e.consume();
            return;
        }

        // Single click: start drag
        if (hitCorner != null) {
            startCornerDrag(hitCorner, mx, my);
            e.consume();
            return;
        }
        if (hitSeg != null) {
            startDividerDrag(hitSeg, mx, my);
            e.consume();
            return;
        }
        // Focus
        var leaf = leafAt(mx, my);
        if (leaf != null) focusedPane.set(leaf);
    }

    private void handleMouseDragged(MouseEvent e) {
        double mx = e.getX(), my = e.getY();
        if (draggingDivider != null) {
            doDividerDrag(draggingDivider, mx, my);
            e.consume();
        } else if (draggingCorner != null) {
            doCornerDrag(draggingCorner, mx, my);
            e.consume();
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        if (draggingDivider != null || draggingCorner != null) {
            draggingDivider = null;
            draggingCorner = null;
            e.consume();
        }
    }

    private boolean hitTestDivider(DividerSegment seg, double mx, double my) {
        return mx >= seg.x1() - DIVIDER_OVERLAP && mx <= seg.x2() + DIVIDER_OVERLAP
            && my >= seg.y1() - DIVIDER_OVERLAP && my <= seg.y2() + DIVIDER_OVERLAP;
    }

    // ── Divider drag ────────────────────────────────────────────────────────────

    private void startDividerDrag(DividerSegment seg, double mx, double my) {
        draggingDivider = seg;
        preDragRects = new LinkedHashMap<>(currentRects);
        dragStartX = mx;
        dragStartY = my;
        var split = seg.split();
        int i = seg.leftIndex();
        dragStartWeightA = split.weights().get(i);
        dragStartWeightB = split.weights().get(i + 1);
    }

    private void doDividerDrag(DividerSegment seg, double mx, double my) {
        var split = seg.split();
        int i = seg.leftIndex();
        boolean horizontal = split.orientation() == Orientation.HORIZONTAL;

        double delta = horizontal ? (mx - dragStartX) : (my - dragStartY);
        double parentExtent = computeParentExtent(split);
        if (parentExtent <= 0) return;

        double dWeight = delta / parentExtent;
        double newA = Math.max(minWeight(split, parentExtent), dragStartWeightA + dWeight);
        double newB = Math.max(minWeight(split, parentExtent), dragStartWeightB - dWeight);

        // Clamp
        double total = dragStartWeightA + dragStartWeightB;
        if (newA + newB > total) {
            if (dWeight > 0) newB = total - newA;
            else newA = total - newB;
        }

        split.weights().set(i, newA);
        split.weights().set(i + 1, newB);
        rebuildLayout();
        applyRects(currentRects);
    }

    // ── Corner drag ─────────────────────────────────────────────────────────────

    private void startCornerDrag(CornerJunction cj, double mx, double my) {
        draggingCorner = cj;
        dragStartX = mx;
        dragStartY = my;
        dragStartWeightA = cj.hSplit().weights().get(cj.hLeftIndex());
        dragStartWeightB = cj.hSplit().weights().get(cj.hLeftIndex() + 1);
        dragStartWeightC = cj.vSplit().weights().get(cj.vLeftIndex());
        dragStartWeightD = cj.vSplit().weights().get(cj.vLeftIndex() + 1);
    }

    private void doCornerDrag(CornerJunction cj, double mx, double my) {
        // Horizontal axis (the hSplit controls vertical placement → drag uses dy)
        {
            var split = cj.hSplit();
            int i = cj.hLeftIndex();
            double delta = my - dragStartY;
            double parentExtent = computeParentExtent(split);
            if (parentExtent > 0) {
                double dWeight = delta / parentExtent;
                double newA = Math.max(minWeight(split, parentExtent), dragStartWeightA + dWeight);
                double newB = Math.max(minWeight(split, parentExtent), dragStartWeightB - dWeight);
                double total = dragStartWeightA + dragStartWeightB;
                if (newA + newB > total) { if (dWeight > 0) newB = total - newA; else newA = total - newB; }
                split.weights().set(i, newA);
                split.weights().set(i + 1, newB);
            }
        }
        // Vertical axis (the vSplit controls horizontal placement → drag uses dx)
        {
            var split = cj.vSplit();
            int i = cj.vLeftIndex();
            double delta = mx - dragStartX;
            double parentExtent = computeParentExtent(split);
            if (parentExtent > 0) {
                double dWeight = delta / parentExtent;
                double newA = Math.max(minWeight(split, parentExtent), dragStartWeightC + dWeight);
                double newB = Math.max(minWeight(split, parentExtent), dragStartWeightD - dWeight);
                double total = dragStartWeightC + dragStartWeightD;
                if (newA + newB > total) { if (dWeight > 0) newB = total - newA; else newA = total - newB; }
                split.weights().set(i, newA);
                split.weights().set(i + 1, newB);
            }
        }
        rebuildLayout();
        applyRects(currentRects);
    }

    private double computeParentExtent(Split split) {
        // Find the rect that encompasses all children of this split
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        boolean horizontal = split.orientation() == Orientation.HORIZONTAL;
        for (var child : split.children()) {
            var childRects = rectsOf(child);
            for (var r : childRects) {
                if (horizontal) { min = Math.min(min, r.x()); max = Math.max(max, r.right()); }
                else { min = Math.min(min, r.y()); max = Math.max(max, r.bottom()); }
            }
        }
        return (max - min) + GUTTER * (split.children().size() - 1);
    }

    private List<Rect> rectsOf(SplitNode node) {
        var result = new ArrayList<Rect>();
        if (node instanceof LeafPane leaf) {
            var r = currentRects.get(leaf.id());
            if (r != null) result.add(r);
        } else if (node instanceof Split split) {
            for (var child : split.children()) result.addAll(rectsOf(child));
        }
        return result;
    }

    private double minWeight(Split split, double parentExtent) {
        boolean horizontal = split.orientation() == Orientation.HORIZONTAL;
        double minPx = horizontal ? MIN_PANE_W : MIN_PANE_H;
        return minPx / parentExtent;
    }

    // ── Pane DnD ────────────────────────────────────────────────────────────────

    private void startPaneDrag(LeafPane leaf, MouseEvent e) {
        dragSource = leaf;
        dragOriginX = e.getScreenX();
        dragOriginY = e.getScreenY();
        dragging = false;
        focusedPane.set(leaf);
    }

    private void doPaneDrag(MouseEvent e) {
        if (dragSource == null) return;
        double dx = e.getScreenX() - dragOriginX;
        double dy = e.getScreenY() - dragOriginY;

        if (!dragging) {
            if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) return;
            dragging = true;
            // Create drag chip
            var title = dragSource.title();
            if (dragSource.content() instanceof io.github.vlaaad.ghosttyfx.TerminalView tv && tv.getTitle() != null) {
                title = tv.getTitle();
            }
            if (title != null && title.length() > 15) title = title.substring(0, 12) + "\u2026";
            if (title == null || title.isEmpty()) title = "Terminal";
            var chipLabel = new javafx.scene.control.Label("\u2261 " + title);
            chipLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11;");
            dragChip = new HBox(chipLabel);
            dragChip.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-background-radius: 6; -fx-padding: 4 10;");
            dragChip.setManaged(false);
            dragChip.setMouseTransparent(true);
            dragChip.setEffect(new javafx.scene.effect.DropShadow(8, Color.rgb(0, 0, 0, 0.4)));
            overlayLayer.getChildren().add(dragChip);

            // Dim source pane
            var srcWrapper = paneWrappers.get(dragSource.id());
            if (srcWrapper != null) srcWrapper.setOpacity(0.5);

            // Create drop highlight
            dropHighlight = new Rectangle();
            dropHighlight.setFill(Color.web("#3B82F6", 0.15));
            dropHighlight.setStroke(Color.web("#3B82F6", 0.6));
            dropHighlight.setStrokeWidth(2);
            dropHighlight.setArcWidth(PANE_RADIUS * 2);
            dropHighlight.setArcHeight(PANE_RADIUS * 2);
            dropHighlight.setMouseTransparent(true);
            dropHighlight.setVisible(false);
            overlayLayer.getChildren().add(dropHighlight);
        }

        // Position chip near cursor (in this workspace's coordinates)
        var local = screenToLocal(e.getScreenX(), e.getScreenY());
        if (local != null && dragChip != null) {
            dragChip.autosize();
            dragChip.relocate(local.getX() + 12, local.getY() + 12);
        }

        // Find which workspace the cursor is over (could be another window)
        SplitWorkspace targetWs = null;
        javafx.geometry.Point2D targetLocal = null;
        for (var ws : ALL_WORKSPACES) {
            var pt = ws.screenToLocal(e.getScreenX(), e.getScreenY());
            if (pt != null && pt.getX() >= 0 && pt.getY() >= 0
                    && pt.getX() <= ws.getWidth() && pt.getY() <= ws.getHeight()) {
                targetWs = ws;
                targetLocal = pt;
                break;
            }
        }

        // Hit test for drop target
        if (targetWs != null && targetLocal != null) {
            var target = targetWs.leafAt(targetLocal.getX(), targetLocal.getY());
            if (target != null && target != dragSource) {
                dropTarget = target;
                // Store which workspace will receive the drop
                dragTargetWorkspace = targetWs;
                var r = targetWs.currentRects.get(target.id());
                if (r != null) {
                    dropZone = computeDropZone(targetLocal.getX(), targetLocal.getY(), r);
                    // Show highlight in the TARGET workspace's overlay
                    ensureDropHighlightIn(targetWs);
                    updateDropHighlight(r, dropZone);
                }
            } else {
                dropTarget = null;
                dragTargetWorkspace = null;
                dropZone = null;
                if (dropHighlight != null) dropHighlight.setVisible(false);
            }
        } else {
            dropTarget = null;
            dragTargetWorkspace = null;
            dropZone = null;
            if (dropHighlight != null) dropHighlight.setVisible(false);
        }
    }

    private void endPaneDrag(MouseEvent e) {
        if (dragSource == null) return;

        // Restore source opacity
        var srcWrapper = paneWrappers.get(dragSource.id());
        if (srcWrapper != null) srcWrapper.setOpacity(1.0);

        // Remove chip and highlight (highlight may be in another workspace's overlay)
        if (dragChip != null) { overlayLayer.getChildren().remove(dragChip); dragChip = null; }
        if (dropHighlight != null) {
            if (dropHighlight.getParent() instanceof Pane p) p.getChildren().remove(dropHighlight);
            dropHighlight = null;
        }

        // Perform drop
        if (dragging && dropTarget != null && dropZone != null) {
            var destWs = dragTargetWorkspace != null ? dragTargetWorkspace : this;
            if (destWs == this) {
                // Same-workspace drop
                captureRectsForAnimation();
                if (dropZone == DropZone.CENTER_SWAP) {
                    swapPanes(dragSource, dropTarget);
                } else {
                    movePane(dragSource, dropTarget, dropZone);
                }
                rebuildAll();
                animateReflow(REFLOW_DURATION);
            } else {
                // Cross-window drop: detach from source, insert in destination
                crossWindowMove(dragSource, dropTarget, dropZone, destWs);
            }
        }

        dragSource = null;
        dropTarget = null;
        dropZone = null;
        dragTargetWorkspace = null;
        dragging = false;
    }

    /** Move a pane from this workspace to a different workspace. */
    private void crossWindowMove(LeafPane source, LeafPane target, DropZone zone, SplitWorkspace destWs) {
        // Detach source from this workspace's tree
        var sourceParent = findParent(root, source);
        if (sourceParent != null) {
            int idx = indexOf(sourceParent, source);
            sourceParent.children().remove(idx);
            sourceParent.weights().remove(idx);
            sourceParent.normalizeWeights();
            if (sourceParent.children().size() == 1) {
                var remaining = sourceParent.children().getFirst();
                var gp = findParent(root, sourceParent);
                if (gp != null) {
                    gp.children().set(indexOf(gp, sourceParent), remaining);
                } else {
                    root = remaining;
                }
            }
        } else if (root == source) {
            // Source was the only pane — workspace becomes empty
            root = null;
        }

        // Remove from source workspace's scene graph
        var wrapper = paneWrappers.remove(source.id());
        if (wrapper != null) contentLayer.getChildren().remove(wrapper);
        paneHeaders.remove(source.id());
        var ring = focusRings.remove(source.id());
        if (ring != null) overlayLayer.getChildren().remove(ring);
        var pastel = pastelOverlays.remove(source.id());
        if (pastel != null) overlayLayer.getChildren().remove(pastel);
        leafIndex.remove(source.id());

        // Rebuild source workspace
        captureRectsForAnimation();
        if (root != null) {
            rebuildAll();
            animateReflow(REFLOW_DURATION);
            // Move focus in source
            if (focusedPane.get() == source) {
                var leaves = allLeaves();
                focusedPane.set(leaves.isEmpty() ? null : leaves.getFirst());
            }
        } else {
            // Workspace is now empty
            if (onEmpty != null) onEmpty.run();
        }

        // Insert into destination workspace
        destWs.captureRectsForAnimation();
        if (zone == DropZone.CENTER_SWAP) {
            // Swap: move target back to source workspace, put source where target was
            // For simplicity, just insert adjacent instead of true swap across windows
            destWs.insertRelative(target, source, Side.RIGHT, false);
        } else {
            var side = switch (zone) {
                case LEFT -> Side.LEFT;
                case RIGHT -> Side.RIGHT;
                case TOP -> Side.TOP;
                case BOTTOM -> Side.BOTTOM;
                default -> Side.RIGHT;
            };
            destWs.insertRelative(target, source, side, false);
        }
        destWs.rebuildAll();
        destWs.focusedPane.set(source);
        destWs.animateReflow(REFLOW_DURATION);
    }

    private void ensureDropHighlightIn(SplitWorkspace ws) {
        if (dropHighlight != null && dropHighlight.getParent() != ws.overlayLayer) {
            if (dropHighlight.getParent() instanceof Pane p) p.getChildren().remove(dropHighlight);
            ws.overlayLayer.getChildren().add(dropHighlight);
        }
    }

    private void cancelPaneDrag() {
        var srcWrapper = paneWrappers.get(dragSource.id());
        if (srcWrapper != null) srcWrapper.setOpacity(1.0);
        if (dragChip != null) { overlayLayer.getChildren().remove(dragChip); dragChip = null; }
        if (dropHighlight != null) {
            if (dropHighlight.getParent() instanceof Pane p) p.getChildren().remove(dropHighlight);
            dropHighlight = null;
        }
        dragSource = null;
        dropTarget = null;
        dropZone = null;
        dragging = false;
    }

    private DropZone computeDropZone(double mx, double my, Rect r) {
        // Relative position within the target pane (0–1)
        double rx = (mx - r.x()) / r.w();
        double ry = (my - r.y()) / r.h();

        // Center zone: inner 50%
        if (rx > 0.25 && rx < 0.75 && ry > 0.25 && ry < 0.75) return DropZone.CENTER_SWAP;

        // Edge bands: pick the nearest edge
        double dLeft = rx, dRight = 1 - rx, dTop = ry, dBottom = 1 - ry;
        double min = Math.min(Math.min(dLeft, dRight), Math.min(dTop, dBottom));
        if (min == dLeft) return DropZone.LEFT;
        if (min == dRight) return DropZone.RIGHT;
        if (min == dTop) return DropZone.TOP;
        return DropZone.BOTTOM;
    }

    private void updateDropHighlight(Rect r, DropZone zone) {
        if (dropHighlight == null) return;
        dropHighlight.setVisible(true);
        switch (zone) {
            case CENTER_SWAP -> {
                dropHighlight.setX(r.x()); dropHighlight.setY(r.y());
                dropHighlight.setWidth(r.w()); dropHighlight.setHeight(r.h());
            }
            case LEFT -> {
                dropHighlight.setX(r.x()); dropHighlight.setY(r.y());
                dropHighlight.setWidth(r.w() / 2); dropHighlight.setHeight(r.h());
            }
            case RIGHT -> {
                dropHighlight.setX(r.x() + r.w() / 2); dropHighlight.setY(r.y());
                dropHighlight.setWidth(r.w() / 2); dropHighlight.setHeight(r.h());
            }
            case TOP -> {
                dropHighlight.setX(r.x()); dropHighlight.setY(r.y());
                dropHighlight.setWidth(r.w()); dropHighlight.setHeight(r.h() / 2);
            }
            case BOTTOM -> {
                dropHighlight.setX(r.x()); dropHighlight.setY(r.y() + r.h() / 2);
                dropHighlight.setWidth(r.w()); dropHighlight.setHeight(r.h() / 2);
            }
        }
    }

    /** Swap two leaf panes in the tree. */
    private void swapPanes(LeafPane a, LeafPane b) {
        var parentA = findParent(root, a);
        var parentB = findParent(root, b);
        if (parentA != null && parentB != null) {
            int idxA = indexOf(parentA, a);
            int idxB = indexOf(parentB, b);
            parentA.children().set(idxA, b);
            parentB.children().set(idxB, a);
        } else if (parentA != null && root == b) {
            int idxA = indexOf(parentA, a);
            parentA.children().set(idxA, b);
            root = a;
        } else if (parentB != null && root == a) {
            int idxB = indexOf(parentB, b);
            parentB.children().set(idxB, a);
            root = b;
        }
    }

    /** Move source pane to be adjacent to target in the given drop zone direction. */
    private void movePane(LeafPane source, LeafPane target, DropZone zone) {
        // Remove source from its current position
        var sourceParent = findParent(root, source);
        if (sourceParent != null) {
            int idx = indexOf(sourceParent, source);
            sourceParent.children().remove(idx);
            sourceParent.weights().remove(idx);
            sourceParent.normalizeWeights();
            // Collapse single-child splits
            if (sourceParent.children().size() == 1) {
                var remaining = sourceParent.children().getFirst();
                var gp = findParent(root, sourceParent);
                if (gp != null) {
                    int pidx = indexOf(gp, sourceParent);
                    gp.children().set(pidx, remaining);
                } else {
                    root = remaining;
                }
            }
        }
        // Insert relative to target
        var side = switch (zone) {
            case LEFT -> Side.LEFT;
            case RIGHT -> Side.RIGHT;
            case TOP -> Side.TOP;
            case BOTTOM -> Side.BOTTOM;
            default -> Side.RIGHT;
        };
        insertRelative(target, source, side, false);
    }

    // ── Double-click to equalize ────────────────────────────────────────────────

    // ── Keyboard navigation ────────────────────────────────────────────────────

    /** Cycle focus to the next pane (wraps around). */
    public void focusNext() {
        cycleFocus(1);
    }

    /** Cycle focus to the previous pane (wraps around). */
    public void focusPrevious() {
        cycleFocus(-1);
    }

    private void cycleFocus(int delta) {
        var leaves = allLeaves();
        if (leaves.isEmpty()) return;
        var current = focusedPane.get();
        int idx = current != null ? leaves.indexOf(current) : -1;
        int next = (idx + delta + leaves.size()) % leaves.size();
        focusedPane.set(leaves.get(next));
    }

    /** Resize the focused pane by adjusting its weight in the given direction. */
    public void resizeFocused(Side side, double pxDelta) {
        var focused = focusedPane.get();
        if (focused == null || zoomed) return;
        var parent = findParent(root, focused);
        if (parent == null) return;

        boolean horizontal = (side == Side.LEFT || side == Side.RIGHT);
        boolean shrink = (side == Side.LEFT || side == Side.TOP);

        // Only resize if parent orientation matches the direction
        boolean parentHorizontal = parent.orientation() == Orientation.HORIZONTAL;
        if (horizontal != parentHorizontal) {
            // Try grandparent
            var grandparent = findParent(root, parent);
            if (grandparent == null) return;
            boolean gpHorizontal = grandparent.orientation() == Orientation.HORIZONTAL;
            if (horizontal != gpHorizontal) return;
            // Resize at grandparent level
            int idx = indexOf(grandparent, parent);
            adjustWeight(grandparent, idx, shrink ? -pxDelta : pxDelta);
        } else {
            int idx = indexOf(parent, focused);
            adjustWeight(parent, idx, shrink ? -pxDelta : pxDelta);
        }
    }

    private void adjustWeight(Split split, int idx, double pxDelta) {
        double parentExtent = computeParentExtent(split);
        if (parentExtent <= 0) return;
        double dWeight = pxDelta / parentExtent;

        // Find a neighbor to take/give weight
        int neighbor = (dWeight > 0 && idx + 1 < split.children().size()) ? idx + 1
                     : (dWeight < 0 && idx > 0) ? idx - 1 : -1;
        if (neighbor < 0) return;

        double wA = split.weights().get(idx) + Math.abs(dWeight);
        double wB = split.weights().get(neighbor) - Math.abs(dWeight);
        if (dWeight < 0) { wA = split.weights().get(idx) - Math.abs(dWeight); wB = split.weights().get(neighbor) + Math.abs(dWeight); }

        double minW = minWeight(split, parentExtent);
        if (wA < minW || wB < minW) return;

        captureRectsForAnimation();
        split.weights().set(idx, wA);
        split.weights().set(neighbor, wB);
        rebuildLayout();
        animateReflow(RESIZE_ANIM_DURATION);
    }

    /** Equalize weights of a split's children (animated). */
    public void equalize(Split split) {
        int n = split.children().size();
        // Use pre-drag rects if available (double-click after a tiny drag from first click)
        animatingFrom.clear();
        animatingFrom.putAll(preDragRects != null ? preDragRects : currentRects);
        preDragRects = null;
        for (int i = 0; i < n; i++) split.weights().set(i, 1.0 / n);
        rebuildLayout();
        animateReflow(RESIZE_ANIM_DURATION);
    }

    // ── Zoom / Unzoom ───────────────────────────────────────────────────────────

    private void zoom() {
        var pane = focusedPane.get();
        if (pane == null || zoomed) return;
        zoomed = true;
        zoomedPane = pane;
        preZoomRects = new LinkedHashMap<>(currentRects);

        var contentBounds = new Rect(PADDING, PADDING, getWidth() - 2 * PADDING, getHeight() - 2 * PADDING);
        var targetRects = new LinkedHashMap<PaneId, Rect>();

        // Zoomed pane fills the workspace
        targetRects.put(pane.id(), contentBounds);

        // Other panes slide off-screen toward their nearest edge
        for (var entry : preZoomRects.entrySet()) {
            if (entry.getKey().equals(pane.id())) continue;
            var r = entry.getValue();
            var exitRect = computeExitRect(r, contentBounds);
            targetRects.put(entry.getKey(), exitRect);
        }

        zoomedRects = targetRects;
        animateToRects(targetRects, ZOOM_DURATION, () -> {
            // Hide non-zoomed panes
            for (var id : targetRects.keySet()) {
                if (!id.equals(pane.id())) {
                    var wrapper = paneWrappers.get(id);
                    if (wrapper != null) wrapper.setVisible(false);
                    var pastel = pastelOverlays.get(id);
                    if (pastel != null) pastel.setVisible(false);
                    var ring = focusRings.get(id);
                    if (ring != null) ring.setVisible(false);
                }
            }
        });
    }

    private void unzoom() {
        if (!zoomed || preZoomRects == null) return;

        // Show all panes at their exit positions
        for (var id : preZoomRects.keySet()) {
            var wrapper = paneWrappers.get(id);
            if (wrapper != null) wrapper.setVisible(true);
            var pastel = pastelOverlays.get(id);
            if (pastel != null) pastel.setVisible(true);
            var ring = focusRings.get(id);
            if (ring != null) ring.setVisible(true);
        }

        // Capture zoomed positions as animation source
        animatingFrom.clear();
        animatingFrom.putAll(zoomedRects);
        // Recompute layout to get correct target positions
        rebuildLayout();
        // Animate from zoomed positions to normal layout
        // Keep zoomed=true during animation so layoutChildren doesn't interfere
        animateToRects(currentRects, ZOOM_DURATION, () -> {
            zoomed = false;
            zoomedPane = null;
            preZoomRects = null;
            zoomedRects = null;
        });
    }

    private Rect computeExitRect(Rect paneRect, Rect contentBounds) {
        // Distance to each edge
        double dLeft = paneRect.x() - contentBounds.x();
        double dRight = contentBounds.right() - paneRect.right();
        double dTop = paneRect.y() - contentBounds.y();
        double dBottom = contentBounds.bottom() - paneRect.bottom();

        // Touching (within 1px) edges get priority
        boolean touchLeft = dLeft < 1;
        boolean touchRight = dRight < 1;
        boolean touchTop = dTop < 1;
        boolean touchBottom = dBottom < 1;

        record EdgeDist(String edge, double dist, boolean touching) {}
        var edges = List.of(
            new EdgeDist("left", dLeft, touchLeft),
            new EdgeDist("right", dRight, touchRight),
            new EdgeDist("top", dTop, touchTop),
            new EdgeDist("bottom", dBottom, touchBottom)
        );

        // Pick: touching edges first (nearest among them), then nearest non-touching
        var touching = edges.stream().filter(EdgeDist::touching).toList();
        EdgeDist best;
        if (!touching.isEmpty()) {
            best = touching.stream().min(Comparator.comparingDouble(EdgeDist::dist)).orElseThrow();
        } else {
            best = edges.stream().min(Comparator.comparingDouble(EdgeDist::dist)).orElseThrow();
        }

        return switch (best.edge()) {
            case "left" -> new Rect(-paneRect.w() - 20, paneRect.y(), paneRect.w(), paneRect.h());
            case "right" -> new Rect(contentBounds.right() + 20, paneRect.y(), paneRect.w(), paneRect.h());
            case "top" -> new Rect(paneRect.x(), -paneRect.h() - 20, paneRect.w(), paneRect.h());
            case "bottom" -> new Rect(paneRect.x(), contentBounds.bottom() + 20, paneRect.w(), paneRect.h());
            default -> paneRect;
        };
    }

    // ── Animation engine ────────────────────────────────────────────────────────

    private void captureRectsForAnimation() {
        animatingFrom.clear();
        animatingFrom.putAll(currentRects);
    }

    private void animateReflow(Duration duration) {
        animateToRects(currentRects, duration, null);
    }

    private void animateToRects(Map<PaneId, Rect> targets, Duration duration, Runnable onFinished) {
        if (currentAnimation != null) currentAnimation.stop();

        var from = new LinkedHashMap<>(animatingFrom.isEmpty() ? currentRects : animatingFrom);
        animatingFrom.clear();

        if (from.equals(targets) || !animationsEnabled.get()) {
            applyRects(targets);
            if (onFinished != null) onFinished.run();
            return;
        }

        // Animate a single progress property 0→1; on each change, interpolate and apply
        var progress = new SimpleDoubleProperty(0);
        progress.addListener((_, _, t) -> {
            var interpolated = new LinkedHashMap<PaneId, Rect>();
            for (var entry : targets.entrySet()) {
                var target = entry.getValue();
                var source = from.getOrDefault(entry.getKey(), target);
                interpolated.put(entry.getKey(), lerp(source, target, t.doubleValue()));
            }
            applyRects(interpolated);
        });

        var kf = new KeyFrame(duration, new KeyValue(progress, 1.0, EASE_IN_OUT));
        currentAnimation = new Timeline(kf);
        currentAnimation.setOnFinished(_ -> {
            applyRects(targets);
            if (onFinished != null) onFinished.run();
        });
        currentAnimation.play();
    }

    private Rect lerp(Rect a, Rect b, double t) {
        return new Rect(
            a.x() + (b.x() - a.x()) * t,
            a.y() + (b.y() - a.y()) * t,
            a.w() + (b.w() - a.w()) * t,
            a.h() + (b.h() - a.h()) * t
        );
    }

    // ── Static demo layout (7 panes) ────────────────────────────────────────────

    /**
     * Creates the reference 7-pane demo layout from the design doc §3.3.
     * Each pane gets a simple colored Region as placeholder content.
     */
    public static SplitWorkspace createDemo() {
        var ws = new SplitWorkspace();
        ws.setContentFactory(() -> {
            var r = new Region();
            r.setStyle("-fx-background-color: transparent;");
            return r;
        });

        // Build the tree per §3.3
        var pane1 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 1");
        var pane7 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 7");
        var pane2 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 2");
        var pane5 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 5");
        var pane6 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 6");
        var pane4 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 4");
        var pane3 = new LeafPane(PaneId.next(), ws.getContentFactory().get(), "Pane 3");

        var leftCol = new Split(Orientation.VERTICAL,
            List.of(pane1, pane7), List.of(0.55, 0.45));
        var topRow = new Split(Orientation.HORIZONTAL,
            List.of(pane2, pane5), List.of(0.5, 0.5));
        var midRow = new Split(Orientation.HORIZONTAL,
            List.of(pane6, pane4), List.of(0.5, 0.5));
        var rightRegion = new Split(Orientation.VERTICAL,
            List.of(topRow, midRow, pane3), List.of(0.33, 0.33, 0.34));
        var root = new Split(Orientation.HORIZONTAL,
            List.of(leftCol, rightRegion), List.of(0.34, 0.66));

        ws.setRoot(root);
        return ws;
    }

    /**
     * Creates a workspace with a single leaf pane.
     */
    public static SplitWorkspace createSingle(Supplier<Node> contentFactory) {
        var ws = new SplitWorkspace();
        ws.setContentFactory(contentFactory);
        var content = contentFactory.get();
        if (content != null) {
            var leaf = new LeafPane(PaneId.next(), content, "");
            ws.setRoot(leaf);
        }
        return ws;
    }
}
