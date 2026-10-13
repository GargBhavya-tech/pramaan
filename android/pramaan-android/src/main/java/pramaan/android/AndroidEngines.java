/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.content.res.AssetManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.StandardEngines;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.EngineUnavailableException;

/**
 * The engine on Android (Build Map #79; deployment Mode D): {@link StandardEngines} — the very assembly the
 * desktop uses — with {@code BitmapFactory} decoding and {@link SystemClock#elapsedRealtimeNanos()}.
 *
 * <p>The bundle is read from the embedding app's assets: {@code <dir>/pramaan-policy.yaml},
 * {@code <dir>/operating-points.json} and {@code <dir>/models/<name>} for exactly the models the table names.
 * A missing, extra or altered model stops the engine before any session exists (fail closed).
 */
public final class AndroidEngines {

    private AndroidEngines() {
    }

    public static EngineBundle loadFromAssets(AssetManager assets, String dir)
            throws IOException, EngineUnavailableException {
        return StandardEngines.loadBundle(read(assets, dir + "/pramaan-policy.yaml"),
                read(assets, dir + "/operating-points.json"), name -> read(assets, dir + "/models/" + name));
    }

    public static StandardEngines engines(EngineBundle bundle, JwsTrust deviceTrust, SecureRandom random) {
        return new StandardEngines(bundle, deviceTrust, random, new BitmapFrameDecoder(), new BitmapImageDecoder(),
                SystemClock::elapsedRealtimeNanos);
    }

    static byte[] read(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path, AssetManager.ACCESS_STREAMING)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
