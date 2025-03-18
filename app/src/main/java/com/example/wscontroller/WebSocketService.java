package com.example.wscontroller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class WebSocketService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private WebSocketManager webSocketManager;
    private DeviceNumberManager deviceNumberManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        deviceNumberManager = new DeviceNumberManager(this);
        webSocketManager = new WebSocketManager(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 创建通知渠道
        createNotificationChannel();
        
        // 创建通知
        Notification notification = new NotificationCompat.Builder(this, "websocket_channel")
                .setContentTitle("通信服务")
                .setContentText("正在运行中...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        
        // 启动前台服务，指定服务类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        // 连接WebSocket
        if (deviceNumberManager.hasDeviceNumber()) {
            webSocketManager.connect();
        }
        
        // 确保服务不会被系统轻易杀死
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "websocket_channel",
                    "WebSocket Service",
                    NotificationManager.IMPORTANCE_LOW);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // 断开WebSocket连接
        if (webSocketManager != null) {
            webSocketManager.disconnect();
        }
        
        // 尝试重启服务
        Intent restartIntent = new Intent(this, WebSocketService.class);
        startService(restartIntent);
    }
} 