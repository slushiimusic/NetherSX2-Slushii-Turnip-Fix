package android.view;
public class ViewTreeObserver {
    public interface OnPreDrawListener { boolean onPreDraw(); }
    public final java.util.List<OnPreDrawListener> listeners = new java.util.ArrayList<>();
    public boolean isAlive() { return true; }
    public void addOnPreDrawListener(OnPreDrawListener listener) { listeners.add(listener); }
    public boolean draw() {
        boolean ready = true;
        for (OnPreDrawListener listener : listeners) ready &= listener.onPreDraw();
        return ready;
    }
}
