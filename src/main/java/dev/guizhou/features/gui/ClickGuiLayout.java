package dev.guizhou.features.gui;

/** Layout in logical GUI pixels. Saved panel positions are never viewport-clamped in storage. */
final class ClickGuiLayout {
    static final float WIDTH = 126, GAP = 8, MARGIN = 12, TOP = 48, FOOTER = 24;
    static final float HEADER = 24, ROW = 19;

    record View(float scale, float width, float height, int columns, int rows, float cellHeight) {
        float x(int index) { return MARGIN + index % columns * (WIDTH + GAP); }
        float y(int index) { return TOP + index / columns * (cellHeight + GAP); }
    }

    static View calculate(int width, int height, double requested, int count) {
        requested = Double.isFinite(requested) ? Math.clamp(requested, .6, 1.5) : 1;
        float scale = (float) Math.min(requested, Math.min(Math.max(1, width) / 360f, Math.max(1, height) / 240f));
        count = Math.max(1, count);
        for (int attempt = 0; ; attempt++) {
            float vw = Math.max(1, width) / scale, vh = Math.max(1, height) / scale;
            int columns = Math.max(1, Math.min(count, (int) ((vw - MARGIN * 2 + GAP) / (WIDTH + GAP))));
            int rows = (count + columns - 1) / columns;
            float cell = (vh - TOP - FOOTER - GAP * (rows - 1)) / rows;
            if (cell >= HEADER + ROW * 3 || attempt == 40)
                return new View(scale, vw, vh, columns, rows, cell);
            scale *= .95f;
        }
    }

    static float visibleX(float saved, float viewport) {
        return Math.clamp(Float.isFinite(saved) ? saved : MARGIN, MARGIN, Math.max(MARGIN, viewport - WIDTH - MARGIN));
    }

    static float visibleY(float saved, float viewport) {
        return Math.clamp(Float.isFinite(saved) ? saved : TOP, TOP, Math.max(TOP, viewport - FOOTER - HEADER - ROW * 3));
    }

    static float snap(float value, float origin) { return origin + Math.round((value - origin) / 4) * 4; }
}
