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
import android.widget.ScrollView;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.net.ConnectivityManager;
import android.net.Network;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends ComponentActivity implements WebSocketManager.MessageListener {

    private TextView deviceNumberTextView;
    private TextView connectionStatusTextView;
    private TextView logTextView;
    private Button setDeviceNumberButton;
    private Button connectButton;
    private Button clearLogButton;

    private DeviceNumberManager deviceNumberManager;
    private WebSocketManager webSocketManager;

    // 记录UI状态与实际连接状态不一致的开始时间
    private long lastStateDifferenceTime = 0;
    private long lastReconnectAttemptTime = 0;

    // 广播接收器
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.wscontroller.LOG_MESSAGE".equals(intent.getAction())) {
                String message = intent.getStringExtra("message");
                if (message != null) {
                    addLog(message);
                }
            }
        }
    };

    // 网络状态变化广播接收器
    private final BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.wscontroller.NETWORK_STATE_CHANGED".equals(intent.getAction())) {
                Log.d("MainActivity", "收到网络状态变化广播");
                boolean forceReconnect = intent.getBooleanExtra("FORCE_RECONNECT", false);
                handleNetworkStateChanged(forceReconnect);
            }
        }
    };

    // 在MainActivity中添加日志限制
    private static final int MAX_LOG_LINES = 100; // 最大日志行数

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
        
        // 设置日志滚动视图的触摸事件
        ScrollView logScrollView = findViewById(R.id.logScrollView);
        // 禁用父视图的拦截
        ((View) logScrollView.getParent()).setOnTouchListener((v, event) -> {
            logScrollView.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        // 初始化管理器
        deviceNumberManager = new DeviceNumberManager(this);
        webSocketManager = new WebSocketManager(this);
        webSocketManager.setMessageListener(this);

        // 初始化连接状态UI
        updateConnectionStatusDisplay();
        
        // 添加日志
        addLog("应用启动，初始连接状态: " + (webSocketManager.isConnected() ? "已连接" : "未连接"));

        // 更新UI显示当前设备编号
        updateDeviceNumberDisplay();

        // 设置按钮点击事件
        setDeviceNumberButton.setOnClickListener(v -> showDeviceNumberDialog());
        connectButton.setOnClickListener(v -> {
            if (webSocketManager.isConnected()) {
                // 添加日志
                addLog("用户点击断开连接");
                webSocketManager.disconnect();
            } else {
                // 添加日志
                addLog("用户点击连接服务器");
                webSocketManager.connect();
            }
            
            // 添加状态检查日志
            new Handler().postDelayed(() -> {
                String uiStatus = connectionStatusTextView.getText().toString();
                addLog("连接操作后状态检查: WebSocket状态=" + 
                       (webSocketManager.isConnected() ? "已连接" : "未连接") + 
                       ", UI显示=" + uiStatus);
            }, 2000);
        });
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
        
        // 检查无障碍服务是否启用
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityServiceDialog();
        }

        try {
            // 注册广播接收器接收日志消息
            IntentFilter filter = new IntentFilter("com.example.wscontroller.LOG_MESSAGE");
            registerReceiver(logReceiver, filter);
            
            // 注册广播接收器接收网络状态变化
            IntentFilter networkFilter = new IntentFilter("com.example.wscontroller.NETWORK_STATE_CHANGED");
            registerReceiver(networkStateReceiver, networkFilter);
        } catch (Exception e) {
            Log.e("MainActivity", "注册广播接收器失败: " + e.getMessage());
        }

        // 注册网络状态监听器
        registerNetworkCallback();

        // 启动定期连接状态检查
        startConnectionStatusCheck();

        // 在MainActivity中添加更频繁的UI状态验证
        startUIConsistencyCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 更新设备编号显示
        updateDeviceNumberDisplay();
        
        // 更新连接状态显示
        updateConnectionStatusDisplay();
    }

    // 更新设备编号显示
    private void updateDeviceNumberDisplay() {
        String number = deviceNumberManager.getDeviceNumber();
        if (number != null && !number.isEmpty()) {
            deviceNumberTextView.setText(number);
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
                            // 保存设备编号
                            deviceNumberManager.saveDeviceNumber(number);
                            // 立即更新显示
                            updateDeviceNumberDisplay();
                            // 如果编号变化了，更新WebSocket连接
                            webSocketManager.updateDeviceNumber(number);
                            addLog("设备编号已更新为: " + number);
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

    // 修改addLog方法
    private void addLog(String message) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String logEntry = timestamp + " - " + message + "\n";

            runOnUiThread(() -> {
                try {
                    if (logTextView != null) {
                        // 添加新日志
                        logTextView.append(logEntry);
                        
                        // 检查日志行数是否超过限制
                        if (countLines(logTextView.getText().toString()) > MAX_LOG_LINES) {
                            trimLog();
                        }
                        
                        // 自动滚动到底部
                        ScrollView scrollView = findViewById(R.id.logScrollView);
                        if (scrollView != null) {
                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                        }
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "更新日志UI失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "添加日志失败: " + e.getMessage());
        }
    }

    // 计算日志行数
    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n").length;
    }

    // 裁剪日志，只保留最新的日志
    private void trimLog() {
        String currentLog = logTextView.getText().toString();
        String[] lines = currentLog.split("\n");
        
        // 如果行数超过最大限制，只保留后面的部分
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder newLog = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                newLog.append(lines[i]).append("\n");
            }
            logTextView.setText(newLog.toString());
        }
    }

    // WebSocket消息监听回调
    @Override
    public void onMessageReceived(String message) {
        addLog("收到消息: " + message);
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        runOnUiThread(() -> {
            // 添加日志记录状态变化
            addLog("连接状态变化: " + (connected ? "已连接" : "未连接"));
            
            if (connected) {
                connectionStatusTextView.setText("已连接");
                connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                connectButton.setText("断开连接");
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
        
        try {
            unregisterReceiver(logReceiver);
            unregisterReceiver(networkStateReceiver);
        } catch (Exception e) {
            Log.e("MainActivity", "注销广播接收器失败: " + e.getMessage());
        }
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

    @Override
    public void onAccessibilityRequired() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("需要无障碍服务权限")
                    .setMessage("要执行网络切换操作，需要启用无障碍服务。是否前往设置？")
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    // 检查无障碍服务是否启用
    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + NetworkAccessibilityService.class.getCanonicalName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e("MainActivity", "无法获取无障碍服务状态: " + e.getMessage());
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(serviceName);
            }
        }
        return false;
    }

    // 显示无障碍服务设置对话框
    private void showAccessibilityServiceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要无障碍服务权限")
                .setMessage("要执行网络切换操作，需要启用无障碍服务。是否前往设置？")
                .setPositiveButton("前往设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 更新连接状态显示
    private void updateConnectionStatusDisplay() {
        boolean connected = webSocketManager.isConnected();
        connectionStatusTextView.setText(connected ? "已连接" : "未连接");
        connectionStatusTextView.setTextColor(getResources().getColor(
                connected ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        connectButton.setText(connected ? "断开连接" : "连接服务器");
    }

    // 处理网络状态变化
    private void handleNetworkStateChanged(boolean forceReconnect) {
        // 更新UI显示为未连接状态
        runOnUiThread(() -> {
            connectionStatusTextView.setText("未连接");
            connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            connectButton.setText("连接服务器");
            addLog("网络状态已变化，连接已断开");
        });
        
        // 强制断开连接
        webSocketManager.forceDisconnect();
        
        // 等待网络恢复后重新连接
        new Handler().postDelayed(() -> {
            addLog("等待网络恢复...");
            // 再等待5秒后尝试重连
            new Handler().postDelayed(() -> {
                addLog("尝试重新连接...");
                webSocketManager.connect();
                
                // 再次检查连接状态
                new Handler().postDelayed(() -> {
                    addLog("执行连接状态验证...");
                    webSocketManager.checkConnectionWithServer();
                    
                    // 最后一次检查
                    new Handler().postDelayed(() -> {
                        if (!webSocketManager.isConnected()) {
                            addLog("连接仍未建立，再次尝试...");
                            webSocketManager.connect();
                        } else {
                            addLog("连接已建立，验证连接状态...");
                            webSocketManager.checkConnectionWithServer();
                        }
                        updateConnectionStatusDisplay();
                    }, 3000);
                }, 3000);
            }, 5000);
        }, 2000);
    }

    // 在onNewIntent方法中处理从飞行模式返回
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("NETWORK_STATE_CHANGED", false)) {
            Log.d("MainActivity", "从飞行模式返回，处理网络状态变化");
            boolean forceReconnect = intent.getBooleanExtra("FORCE_RECONNECT", false);
            handleNetworkStateChanged(forceReconnect);
        }
    }

    // 注册网络状态监听器
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        Log.d("MainActivity", "网络可用");
                        
                        // 如果当前显示未连接，尝试重连
                        runOnUiThread(() -> {
                            if (connectionStatusTextView.getText().toString().equals("未连接")) {
                                addLog("网络已恢复，尝试重新连接...");
                                new Handler().postDelayed(() -> {
                                    webSocketManager.connect();
                                }, 2000);
                            }
                        });
                    }
                    
                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        Log.d("MainActivity", "网络断开");
                        
                        // 更新UI显示为未连接
                        runOnUiThread(() -> {
                            connectionStatusTextView.setText("未连接");
                            connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                            connectButton.setText("连接服务器");
                            addLog("网络已断开");
                        });
                    }
                });
            }
        }
    }

    // 在适当的生命周期方法中清理缓存
    @Override
    protected void onPause() {
        super.onPause();
        
        // 如果日志过长，在页面不可见时裁剪
        if (logTextView != null && countLines(logTextView.getText().toString()) > MAX_LOG_LINES / 2) {
            trimLog();
        }
    }

    // 添加定期清理缓存的方法
    private void scheduleCacheCleaning() {
        new Handler().postDelayed(() -> {
            // 清理应用缓存
            try {
                File cacheDir = getCacheDir();
                deleteDir(cacheDir);
                Log.d("MainActivity", "已清理应用缓存");
            } catch (Exception e) {
                Log.e("MainActivity", "清理缓存失败: " + e.getMessage());
            }
            
            // 继续定期清理
            scheduleCacheCleaning();
        }, 24 * 60 * 60 * 1000); // 每24小时清理一次
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir == null || dir.delete();
    }

    // 改进定期连接检查，添加重连逻辑
    private void startConnectionStatusCheck() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查连接状态
                if (webSocketManager.isConnected()) {
                    // 如果显示已连接，执行额外检查
                    webSocketManager.checkConnectionWithServer();
                } else if (connectionStatusTextView.getText().toString().equals("已连接")) {
                    // UI显示已连接但实际未连接，修正状态并触发重连
                    runOnUiThread(() -> {
                        connectionStatusTextView.setText("未连接");
                        connectionStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        connectButton.setText("连接服务器");
                        addLog("检测到连接状态不一致，已更新UI");
                        
                        // 添加：触发重连
                        addLog("尝试重新建立连接...");
                        webSocketManager.connect();
                    });
                } else if (!webSocketManager.isConnected() && 
                           connectionStatusTextView.getText().toString().equals("未连接")) {
                    // 添加：如果UI和实际状态都是未连接，定期尝试重连
                    long timeSinceLastReconnect = System.currentTimeMillis() - lastReconnectAttemptTime;
                    if (timeSinceLastReconnect > 60000) { // 至少60秒尝试一次重连
                        addLog("连接已断开超过1分钟，尝试自动重连...");
                        lastReconnectAttemptTime = System.currentTimeMillis();
                        webSocketManager.connect();
                    }
                }
                
                // 继续下一次检查
                new Handler().postDelayed(this, 20000); // 每20秒检查一次
            }
        }, 20000); // 首次延迟20秒
    }

    // 改进UI状态一致性检查，添加重连逻辑
    private void startUIConsistencyCheck() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查WebSocketManager状态与UI显示是否一致
                boolean managerConnected = webSocketManager.isConnected();
                boolean uiShowsConnected = connectionStatusTextView.getText().toString().equals("已连接");
                
                // 如果不一致，且差异持续超过10秒，才强制更新UI
                if (managerConnected != uiShowsConnected) {
                    // 记录差异发现时间
                    if (lastStateDifferenceTime == 0) {
                        lastStateDifferenceTime = System.currentTimeMillis();
                        Log.d("MainActivity", "检测到UI状态与连接状态不一致，开始计时");
                    } 
                    // 如果差异持续超过10秒，强制更新
                    else if (System.currentTimeMillis() - lastStateDifferenceTime > 10000) {
                        addLog("UI状态与连接状态不一致超过10秒，强制更新UI");
                        onConnectionStateChanged(managerConnected);
                        lastStateDifferenceTime = 0;
                        
                        // 添加：如果实际未连接但UI显示已连接，触发重连
                        if (!managerConnected && uiShowsConnected) {
                            addLog("检测到连接已断开，尝试重新连接...");
                            webSocketManager.connect();
                        }
                    }
                } else {
                    // 状态一致，重置差异时间
                    lastStateDifferenceTime = 0;
                }
                
                // 每15秒检查一次，减少频率
                new Handler().postDelayed(this, 15000);
            }
        }, 15000);
    }

    // 在MainActivity中添加实际连接测试方法
    private void testActualConnection() {
        // 如果UI显示未连接，但我们怀疑实际可能已连接
        if (!webSocketManager.isConnected()) {
            addLog("UI显示未连接，执行实际连接测试...");
            
            // 请求服务器发送一条测试消息
            new Thread(() -> {
                try {
                    // 构建测试URL
                    String deviceId = deviceNumberManager.getDeviceNumber();
                    String testUrl = "http://101.34.211.156:9000/send?targetDevice=" + 
                                    deviceId + "&content=连接测试&type=test";
                    
                    // 发送HTTP请求
                    URL url = new URL(testUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    
                    // 检查响应
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        // 如果服务器返回成功，等待3秒看是否收到消息
                        runOnUiThread(() -> addLog("已请求服务器发送测试消息，等待响应..."));
                        
                        // 3秒后检查是否需要强制更新连接状态
                        new Handler().postDelayed(() -> {
                            // 如果在这期间连接状态已更新，不需要操作
                            if (!webSocketManager.isConnected()) {
                                // 强制重新验证连接
                                webSocketManager.verifyConnection();
                            }
                        }, 3000);
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "连接测试失败", e);
                }
            }).start();
        }
    }
}