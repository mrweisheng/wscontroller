# 手机A与B通信与自动控制技术方案

## 1. 项目概述

### 1.1 需求背景
- 手机A运行hamibot脚本，需要在特定时刻通知手机B执行操作
- 手机B需要在收到通知后执行特定操作，包括控制飞行模式
- 通信需要高效可靠，最小化延迟
- 需要解决手机B频繁飞行模式切换导致的IP变化问题
- 需要支持多台B手机，并保证消息只被对应编号的B手机处理

### 1.2 解决方案概述
设计基于WebSocket的云服务器中转通信系统，实现手机A向特定编号的手机B的可靠消息传递，并通过无障碍服务在手机B上实现系统控制。每台B手机通过唯一的三位数字编号进行标识。

### 1.3 系统架构
![系统架构图](系统架构示意)

系统由三部分组成：
1. **手机A端**：运行hamibot脚本，通过HTTP请求发送指令到特定编号的B手机
2. **云服务器**：中转消息，维护WebSocket连接，管理设备编号映射
3. **手机B端**：自定义Android应用，通过设备编号标识，执行系统控制

## 2. 技术方案详细设计

### 2.1 云服务器组件

#### 2.1.1 WebSocket服务
```javascript
// 服务器端WebSocket服务 (Node.js实现)
const WebSocket = require('ws');
const express = require('express');
const http = require('http');
const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// 存储客户端连接，使用设备编号作为键
const clients = new Map();

// WebSocket连接处理
wss.on('connection', (ws, req) => {
    const deviceId = req.url.split('/').pop(); // 从URL获取设备ID
    
    console.log(`设备 ${deviceId} 已连接`);
    clients.set(deviceId, { ws, lastSeen: Date.now() });
    
    // 处理连接关闭
    ws.on('close', () => {
        console.log(`设备 ${deviceId} 已断开连接`);
        clients.delete(deviceId);
    });
    
    // 处理来自B的消息
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            console.log(`收到来自 ${deviceId} 的消息:`, data);
            
            // 处理状态更新或确认消息
            if (data.type === 'status') {
                clients.get(deviceId).status = data.status;
            }
            
            // 处理设备编号注册
            if (data.type === 'register') {
                const oldDeviceId = deviceId;
                const newDeviceId = data.deviceNumber;
                
                // 更新设备编号映射
                if (oldDeviceId !== newDeviceId) {
                    const clientData = clients.get(oldDeviceId);
                    clients.delete(oldDeviceId);
                    clients.set(newDeviceId, clientData);
                    console.log(`设备编号从 ${oldDeviceId} 更新为 ${newDeviceId}`);
                }
            }
        } catch (e) {
            console.error('解析消息错误:', e);
        }
    });
    
    // 保持连接活跃
    ws.on('pong', () => {
        if (clients.has(deviceId)) {
            clients.get(deviceId).lastSeen = Date.now();
        }
    });
});

// 定期发送ping维持连接
setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) return ws.terminate();
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

// 启动服务器
server.listen(8080, () => {
    console.log('WebSocket服务器已启动，监听端口 8080');
});
```

#### 2.1.2 HTTP API接口
```javascript
// HTTP API服务，供手机A调用
app.use(express.json());

// 发送消息API
app.post('/send', (req, res) => {
    const { targetDevice, message } = req.body;
    
    if (!targetDevice || !message) {
        return res.status(400).json({ 
            success: false, 
            error: '缺少目标设备ID或消息内容'
        });
    }
    
    // 检查设备是否连接
    if (!clients.has(targetDevice)) {
        return res.status(404).json({
            success: false,
            error: '目标设备未连接'
        });
    }
    
    try {
        // 向特定编号的B发送消息
        const client = clients.get(targetDevice);
        client.ws.send(JSON.stringify({
            ...message,
            targetDevice // 包含目标设备编号
        }));
        
        res.json({
            success: true,
            message: '消息已发送',
            deviceStatus: client.status || 'unknown'
        });
    } catch (error) {
        console.error('发送消息错误:', error);
        res.status(500).json({
            success: false,
            error: '发送消息失败'
        });
    }
});

// 获取设备状态API
app.get('/status/:deviceId', (req, res) => {
    const deviceId = req.params.deviceId;
    
    if (clients.has(deviceId)) {
        const client = clients.get(deviceId);
        res.json({
            online: true,
            lastSeen: client.lastSeen,
            status: client.status || 'unknown'
        });
    } else {
        res.json({
            online: false
        });
    }
});

// 获取所有在线设备
app.get('/devices', (req, res) => {
    const onlineDevices = [];
    
    clients.forEach((value, key) => {
        onlineDevices.push({
            deviceId: key,
            lastSeen: value.lastSeen,
            status: value.status || 'unknown'
        });
    });
    
    res.json({
        devices: onlineDevices
    });
});
```

