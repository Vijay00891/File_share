package com.vijay.localfileshare;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen camera view that reads QR codes. Uses the classic camera API to avoid extra libraries. */
@SuppressWarnings("deprecation")
class ScannerView implements SurfaceHolder.Callback, Camera.PreviewCallback {
    interface Listener {
        /** Return true to accept the code and stop scanning, false to keep looking. */
        boolean onScanned(String text);

        void onScannerClosed();
    }

    final LinearLayout root;
    private final Activity activity;
    private final Listener listener;
    private final FrameLayout stage;
    private final SurfaceView surface;
    private final TextView hint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private Camera camera;
    private int frameWidth;
    private int frameHeight;
    private boolean released;
    private boolean finished;

    ScannerView(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        root = Ui.column(activity);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);

        LinearLayout bar = Ui.row(activity);
        bar.setPadding(dp(8), dp(8), dp(20), dp(4));
        ImageView close = Ui.icon(activity, R.drawable.ic_close, Ui.INK);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setBackground(Ui.ripple(activity, 0x00FFFFFF, 24));
        close.setContentDescription("Close scanner");
        close.setOnClickListener(v -> listener.onScannerClosed());
        bar.addView(close, Ui.lp(dp(48), dp(48)));
        bar.addView(Ui.text(activity, "Scan QR code", 20, Ui.INK, true));
        root.addView(bar);

        stage = new FrameLayout(activity);
        stage.setBackground(Ui.shape(activity, 0xFF000000, 24));
        stage.setClipToOutline(true);
        surface = new SurfaceView(activity);
        stage.addView(surface, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1);
        stageParams.setMargins(dp(20), dp(12), dp(20), dp(12));
        root.addView(stage, stageParams);

        hint = Ui.text(activity, "Point the camera at the QR code on the sending phone.", 15, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(28), dp(4), dp(28), dp(28));
        root.addView(hint);

        surface.getHolder().addCallback(this);
    }

    void destroy() {
        released = true;
        releaseCamera();
        decoder.shutdownNow();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (released || camera != null) return;
        try {
            int cameraId = backCameraId();
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = pickSize(parameters.getSupportedPreviewSizes());
            parameters.setPreviewSize(size.width, size.height);
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(parameters);
            frameWidth = size.width;
            frameHeight = size.height;

            int orientation = displayOrientation(cameraId);
            camera.setDisplayOrientation(orientation);
            fitSurface(orientation % 180 != 0);

            camera.setPreviewDisplay(holder);
            camera.addCallbackBuffer(new byte[frameWidth * frameHeight * 3 / 2]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
        } catch (Exception error) {
            releaseCamera();
            hint.setText("The camera couldn't be opened. Close this and pick the sender from the list instead.");
            hint.setTextColor(Ui.DANGER);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        if (released || finished || data == null) return;
        int width = frameWidth;
        int height = frameHeight;
        decoder.execute(() -> {
            String text = Qr.decode(data, width, height);
            main.post(() -> {
                if (released || finished) return;
                if (text != null && listener.onScanned(text)) {
                    finished = true;
                } else if (camera != null) {
                    // Hand the buffer back so the camera delivers the next frame.
                    camera.addCallbackBuffer(data);
                }
            });
        });
    }

    private void releaseCamera() {
        if (camera == null) return;
        try {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        camera.release();
        camera = null;
    }

    private static int backCameraId() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    // Sharp enough to read a code from arm's length without making each frame slow to decode.
    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (Math.abs(size.width - 1280) < Math.abs(best.width - 1280)) best = size;
        }
        return best;
    }

    private int displayOrientation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int degrees;
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (info.orientation + degrees) % 360) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    /** Sizes the preview to cover the stage without stretching the picture. */
    private void fitSurface(boolean rotated) {
        int stageWidth = stage.getWidth();
        int stageHeight = stage.getHeight();
        if (stageWidth == 0 || stageHeight == 0) return;
        float pictureWidth = rotated ? frameHeight : frameWidth;
        float pictureHeight = rotated ? frameWidth : frameHeight;
        float scale = Math.max(stageWidth / pictureWidth, stageHeight / pictureHeight);
        surface.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(pictureWidth * scale), Math.round(pictureHeight * scale), Gravity.CENTER));
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }
}
