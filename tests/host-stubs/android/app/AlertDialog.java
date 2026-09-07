package android.app; public class AlertDialog {
 public static int shown;
 public static class Builder {
  public Builder(android.content.Context c) {}
  public Builder setTitle(CharSequence s) { return this; }
  public Builder setMessage(CharSequence s) { return this; }
  public Builder setPositiveButton(CharSequence s,android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setNegativeButton(CharSequence s,android.content.DialogInterface.OnClickListener l) { return this; }
  public AlertDialog show() { shown++; return new AlertDialog(); }
 }
}