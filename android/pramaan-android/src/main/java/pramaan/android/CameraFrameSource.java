/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import pramaan.core.session.SessionContext;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.core.source.SignedCapture;

/**
 * The phone's own camera as a stream of frames for the engine: the front or back camera through Camera2, throttled to a target
 * frame rate and delivered as upright JPEG {@link Frame}s, with the sensor's timestamp. This is the frame source of deployment
 * Mode D (the engine runs inside the SBI app, which owns the camera: Bible Part 2).
 *
 * <p>It only streams. A signed capture needs a key the device holds, so {@link #capture} refuses; the Mode D device that signs
 * captures wraps this source. The caller must hold the {@code CAMERA} permission, which this class checks and reports as
 * {@link DeviceException.Reason#UNAVAILABLE}, never as a crash. Frames are not mirrored (a preview may mirror itself) and are
 * rotated by the sensor's mounting angle, so the face is upright when the phone is held in portrait.
 */
public final class CameraFrameSource implements FaceFrameSource {

    private static final long OPEN_TIMEOUT_SECONDS = 6;
    private static final int JPEG_QUALITY = 90;

    private final Context context;
    private final boolean front;
    private final int width;
    private final int height;
    private final long intervalNanos;
    private final Clock clock;

    private String cameraId;
    private int sensorOrientation;
    private DeviceMetadata metadata;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile FrameSink sink;
    private volatile boolean stopping;
    private volatile Surface previewSurface;
    private long index;
    private long lastEmitNanos;
    private volatile long conversionNanosTotal;
    private volatile long conversionCount;

    /**
     * @param front true for the selfie camera
     * @param width requested analysis width in the sensor's orientation (landscape), for example 640
     * @param height requested analysis height, for example 480
     * @param targetFps frames a second to deliver; the camera runs faster and the rest are dropped before the costly conversion
     */
    public CameraFrameSource(Context context, boolean front, int width, int height, double targetFps, Clock clock) {
        this.context = context.getApplicationContext();
        this.front = front;
        this.width = width;
        this.height = height;
        this.intervalNanos = (long) (1_000_000_000L / targetFps);
        this.clock = clock;
    }

    /** Mean milliseconds the camera thread spent turning one camera image into an upright JPEG (it competes with the analysis for CPU). */
    public double meanConversionMillis() {
        long n = conversionCount;
        return n == 0 ? 0 : conversionNanosTotal / 1e6 / n;
    }

    /** A surface to show the camera's picture on, for a person who needs to see themselves; set before {@link #start}. */
    public void setPreviewSurface(Surface preview) {
        this.previewSurface = preview;
    }

