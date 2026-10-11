package android.os; public class Handler {
 public static final java.util.List<Runnable> delayed = new java.util.ArrayList<>();
 public Handler() {} public Handler(Looper l) {}
 public boolean post(Runnable r) { r.run(); return true; }
 public boolean postDelayed(Runnable r,long delay) { delayed.add(r); return true; }
 public void removeCallbacks(Runnable r) { delayed.removeIf(x -> x == r); }
}
