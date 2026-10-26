/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The capture screen of the SBI app, built in code so the library needs no resources: the front camera's preview, mirrored as a
 * mirror is (so "turn to your left" points to the screen's left), an oval to place the face in, and a line of text under it.
 * The oval is dimmed outside and drawn green while the engine is satisfied with the picture and orange while it is asking for
 * a change, so a person who cannot tell whether they are centred can see it at once.
 *
 * <p>The preview shows only the camera's own picture; the engine reads its frames from a separate stream and nothing here is stored.
 * Hold the phone upright (portrait): the preview is laid out as a 3:4 portrait box.
 */
public final class ScanView extends FrameLayout {

    private static final int OK = Color.rgb(46, 160, 67);
    private static final int ADJUST = Color.rgb(240, 150, 30);

    private final TextureView texture;
    private final OvalView oval;
    private final TextView text;
    private final CountDownLatch surfaceReady = new CountDownLatch(1);
    private volatile Surface surface;
    private final int bufferWidth;
    private final int bufferHeight;

    /**
     * @param bufferWidth the preview buffer's width in the sensor's (landscape) orientation, for example 640
     * @param bufferHeight its height, for example 480; must be a size the camera supports for previews
     */
    public ScanView(Context context, int bufferWidth, int bufferHeight) {
        super(context);
        this.bufferWidth = bufferWidth;
        this.bufferHeight = bufferHeight;
        setBackgroundColor(Color.BLACK);
        texture = new TextureView(context);
        texture.setScaleX(-1f);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                st.setDefaultBufferSize(ScanView.this.bufferWidth, ScanView.this.bufferHeight);
                surface = new Surface(st);
                surfaceReady.countDown();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        oval = new OvalView(context);
        text = new TextView(context);
        text.setTextSize(22);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(27, 79, 138));
        text.setGravity(Gravity.CENTER);
        text.setPadding(32, 24, 32, 24);
        addView(texture);
        addView(oval);
        addView(text);
    }

    /** The surface the camera draws the preview into; waits for the view to be on screen. Call from a non-UI thread. */
    public Surface awaitPreviewSurface(long timeoutMillis) throws InterruptedException {
        return surfaceReady.await(timeoutMillis, TimeUnit.MILLISECONDS) ? surface : null;
    }

    /** Shows a line of instruction under the preview; safe to call from any thread. */
    public void say(String line) {
        post(() -> text.setText(line));
    }

    /** Green when the engine is satisfied with the picture, orange while it is asking for a change. */
    public void setFaceOk(boolean ok) {
        post(() -> oval.setColor(ok ? OK : ADJUST));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int ph = Math.min(h * 7 / 10, w * 4 / 3);
        int pw = ph * 3 / 4;
        texture.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
        oval.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
        text.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, h - ph), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int pw = texture.getMeasuredWidth();
        int ph = texture.getMeasuredHeight();
        int px = (w - pw) / 2;
        texture.layout(px, 0, px + pw, ph);
        oval.layout(px, 0, px + pw, ph);
        text.layout(0, ph, w, b - t);
    }

    /** The dimmed frame with an oval cut out, outlined in the current colour. */
    private static final class OvalView extends View {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outside = new Path();
        private final RectF area = new RectF();

        OvalView(Context context) {
            super(context);
            dim.setColor(Color.argb(150, 0, 0, 0));
            dim.setStyle(Paint.Style.FILL);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(10f);
            ring.setColor(ADJUST);
        }

        void setColor(int color) {
            ring.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float ow = w * 0.66f;
            float oh = Math.min(ow * 1.33f, h * 0.82f);
            float cx = w / 2f;
            float cy = h * 0.46f;
            area.set(cx - ow / 2, cy - oh / 2, cx + ow / 2, cy + oh / 2);
            outside.reset();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(0, 0, w, h, Path.Direction.CW);
            outside.addOval(area, Path.Direction.CW);
            canvas.drawPath(outside, dim);
            canvas.drawOval(area, ring);
        }
    }
}
