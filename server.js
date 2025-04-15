// server.js - WebSocket中转服务器
const WebSocket = require('ws');
const express = require('express');
const http = require('http');
const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// 存储客户端连接，使用设备编号作为键
const clients = new Map();

// 启用JSON请求体解析
app.use(express.json());

// 跨域支持
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
  res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') {
    res.sendStatus(200);
  } else {
    next();
  }
});

// WebSocket连接处理
wss.on('connection', (ws, req) => {
    // 获取初始连接ID（后续可能会更新）
    const initialId = req.url.split('/').pop();
    let deviceId = initialId;
    
    console.log(`设备初始连接, ID: ${deviceId}`);
    clients.set(deviceId, { ws, lastSeen: Date.now() });
    
    // 添加连接计数日志
    console.log(`新连接建立，当前连接数: ${clients.size}`);
    
    // 处理连接关闭
    ws.on('close', (code, reason) => {
        console.log(`设备 ${deviceId} 连接关闭，代码: ${code}, 原因: ${reason || '未提供'}`);
        clients.delete(deviceId);
        console.log(`连接关闭后，当前连接数: ${clients.size}`);
    });
    
    // 添加连接错误事件处理
    ws.on('error', (error) => {
        console.error(`设备 ${deviceId} 连接错误:`, error);
    });
    
    // 处理来自B手机的消息
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            console.log(`收到来自 ${deviceId} 的消息:`, data);
            
            // 处理ping消息，立即回复pong
            if (data.type === 'ping') {
                // 更新最后活动时间
                if (clients.has(deviceId)) {
                    clients.get(deviceId).lastSeen = Date.now();
                    
                    // 如果是连接检查ping，记录信息
                    if (data.checkConnection) {
                        console.log(`设备 ${deviceId} 发送了连接检查ping`);
                    }
                }
                
                try {
                    // 立即回复pong
                    ws.send(JSON.stringify({
                        type: 'pong',
                        timestamp: Date.now(),
                        echo: data.timestamp // 回显客户端发送的时间戳
                    }));
                } catch (e) {
                    console.error(`回复pong消息失败:`, e);
                    
                    // 如果发送失败，可能连接已断开
                    if (clients.has(deviceId)) {
                        clients.delete(deviceId);
                    }
                }
                return; // 不需要进一步处理ping消息
            }
            
            // 处理状态更新
            if (data.type === 'status') {
                if (clients.has(deviceId)) {
                    clients.get(deviceId).status = data.status;
                    console.log(`设备 ${deviceId} 状态更新为: ${data.status}`);
                }
            }
            
            // 处理设备编号注册/更新
            if (data.type === 'register') {
                const newDeviceId = data.deviceNumber;
                
                // 验证设备编号格式
                if (!newDeviceId.match(/^\d{3}$/)) {
                    console.error(`设备 ${deviceId} 尝试注册无效的编号: ${newDeviceId}`);
                    return;
                }
                
                // 更新设备编号映射
                if (deviceId !== newDeviceId) {
                    console.log(`设备编号从 ${deviceId} 更新为 ${newDeviceId}`);
                    
                    // 检查新编号是否已被使用
                    if (clients.has(newDeviceId)) {
                        console.warn(`编号 ${newDeviceId} 已被使用，将替换现有连接`);
                        
                        // 通知旧设备连接被替换（如果还连接着）
                        const oldClient = clients.get(newDeviceId);
                        if (oldClient.ws.readyState === WebSocket.OPEN) {
                            try {
                                oldClient.ws.send(JSON.stringify({
                                    type: 'system',
                                    action: 'connection_replaced',
                                    message: '您的连接已被相同编号的新设备替换'
                                }));
                                oldClient.ws.close();
                            } catch (e) {
                                console.error('通知旧设备时出错:', e);
                            }
                        }
                    }
                    
                    // 移除旧映射，创建新映射
                    const clientData = clients.get(deviceId);
                    clients.delete(deviceId);
                    clients.set(newDeviceId, clientData);
                    deviceId = newDeviceId;
                    
                    // 确认注册成功
                    ws.send(JSON.stringify({
                        type: 'system',
                        action: 'register_success',
                        deviceNumber: deviceId
                    }));
                }
            }
            
            // 处理客户端主动断开连接
            if (data.type === 'disconnect') {
                console.log(`设备 ${deviceId} 请求断开连接`);
                // 不立即删除，等待连接真正关闭
                if (clients.has(deviceId)) {
                    clients.get(deviceId).disconnectRequested = true;
                }
                return;
            }
        } catch (e) {
            console.error('解析消息错误:', e);
        }
    });
    
    // 设置ping/pong保持连接
    ws.isAlive = true;
    ws.on('pong', () => {
        ws.isAlive = true;
        if (clients.has(deviceId)) {
            clients.get(deviceId).lastSeen = Date.now();
        }
    });
});

