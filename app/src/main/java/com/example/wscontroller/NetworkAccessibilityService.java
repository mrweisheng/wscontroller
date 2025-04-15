package com.example.wscontroller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class NetworkAccessibilityService extends AccessibilityService {
    private static final String TAG = "NetworkAccessibility";
    private static NetworkAccessibilityService instance;
    
    // 保存最后一次找到的飞行模式节点的位置信息
    private Rect lastAirplaneModeLocation = null;
    private boolean isToggleInProgress = false;

    // 优化日志输出，减少不必要的日志
    private static final boolean VERBOSE_LOGGING = false; // 设置为false可减少日志输出

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理事件
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务中断");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "无障碍服务已连接");
    }

    public static NetworkAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isEnabled() {
        return instance != null;
    }

    // 执行完整的网络切换流程
    public void toggleNetwork() {
        if (isToggleInProgress) {
            Log.d(TAG, "网络切换操作正在进行中，忽略重复请求");
            return;
        }
        
        isToggleInProgress = true;
        Log.d(TAG, "开始执行网络切换流程");
        
        // 重置状态
        lastAirplaneModeLocation = null;
        
        // 第一步：下拉两次打开快速设置面板
        performSwipeDown();
    }
    
    // 执行下拉操作打开系统设置面板
    private void performSwipeDown() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "API级别过低，不支持手势操作");
            isToggleInProgress = false;
            return;
        }

        Log.d(TAG, "开始执行第一次下拉操作");
        // 执行第一次下拉
        performSwipeDownGesture(new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "第一次下拉完成，等待500ms后执行第二次下拉");
                
                // 等待500ms后执行第二次下拉
                new Handler().postDelayed(() -> {
                    performSwipeDownGesture(new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            super.onCompleted(gestureDescription);
                            Log.d(TAG, "第二次下拉完成，等待1000ms后查找飞行模式文本");
                            
                            // 等待1000ms后查找飞行模式文本
                            new Handler().postDelayed(() -> {
                                // 第二步：点击飞行模式开启
                                findAndClickAirplaneModeText(true);
                            }, 1000);
                        }
                        
                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            super.onCancelled(gestureDescription);
                            Log.e(TAG, "第二次下拉手势被取消");
                            isToggleInProgress = false;
                        }
                    });
                }, 500);
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.e(TAG, "第一次下拉手势被取消");
                isToggleInProgress = false;
            }
        });
    }
    
    // 执行下拉手势
    private void performSwipeDownGesture(GestureResultCallback callback) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        // 从屏幕顶部2/3位置开始下拉
        float startX = screenWidth * 2 / 3;
        
        // 创建从屏幕顶部向下滑动的路径
        Path path = new Path();
        path.moveTo(startX, 0); // 从屏幕顶部2/3位置开始
        path.lineTo(startX, screenHeight / 2); // 滑动到屏幕中间

        // 创建手势描述
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500)); // 500ms的手势时长

        // 执行手势
        dispatchGesture(builder.build(), callback, null);
    }
    
    // 查找飞行模式文本并点击
    private void findAndClickAirplaneModeText(boolean isFirstClick) {
        Log.d(TAG, "开始查找飞行模式文本" + (isFirstClick ? "(第一次点击)" : "(第二次点击)"));
        
        // 如果是第二次点击，并且我们已经知道飞行模式的位置，直接点击
        if (!isFirstClick && lastAirplaneModeLocation != null) {
            Log.d(TAG, "第二次点击：使用上次保存的位置直接点击飞行模式: " + lastAirplaneModeLocation.toString());
            
            // 直接使用保存的位置点击
            boolean clicked = performClickAtPosition(
                    lastAirplaneModeLocation.centerX(), 
                    lastAirplaneModeLocation.centerY());
            
            Log.d(TAG, "第二次点击结果: " + clicked);
            
            // 无论点击是否成功，都继续执行热点切换操作
            Log.d(TAG, "第二次点击后，等待1秒后切换热点");
            new Handler().postDelayed(this::toggleHotspot, 1000);
            
            // 不再继续查找节点
            return;
        }
        
        // 以下是第一次点击的逻辑
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "无法获取活动窗口");
            isToggleInProgress = false;
            return;
        }

        // 打印根节点信息，帮助调试
        Log.d(TAG, "根节点类名: " + rootNode.getClassName());
        Log.d(TAG, "根节点包名: " + rootNode.getPackageName());
        Log.d(TAG, "子节点数量: " + rootNode.getChildCount());

        boolean clicked = false;
        
        // 查找包含"飞行模式"文本的节点
        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText("飞行模式");
        
        // 记录找到的节点数量
        if (nodes != null) {
            Log.d(TAG, "找到包含'飞行模式'的节点数量: " + nodes.size());
        } else {
            Log.d(TAG, "未找到包含'飞行模式'的节点");
        }
        
        // 如果没找到中文，尝试查找英文
        if (nodes == null || nodes.isEmpty()) {
            nodes = rootNode.findAccessibilityNodeInfosByText("Airplane mode");
            if (nodes != null) {
                Log.d(TAG, "找到包含'Airplane mode'的节点数量: " + nodes.size());
            }
        }
        
        // 如果找到了节点
        if (nodes != null && !nodes.isEmpty()) {
            // 遍历所有找到的节点，打印详细信息
            for (int i = 0; i < nodes.size(); i++) {
                AccessibilityNodeInfo node = nodes.get(i);
                Log.d(TAG, "节点 " + i + " 信息:");
                Log.d(TAG, "  文本: " + node.getText());
                Log.d(TAG, "  描述: " + node.getContentDescription());
                Log.d(TAG, "  类名: " + node.getClassName());
                Log.d(TAG, "  可点击: " + node.isClickable());
                
                Rect nodeBounds = new Rect();
                node.getBoundsInScreen(nodeBounds);
                Log.d(TAG, "  位置: " + nodeBounds.toString());
            }
            
            // 使用第一个找到的节点
            AccessibilityNodeInfo textNode = nodes.get(0);
            
            // 保存节点位置，用于第二次点击
            Rect textRect = new Rect();
            textNode.getBoundsInScreen(textRect);
            lastAirplaneModeLocation = new Rect(textRect);
            
            Log.d(TAG, "保存飞行模式位置: " + lastAirplaneModeLocation.toString());
            
            // 尝试多种点击策略，但确保只执行一次成功的点击
            
            // 策略1: 直接点击节点（如果节点可点击）
            if (!clicked && textNode.isClickable()) {
                Log.d(TAG, "尝试直接点击节点");
                clicked = textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "直接点击结果: " + clicked);
            }
            
            // 策略2: 点击节点的父节点（如果父节点可点击）
            if (!clicked) {
                AccessibilityNodeInfo parent = textNode.getParent();
                if (parent != null) {
                    Log.d(TAG, "尝试点击父节点");
                    if (parent.isClickable()) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d(TAG, "点击父节点结果: " + clicked);
                    }
                    parent.recycle();
                }
            }
            
            // 策略3: 在文本上方不同距离处点击
            if (!clicked) {
                // 只保留40, 60, 80的偏移量
                int[] offsets = {40, 60, 80};
                for (int offset : offsets) {
                    if (clicked) break; // 如果已经点击成功，跳出循环
                    
                    int clickX = textRect.centerX();
                    int clickY = textRect.top - offset;
                    
                    // 确保点击位置在屏幕内
                    if (clickY < 0) clickY = 0;
                    
                    Log.d(TAG, "尝试在文本上方" + offset + "px处点击: x=" + clickX + ", y=" + clickY);
                    clicked = performClickAtPosition(clickX, clickY);
                    Log.d(TAG, "偏移量" + offset + "px点击结果: " + clicked);
                    
                    if (clicked) {
                        // 如果点击成功，更新保存的位置为实际点击位置
                        lastAirplaneModeLocation = new Rect(
                                clickX - 5, clickY - 5, 
                                clickX + 5, clickY + 5);
                        Log.d(TAG, "成功使用偏移量" + offset + "px，保存点击位置: " + lastAirplaneModeLocation.toString());
                        break;
                    }
                }
            }
            
            // 策略4: 点击文本所在区域的中心
            if (!clicked) {
                int clickX = textRect.centerX();
                int clickY = textRect.centerY();
                
                Log.d(TAG, "尝试点击文本中心: x=" + clickX + ", y=" + clickY);
                clicked = performClickAtPosition(clickX, clickY);
                Log.d(TAG, "点击结果: " + clicked);
                
                if (clicked) {
                    // 如果点击成功，更新保存的位置为实际点击位置
                    lastAirplaneModeLocation = new Rect(
                            clickX - 5, clickY - 5, 
                            clickX + 5, clickY + 5);
                }
            }
            
            // 回收节点
            textNode.recycle();
        } else {
            // 如果没找到文本节点，尝试查找所有可点击的节点
            Log.d(TAG, "未找到飞行模式文本，尝试查找所有可点击节点");
            findAndLogAllClickableNodes(rootNode);
        }
        
        if (!clicked) {
            Log.e(TAG, "所有点击策略均失败");
            isToggleInProgress = false;
        } else {
            Log.d(TAG, "点击飞行模式成功");
            
            if (isFirstClick) {
                // 第一次点击成功后，等待1.5秒再次点击
                Log.d(TAG, "等待1.5秒后再次点击飞行模式");
                new Handler().postDelayed(() -> {
                    findAndClickAirplaneModeText(false);
                }, 1500);
            } else {
                // 第二次点击成功后，等待500ms后返回APP
                Log.d(TAG, "等待500ms后返回APP");
                new Handler().postDelayed(this::returnToApp, 500);
            }
        }
        
        // 回收根节点
        rootNode.recycle();
    }
    
    // 返回APP
    private void returnToApp() {
        Log.d(TAG, "准备返回APP");
        
        // 方法1: 使用HOME键，然后启动我们的APP
        boolean homeResult = performGlobalAction(GLOBAL_ACTION_HOME);
        Log.d(TAG, "执行HOME操作结果: " + homeResult);
        
        // 等待短暂时间后启动我们的APP
        new Handler().postDelayed(() -> {
            Log.d(TAG, "尝试启动我们的APP");
            String packageName = getPackageName();
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                // 添加网络状态变化的标志
                launchIntent.putExtra("NETWORK_STATE_CHANGED", true);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                try {
                    startActivity(launchIntent);
                    Log.d(TAG, "成功启动APP: " + packageName);
                    
                    // 发送广播通知网络状态变化
                    sendNetworkStateChangedBroadcast();
                } catch (Exception e) {
                    Log.e(TAG, "启动APP失败: " + e.getMessage());
                    
                    // 如果启动失败，尝试使用组件名称启动
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(packageName, packageName + ".MainActivity");
                        intent.putExtra("NETWORK_STATE_CHANGED", true);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                        Log.d(TAG, "使用组件名称成功启动APP");
                        
                        // 发送广播通知网络状态变化
                        sendNetworkStateChangedBroadcast();
                    } catch (Exception ex) {
                        Log.e(TAG, "使用组件名称启动APP失败: " + ex.getMessage());
                    }
                }
            } else {
                Log.e(TAG, "无法获取启动Intent");
                
                // 尝试使用组件名称启动
                try {
                    Intent intent = new Intent();
                    intent.setClassName(packageName, packageName + ".MainActivity");
                    intent.putExtra("NETWORK_STATE_CHANGED", true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    Log.d(TAG, "使用组件名称成功启动APP");
                    
                    // 发送广播通知网络状态变化
                    sendNetworkStateChangedBroadcast();
                } catch (Exception e) {
                    Log.e(TAG, "使用组件名称启动APP失败: " + e.getMessage());
                }
            }
            
            // 完成整个流程
            isToggleInProgress = false;
            Log.d(TAG, "网络切换流程完成");
        }, 300);
    }
    
    // 发送网络状态变化广播
    private void sendNetworkStateChangedBroadcast() {
        try {
            Intent intent = new Intent("com.example.wscontroller.NETWORK_STATE_CHANGED");
            // 添加强制重连标志
            intent.putExtra("FORCE_RECONNECT", true);
            sendBroadcast(intent);
            Log.d(TAG, "已发送网络状态变化广播（带强制重连标志）");
        } catch (Exception e) {
            Log.e(TAG, "发送网络状态变化广播失败: " + e.getMessage());
        }
    }
    
    // 查找并记录所有可点击的节点（调试辅助）
    private void findAndLogAllClickableNodes(AccessibilityNodeInfo node) {
        if (node == null) return;
        
        if (node.isClickable()) {
            Rect nodeBounds = new Rect();
            node.getBoundsInScreen(nodeBounds);
            
            Log.d(TAG, "可点击节点:");
            Log.d(TAG, "  文本: " + node.getText());
            Log.d(TAG, "  描述: " + node.getContentDescription());
            Log.d(TAG, "  类名: " + node.getClassName());
            Log.d(TAG, "  位置: " + nodeBounds.toString());
        }
        
        // 递归查找子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAndLogAllClickableNodes(child);
                child.recycle();
            }
        }
    }
    
    // 在指定位置执行点击操作
    private boolean performClickAtPosition(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 10));
        
        // 使用同步方式执行点击
        final boolean[] result = {false};
        final Object lock = new Object();
        
        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "点击操作完成: x=" + x + ", y=" + y);
                synchronized (lock) {
                    result[0] = true;
                    lock.notify();
                }
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.e(TAG, "点击操作被取消: x=" + x + ", y=" + y);
                synchronized (lock) {
                    result[0] = false;
                    lock.notify();
                }
            }
        }, null);
        
        // 等待点击操作完成
        synchronized (lock) {
            try {
                // 最多等待500ms
                lock.wait(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待点击操作完成时被中断", e);
            }
        }
        
        return result[0];
    }

    // 修改日志方法
    private void logDebug(String message) {
        if (VERBOSE_LOGGING) {
            Log.d(TAG, message);
        }
    }

    // 在飞行模式切换后点击热点开关
    private void toggleHotspot() {
        Log.d(TAG, "开始执行热点切换");
        
        // 等待1秒后查找热点文本
        new Handler().postDelayed(() -> {
            findAndClickHotspotText();
        }, 1000);
    }

    // 查找热点文本并点击
    private void findAndClickHotspotText() {
        logDebug("开始查找热点文本");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "无法获取活动窗口");
            isToggleInProgress = false;
            return;
        }

        // 打印根节点信息，帮助调试
        Log.d(TAG, "根节点类名: " + rootNode.getClassName());
        Log.d(TAG, "根节点包名: " + rootNode.getPackageName());
        Log.d(TAG, "子节点数量: " + rootNode.getChildCount());

        boolean clicked = false;
        
        // 查找包含"热点"文本的节点
        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText("热点");
        
        // 记录找到的节点数量
        if (nodes != null) {
            Log.d(TAG, "找到包含'热点'的节点数量: " + nodes.size());
        } else {
            Log.d(TAG, "未找到包含'热点'的节点");
        }
        
        // 如果没找到中文，尝试查找英文
        if (nodes == null || nodes.isEmpty()) {
            nodes = rootNode.findAccessibilityNodeInfosByText("Hotspot");
            if (nodes != null) {
                Log.d(TAG, "找到包含'Hotspot'的节点数量: " + nodes.size());
            }
        }
        
        // 如果找到了节点
        if (nodes != null && !nodes.isEmpty()) {
            // 遍历所有找到的节点，打印详细信息
            for (int i = 0; i < nodes.size(); i++) {
                AccessibilityNodeInfo node = nodes.get(i);
                Log.d(TAG, "节点 " + i + " 信息:");
                Log.d(TAG, "  文本: " + node.getText());
                Log.d(TAG, "  描述: " + node.getContentDescription());
                Log.d(TAG, "  类名: " + node.getClassName());
                Log.d(TAG, "  可点击: " + node.isClickable());
                
                Rect nodeBounds = new Rect();
                node.getBoundsInScreen(nodeBounds);
                Log.d(TAG, "  位置: " + nodeBounds.toString());
            }
            
            // 使用第一个找到的节点
            AccessibilityNodeInfo textNode = nodes.get(0);
            
            // 获取节点位置
            Rect textRect = new Rect();
            textNode.getBoundsInScreen(textRect);
            
            // 尝试多种点击策略，但确保只执行一次成功的点击
            
            // 策略1: 直接点击节点（如果节点可点击）
            if (!clicked && textNode.isClickable()) {
                Log.d(TAG, "尝试直接点击热点节点");
                clicked = textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "直接点击结果: " + clicked);
            }
            
            // 策略2: 点击节点的父节点（如果父节点可点击）
            if (!clicked) {
                AccessibilityNodeInfo parent = textNode.getParent();
                if (parent != null) {
                    Log.d(TAG, "尝试点击热点父节点");
                    if (parent.isClickable()) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d(TAG, "点击父节点结果: " + clicked);
                    }
                    parent.recycle();
                }
            }
            
            // 策略3: 在文本上方不同距离处点击
            if (!clicked) {
                // 使用40, 60, 80的偏移量
                int[] offsets = {40, 60, 80};
                for (int offset : offsets) {
                    if (clicked) break; // 如果已经点击成功，跳出循环
                    
                    int clickX = textRect.centerX();
                    int clickY = textRect.top - offset;
                    
                    // 确保点击位置在屏幕内
                    if (clickY < 0) clickY = 0;
                    
                    Log.d(TAG, "尝试在热点文本上方" + offset + "px处点击: x=" + clickX + ", y=" + clickY);
                    clicked = performClickAtPosition(clickX, clickY);
                    Log.d(TAG, "偏移量" + offset + "px点击结果: " + clicked);
                    
                    if (clicked) {
                        Log.d(TAG, "成功使用偏移量" + offset + "px点击热点");
                        break;
                    }
                }
            }
            
            // 策略4: 点击文本所在区域的中心
            if (!clicked) {
                int clickX = textRect.centerX();
                int clickY = textRect.centerY();
                
                Log.d(TAG, "尝试点击热点文本中心: x=" + clickX + ", y=" + clickY);
                clicked = performClickAtPosition(clickX, clickY);
                Log.d(TAG, "点击热点文本中心结果: " + clicked);
            }
            
            // 回收节点
            textNode.recycle();
        } else {
            // 如果没找到文本节点，尝试查找所有可点击的节点
            Log.d(TAG, "未找到热点文本，尝试查找所有可点击节点");
            findAndLogAllClickableNodes(rootNode);
        }
        
        if (!clicked) {
            Log.e(TAG, "点击热点失败");
            isToggleInProgress = false;
        } else {
            Log.d(TAG, "点击热点成功");
            
            // 点击成功后，等待1秒后返回APP
            Log.d(TAG, "等待1秒后返回APP");
            new Handler().postDelayed(this::returnToApp, 1000);
        }
        
        // 回收根节点
        rootNode.recycle();
    }
} 