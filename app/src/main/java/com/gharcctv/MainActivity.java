package com.gharcctv;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 101;
    private TextView tvStatus, tvUrl, tvIp;
    private Button btnStart, btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvUrl    = findViewById(R.id.tvUrl);
        tvIp     = findViewById(R.id.tvIp);
        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> requestPermsAndStart());
        btnStop.setOnClickListener(v -> stopService());

        updateIpDisplay();
        if (CameraService.isRunning) {
            tvStatus.setText("LIVE — Camera chal rahi hai");
            btnStart.setVisibility(View.GONE);
            btnStop.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateIpDisplay();
    }

    private void updateIpDisplay() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        int ip = info.getIpAddress();
        String ipStr = Formatter.formatIpAddress(ip);
        tvIp.setText("Phone IP: " + ipStr);
        tvUrl.setText("Phone 2 me ye URL kholo:\nhttp://" + ipStr + ":8080");
    }

    private void requestPermsAndStart() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
        } else {
            startCameraService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS) {
            boolean camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (camGranted) startCameraService();
            else tvStatus.setText("Camera permission do — Settings me jaao");
        }
    }

    private void startCameraService() {
        Intent intent = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent);
        else
            startService(intent);

        tvStatus.setText("LIVE — Camera chal rahi hai\nPhone 2 me URL kholo");
        btnStart.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);
        updateIpDisplay();
    }

    private void stopService() {
        stopService(new Intent(this, CameraService.class));
        tvStatus.setText("Band hai — Start karo");
        btnStart.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.GONE);
    }
}
