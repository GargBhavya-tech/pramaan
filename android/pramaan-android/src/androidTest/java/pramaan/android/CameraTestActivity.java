/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * A foreground screen for the on-device camera tests, in the test APK only: it asks for the camera permission with the normal
 * system dialog (the shell route is blocked on some phones), keeps the screen on, and hosts the same {@link ScanView} the SBI app
 * uses: the camera's picture mirrored, an oval to place the face in, and the instruction text. It stores nothing.
 */
public final class CameraTestActivity extends Activity {

    /** The running screen, for the test in the same process. Set while the activity exists. */
    static volatile CameraTestActivity current;

    private ScanView scan;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        scan = new ScanView(this, 640, 480);
        scan.say("PRAMAAN camera test");
        setContentView(scan);
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

    /** The view with the preview, the oval and the text. */
    public ScanView scanView() {
        return scan;
    }

    /** Shows a line to the person holding the phone; safe to call from any thread. */
    public void say(String line) {
        scan.say(line);
    }
}
