package com.example.wscontroller;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.Manifest;

public class MainActivity extends ComponentActivity implements WebSocketManager.MessageListener {

    private TextView deviceNumberTextView;
    private TextView connectionStatusTextView;
    private TextView logTextView;
    private Button setDeviceNumberButton;
    private Button connectButton;
    private Button clearLogButton;

    private DeviceNumberManager deviceNumberManager;
    private WebSocketManager webSocketManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        deviceNumberTextView = findViewById(R.id.deviceNumberTextView);
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView);
        logTextView = findViewById(R.id.logTextView);
        setDeviceNumberButton = findViewById(R.id.setDeviceNumberButton);
        connectButton = findViewById(R.id.connectButton);
        clearLogButton = findViewById(R.id.clearLogButton);

        // 初始化管理器
        deviceNumberManager = new DeviceNumberManager(this);
        webSocketManager = new WebSocketManager(this);
        webSocketManager.setMessageListener(this);

        // 更新UI显示当前设备编号
        updateDeviceNumberDisplay();

        // 设置按钮点击事件
        setDeviceNumberButton.setOnClickListener(v -> showDeviceNumberDialog());
        connectButton.setOnClickListener(v -> toggleConnection());
        clearLogButton.setOnClickListener(v -> clearLog());

        // 如果已有设备编号，自动连接
        if (deviceNumberManager.hasDeviceNumber()) {
            webSocketManager.connect();
        }

        // 在适当的位置（如onCreate或设备编号设置后）
        startWebSocketService();

        // 请求通知权限（Android 13及以上需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 更新设备编号显示
        updateDeviceNumberDisplay();
    }

    // 更新设备编号显示
    private void updateDeviceNumberDisplay() {
        if (deviceNumberManager.hasDeviceNumber()) {
            deviceNumberTextView.setText(deviceNumberManager.getDeviceNumber());
        } else {
            deviceNumberTextView.setText("未设置");
        }
    }

    // 显示设备编号设置对话框
    private void showDeviceNumberDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_number, null);
        EditText deviceNumberEditText = dialogView.findViewById(R.id.deviceNumberEditText);

        // 如果已有设备编号，显示在输入框中
        if (deviceNumberManager.hasDeviceNumber()) {
            deviceNumberEditText.setText(deviceNumberManager.getDeviceNumber());
        }

        new AlertDialog.Builder(this)
                .setTitle("设置设备编号")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String number = deviceNumberEditText.getText().toString().trim();
                    if (number.matches("\\d{3}")) {
                        try {
                            // 如果编号变化了，更新WebSocket连接
                            if (!number.equals(deviceNumberManager.getDeviceNumber())) {
                                webSocketManager.updateDeviceNumber(number);
                                updateDeviceNumberDisplay();
                                addLog("设备编号已更新为: " + number);
                            }
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "设备编号必须是三位数字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 切换连接状态
    private void toggleConnection() {
        if (!deviceNumberManager.hasDeviceNumber()) {
            Toast.makeText(this, "请先设置设备编号", Toast.LENGTH_SHORT).show();
            showDeviceNumberDialog();
            return;
        }

        if (connectionStatusTextView.getText().toString().equals("已连接")) {
            // 断开连接
            webSocketManager.disconnect();
            connectButton.setText("连接服务器");
            addLog("已断开服务器连接");
        } else {
            // 连接服务器
            webSocketManager.connect();
            connectButton.setText("正在连接...");
            addLog("正在连接服务器...");
        }
    }

    // 添加日志
    private void addLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = timestamp + " - " + message + "\n";

        runOnUiThread(() -> {
            logTextView.append(logEntry);
            // 自动滚动到底部
            View parent = (View) logTextView.getParent();
            parent.post(() -> {
                if (parent instanceof androidx.core.widget.NestedScrollView) {
                    ((androidx.core.widget.NestedScrollView) parent).fullScroll(View.FOCUS_DOWN);
                }
            });
        });
    }

    // WebSocket消息监听回调
    @Override
    public void onMessageReceived(String message) {
        addLog("收到消息: " + message);
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        runOnUiThread(() -> {
            if (connected) {
                connectionStatusTextView.setText("已连接");
                connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                connectButton.setText("断开连接");
                addLog("已连接到服务器");
                
                // 确保设备编号显示正确
                updateDeviceNumberDisplay();
            } else {
                connectionStatusTextView.setText("未连接");
                connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                connectButton.setText("连接服务器");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 应用关闭时断开连接
        webSocketManager.disconnect();
    }

    // 在适当的位置（如onCreate或设备编号设置后）
    private void startWebSocketService() {
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // 添加清理日志方法
    private void clearLog() {
        logTextView.setText("");
        addLog("日志已清除");
    }
}