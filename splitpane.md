# Tiling Split-Pane Terminal — Design Document (JavaFX)

**Status:** Draft for implementation
**Source material:** Two screen recordings of Mitchell Hashimoto's "SuperSplit" split-layout framework demos (macOS). This document specifies the observed layout, chrome, and interactions precisely enough for an agent to implement the equivalent in a JavaFX application where each pane hosts a terminal widget.

---

## 1. Purpose & scope

Build a tiling split-pane workspace component (`SplitWorkspace`) for a JavaFX terminal application. Every leaf pane hosts one terminal session. The component must support:

1. Recursive horizontal/vertical splits with weighted sizing.
2. Click-to-focus with a visible focus ring.
3. Toolbar-driven pane creation (insert left/right/up/down), pane close, zoom toggle, layout reset, and new window.
4. Divider dragging (single axis) **and corner dragging at divider junctions (both axes simultaneously)**.
5. Zoom/unzoom of the focused pane, with non-focused panes **animating out toward their nearest window edge** (preferring an edge they already touch) and animating back on unzoom.
6. Pane drag & drop via the pane header: floating drag chip, live drop-target highlight, animated reflow on drop — **including cross-window drag & drop**.
7. All geometry changes animated; full accessibility integration (screen reader equivalent of the original's VoiceOver support).

Out of scope here: terminal emulation itself, session management, persistence format details.

---

## 2. Observed reference behavior (what the videos show)

**Video A (≈27 s, "zoom/unzoom"):**
- A single window titled "SuperSplit Example" with 7 pastel-colored panes in a tiled layout (layout reproduced in §3.3).
- Clicking a pane gives it a blue rounded focus ring (observed moving between Pane 4 → Pane 1 → Pane 6).
- A vertical divider between two panes is dragged with a ◄|► resize cursor.
- Zoom: the focused pane (Pane 6, an interior pane) animates to fill the entire window content area. Every other pane slides out toward a window edge and disappears offscreen. Per the author: each pane picks its **nearest window edge**, but an edge the pane is **already touching wins over pure distance**.
- Unzoom: the same panes animate back in from those edges to their original tiles.

**Video B (≈13 s, "drag & drop + corner drag + cross-window"):**
- Dragging a pane by its header shows a small floating chip near the cursor with the pane's icon + truncated title ("Pan…"). The hovered drop-target pane highlights blue. On drop, the layout reflows with animation (Pane 2 and Pane 7 effectively swapped columns).
- A drag at the junction where three panes meet resizes **both axes at once** (corner drag).
- Two workspace windows overlap; a pane is dragged from one window and dropped into the other. The destination shows a blue insertion highlight; the pane transfers and both layouts animate to their new states.

**Video C (≈6 s, original "work-in-progress" post):** the author states the framework targets *nearly all Apple platforms (macOS, iOS, …)* — i.e., the interaction model is designed to be touch-capable, not mouse-only. Header-position tracking of the clip shows two behaviors:
- **Animated swap:** two vertically stacked panes exchange places, both headers traveling their direct paths simultaneously and crossing mid-flight (~300 ms). Swap is a first-class, animated operation — not a remove+insert reflow.
- **Animated weight change:** a pane's height redistributes among its column siblings as a smooth ~300 ms transition with no continuous drag tracking — programmatic/command-driven resizes animate, distinct from live divider drags.

---

## 3. Layout model

### 3.1 Split tree

The workspace is a tree:

```
Node := Split | Leaf
Split := { orientation: HORIZONTAL | VERTICAL,
           children: List<Node>   (n >= 2),
           weights:  List<double> (sum = 1.0, parallel to children) }
Leaf  := { paneId, title, icon, content (terminal view) }
```

- `HORIZONTAL` split lays children left→right; `VERTICAL` lays children top→bottom.
- N-ary children (not just binary) so that three columns are one `HORIZONTAL` node with three weights — this makes divider junctions and corner drags natural.
- Weights are the single source of truth for sizes. Pixel rects are derived at layout time: `child.extent = (parent.extent - totalGutter) * weight`.

### 3.2 Geometry constants

| Token | Value | Notes |
|---|---|---|
| `GUTTER` | 10 px | Gap between sibling panes (also the divider hit area) |
| `PADDING` | 10 px | Inset between window content edge and outer panes |
| `PANE_RADIUS` | 8 px | Pane corner radius |
| `HEADER_H` | 26 px | Pane header height |
| `MIN_PANE_W/H` | 120 / 80 px | Clamp for divider/corner drags |
| `FOCUS_RING` | 2 px, `#3B82F6` | Rounded, drawn on pane border |
| `DIVIDER_HIT` | full gutter width, plus 3 px overlap into each pane | Forgiving hit zone |
| `CORNER_HIT` | 14×14 px square centered on divider junctions | Dual-axis drag zone |

### 3.3 Reference layout (initial state / demo layout)

Reproduce this as the default demo layout (7 panes). Tree:

```
HORIZONTAL (weights 0.34, 0.66)
├── VERTICAL (0.55, 0.45)            # left column
│   ├── Leaf "Pane 1"
│   └── Leaf "Pane 7"
└── VERTICAL (0.33, 0.33, 0.34)      # right region
    ├── HORIZONTAL (0.5, 0.5)        # top row
    │   ├── Leaf "Pane 2"
    │   └── Leaf "Pane 5"
    ├── HORIZONTAL (0.5, 0.5)        # middle row
    │   ├── Leaf "Pane 6"
    │   └── Leaf "Pane 4"
    └── Leaf "Pane 3"                # bottom, spans both columns
```

This exercises the important cases: a pane spanning siblings' columns (Pane 3), interior panes with no window edge contact (Pane 6), and junctions where 3–4 dividers meet (corner-drag test points).

---

## 4. Visual design

### 4.1 Window & toolbar

- Window content background: near-white `#FAFAFA`.
- Title bar carries a right-aligned toolbar of 8 icon buttons (monochrome, ~16 px glyphs, 28 px hit targets, 6 px spacing). Left→right:

| # | Glyph (Ikonli suggestion) | Action | Shortcut |
|---|---|---|---|
| 1 | `⇤` arrow-to-left-bar (`mdi2-arrow-collapse-left`) | New pane, inserted at **left** of focused pane | `Ctrl/Cmd+Alt+Left` |
| 2 | `⇥` arrow-to-right-bar (`mdi2-arrow-collapse-right`) | New pane at **right** of focused pane | `Ctrl/Cmd+Alt+Right` |
| 3 | `⤒` arrow-to-top-bar (`mdi2-arrow-collapse-up`) | New pane **above** focused pane | `Ctrl/Cmd+Alt+Up` |
| 4 | `⤓` arrow-to-bottom-bar (`mdi2-arrow-collapse-down`) | New pane **below** focused pane | `Ctrl/Cmd+Alt+Down` |
| 5 | `✕` (`mdi2-close`) | Close focused pane | `Ctrl/Cmd+W` |
| 6 | `⤢` diagonal expand (`mdi2-arrow-expand`) | Zoom / unzoom focused pane (toggle) | `Ctrl/Cmd+Shift+Enter` |
| 7 | `↺` (`mdi2-restore` / `mdi2-undo-variant`) | Reset layout to default | — |
| 8 | window+arrow (`mdi2-open-in-new`) | New workspace window | `Ctrl/Cmd+N` |

Glyph-to-action mapping for 7 and 8 is inferred from the demos (a reset occurs between takes; a second window exists for cross-window DnD); adjust labels if product needs differ, but keep all eight actions.

Split semantics for buttons 1–4: the focused leaf is replaced by a `Split` of matching orientation containing `[new, focused]` or `[focused, new]` at weights `0.5/0.5`. If the parent split already has the same orientation, insert the new leaf as a sibling instead (renormalizing weights) — this keeps trees shallow.

### 4.2 Pane chrome

Each pane is a rounded card:

- **Header (26 px):** left-aligned 12 px type icon + title label (12 px, medium weight, `#333`); right-aligned subtle drag affordance (`≡`, `#999`, shown at 40% opacity, 100% on hover). Header background is a slightly darker tint of the pane color. **The entire header is the drag grip.**
- **Body:** hosts the terminal view. In the demo, panes are tinted pastels with a large centered number; in the terminal app, body = terminal canvas, and the pastel tint becomes a per-session accent used in the header only.
- **Border:** 1 px `#E0E0E0`; focused pane swaps to the 2 px blue focus ring (`#3B82F6`), radius matching `PANE_RADIUS`.
- **Drop-target state:** whole pane overlaid with `#3B82F6` at 15% alpha plus 2 px blue border; if the drop is an edge-insert (see §5.6), only the target half of the pane is overlaid.

Demo pastel palette (for the sample app / default accents): `#E3E7FB` (1), `#FDEBDD` (2), `#FBE7F3` (3), `#E5F3E8` (4), `#FBDFDF` (5), `#DFF1F7` (6), `#E4E9F7` (7).

---

## 5. Interaction spec

### 5.1 Focus
- Mouse press anywhere in a pane focuses it (and forwards focus to its terminal).
- Exactly one focused pane per window. Focus ring animates in/out (120 ms fade).
- `Ctrl/Cmd+Alt+Arrow` *without* a new-pane modifier may later be reserved for directional focus move; not required for v1.

### 5.2 Split / new pane
As defined in §4.1. The new pane appears with a 180 ms animation: siblings animate to their new rects while the new pane fades/scales in (0.95 → 1.0 scale, 0 → 1 opacity).

### 5.3 Close pane
- Removes the leaf; its weight is redistributed proportionally among remaining siblings. A split left with one child collapses into that child (tree normalization).
- Remaining panes animate to new rects (180 ms). Focus moves to the nearest sibling (same parent, nearest index).

### 5.4 Divider drag (single axis)
- Hovering a gutter between siblings shows `H_RESIZE`/`V_RESIZE` cursor.
- Dragging adjusts the two adjacent weights only (`wA += Δ/parentExtent; wB -= …`), clamped by `MIN_PANE_W/H`. Live, unanimated (direct manipulation).
- Divider position affects **all** panes spanning that divider (they share the parent split), matching the demo where one divider resizes a whole column.
- **Programmatic weight changes animate** (~300 ms, same easing as reflow): any resize not driven by a live pointer drag — API calls (`setWeights`), keyboard resize (§6), equalize — runs through the rect animator. Add a convenience gesture: **double-click a divider to equalize** the weights of its parent split.

### 5.5 Corner drag (dual axis)
- Where a horizontal and vertical gutter cross (junction of ≥3 panes), a `CORNER_HIT` zone takes precedence over both dividers. Cursor: `MOVE` (or a four-way arrow).
- Dragging updates the horizontal weights of the enclosing `HORIZONTAL` split **and** the vertical weights of the enclosing `VERTICAL` split simultaneously — implement as two independent single-axis drags fed from one mouse delta.
- Junction discovery: after computing layout rects, collect divider segments; any point where a horizontal and vertical segment intersect is a junction.

### 5.6 Pane drag & drop
- **Initiate:** press + drag ≥ 6 px on a pane header.
- **Drag visuals:** the source pane dims to 50% opacity and shows a dashed outline. A floating **drag chip** follows the cursor at (+12, +12): rounded rect, pane icon + title truncated to ~10 chars with ellipsis, drop shadow.
- **Drop zones on a hovered target pane (5 zones):**
  - Center (inner 50% box): **swap** source and target leaves. The swap animation moves *both* panes simultaneously along their direct paths (they visibly cross), ~300 ms — confirmed in Video C. Panes render above dividers/background during the crossing (bring both to front for the duration).
  - Left/right/top/bottom edge bands (outer 25% each side): **insert** source relative to target as a split in that direction (same insertion logic as toolbar splits); overlay highlights the corresponding half of the target.
- **Drop:** tree is mutated, then all panes animate (220 ms) from their previous rects to new rects. The dragged pane animates from the chip's drop point into its final rect.
- **Cancel:** `Esc` or drop on a non-target region → chip animates back to the source pane (150 ms), opacity restores.

### 5.7 Cross-window drag & drop
- Dragging past the window bounds keeps the chip alive (see §7.4). Hovering another workspace window's pane shows the same drop-zone highlights there.
- On drop: leaf is detached from the source tree (source window reflows with animation) and inserted into the destination tree (destination reflows). The terminal session object moves with the leaf — never recreated.
- If the source window's tree becomes empty, close that window.

### 5.8 Zoom / unzoom
- **Zoom (toggle on):** focused pane animates its rect to the full content area (padding respected). Every other pane animates out along an **exit edge** and is removed from the scene at animation end (kept in the model, marked hidden).
- **Exit-edge selection** (this is the signature behavior):
  1. If the pane's current rect touches one or more window content edges (within 1 px), choose among the touching edges the one nearest the pane's center. *Touching beats distance.*
  2. Otherwise choose the edge with minimum distance from the pane's rect (left = `x`, right = `W − (x+w)`, top = `y`, bottom = `H − (y+h)`).
  3. The exit rect is the pane's rect translated fully past that edge (e.g. left exit → `x = −w − 20`). Panes keep their size while sliding out.
- **Unzoom:** hidden panes are re-added at their exit rects and everything animates back to the tree-derived layout. Duration 250 ms, `Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0)` (ease-in-out).
- While zoomed: divider/corner drags disabled; splits/close/dnd operate on the zoomed pane only; any structural change triggers implicit unzoom first.

### 5.9 Animation timing table

| Transition | Duration | Easing |
|---|---|---|
| Focus ring in/out | 120 ms | linear opacity |
| Reflow (split/close/drop insert) | 180–220 ms | ease-in-out spline above |
| Pane swap (crossing paths) | 300 ms | ease-in-out |
| Programmatic resize / equalize | 300 ms | ease-in-out |
| Zoom / unzoom | 250 ms | ease-in-out |
| Drag-cancel chip return | 150 ms | ease-out |
| New-pane fade/scale in | 180 ms | ease-out |

Every frame of these animations re-layouts terminal children (rect interpolation), matching the reference where panes visibly reflow mid-animation. Terminal PTY resize (`TIOCSWINSZ`) should be throttled to animation end + at most one mid-flight update, to avoid resize storms.

---

## 6. Keyboard & accessibility

- All toolbar actions have shortcuts (§4.1 table).
- Panes are focus-traversable (`Tab`/`Shift+Tab` cycles panes when terminal isn't capturing; provide `Ctrl+Tab` as terminal-safe cycle).
- JavaFX accessibility: each pane sets `setAccessibleRole(AccessibleRole.PANE)` (or `GROUP`), `setAccessibleText(title + ", terminal pane")`; toolbar buttons get accessible names matching their action; focus changes fire accessibility focus notifications automatically via JavaFX focus. Zoom state reflected in accessible help text ("zoomed, press … to restore").
- Divider/corner resize must be keyboard-reachable: with a pane focused, `Ctrl/Cmd+Shift+Arrow` grows/shrinks it by 16 px steps (adjusting the same weights the mouse would).

### 6.1 Touch input
The reference framework explicitly targets touch platforms (Video C), and JavaFX apps increasingly run on touch screens. Requirements:
- All pointer interactions work with `TouchEvent`/synthesized mouse events: tap = focus, drag on header = pane DnD, drag on gutter = divider resize.
- **Effective hit targets ≥ 24 px on touch**: when the last input was touch, expand divider and corner hit zones (gutter stays 10 px visually; the invisible hit rect grows).
- **Long-press (350 ms) on a pane header** starts pane DnD on touch, disambiguating from scroll gestures inside terminal content; a light haptic-style visual pulse on the header signals drag start.
- Drop-zone edge bands widen to 33% each on touch (center swap zone shrinks accordingly) since finger targeting is coarser.

---

## 7. JavaFX implementation guidance

### 7.1 Do not nest `SplitPane`s
Nested `SplitPane` cannot express corner drags, exit-edge zoom animations, cross-tree animated reflow, or drag chips. Instead:

- One custom `SplitWorkspace extends Region` owns **all** leaf panes as direct children plus an overlay layer.
- `layoutChildren()` computes each leaf's target rect from the split tree (pure function `Map<PaneId, Rect> computeRects(tree, contentBounds)`), then either applies rects directly (idle) or lets the animator drive them.

### 7.2 Animation engine
- Keep per-pane `currentRect` and `targetRect`. On any tree mutation: capture current rects → recompute targets → run one shared `Timeline` (or `AnimationTimer`) interpolating all rects; call `resizeRelocate` each pulse.
- Zoom uses the same engine with synthetic offscreen targets from §5.8.
- This mirrors the original's "all animations backed by CoreAnimation" with JavaFX's pulse-driven equivalent.

### 7.3 Hit testing & overlays
- A transparent overlay `Pane` (mouse-transparent except during interactions) hosts: divider cursors (via `setCursor` on invisible hit rects regenerated after each layout), corner hit rects (added **after** divider rects so they win picking), drop-zone highlight rect, and the drag chip node.
- Drag chip: a small `HBox` with effect `DropShadow(8, rgba(0,0,0,0.25))`, moved with `relocate` on `MouseEvent.MOUSE_DRAGGED`.

### 7.4 Cross-window DnD
Same-JVM windows: use JavaFX **full-press-drag-release** only works within a scene, so instead:
- Maintain a static `WorkspaceRegistry` of open workspace windows.
- During a header drag, on each `MOUSE_DRAGGED` convert to screen coords and ask the registry which window's content contains the point; route hover/drop-zone feedback to that workspace.
- Render the chip in the source window while inside it; when the cursor is over another window, hide the source chip and show a twin chip in the destination's overlay (or use an undecorated always-on-top transparent `Stage` as a global chip layer — simplest cross-window illusion).
- On release, perform detach/insert on the two models; each workspace runs its own reflow animation. The terminal node (`Node` hosting the PTY view) is reparented, not recreated.

### 7.5 Styling
- Use CSS pseudo-classes on the pane node: `:focused-pane`, `:drop-target`, `:dragging`. Keep colors/radii in a stylesheet mirroring §4 tokens.

### 7.6 Terminal embedding
- Leaf content is any `Node`; for terminals use your terminal view (e.g. a JediTerm-style canvas or TamboUI surface). Contract: it must tolerate rapid `resize` calls; the workspace throttles PTY size updates per §5.9.

---

## 8. Model & API sketch

```java
public sealed interface SplitNode permits Split, LeafPane {}

public final class Split implements SplitNode {
    Orientation orientation;               // HORIZONTAL | VERTICAL
    List<SplitNode> children;              // size >= 2
    List<Double> weights;                  // sum == 1.0
}

public final class LeafPane implements SplitNode {
    PaneId id; String title; Node icon; Node content;
}

public final class SplitWorkspace extends Region {
    ObjectProperty<SplitNode> root();
    ObjectProperty<LeafPane> focusedPane();
    void splitFocused(Side side);          // toolbar buttons 1-4
    void closeFocused();
    void toggleZoom();
    void resetLayout(Supplier<SplitNode> defaultLayout);
    void movePane(PaneId source, PaneId target, DropZone zone); // SWAP|LEFT|RIGHT|TOP|BOTTOM
    void swapPanes(PaneId a, PaneId b);    // animated crossing swap (also used by DropZone.CENTER_SWAP)
    void setWeights(Split split, List<Double> weights); // animated (~300 ms) unless called during a live drag
    void equalize(Split split);            // convenience; double-click divider gesture
    // events
    ObservableList<WorkspaceEvent> events(); // PANE_OPENED, PANE_CLOSED, PANE_MOVED, ZOOM_CHANGED
}

enum DropZone { CENTER_SWAP, LEFT, RIGHT, TOP, BOTTOM }
```

Tree mutations are pure operations on the model (`insertRelative`, `remove`, `swap`, `normalize`), each returning the new root — this makes undo (`↺` as true undo later) and persistence trivial.

---

## 9. Acceptance criteria

1. Default 7-pane demo layout matches §3.3 within weight tolerance.
2. Clicking any pane shows the blue focus ring; exactly one ring per window.
3. Each of the 8 toolbar actions works with mouse and shortcut.
4. Dragging a divider resizes all panes sharing it; min sizes enforced.
5. Dragging a divider junction resizes both axes in one gesture.
6. Zooming interior Pane 6: it fills the window; Pane 1/7 exit left, 2 exits top, 5/4 exit right, 3 exits bottom (touching-edge rule); unzoom restores exactly, animated both ways.
7. Header-drag shows a drag chip; the five drop zones highlight and behave per §5.6; `Esc` cancels with a return animation.
8. A pane dragged into a second window transfers its live terminal session without restart; both windows animate.
9. Screen reader announces pane titles, focus changes, and toolbar actions.
10. No PTY resize storms: at most ~2 resizes per animated transition per pane.
11. Swapping two panes animates both simultaneously along crossing paths (~300 ms); neither pane blinks or reflows through intermediate layouts.
12. `setWeights`/`equalize`/keyboard resize animate (~300 ms); live divider drags remain direct (unanimated).
13. On a touch screen: tap focuses, long-press header starts DnD, gutters are draggable with a finger (expanded hit zones).

---

## 10. Implementation order (suggested)

1. Model + `computeRects` + static rendering of §3.3 (no interaction).
2. Focus + toolbar splits/close (instant, no animation).
3. Shared rect animator; wire reflow animations.
4. Divider drag → corner drag.
5. Zoom/unzoom with exit-edge algorithm.
6. In-window DnD (chip, zones, drop, cancel).
7. Cross-window DnD via registry + global chip layer.
8. Accessibility, keyboard resize, CSS polish.
9. Terminal embedding + PTY resize throttling.