### 2.2 手机B端应用

#### 2.2.0 设备编号管理
```java
public class DeviceNumberManager {
    private static final String DEVICE_NUMBER_FILE = "device_number.txt";
    private static final String DEVICE_NUMBER_PREF_KEY = "device_number";
    private static final String PREFS_NAME = "device_prefs";
    
    private Context context;
    private SharedPreferences prefs;
    private String deviceNumber;
    
    public DeviceNumberManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.deviceNumber = loadDeviceNumber();
    }
    
    // 加载设备编号
    private String loadDeviceNumber() {
        // 先尝试从文件读取
        String number = readDeviceNumberFromFile();
        
        // 如果文件中没有，从SharedPreferences中读取
        if (number == null || number.isEmpty()) {
            number = prefs.getString(DEVICE_NUMBER_PREF_KEY, "");
            
            // 如果SharedPreferences中有，写入文件保持一致
            if (!number.isEmpty()) {
                writeDeviceNumberToFile(number);
            }
        } else {
            // 如果文件中有，更新SharedPreferences保持一致
            prefs.edit().putString(DEVICE_NUMBER_PREF_KEY, number).apply();
        }
        
        return number;
    }
    
    // 从文件读取设备编号
    private String readDeviceNumberFromFile() {
        File file = new File(context.getExternalFilesDir(null), DEVICE_NUMBER_FILE);
        if (!file.exists()) {
            return "";
        }
        
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String number = reader.readLine();
            reader.close();
            return number != null ? number.trim() : "";
        } catch (IOException e) {
            Log.e("DeviceNumberManager", "读取设备编号文件失败", e);
            return "";
        }
    }
    
    // 写入设备编号到文件
    private void writeDeviceNumberToFile(String number) {
        File file = new File(context.getExternalFilesDir(null), DEVICE_NUMBER_FILE);
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(number);
            writer.close();
        } catch (IOException e) {
            Log.e("DeviceNumberManager", "写入设备编号文件失败", e);
        }
    }
    
    // 保存新的设备编号
    public void saveDeviceNumber(String number) {
        // 验证编号格式 (三位数字)
        if (!number.matches("\\d{3}")) {
            throw new IllegalArgumentException("设备编号必须是三位数字");
        }
        
        // 保存到内存、文件和SharedPreferences
        this.deviceNumber = number;
        writeDeviceNumberToFile(number);
        prefs.edit().putString(DEVICE_NUMBER_PREF_KEY, number).apply();
    }
    
    // 获取当前设备编号
    public String getDeviceNumber() {
        return deviceNumber;
    }
    
    // 检查是否已设置设备编号
    public boolean hasDeviceNumber() {
        return deviceNumber != null && !deviceNumber.isEmpty();
    }
}
```

#### 2.2.1 WebSocket客户端
```java
public class WebSocketManager {
    private static final String SERVER_URL = "ws://your-server-url:8080/device/";
    private String deviceId; // 设备编号
    
    private WebSocket webSocket;
    private boolean isConnecting = false;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
            .build();
    
    private DeviceNumberManager deviceNumberManager;
    
    public WebSocketManager(Context context) {
        this.deviceNumberManager = new DeviceNumberManager(context);
        this.deviceId = deviceNumberManager.getDeviceNumber();
    }
    
    // 启动WebSocket连接
    public void connect() {
        if (isConnecting || !deviceNumberManager.hasDeviceNumber()) return;
        isConnecting = true;
        
        // 使用设备编号建立连接
        deviceId = deviceNumberManager.getDeviceNumber();
        Request request = new Request.Builder()
                .url(SERVER_URL + deviceId)
                .build();
                
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                isConnecting = false;
                Log.d("WebSocket", "连接已建立");
                
                // 发送状态更新和设备编号注册
                sendRegistration();
                sendStatus("ready");
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d("WebSocket", "收到消息: " + text);
                handleMessage(text);
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                isConnecting = false;
                Log.d("WebSocket", "连接已关闭: " + reason);
                
                // 尝试重连
                reconnect();
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                isConnecting = false;
                Log.e("WebSocket", "连接失败: " + t.getMessage());
                
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
        new Handler().postDelayed(this::connect, 5000); // 5秒后重连
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
                    AirplaneModeController.toggle();
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
}
```