// 简化连接清理机制，保留核心功能
const connectionCheckInterval = setInterval(() => {
    const now = Date.now();
    const timeout = 30 * 1000; // 30秒超时
    
    // 简单记录当前连接数
    console.log(`当前连接数: ${clients.size}`);
    
    clients.forEach((client, id) => {
        // 检查连接状态
        const connectionState = client.ws.readyState;
        const timeSinceLastSeen = now - client.lastSeen;
        
        // 如果客户端请求断开连接，或者超过30秒没有活动，检查连接
        if (client.disconnectRequested || timeSinceLastSeen > timeout) {
            console.log(`设备 ${id} 需要检查连接: ${client.disconnectRequested ? '请求断开' : '超时'}`);
            
            try {
                if (connectionState === WebSocket.OPEN) {
                    // 如果是请求断开或超时严重，直接关闭
                    if (client.disconnectRequested || timeSinceLastSeen > timeout + 15000) {
                        console.log(`关闭设备 ${id} 的连接`);
                        client.ws.close();
                        clients.delete(id);
                    } else {
                        // 否则发送ping帧检测连接
                        client.ws.ping();
                        
                        // 设置10秒后检查
                        setTimeout(() => {
                            if (clients.has(id)) {
                                const newTimeSinceLastSeen = Date.now() - clients.get(id).lastSeen;
                                if (newTimeSinceLastSeen > timeSinceLastSeen) {
                                    console.log(`设备 ${id} 未响应ping，清理连接`);
                                    clients.delete(id);
                                }
                            }
                        }, 10000);
                    }
                } else {
                    // 如果连接不是OPEN状态，直接清理
                    console.log(`设备 ${id} 连接状态异常(${connectionState})，清理连接`);
                    clients.delete(id);
                }
            } catch (e) {
                console.error(`检测设备 ${id} 连接时出错:`, e);
                clients.delete(id);
            }
        }
    });
}, 15000); // 每15秒检查一次

// 清理interval当服务器关闭
wss.on('close', () => {
    clearInterval(connectionCheckInterval);
});

