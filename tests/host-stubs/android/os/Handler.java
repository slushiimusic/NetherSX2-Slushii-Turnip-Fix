package android.os; public class Handler {
 public Handler() {} public Handler(Looper l) {}
 public boolean post(Runnable r) { r.run(); return true; }
 public boolean postDelayed(Runnable r,long delay) { return true; }
 public void removeCallbacks(Runnable r) {}
}