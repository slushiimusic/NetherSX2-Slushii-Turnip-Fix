package android.view;
public class View {
    public int y, height, left, top, right, bottom, paddingWrites;
    public boolean shown = true;
    public final ViewTreeObserver observer = new ViewTreeObserver();
    public int getHeight() { return height; }
    public boolean isShown() { return shown; }
    public void getLocationInWindow(int[] position) { position[0] = 0; position[1] = y; }
    public int getPaddingLeft() { return left; }
    public int getPaddingTop() { return top; }
    public int getPaddingRight() { return right; }
    public int getPaddingBottom() { return bottom; }
    public void setPadding(int l, int t, int r, int b) {
        left = l; top = t; right = r; bottom = b; paddingWrites++;
    }
    public ViewTreeObserver getViewTreeObserver() { return observer; }
}