// 修改发送消息API - 从POST改为GET
app.get('/send', (req, res) => {
    // 从查询参数获取数据，而不是请求体
    const targetDevice = req.query.targetDevice;
    
    // 尝试解析message参数
    let message;
    try {
        // 如果message是JSON字符串，解析它
        message = JSON.parse(req.query.message);
    } catch (e) {
        // 如果解析失败，尝试使用其他参数构建消息对象
        const messageType = req.query.type || 'text';
        const messageContent = req.query.content;
        
        if (!messageContent) {
            return res.status(400).json({ 
                success: false, 
                error: '缺少消息内容参数(content或message)'
            });
        }
        
        // 构建消息对象
        message = {
            type: messageType,
            content: messageContent
        };
    }
    
    // 验证请求
    if (!targetDevice || !message) {
        return res.status(400).json({ 
            success: false, 
            error: '缺少目标设备ID或消息内容'
        });
    }
    
    // 验证设备编号格式
    if (!targetDevice.match(/^\d{3}$/)) {
        return res.status(400).json({
            success: false,
            error: '设备编号格式无效，必须是三位数字'
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
        // 向B发送消息
        const client = clients.get(targetDevice);
        if (client.ws.readyState !== WebSocket.OPEN) {
            // 清理无效连接
            clients.delete(targetDevice);
            return res.status(410).json({
                success: false,
                error: '目标设备连接已关闭'
            });
        }
        
        // 添加目标设备ID和消息ID
        const messageToSend = {
            ...message,
            targetDevice,
            messageId: `msg_${Date.now()}_${Math.floor(Math.random() * 1000)}`
        };
        
        client.ws.send(JSON.stringify(messageToSend));
        
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

// 保留原有的POST方法以保持向后兼容性
app.post('/send', (req, res) => {
    const { targetDevice, message } = req.body;
    
    // 验证请求
    if (!targetDevice || !message) {
        return res.status(400).json({ 
            success: false, 
            error: '缺少目标设备ID或消息内容'
        });
    }
    
    // 验证设备编号格式
    if (!targetDevice.match(/^\d{3}$/)) {
        return res.status(400).json({
            success: false,
            error: '设备编号格式无效，必须是三位数字'
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
        // 向B发送消息
        const client = clients.get(targetDevice);
        if (client.ws.readyState !== WebSocket.OPEN) {
            // 清理无效连接
            clients.delete(targetDevice);
            return res.status(410).json({
                success: false,
                error: '目标设备连接已关闭'
            });
        }
        
        // 添加目标设备ID和消息ID
        const messageToSend = {
            ...message,
            targetDevice,
            messageId: `msg_${Date.now()}_${Math.floor(Math.random() * 1000)}`
        };
        
        client.ws.send(JSON.stringify(messageToSend));
        
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
    
    // 验证设备编号格式
    if (!deviceId.match(/^\d{3}$/)) {
        return res.status(400).json({
            success: false,
            error: '设备编号格式无效，必须是三位数字'
        });
    }
    
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
        count: onlineDevices.length,
        devices: onlineDevices
    });
});

// 主页路由 - 简单状态页面
app.get('/', (req, res) => {
    const connectedDevices = [];
    clients.forEach((value, key) => {
        connectedDevices.push(`设备ID: ${key}, 状态: ${value.status || 'unknown'}, 最后活动: ${new Date(value.lastSeen).toLocaleString()}`);
    });
    
    res.send(`
        <html>
            <head>
                <title>手机通信服务状态</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: Arial, sans-serif; padding: 20px; }
                    h1 { color: #333; }
                    .status { margin: 20px 0; padding: 15px; background: #f5f5f5; border-radius: 5px; }
                    .device { margin: 10px 0; padding: 10px; background: #fff; border: 1px solid #ddd; border-radius: 3px; }
                </style>
            </head>
            <body>
                <h1>手机通信服务状态</h1>
                <div class="status">
                    <p>服务器运行中</p>
                    <p>当前连接设备数: ${clients.size}</p>
                </div>
                <h2>连接设备列表:</h2>
                <div class="devices">
                    ${connectedDevices.length > 0 ? 
                        connectedDevices.map(device => `<div class="device">${device}</div>`).join('') : 
                        '<p>当前没有设备连接</p>'}
                </div>
            </body>
        </html>
    `);
});

// 处理404
app.use((req, res) => {
    res.status(404).json({
        success: false,
        error: '请求的资源不存在'
    });
});

// 启动服务器
const PORT = process.env.PORT || 9000;
server.listen(PORT, () => {
    console.log(`WebSocket服务器已启动，监听端口 ${PORT}`);
});

// 在server.js中添加更频繁的ping检查
const PING_INTERVAL = 30000; // 30秒检查一次

// 定期检查所有连接
setInterval(() => {
    const now = Date.now();
    
    clients.forEach((client, deviceId) => {
        // 如果超过60秒没有活动，发送ping检查连接
        if (now - client.lastSeen > 60000) {
            try {
                // 检查连接是否仍然打开
                if (client.ws.readyState === WebSocket.OPEN) {
                    console.log(`设备 ${deviceId} 超过60秒无活动，发送ping检查`);
                    
                    // 发送ping帧
                    client.ws.ping();
                    
                    // 设置超时检查
                    setTimeout(() => {
                        // 如果在5秒内没有收到pong，认为连接已断开
                        if (clients.has(deviceId) && now - clients.get(deviceId).lastSeen > 60000) {
                            console.log(`设备 ${deviceId} ping超时，关闭连接`);
                            client.ws.terminate(); // 强制关闭连接
                            clients.delete(deviceId);
                        }
                    }, 5000);
                } else {
                    // 连接已关闭，清理
                    console.log(`设备 ${deviceId} 连接已关闭，清理`);
                    clients.delete(deviceId);
                }
            } catch (e) {
                console.error(`检查设备 ${deviceId} 连接时出错:`, e);
                clients.delete(deviceId);
            }
        }
    });
}, PING_INTERVAL);