#### 2.2.2 无障碍服务实现飞行模式控制
```java
public class AirplaneModeService extends AccessibilityService {
    private static AirplaneModeService instance;
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    
    // 回调接口
    public interface ToggleCallback {
        void onComplete();
    }
    
    private static ToggleCallback pendingCallback;
    private static boolean isEnabling = false;
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pendingCallback == null) return;
        
        // 检测快捷设置面板
        if (event.getPackageName() != null && 
            SYSTEMUI_PACKAGE.equals(event.getPackageName().toString())) {
            
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                
                // 查找并点击飞行模式按钮
                findAndClickAirplaneButton();
            }
        }
    }
    
    @Override
    public void onInterrupt() {}
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }
    
    // 查找并点击飞行模式按钮
    private void findAndClickAirplaneButton() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        // 以下查找逻辑需要根据目标设备UI调整
        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText("飞行模式");
        if (nodes.isEmpty()) {
            nodes = rootNode.findAccessibilityNodeInfosByText("Airplane mode");
        }
        
        for (AccessibilityNodeInfo node : nodes) {
            // 向上查找可点击的父节点
            AccessibilityNodeInfo clickableNode = findClickableParent(node);
            if (clickableNode != null) {
                clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                
                // 操作完成，触发回调
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (pendingCallback != null) {
                        ToggleCallback callback = pendingCallback;
                        pendingCallback = null;
                        callback.onComplete();
                    }
                });
                
                break;
            }
        }
        
        rootNode.recycle();
    }
    
    // 查找可点击的父节点
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        if (node.isClickable()) {
            return node;
        }
        
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                return parent;
            }
            AccessibilityNodeInfo temp = parent;
            parent = parent.getParent();
            if (parent != parent) {
                temp.recycle();
            }
        }
        
        return null;
    }
    
    // 打开快捷设置并切换飞行模式
    public static void toggleAirplaneMode(ToggleCallback callback) {
        if (instance == null) {
            if (callback != null) {
                callback.onComplete();
            }
            return;
        }
        
        pendingCallback = callback;
        instance.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }
}
```

#### 2.2.3 飞行模式控制器
```java
public class AirplaneModeController {
    private static final int TOGGLE_DELAY = 1000; // 飞行模式开启后等待时间(毫秒)
    private static boolean isProcessing = false;
    
    // 切换飞行模式(开启然后关闭)
    public static void toggle() {
        if (isProcessing) return;
        isProcessing = true;
        
        // 通知WebSocket连接将断开
        WebSocketManager manager = new WebSocketManager(App.getContext());
        manager.sendStatus("toggling_airplane");
        
        // 开启飞行模式
        AirplaneModeService.toggleAirplaneMode(new AirplaneModeService.ToggleCallback() {
            @Override
            public void onComplete() {
                // 等待指定时间
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    // 关闭飞行模式
                    AirplaneModeService.toggleAirplaneMode(new AirplaneModeService.ToggleCallback() {
                        @Override
                        public void onComplete() {
                            isProcessing = false;
                            
                            // 等待网络恢复后重连
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                manager.connect();
                            }, 3000); // 等待网络恢复
                        }
                    });
                }, TOGGLE_DELAY); // 飞行模式开启时间
            }
        });
    }
}
```

### 2.3 手机A端hamibot脚本

