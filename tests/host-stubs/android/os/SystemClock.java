package android.os; public class SystemClock {
 public static long uptimeMillis() { return 100000L; }
 public static long elapsedRealtime() { return uptimeMillis(); }
}