package com.example.wscontroller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
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
    // 修改为你的服务器地址
    private static final String SERVER_URL = "ws://101.34.211.156:9000/device/";
    private String deviceId; // 设备编号

    private WebSocket webSocket;
    private boolean isConnecting = false;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
            .build();

    private DeviceNumberManager deviceNumberManager;
    private MessageListener messageListener;

    public interface MessageListener {
        void onMessageReceived(String message);
        void onConnectionStateChanged(boolean connected);
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
        if (isConnecting || !deviceNumberManager.hasDeviceNumber()) return;
        isConnecting = true;

        // 使用设备编号建立连接
        deviceId = deviceNumberManager.getDeviceNumber();
        Log.d("WebSocket", "尝试连接到: " + SERVER_URL + deviceId);
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

                if (messageListener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            messageListener.onConnectionStateChanged(true));
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d("WebSocket", "收到消息: " + text);

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
                isConnecting = false;
                Log.d("WebSocket", "连接已关闭: " + reason);

                if (messageListener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            messageListener.onConnectionStateChanged(false));
                }

                // 尝试重连
                reconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                isConnecting = false;
                Log.e("WebSocket", "连接失败: " + t.getMessage());
                Log.e("WebSocket", "连接失败详情: ", t);

                if (messageListener != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            messageListener.onConnectionStateChanged(false));
                }

                // 尝试重连
                reconnect();
            }
        });
    }

    // 发送设备编号注册信息
    private void sendRegistration() {
        if (webSocket != null && deviceNumberManager.hasDeviceNumber()) {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "register");
                json.put("deviceNumber", deviceNumberManager.getDeviceNumber());
                webSocket.send(json.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    // 重新连接
    private void reconnect() {
        new Handler(Looper.getMainLooper()).postDelayed(this::connect, 5000); // 5秒后重连
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

            switch (action) {
                case "toggleAirplane":
                    // 暂时不实现无障碍服务控制
                    Log.d("WebSocket", "收到飞行模式切换指令");
                    break;
                // 处理其他动作...
                default:
                    Log.w("WebSocket", "未知动作: " + action);
            }
        } catch (JSONException e) {
            Log.e("WebSocket", "解析消息错误: " + e.getMessage());
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
        if (webSocket != null) {
            webSocket.close(1000, "用户关闭");
            webSocket = null;
        }
    }

    // 添加发送通知的方法
    private void sendNotification(String message) {
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
        
        try {
            JSONObject json = new JSONObject(message);
            if (json.has("type") && json.getString("type").equals("text")) {
                content = json.optString("content", message);
            }
        } catch (JSONException e) {
            // 解析失败，使用原始消息
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
    }
}