    @Override
    public DeviceCapabilities discover() throws DeviceException {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "the CAMERA permission is not granted");
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            int wanted = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == wanted) {
                    cameraId = id;
                    Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = orientation == null ? 0 : orientation;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "cannot list cameras: " + e.getMessage(), e);
        }
        if (cameraId == null) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "no " + (front ? "front" : "back") + " camera");
        }
        metadata = new DeviceMetadata("pramaan-phone-camera", cameraId, "MODE_D", DeviceLevel.L0, Build.MANUFACTURER, Build.MODEL,
                "none");
        return new DeviceCapabilities(metadata, EnumSet.of(Capability.SENSOR_TIMESTAMPS));
    }

    @Override
    public synchronized void start(SessionContext sessionContext, FrameSink frameSink) throws DeviceException {
        if (cameraId == null) {
            discover();
        }
        if (camera != null) {
            throw new IllegalStateException("already started");
        }
        stopping = false;
        sink = frameSink;
        index = 0;
        lastEmitNanos = 0;
        thread = new HandlerThread("pramaan-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::onImage, handler);

        CountDownLatch ready = new CountDownLatch(1);
        String[] failure = new String[1];
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice opened) {
                    camera = opened;
                    try {
                        List<Surface> outputs = new ArrayList<>();
                        outputs.add(reader.getSurface());
                        if (previewSurface != null) {
                            outputs.add(previewSurface);
                        }
                        opened.createCaptureSession(outputs,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession configured) {
                                        session = configured;
                                        try {
                                            android.hardware.camera2.CaptureRequest.Builder b =
                                                    opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader.getSurface());
                                            if (previewSurface != null) {
                                                b.addTarget(previewSurface);
                                            }
                                            b.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            configured.setRepeatingRequest(b.build(), null, handler);
                                        } catch (CameraAccessException | IllegalStateException e) {
                                            failure[0] = "cannot start the stream: " + e.getMessage();
                                        }
                                        ready.countDown();
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession failed) {
                                        failure[0] = "the camera session could not be configured";
                                        ready.countDown();
                                    }
                                }, handler);
                    } catch (CameraAccessException e) {
                        failure[0] = "cannot create the camera session: " + e.getMessage();
                        ready.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice disconnected) {
                    FrameSink s = sink;
                    if (s != null && !stopping) {
                        s.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.DISCONNECTED, "the camera was taken by another app",
                                Instant.now(clock)));
                    }
                    failure[0] = failure[0] == null ? "the camera disconnected" : failure[0];
                    ready.countDown();
                }

                @Override
                public void onError(CameraDevice errored, int error) {
                    FrameSink s = sink;
                    if (s != null && !stopping) {
                        s.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.ERROR, "camera error " + error, Instant.now(clock)));
                    }
                    failure[0] = failure[0] == null ? "camera error " + error : failure[0];
                    ready.countDown();
                }
            }, handler);
            if (!ready.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure[0] = "the camera did not open within " + OPEN_TIMEOUT_SECONDS + " s";
            }
        } catch (CameraAccessException | SecurityException e) {
            failure[0] = "cannot open the camera: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure[0] = "interrupted while opening the camera";
        }
        if (failure[0] != null) {
            stop();
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, failure[0]);
        }
        frameSink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.CONNECTED, metadata.toString(), Instant.now(clock)));
    }

    private void onImage(ImageReader r) {
        Image image = r.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            FrameSink s = sink;
            if (s == null || stopping || (lastEmitNanos != 0 && now - lastEmitNanos < intervalNanos)) {
                return;
            }
            lastEmitNanos = now;
            long c0 = System.nanoTime();
            byte[] jpeg = toUprightJpeg(image);
            conversionNanosTotal += System.nanoTime() - c0;
            conversionCount++;
            boolean swapped = sensorOrientation == 90 || sensorOrientation == 270;
            s.onFrame(new Frame(jpeg, PixelFormat.JPEG, swapped ? image.getHeight() : image.getWidth(),
                    swapped ? image.getWidth() : image.getHeight(), image.getTimestamp(), Instant.now(clock), index++, metadata));
        } catch (RuntimeException e) {
            FrameSink s = sink;
            if (s != null && !stopping) {
                s.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.ERROR, "a camera frame could not be converted: " + e,
                        Instant.now(clock)));
            }
        } finally {
            image.close();
        }
    }

    /** YUV_420_888 to NV21 to JPEG, then rotated by the sensor's mounting angle so the face is upright in portrait. */
    private byte[] toUprightJpeg(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane yPlane = image.getPlanes()[0];
        copyPlane(yPlane.getBuffer(), yPlane.getRowStride(), yPlane.getPixelStride(), w, h, nv21);
        Image.Plane u = image.getPlanes()[1];
        Image.Plane v = image.getPlanes()[2];
        int cw = w / 2;
        int ch = h / 2;
        ByteBuffer ub = u.getBuffer();
        ByteBuffer vb = v.getBuffer();
        int o = w * h;
        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                nv21[o++] = vb.get(row * v.getRowStride() + col * v.getPixelStride());
                nv21[o++] = ub.get(row * u.getRowStride() + col * u.getPixelStride());
            }
        }
        ByteArrayOutputStream flat = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), JPEG_QUALITY, flat);
        if (sensorOrientation == 0) {
            return flat.toByteArray();
        }
        byte[] bytes = flat.toByteArray();
        Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Matrix m = new Matrix();
        m.postRotate(sensorOrientation);
        Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        raw.recycle();
        rotated.recycle();
        return out.toByteArray();
    }

    private static void copyPlane(ByteBuffer src, int rowStride, int pixelStride, int w, int h, byte[] dst) {
        int o = 0;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                dst[o++] = src.get(row * rowStride + col * pixelStride);
            }
        }
    }

    @Override
    public SignedCapture capture(pramaan.core.source.CaptureRequest request) throws DeviceException {
        throw new DeviceException(DeviceException.Reason.PROTOCOL,
                "this source only streams frames; the Mode D device that holds the signing key wraps it to capture");
    }

    @Override
    public synchronized void stop() {
        stopping = true;
        sink = null;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }
}