```javascript
// 手机A的Hamibot脚本
const SERVER_URL = "http://your-server-url:8080";

/**
 * 向指定编号的手机B发送指令
 * @param {string} targetDevice - 目标设备编号(三位数字)
 * @param {string} action - 操作指令
 * @param {Object} params - 附加参数
 * @returns {Promise<boolean>} - 是否成功
 */
function sendCommandToDeviceB(targetDevice, action, params = {}) {
    return new Promise((resolve, reject) => {
        try {
            // 验证设备编号格式
            if (!targetDevice.match(/^\d{3}$/)) {
                console.error("设备编号必须是三位数字");
                resolve(false);
                return;
            }
            
            // 构建消息
            const message = {
                action: action,
                ...params,
                timestamp: Date.now()
            };
            
            // 构建请求
            const requestBody = JSON.stringify({
                targetDevice: targetDevice,
                message: message
            });
            
            // 发送HTTP请求
            http.request({
                url: SERVER_URL + "/send",
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: requestBody,
                timeout: 10000
            }, function(response, error) {
                if (error) {
                    console.error("发送命令失败:", error);
                    resolve(false);
                    return;
                }
                
                try {
                    const result = JSON.parse(response.body.string());
                    if (result.success) {
                        console.log("命令发送成功");
                        resolve(true);
                    } else {
                        console.error("服务器返回错误:", result.error);
                        resolve(false);
                    }
                } catch (e) {
                    console.error("解析响应失败:", e);
                    resolve(false);
                }
            });
        } catch (e) {
            console.error("发送命令出错:", e);
            resolve(false);
        }
    });
}

/**
 * 获取所有在线设备
 * @returns {Promise<Array>} - 设备列表
 */
function getOnlineDevices() {
    return new Promise((resolve, reject) => {
        http.request({
            url: SERVER_URL + "/devices",
            method: "GET",
            timeout: 5000
        }, function(response, error) {
            if (error) {
                console.error("获取设备列表失败:", error);
                resolve([]);
                return;
            }
            
            try {
                const result = JSON.parse(response.body.string());
                resolve(result.devices || []);
            } catch (e) {
                console.error("解析设备列表失败:", e);
                resolve([]);
            }
        });
    });
}

/**
 * 检查特定编号设备的状态
 * @param {string} deviceNumber - 设备编号
 * @returns {Promise<Object>} - 状态信息
 */
function checkDeviceStatus(deviceNumber) {
    return new Promise((resolve, reject) => {
        http.request({
            url: SERVER_URL + "/status/" + deviceNumber,
            method: "GET",
            timeout: 5000
        }, function(response, error) {
            if (error) {
                console.error("检查状态失败:", error);
                resolve({ online: false, error: error });
                return;
            }
            
            try {
                const result = JSON.parse(response.body.string());
                resolve(result);
            } catch (e) {
                console.error("解析状态失败:", e);
                resolve({ online: false, error: e });
            }
        });
    });
}

// 使用示例
async function main() {
    // 设置目标设备编号
    const targetDevice = "001"; // 需要控制的B手机编号
    
    // 检查设备状态
    const status = await checkDeviceStatus(targetDevice);
    console.log(`设备 ${targetDevice} 状态:`, status);
    
    if (status.online) {
        // 发送控制飞行模式的命令
        const success = await sendCommandToDeviceB(targetDevice, "toggleAirplane");
        if (success) {
            console.log("飞行模式控制命令已发送");
        } else {
            console.log("命令发送失败");
        }
    } else {
        console.log(`设备 ${targetDevice} 不在线，无法发送命令`);
    }
}

// 执行主函数
main();
```

## 3. 实现注意事项

### 3.1 无障碍服务配置

在AndroidManifest.xml中添加：

```xml
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<service
    android:name=".AirplaneModeService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

accessibility_service_config.xml:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFlags="flagDefault"
    android:accessibilityFeedbackType="feedbackAllMask"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100" />
```

### 3.2 前台服务实现

为确保应用在后台稳定运行，需要实现前台服务：

```java
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
                .setSmallIcon(R.drawable.ic_notification)
                .build();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);
        
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
        // 尝试重启服务
        Intent restartIntent = new Intent(this, WebSocketService.class);
        startService(restartIntent);
    }
}
```

### 3.3 设备编号设置界面

```java
public class DeviceNumberActivity extends AppCompatActivity {
    private EditText deviceNumberEditText;
    private Button saveButton;
    private DeviceNumberManager deviceNumberManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_number);
        
        deviceNumberManager = new DeviceNumberManager(this);
        
        deviceNumberEditText = findViewById(R.id.deviceNumberEditText);
        saveButton = findViewById(R.id.saveButton);
        
        // 显示当前设备编号
        if (deviceNumberManager.hasDeviceNumber()) {
            deviceNumberEditText.setText(deviceNumberManager.getDeviceNumber());
        }
        
        // 保存按钮
        saveButton.setOnClickListener(v -> {
            String number = deviceNumberEditText.getText().toString().trim();
            
            // 验证输入
            if (!number.matches("\\d{3}")) {
                Toast.makeText(this, "设备编号必须是三位数字", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                // 保存编号
                deviceNumberManager.saveDeviceNumber(number);
                
                // 重启WebSocket服务
                Intent serviceIntent = new Intent(this, WebSocketService.class);
                stopService(serviceIntent);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                
                Toast.makeText(this, "设备编号已保存", Toast.LENGTH_SHORT).show();
                finish();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
```

### 3.4 应用启动时自动启动服务和检查设备编号

