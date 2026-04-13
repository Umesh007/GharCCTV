package com.gharcctv;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class CameraService extends Service {

    private static final String TAG = "GharCCTV";
    private static final String CH_ID = "gharcctv_ch";
    private static final int NOTIF_ID = 1;
    private static final int HTTP_PORT = 8080;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    public static volatile boolean isRunning = false;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private PowerManager.WakeLock wakeLock;

    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private volatile boolean streaming = false;

    private ServerSocket serverSocket;
    private final List<OutputStream> clients = Collections.synchronizedList(new ArrayList<>());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification();
        if (!isRunning) {
            isRunning = true;
            streaming = true;
            acquireWakeLock();
            startCameraThread();
            startHttpServer();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        streaming = false;
        isRunning = false;
        stopCamera();
        stopHttpServer();
        releaseWakeLock();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }

    // ── FOREGROUND NOTIFICATION ──────────────────────────────────────────────

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Ghar CCTV", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Camera background me chal rahi hai");
            nm.createNotificationChannel(ch);
        }
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ipStr = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());

        Notification notif = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Ghar CCTV — LIVE")
                .setContentText("Dekho: http://" + ipStr + ":" + HTTP_PORT)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif);
    }

    // ── WAKE LOCK ─────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GharCCTV::WakeLock");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── CAMERA2 ───────────────────────────────────────────────────────────────

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(this::openCamera);
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = getBackCameraId();
            if (camId == null) { Log.e(TAG, "No back camera"); return; }

            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    latestJpeg.set(bytes);
                    broadcastFrame(bytes);
                } finally {
                    img.close();
                }
            }, cameraHandler);

            cameraManager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); Log.e(TAG,"Camera error: "+error); }
            }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private String getBackCameraId() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return cameraManager.getCameraIdList()[0];
    }

    private void startCaptureSession() {
        try {
            List<android.view.Surface> surfaces = Collections.singletonList(imageReader.getSurface());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                List<OutputConfiguration> outConfigs = new ArrayList<>();
                outConfigs.add(new OutputConfiguration(imageReader.getSurface()));
                SessionConfiguration sessionConfig = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outConfigs,
                        command -> cameraHandler.post(command),
                        new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                startRepeatingRequest();
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "Session config failed");
                            }
                        }
                );
                cameraDevice.createCaptureSession(sessionConfig);
            } else {
                cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        startRepeatingRequest();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Session config failed");
                    }
                }, cameraHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, "createCaptureSession failed", e);
        }
    }

    private void startRepeatingRequest() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
            Log.i(TAG, "Camera streaming started");
        } catch (Exception e) {
            Log.e(TAG, "setRepeatingRequest failed", e);
        }
    }

    private void stopCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice != null)   { cameraDevice.close();   cameraDevice   = null; } } catch (Exception ignored) {}
        try { if (imageReader != null)    { imageReader.close();     imageReader    = null; } } catch (Exception ignored) {}
    }

    // ── HTTP MJPEG SERVER ─────────────────────────────────────────────────────

    private void startHttpServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(HTTP_PORT, 10, InetAddress.getByName("0.0.0.0"));
                Log.i(TAG, "HTTP server started on port " + HTTP_PORT);
                while (streaming && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (Exception e) {
                if (streaming) Log.e(TAG, "Server error", e);
            }
        }, "HttpServer").start();
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { socket.close(); return; }

            // Read all headers
            while (true) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) break;
            }

            OutputStream out = socket.getOutputStream();

            if (requestLine.startsWith("GET / ") || requestLine.startsWith("GET /stream")) {
                // Serve MJPEG stream
                String header =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--gharcctv\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n\r\n";
                out.write(header.getBytes());
                out.flush();

                clients.add(out);
                // Keep sending latest frame on loop until disconnect
                try {
                    while (streaming && !socket.isClosed()) {
                        byte[] frame = latestJpeg.get();
                        if (frame != null) {
                            try {
                                String partHeader =
                                    "--gharcctv\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: " + frame.length + "\r\n\r\n";
                                out.write(partHeader.getBytes());
                                out.write(frame);
                                out.write("\r\n".getBytes());
                                out.flush();
                            } catch (Exception e) {
                                break;
                            }
                        }
                        Thread.sleep(80); // ~12 FPS
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    clients.remove(out);
                }
            } else if (requestLine.startsWith("GET /snap")) {
                // Single JPEG snapshot
                byte[] frame = latestJpeg.get();
                if (frame == null) frame = new byte[0];
                String resp =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + frame.length + "\r\n\r\n";
                out.write(resp.getBytes());
                out.write(frame);
                out.flush();
            } else {
                // Serve a simple HTML viewer page
                String html = buildViewerHtml();
                byte[] body = html.getBytes("UTF-8");
                String resp =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + body.length + "\r\n\r\n";
                out.write(resp.getBytes());
                out.write(body);
                out.flush();
            }

            socket.close();
        } catch (Exception e) {
            Log.d(TAG, "Client handler error: " + e.getMessage());
        }
    }

    private void broadcastFrame(byte[] frame) {
        // Frame is broadcast via the polling loop in each client thread
        // latestJpeg.set() is already called before this
    }

    private void stopHttpServer() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    private String buildViewerHtml() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        return "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Ghar CCTV</title>" +
            "<style>" +
            "body{margin:0;background:#000;display:flex;flex-direction:column;align-items:center;min-height:100vh;font-family:sans-serif;color:#fff;}" +
            "h2{margin:12px 0 8px;font-size:18px;color:#4ade80;}" +
            "img{width:100%;max-width:900px;border-radius:10px;}" +
            ".info{font-size:13px;color:#888;margin:8px;text-align:center;}" +
            ".dot{display:inline-block;width:8px;height:8px;background:#4ade80;border-radius:50%;animation:pulse 1.5s infinite;margin-right:6px;}" +
            "@keyframes pulse{0%,100%{opacity:1}50%{opacity:0.3}}" +
            "</style></head><body>" +
            "<h2><span class='dot'></span>Ghar CCTV — Live</h2>" +
            "<img src='/stream' />" +
            "<p class='info'>Camera: " + ip + ":8080</p>" +
            "<p class='info'>Snapshot: <a href='/snap' style='color:#60a5fa'>/snap</a></p>" +
            "</body></html>";
        }
}
