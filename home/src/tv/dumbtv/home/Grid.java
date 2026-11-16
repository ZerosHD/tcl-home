package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays out same-size tiles in as many columns as fit the available width, wrapping to a new
 * row instead of scrolling sideways. Used in place of the horizontal strip when Options >
 * Layout is "grid"; column count follows the tile size setting, not a fixed number, so Small,
 * Medium and Large each pack a different number of columns into the same screen width.
 *
 * The outer vertical ScrollView does all the scrolling; this view only ever grows as tall as
 * its rows need.
 */
final class Grid extends ViewGroup {
    private final int hGap, vGap;
    /** The app grid may put its first row on a dock: a frosted panel, set a little apart. */
    private final boolean mayDock;
    private final int dockPad, dockGap;
    private final float dockRadius;
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dockArea = new RectF();

    Grid(Context c, boolean mayDock) {
        super(c);
        this.mayDock = mayDock;
        dockPad = Ui.dp(c, 12);
        dockGap = Ui.dp(c, 20);
        dockRadius = Ui.dp(c, 24);
        panel.setColor(0x26FFFFFF);
        panelEdge.setStyle(Paint.Style.STROKE);
        panelEdge.setStrokeWidth(Math.max(1f, Ui.dp(c, 0.75f)));
        panelEdge.setColor(0x38FFFFFF);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        hGap = Ui.dp(c, 18);
        vGap = Ui.dp(c, 18);
    }

    private boolean dock() {
        return mayDock && "on".equals(Config.get(getContext()).get(Config.DOCK));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (dockArea.isEmpty()) return;
        canvas.drawRoundRect(dockArea, dockRadius, dockRadius, panel);
        canvas.drawRoundRect(dockArea, dockRadius, dockRadius, panelEdge);
    }

    private int columns(int availableWidth, int childWidth) {
        return Math.max(1, (availableWidth + hGap) / (childWidth + hGap));
    }

    /** Columns in the current layout; 1 before the first layout pass or with no tiles. */
    int columns() {
        if (getChildCount() == 0 || getWidth() == 0) return 1;
        return columns(getWidth() - getPaddingLeft() - getPaddingRight(), getChildAt(0).getMeasuredWidth());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int n = getChildCount();
        int cw = 0, ch = 0;
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            cw = child.getMeasuredWidth();
            ch = child.getMeasuredHeight();
        }
        int height = getPaddingTop() + getPaddingBottom();
        if (n > 0) {
            int avail = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int cols = columns(avail, cw);
            int rows = (n + cols - 1) / cols;
            height += rows * ch + (rows - 1) * vGap + (dock() && rows > 1 ? dockGap : 0);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        dockArea.setEmpty();
        if (n == 0) return;
        View first = getChildAt(0);
        int cw = first.getMeasuredWidth(), ch = first.getMeasuredHeight();
        int avail = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
        int cols = columns(avail, cw);
        boolean docked = dock();
        if (docked) {
            int inRow = Math.min(n, cols);
            dockArea.set(getPaddingLeft() - dockPad, Math.max(0, getPaddingTop() - dockPad),
                    getPaddingLeft() + inRow * cw + (inRow - 1) * hGap + dockPad,
                    getPaddingTop() + ch + dockPad * 0.9f);
        }
        for (int i = 0; i < n; i++) {
            int col = i % cols, row = i / cols;
            int x = getPaddingLeft() + col * (cw + hGap);
            int y = getPaddingTop() + row * (ch + vGap) + (docked && row > 0 ? dockGap : 0);
            getChildAt(i).layout(x, y, x + cw, y + ch);
        }
    }
}
