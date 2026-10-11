package xyz.aethersx2.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

/** Compatibility entry only; the emulator and all saved data remain in Pink. */
public final class EmulationActivity extends Activity {
    private static final String PINK = "xyz.aethersx2.cpink02";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { finish(); return; }
        Intent incoming = getIntent();
        String path = incoming.getStringExtra("bootPath");
        if (path == null && incoming.getData() != null) path = incoming.getData().toString();
        if (path == null || path.trim().isEmpty()) {
            error("Choose a game in Daijisho", "Select ps2 - NetherSX2 Turnip, then open a game.");
            return;
        }
        Uri uri = Uri.parse(path);
        String scheme = uri.getScheme();
        if (scheme != null && !"content".equals(scheme) && !"file".equals(scheme)) {
            error("Cannot open this game", "Choose a game stored on your device.");
            return;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(PINK, "xyz.aethersx2.android.EmulationActivity");
        launch.putExtra("bootPath", path);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // A grant in an extra alone is not propagated. Forward it as ClipData
        // only when this connector actually holds it. Otherwise Pink uses its
        // own folder permission; inventing a grant throws SecurityException.
        if ("content".equals(scheme) && checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            launch.setClipData(ClipData.newRawUri("Game", uri));
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivity(launch);
            finish();
        } catch (android.content.ActivityNotFoundException e) {
            error("Install Slushii Pink", "This connector opens games in Slushii Pink. Install Pink first.");
        } catch (SecurityException e) {
            error("Allow access to your game folder", "Open Slushii Pink, add this game's folder, then try Daijisho again.");
        }
    }

    private void error(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }
}