在MainActivity中添加：

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    
    // 检查设备编号
    DeviceNumberManager deviceNumberManager = new DeviceNumberManager(this);
    if (!deviceNumberManager.hasDeviceNumber()) {
        // 引导用户设置设备编号
        showDeviceNumberDialog();
    } else {
        // 已有编号，检查无障碍服务是否启用
        if (!isAccessibilityServiceEnabled()) {
            // 引导用户启用无障碍服务
            showAccessibilityServiceDialog();
        }
        
        // 启动WebSocket服务
        startWebSocketService();
    }
}

// 显示设备编号设置对话框
private void showDeviceNumberDialog() {
    new AlertDialog.Builder(this)
        .setTitle("设置设备编号")
        .setMessage("请为此设备设置一个唯一的三位数字编号(例如: 001)")
        .setPositiveButton("设置", (dialog, which) -> {
            Intent intent = new Intent(this, DeviceNumberActivity.class);
            startActivity(intent);
        })
        .setCancelable(false)
        .show();
}

// 检查无障碍服务是否已启用
private boolean isAccessibilityServiceEnabled() {
    String serviceName = getPackageName() + "/" + AirplaneModeService.class.getCanonicalName();
    int accessibilityEnabled = 0;
    try {
        accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
    } catch (Settings.SettingNotFoundException e) {
        e.printStackTrace();
    }
    
    if (accessibilityEnabled == 1) {
        String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue != null) {
            return settingValue.contains(serviceName);
        }
    }
    
    return false;
}

// 显示无障碍服务引导对话框
private void showAccessibilityServiceDialog() {
    new AlertDialog.Builder(this)
        .setTitle("需要开启无障碍服务")
        .setMessage("此应用需要无障碍服务权限才能控制系统设置。请前往设置开启。")
        .setPositiveButton("前往设置", (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        })
        .setNegativeButton("取消", null)
        .setCancelable(false)
        .show();
}

// 启动WebSocket服务
private void startWebSocketService() {
    Intent serviceIntent = new Intent(this, WebSocketService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(serviceIntent);
    } else {
        startService(serviceIntent);
    }
}

@Override
protected void onResume() {
    super.onResume();
    
    // 每次回到应用都检查设备编号
    DeviceNumberManager deviceNumberManager = new DeviceNumberManager(this);
    if (!deviceNumberManager.hasDeviceNumber()) {
        showDeviceNumberDialog();
    }
}
```

## 4. 安全性考虑

### 4.1 通信安全
- 使用WSS (WebSocket Secure)替代WS
- 添加简单的身份验证机制
- 对通信内容加密
- 验证设备编号的合法性

### 4.2 API访问控制
- 添加访问令牌验证
- 限制请求频率
- 记录异常访问日志
- 验证发送目标设备编号的格式

### 4.3 权限控制
- 最小化请求的系统权限
- 用户同意后才启用无障碍服务
- 明确告知用户设备编号的用途

## 5. 故障处理

### 5.1 网络异常处理
- 实现指数退避重连策略
- 维护连接状态监控
- 添加网络状态变化监听
- 断网时保存待发送的命令

### 5.2 系统兼容性
- 适配不同Android版本的快捷设置UI
- 处理厂商定制UI的差异
- 兼容不同存储权限策略

### 5.3 服务状态监控
- 添加服务状态检查机制
- 实现自动恢复逻辑
- 离线消息队列处理
- 设备编号冲突检测

## 6. 部署指南

### 6.1 服务器部署
1. 准备云服务器，开放8080端口
2. 安装Node.js环境
3. 部署WebSocket和HTTP API服务
4. 配置自动重启和日志管理
5. 可选：配置SSL证书实现加密通信

### 6.2 手机B应用安装
1. 使用Android Studio构建APK
2. 手动安装到设备B
3. 首次启动时设置三位数字设备编号
4. 授予无障碍服务权限
5. 验证WebSocket连接状态
6. 测试飞行模式控制功能

### 6.3 手机A配置
1. 配置hamibot脚本，设置正确的服务器地址
2. 指定目标B手机的三位数编号
3. 测试发送命令功能
4. 验证整体通信流程

## 7. 测试计划

### 7.1 单元测试
- WebSocket连接建立与断开
- 消息发送与接收
- 无障碍服务操作验证
- 设备编号管理功能

### 7.2 集成测试
- A发送命令到特定编号B的完整流程
- 飞行模式切换的可靠性测试
- 网络异常情况下的恢复能力
- 多设备并发通信测试

### 7.3 性能测试
- 指令响应时间测量
- 长时间运行稳定性
- 资源消耗监控
- 大量设备连接时的服务器负载测试