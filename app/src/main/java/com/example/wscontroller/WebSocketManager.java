package com.example.wscontroller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketManager {
    // 修改为实际服务器地址
    private static final String SERVER_URL = "ws://101.34.211.156:9000/device/";
    private String deviceId; // 设备编号

    private WebSocket webSocket;
    private boolean isConnecting = false;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
            .build();

    private DeviceNumberManager deviceNumberManager;
    private MessageListener messageListener;

    // 添加连接状态变量
    private boolean isConnected = false;

    // 添加心跳检测
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (webSocket != null && isConnected) {
                try {
                    // 发送心跳消息
                    JSONObject json = new JSONObject();
                    json.put("type", "ping");
                    json.put("timestamp", System.currentTimeMillis());
                    json.put("deviceId", deviceId);
                    
                    // 记录发送时间
                    long sentTime = System.currentTimeMillis();
                    
                    // 发送心跳并检查结果
                    boolean sent = webSocket.send(json.toString());
                    if (!sent) {
                        Log.d("WebSocket", "心跳消息发送失败，但不立即断开连接");
                        // 增加失败计数而不是立即断开
                        consecutiveFailedChecks++;
                        
                        if (consecutiveFailedChecks >= REQUIRED_FAILED_CHECKS) {
                            Log.d("WebSocket", "多次心跳失败，更新连接状态为断开");
                            updateConnectionState(false);
                            // 尝试重连
                            reconnect();
                            return;
                        }
                    } else {
                        // 心跳发送成功，重置失败计数
                        consecutiveFailedChecks = 0;
                    }
                    
                    // 设置心跳响应超时检查，但不立即断开连接
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        // 如果在15秒内没有收到pong响应，增加失败计数
                        if (isConnected && lastPongTime < sentTime) {
                            Log.d("WebSocket", "心跳超时，15秒内未收到pong响应");
                            consecutiveFailedChecks++;
                            
                            if (consecutiveFailedChecks >= REQUIRED_FAILED_CHECKS) {
                                Log.d("WebSocket", "多次心跳超时，强制断开重连");
                                // 强制断开连接
                                forceDisconnect();
                                // 延迟1秒后重连
                                new Handler(Looper.getMainLooper()).postDelayed(() -> connect(), 1000);
                            }
                        } else {
                            // 收到了pong响应，重置失败计数
                            consecutiveFailedChecks = 0;
                        }
                    }, 15000); // 15秒超时
                } catch (Exception e) {
                    Log.e("WebSocket", "发送心跳消息失败", e);
                    // 增加失败计数而不是立即断开
                    consecutiveFailedChecks++;
                    
                    if (consecutiveFailedChecks >= REQUIRED_FAILED_CHECKS) {
                        // 更新连接状态为断开
                        updateConnectionState(false);
                        // 尝试重连
                        reconnect();
                        return;
                    }
                }
                
                // 继续下一次心跳
                heartbeatHandler.postDelayed(this, 15000); // 15秒一次
            }
        }
    };

    // 添加指数退避重连机制
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_RECONNECT_DELAY = 5000; // 5秒

    // 添加最后一次收到pong的时间戳
    private long lastPongTime = 0;

    // 添加连接状态稳定性控制变量
    private long lastStateChangeTime = 0;
    private static final long STATE_CHANGE_COOLDOWN = 5000; // 状态变化冷却时间(5秒)
    private int consecutiveFailedChecks = 0; // 连续失败检查次数
    private static final int REQUIRED_FAILED_CHECKS = 2; // 需要多少次连续失败才改变状态

    // 添加自动重连相关变量
    private boolean autoReconnectEnabled = true; // 是否启用自动重连
    private Handler autoReconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable autoReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected && autoReconnectEnabled) {
                Log.d("WebSocket", "执行自动重连...");
                connect();
                
                // 如果仍然需要重连，安排下一次尝试
                if (!isConnected) {
                    // 使用指数退避策略
                    long delay = calculateReconnectDelay();
                    Log.d("WebSocket", "重连未成功，将在 " + (delay/1000) + " 秒后再次尝试");
                    autoReconnectHandler.postDelayed(this, delay);
                }
            }
        }
    };

    public interface MessageListener {
        void onMessageReceived(String message);
        void onConnectionStateChanged(boolean connected);
        void onAccessibilityRequired();
    }

    public WebSocketManager(Context context) {
        this.deviceNumberManager = new DeviceNumberManager(context);
        this.deviceId = deviceNumberManager.getDeviceNumber();
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    // 启动WebSocket连接
    public void connect() {
        // 启用自动重连
        autoReconnectEnabled = true;
        
        if (isConnecting || !deviceNumberManager.hasDeviceNumber()) return;
        
        // 先断开现有连接
        forceDisconnect();
        
        isConnecting = true;

        // 使用设备编号建立连接
        deviceId = deviceNumberManager.getDeviceNumber();
        Log.d("WebSocket", "尝试连接到: " + SERVER_URL + deviceId);
        
        // 检查网络状态
        ConnectivityManager cm = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        
        if (!isConnected) {
            Log.d("WebSocket", "网络未连接，无法建立WebSocket连接");
            isConnecting = false;
            
            // 通知UI网络未连接
            if (messageListener != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    messageListener.onMessageReceived("网络未连接，无法建立连接");
                    messageListener.onConnectionStateChanged(false);
                });
            }
            return;
        }
        
        Request request = new Request.Builder()
                .url(SERVER_URL + deviceId)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                isConnecting = false;
                Log.d("WebSocket", "连接已建立");

                // 发送设备编号注册
                sendRegistration();
                sendStatus("ready");

                // 发送连接成功通知
                sendConnectionNotification();

                // 更新连接状态 - 确保在主线程中更新UI
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateConnectionState(true);
                    
                    // 添加额外日志确认状态更新
                    Log.d("WebSocket", "连接状态已更新为：已连接");
                });

                // 启动心跳检测
                startHeartbeat();

                // 重置重连计数
                reconnectAttempts = 0;

                // 启动定期状态更新
                startPeriodicStatusUpdate();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d("WebSocket", "收到消息: " + text);
                
                // 处理pong响应
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type", "");
                    if ("pong".equals(type)) {
                        lastPongTime = System.currentTimeMillis();
                        Log.d("WebSocket", "收到pong响应，更新最后pong时间");
                        return; // 不需要进一步处理pong消息
                    }
                } catch (Exception e) {
                    // 解析失败，当作普通消息处理
                }

                // 发送系统通知
                sendNotification(text);

                if (messageListener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            messageListener.onMessageReceived(text));
                }

                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d("WebSocket", "连接已关闭: 代码=" + code + ", 原因=" + reason);
                
                // 立即更新连接状态为断开
                updateConnectionState(false);
                
                // 清理资源 - 使用外部类的引用
                WebSocketManager.this.webSocket = null;  // 修正这一行
                isConnecting = false;
                
                // 如果不是客户端主动关闭，尝试重连
                if (code != 1000 && code != 1001) {
                    reconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e("WebSocket", "连接失败: " + (t != null ? t.getMessage() : "未知错误"));
                
                // 立即更新连接状态为断开
                updateConnectionState(false);
                
                // 清理资源 - 使用外部类的引用
                WebSocketManager.this.webSocket = null;  // 修正这一行
                isConnecting = false;
                
                // 尝试重连
                reconnect();
            }
        });
    }

    // 发送设备编号注册信息
    private void sendRegistration() {
        try {
            if (webSocket != null) {
                JSONObject json = new JSONObject();
                json.put("type", "register");
                json.put("deviceNumber", deviceId);
                json.put("timestamp", System.currentTimeMillis());
                
                boolean sent = webSocket.send(json.toString());
                Log.d("WebSocket", "发送注册消息: " + json.toString() + ", 结果: " + sent);
                
                if (!sent) {
                    Log.d("WebSocket", "注册消息发送失败，连接可能已断开");
                    forceDisconnect();
                    new Handler(Looper.getMainLooper()).postDelayed(this::connect, 1000);
                }
            }
        } catch (Exception e) {
            Log.e("WebSocket", "发送注册消息失败", e);
        }
    }

    // 重新连接
    private void reconnect() {
        // 如果已经在重连中，不要重复操作
        if (isConnecting) return;
        
        // 如果达到最大重试次数，延长等待时间
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d("WebSocket", "达到最大重连次数，延长等待时间");
            reconnectAttempts = 0;
            
            // 延迟30秒后再次尝试
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // 确保连接状态正确
                updateConnectionState(false);
                // 尝试连接
                connect();
            }, 30000);
            return;
        }
        
        // 计算延迟时间（指数退避）
        long delay = BASE_RECONNECT_DELAY * (long)Math.pow(1.5, reconnectAttempts);
        reconnectAttempts++;
        
        Log.d("WebSocket", "计划在 " + delay + "ms 后进行第 " + reconnectAttempts + " 次重连");
        
        // 延迟后重连
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // 确保连接状态正确
            updateConnectionState(false);
            // 尝试连接
            connect();
        }, delay);
    }

    // 发送状态更新
    public void sendStatus(String status) {
        if (webSocket != null) {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "status");
                json.put("status", status);
                webSocket.send(json.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    // 处理接收到的消息
    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);

            // 验证消息是否发给当前设备
            String targetDevice = json.optString("targetDevice");
            if (!targetDevice.isEmpty() && !targetDevice.equals(deviceNumberManager.getDeviceNumber())) {
                Log.d("WebSocket", "消息目标不匹配，忽略");
                return;
            }

            String action = json.optString("action");
            String content = json.optString("content", "");

            switch (action) {
                case "toggleAirplane":
                    // 暂时不实现无障碍服务控制
                    Log.d("WebSocket", "收到飞行模式切换指令");
                    break;
                default:
                    // 检查消息内容是否包含"请切换网络"
                    if (content.contains("请切换网络") || message.contains("请切换网络")) {
                        Log.d("WebSocket", "收到切换网络指令");
                        
                        // 通知UI更新日志
                        if (messageListener != null) {
                            new Handler(Looper.getMainLooper()).post(() ->
                                    messageListener.onMessageReceived("收到切换网络指令，准备执行下拉操作"));
                        }
                        
                        performNetworkSwitch();
                    } else {
                        Log.w("WebSocket", "未知动作: " + action);
                    }
            }
        } catch (JSONException e) {
            // 尝试检查原始消息
            if (message.contains("请切换网络")) {
                Log.d("WebSocket", "收到切换网络指令(文本匹配)");
                
                // 通知UI更新日志
                if (messageListener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            messageListener.onMessageReceived("收到切换网络指令(文本匹配)，准备执行下拉操作"));
                }
                
                performNetworkSwitch();
            } else {
                Log.e("WebSocket", "解析消息错误: " + e.getMessage());
            }
        }
    }

    // 执行网络切换操作
    private void performNetworkSwitch() {
        // 检查无障碍服务是否启用
        NetworkAccessibilityService service = NetworkAccessibilityService.getInstance();
        if (service != null) {
            // 通知UI更新日志
            if (messageListener != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        messageListener.onMessageReceived("正在执行网络切换操作..."));
            }
            
            // 在主线程中执行
            new Handler(Looper.getMainLooper()).post(() -> {
                service.toggleNetwork(); // 调用新的toggleNetwork方法
            });
        } else {
            Log.e("WebSocket", "无障碍服务未启用，无法执行网络切换");
            
            // 通知UI更新日志
            if (messageListener != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        messageListener.onMessageReceived("无障碍服务未启用，无法执行网络切换"));
            }
            
            // 通知用户需要启用无障碍服务
            if (messageListener != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        messageListener.onAccessibilityRequired());
            }
        }
    }

    // 更新设备编号
    public void updateDeviceNumber(String newNumber) {
        // 关闭现有连接
        if (webSocket != null) {
            webSocket.close(1000, "设备编号更新");
        }

        // 更新设备编号
        deviceNumberManager.saveDeviceNumber(newNumber);
        deviceId = newNumber;

        // 重新连接
        connect();
    }

    // 关闭连接
    public void disconnect() {
        Log.d("WebSocket", "用户主动断开连接");
        
        // 禁用自动重连
        autoReconnectEnabled = false;
        stopAutoReconnect();
        
        // 断开连接
        forceDisconnect();
    }

    // 修改发送通知的方法
    private void sendNotification(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "");
            String action = json.optString("action", "");
            
            // 只对特定类型的消息发送通知
            // 系统消息和欢迎消息不发送通知
            if (type.equals("system") && (action.equals("welcome") || 
                                          action.equals("register_success") || 
                                          action.equals("status_updated"))) {
                return;
            }
            
            // 创建通知
            Context context = App.getContext();
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            
            // 创建通知渠道（Android 8.0及以上需要）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "message_channel",
                        "消息通知",
                        NotificationManager.IMPORTANCE_HIGH);
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                manager.createNotificationChannel(channel);
            }
            
            // 解析消息内容，提取有用信息
            String title = "收到新消息";
            String content = message;
            
            if (json.has("message")) {
                content = json.optString("message", message);
            } else if (json.has("content")) {
                content = json.optString("content", message);
            }
            
            // 创建通知
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "message_channel")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            
            // 发送通知
            try {
                // 使用随机ID，确保每条消息都显示
                int notificationId = (int) System.currentTimeMillis();
                notificationManager.notify(notificationId, builder.build());
            } catch (SecurityException e) {
                Log.e("WebSocket", "没有通知权限", e);
            }
        } catch (JSONException e) {
            // JSON解析失败，不发送通知
            Log.e("WebSocket", "解析消息失败，不发送通知", e);
        }
    }

    // 添加连接成功通知方法
    private void sendConnectionNotification() {
        // 创建通知
        Context context = App.getContext();
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        
        // 创建通知渠道（Android 8.0及以上需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "connection_channel",
                    "连接状态通知",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        
        // 创建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "connection_channel")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("连接成功")
                .setContentText("已成功连接到服务器")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        
        // 发送通知
        try {
            notificationManager.notify(1001, builder.build());
        } catch (SecurityException e) {
            Log.e("WebSocket", "没有通知权限", e);
        }
    }

    // 改进isConnected方法，增加容错性
    public boolean isConnected() {
        // 检查基本状态
        if (!isConnected || webSocket == null) {
            return false;
        }
        
        // 检查最后一次pong响应时间，但使用更宽松的超时时间
        long timeSinceLastPong = System.currentTimeMillis() - lastPongTime;
        if (lastPongTime > 0 && timeSinceLastPong > 120000) { // 2分钟内没有pong响应
            Log.d("WebSocket", "连接状态检查：超过2分钟未收到pong响应");
            return false;
        }
        
        return true;
    }

    // 改进updateConnectionState方法，增加状态稳定性机制
    private void updateConnectionState(boolean connected) {
        // 记录当前时间
        long currentTime = System.currentTimeMillis();
        
        // 如果距离上次状态变化时间太短，且不是强制性的状态变化，则忽略
        if (currentTime - lastStateChangeTime < STATE_CHANGE_COOLDOWN) {
            // 如果是要设置为已连接，可以立即执行（提高响应速度）
            if (connected && !isConnected) {
                Log.d("WebSocket", "虽然在冷却期，但允许立即更新为已连接状态");
            } 
            // 如果是要设置为未连接，需要累计失败次数
            else if (!connected && isConnected) {
                consecutiveFailedChecks++;
                Log.d("WebSocket", "连接检查失败，当前累计失败次数: " + consecutiveFailedChecks);
                
                // 如果失败次数不够，暂不更新状态
                if (consecutiveFailedChecks < REQUIRED_FAILED_CHECKS) {
                    Log.d("WebSocket", "失败次数不足，保持当前连接状态");
                    return;
                }
                Log.d("WebSocket", "达到所需失败次数，准备更新状态");
            }
            // 如果状态没变化，直接返回
            else if (connected == isConnected) {
                return;
            }
        }
        
        // 如果要设置为未连接，先进行实际连接检查
        if (!connected && webSocket != null) {
            try {
                // 发送一个简单消息测试连接
                JSONObject testMsg = new JSONObject();
                testMsg.put("type", "connection_test");
                testMsg.put("timestamp", currentTime);
                boolean sent = webSocket.send(testMsg.toString());
                
                // 如果消息能发送成功，说明连接实际上是有效的
                if (sent) {
                    Log.d("WebSocket", "尝试设置为未连接状态，但连接测试成功，保持连接状态");
                    consecutiveFailedChecks = 0; // 重置失败计数
                    return; // 直接返回，不更新状态
                }
            } catch (Exception e) {
                // 发送失败，确认连接已断开
                Log.d("WebSocket", "连接测试失败，确认连接已断开", e);
            }
        }
        
        // 记录之前的状态
        boolean wasConnected = isConnected;
        
        // 更新当前状态
        isConnected = connected;
        
        // 如果状态发生变化，通知监听器
        if (wasConnected != connected) {
            // 更新最后状态变化时间
            lastStateChangeTime = currentTime;
            
            // 重置失败计数
            consecutiveFailedChecks = 0;
            
            Log.d("WebSocket", "连接状态变化: " + (connected ? "已连接" : "已断开") + 
                  " (WebSocket对象: " + (webSocket != null ? "存在" : "不存在") + ")");
            
            // 如果断开连接，确保清理资源并启动自动重连
            if (!connected) {
                // 停止心跳
                stopHeartbeat();
                
                // 如果WebSocket对象仍然存在，尝试关闭它
                if (webSocket != null) {
                    try {
                        webSocket.close(1000, "客户端主动断开");
                    } catch (Exception e) {
                        Log.e("WebSocket", "关闭WebSocket时出错", e);
                    }
                    webSocket = null;
                }
                
                // 启动自动重连
                startAutoReconnect();
            } else {
                // 连接成功，停止自动重连
                stopAutoReconnect();
                reconnectAttempts = 0;
            }
            
            // 通知UI更新 - 使用final变量
            final boolean finalConnected = connected;
            if (messageListener != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    messageListener.onConnectionStateChanged(finalConnected);
                    // 添加日志确认UI通知已发送
                    Log.d("WebSocket", "已通知UI更新连接状态为: " + (finalConnected ? "已连接" : "已断开"));
                });
            }
        }
    }

    // 开始心跳检测
    private void startHeartbeat() {
        stopHeartbeat(); // 先停止现有的心跳
        heartbeatHandler.postDelayed(heartbeatRunnable, 20000);
    }

    // 停止心跳检测
    private void stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
    }

    // 检查连接状态
    private void checkConnectionState() {
        boolean shouldBeConnected = webSocket != null;
        
        // 如果状态不一致，更新状态
        if (isConnected != shouldBeConnected) {
            updateConnectionState(shouldBeConnected);
        }
    }

    // 改进强制断开连接方法
    public void forceDisconnect() {
        Log.d("WebSocket", "强制断开连接");
        
        // 停止心跳检测
        stopHeartbeat();
        
        // 更新连接状态为断开
        updateConnectionState(false);
        
        // 关闭现有连接
        if (webSocket != null) {
            try {
                // 先发送一个断开连接的消息，让服务器知道
                try {
                    JSONObject disconnectMsg = new JSONObject();
                    disconnectMsg.put("type", "disconnect");
                    disconnectMsg.put("deviceId", deviceId);
                    disconnectMsg.put("timestamp", System.currentTimeMillis());
                    webSocket.send(disconnectMsg.toString());
                } catch (Exception e) {
                    // 忽略发送断开消息的错误
                }
                
                // 关闭连接
                webSocket.close(1001, "客户端主动断开");
                
                // 确保连接真的关闭
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (webSocket != null) {
                        try {
                            webSocket.cancel(); // 强制取消
                        } catch (Exception e) {
                            // 忽略错误
                        }
                        webSocket = null;
                    }
                }, 1000);
            } catch (Exception e) {
                Log.e("WebSocket", "关闭连接时出错", e);
                webSocket = null;
            }
        }
        
        // 重置状态
        isConnecting = false;
        lastPongTime = 0;
        reconnectAttempts = 0;
    }

    // 检查与服务器的连接状态
    public void checkConnectionWithServer() {
        if (webSocket == null) {
            Log.d("WebSocket", "WebSocket对象为null，连接已断开");
            updateConnectionState(false);
            return;
        }
        
        try {
            // 发送ping消息检查连接
            JSONObject pingMessage = new JSONObject();
            pingMessage.put("type", "ping");
            pingMessage.put("timestamp", System.currentTimeMillis());
            pingMessage.put("deviceId", deviceId);
            pingMessage.put("checkConnection", true); // 标记这是一个连接检查ping
            
            long sentTime = System.currentTimeMillis();
            boolean sent = webSocket.send(pingMessage.toString());
            Log.d("WebSocket", "发送连接检查ping: " + sent + ", 时间: " + sentTime);
            
            if (!sent) {
                Log.d("WebSocket", "连接检查ping发送失败，连接已断开");
                forceDisconnect();
                new Handler(Looper.getMainLooper()).postDelayed(this::connect, 1000);
                return;
            }
            
            // 设置超时检查
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // 如果在5秒内没有收到pong响应，认为连接已断开
                if (isConnected && lastPongTime < sentTime) {
                    Log.d("WebSocket", "连接检查超时，5秒内未收到pong响应");
                    
                    // 尝试重新注册设备
                    sendRegistration();
                    
                    // 再次检查
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (lastPongTime < sentTime) {
                            Log.d("WebSocket", "重新注册后仍未收到响应，强制重连");
                            forceDisconnect();
                            connect();
                        } else {
                            Log.d("WebSocket", "重新注册后收到响应，连接恢复");
                        }
                    }, 3000);
                } else {
                    Log.d("WebSocket", "连接检查通过，收到pong响应");
                }
            }, 5000);
        } catch (Exception e) {
            Log.e("WebSocket", "发送连接检查ping失败", e);
            forceDisconnect();
            new Handler(Looper.getMainLooper()).postDelayed(this::connect, 1000);
        }
    }

    // 添加定期连接状态验证
    public void startPeriodicConnectionCheck() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    // 验证连接状态
                    checkConnectionWithServer();
                }
                
                // 继续下一次检查
                new Handler(Looper.getMainLooper()).postDelayed(this, 45000); // 45秒检查一次
            }
        }, 45000);
    }

    // 添加主动验证连接的方法
    public void verifyConnection() {
        if (webSocket == null) {
            Log.d("WebSocket", "WebSocket对象为null，连接已断开");
            updateConnectionState(false);
            return;
        }
        
        try {
            // 发送特殊的验证ping消息
            JSONObject pingMessage = new JSONObject();
            pingMessage.put("type", "ping");
            pingMessage.put("timestamp", System.currentTimeMillis());
            pingMessage.put("deviceId", deviceId);
            pingMessage.put("verifyConnection", true);
            
            long sentTime = System.currentTimeMillis();
            boolean sent = webSocket.send(pingMessage.toString());
            
            if (!sent) {
                Log.d("WebSocket", "验证ping发送失败，连接已断开");
                forceDisconnect();
                connect(); // 立即尝试重连
                return;
            }
            
            // 设置验证超时检查
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // 如果在5秒内没有收到pong响应，认为连接已断开
                if (isConnected && lastPongTime < sentTime) {
                    Log.d("WebSocket", "连接验证超时，强制重连");
                    forceDisconnect();
                    connect();
                }
            }, 5000);

            // 在重要事件后更新状态
            if (isConnected && lastPongTime >= sentTime) {
                // 连接验证成功，更新状态
                sendStatus("ready");
            }
        } catch (Exception e) {
            Log.e("WebSocket", "验证连接失败", e);
            forceDisconnect();
            connect();
        }
    }

    // 添加定期状态更新
    private void startPeriodicStatusUpdate() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && webSocket != null) {
                    // 发送当前状态
                    sendStatus("ready");
                    
                    // 记录日志
                    Log.d("WebSocket", "已发送定期状态更新");
                }
                
                // 每5分钟更新一次状态
                new Handler(Looper.getMainLooper()).postDelayed(this, 5 * 60 * 1000);
            }
        }, 5 * 60 * 1000);
    }

    // 计算重连延迟（指数退避）
    private long calculateReconnectDelay() {
        // 基础延迟5秒，最大延迟2分钟
        long delay = Math.min(BASE_RECONNECT_DELAY * (1 << Math.min(reconnectAttempts, 5)), 120000);
        reconnectAttempts++;
        return delay;
    }

    // 启动自动重连
    private void startAutoReconnect() {
        stopAutoReconnect(); // 先停止现有的重连任务
        
        if (autoReconnectEnabled) {
            Log.d("WebSocket", "启动自动重连机制");
            reconnectAttempts = 0; // 重置重连尝试次数
            autoReconnectHandler.postDelayed(autoReconnectRunnable, 1000); // 1秒后开始第一次重连
        }
    }

    // 停止自动重连
    private void stopAutoReconnect() {
        autoReconnectHandler.removeCallbacks(autoReconnectRunnable);
    }
}