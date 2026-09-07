package android.view;
public class Display {
 private final float current;
 private final Mode[] modes;
 public Display(float current, float... supported) {
  this.current=current; modes=new Mode[supported.length];
  for(int i=0;i<supported.length;i++) modes[i]=new Mode(supported[i]);
 }
 public float getRefreshRate() { return current; }
 public Mode getMode() { return new Mode(current); }
 public Mode[] getSupportedModes() { return modes; }
 public static class Mode {
  private final float hz;
  public Mode(float hz) { this.hz=hz; }
  public float getRefreshRate() { return hz; }
 }
}
