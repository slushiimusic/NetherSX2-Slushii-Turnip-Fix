package android.content;
public class Context {
 public Object windowService;
 public Object getSystemService(String name) { return windowService; }
 private final java.io.File files;
 public Context() { files=null; }
 public Context(java.io.File files) { this.files=files; }
 public java.io.File getExternalFilesDir(String type) { return files; }
 public Context getApplicationContext() { return this; }
}
