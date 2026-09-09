package android.content.res;
public class Resources {
    public int getIdentifier(String name, String type, String pkg) {
        // Dedicated settings screens have no id/toolbar.
        return name.equals("settings") ? 1 : name.equals("action_bar_container") ? 2 : 0;
    }
}
