package android.app;
public class Activity {
    public final android.view.Window window = new android.view.Window();
    public final android.content.res.Resources resources = new android.content.res.Resources();
    public android.view.View settings, bar;
    public android.view.Window getWindow() { return window; }
    public android.content.res.Resources getResources() { return resources; }
    public String getPackageName() { return "xyz.aethersx2.cpink02"; }
    @SuppressWarnings("unchecked")
    public <T extends android.view.View> T findViewById(int id) {
        return (T) (id == 1 ? settings : id == 2 ? bar : null);
    }
}
