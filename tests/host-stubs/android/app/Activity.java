package android.app; public class Activity extends android.content.Context {
 public Activity(java.io.File files) { super(files); }
 public boolean isFinishing() { return false; } public boolean isDestroyed() { return false; }
 public boolean hasWindowFocus() { return false; }
 public void runOnUiThread(Runnable r) { r.run(); }
}