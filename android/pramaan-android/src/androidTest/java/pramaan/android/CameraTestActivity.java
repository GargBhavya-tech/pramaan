/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A foreground screen for the live-camera test, in the test APK only: it asks for the camera permission with the normal
 * system dialog (the shell route is blocked on some phones), keeps the screen on, and shows the person holding the phone what
 * to do and how long is left. It draws no camera preview and stores nothing.
 */
public final class CameraTestActivity extends Activity {

    /** The running screen, for the test in the same process. Set while the activity exists. */
    static volatile CameraTestActivity current;

    private TextView text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        text = new TextView(this);
        text.setTextSize(28);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(27, 79, 138));
        text.setGravity(Gravity.CENTER);
        text.setPadding(40, 40, 40, 40);
        text.setText("PRAMAAN camera test");
        setContentView(text);
        current = this;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            say("Tap ALLOW so the test can use the front camera");
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    /** Shows a line to the person holding the phone; safe to call from any thread. */
    public void say(String line) {
        runOnUiThread(() -> text.setText(line));
    }
}
