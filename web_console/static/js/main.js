/**
 * LXB Web Console - Frontend Logic
 */

// 全局状态
const state = {
    connected: false,
    stats: {
        sent: 0,
        success: 0,
        failed: 0
    }
};

// DOM 元素
let connectBtn, disconnectBtn, connectionStatus;
let statsSent, statsSuccess, statsFailed;
let logContainer;

/**
 * 页面加载完成后初始化
 */
document.addEventListener('DOMContentLoaded', () => {
    // 获取 DOM 元素
    connectBtn = document.getElementById('btn-connect');
    disconnectBtn = document.getElementById('btn-disconnect');
    connectionStatus = document.getElementById('connection-status');
    statsSent = document.getElementById('stats-sent');
    statsSuccess = document.getElementById('stats-success');
    statsFailed = document.getElementById('stats-failed');
    logContainer = document.getElementById('log-container');

    // 绑定事件
    connectBtn.addEventListener('click', handleConnect);
    disconnectBtn.addEventListener('click', handleDisconnect);
    document.getElementById('btn-clear-log').addEventListener('click', clearLog);

    // 绑定命令按钮
    bindCommandButtons();

    // 定期检查连接状态
    setInterval(checkConnectionStatus, 5000);

    addLog('info', '控制台已就绪，请输入 Android 设备 WiFi IP 并连接');
});

/**
 * 绑定所有命令按钮
 */
function bindCommandButtons() {
    document.querySelectorAll('[data-cmd]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const cmd = e.target.getAttribute('data-cmd');
            handleCommand(cmd);
        });
    });
}

/**
 * 处理命令
 */
function handleCommand(cmd) {
    switch(cmd) {
        // =============================================================
        // Link Layer
        // =============================================================
        case 'handshake':
            sendCommand('/api/command/handshake', {}, 'HANDSHAKE');
            break;
        case 'heartbeat':
            sendCommand('/api/command/heartbeat', {}, 'HEARTBEAT');
            break;

        // =============================================================
        // Input Layer
        // =============================================================
        case 'tap':
            const x = parseInt(document.getElementById('tap-x').value);
            const y = parseInt(document.getElementById('tap-y').value);
            sendCommand('/api/command/tap', { x, y }, `TAP (${x}, ${y})`);
            break;

        case 'swipe':
            const x1 = parseInt(document.getElementById('swipe-x1').value);
            const y1 = parseInt(document.getElementById('swipe-y1').value);
            const x2 = parseInt(document.getElementById('swipe-x2').value);
            const y2 = parseInt(document.getElementById('swipe-y2').value);
            const duration = parseInt(document.getElementById('swipe-duration').value);
            sendCommand('/api/command/swipe', { x1, y1, x2, y2, duration },
                `SWIPE (${x1},${y1})->(${x2},${y2}) ${duration}ms`);
            break;

        case 'long_press':
            const lpx = parseInt(document.getElementById('longpress-x').value);
            const lpy = parseInt(document.getElementById('longpress-y').value);
            const lpd = parseInt(document.getElementById('longpress-duration').value);
            sendCommand('/api/command/long_press', { x: lpx, y: lpy, duration: lpd },
                `LONG_PRESS (${lpx}, ${lpy}) ${lpd}ms`);
            break;

        case 'unlock':
            sendCommand('/api/command/unlock', {}, 'UNLOCK');
            break;

        // =============================================================
        // Input Extension
        // =============================================================
        case 'input_text':
            const text = document.getElementById('input-text').value;
            sendCommand('/api/command/input_text', { text }, `INPUT_TEXT "${text}"`);
            break;

        case 'key_home':
            sendCommand('/api/command/key_event', { keycode: 'home' }, 'KEY_HOME');
            break;
        case 'key_back':
            sendCommand('/api/command/key_event', { keycode: 'back' }, 'KEY_BACK');
            break;
        case 'key_enter':
            sendCommand('/api/command/key_event', { keycode: 'enter' }, 'KEY_ENTER');
            break;
        case 'key_menu':
            sendCommand('/api/command/key_event', { keycode: 'menu' }, 'KEY_MENU');
            break;
        case 'key_recent':
            sendCommand('/api/command/key_event', { keycode: 'recent' }, 'KEY_RECENT');
            break;

        // =============================================================
        // Sense Layer
        // =============================================================
        case 'get_activity':
            sendCommand('/api/command/get_activity', {}, 'GET_ACTIVITY');
            break;

        case 'get_screen_state':
            sendCommand('/api/command/get_screen_state', {}, 'GET_SCREEN_STATE');
            break;

        case 'get_screen_size':
            sendCommand('/api/command/get_screen_size', {}, 'GET_SCREEN_SIZE');
            break;

        case 'find_node':
            const query = document.getElementById('find-query').value;
            if (!query) {
                addLog('error', '请输入查找文本');
                return;
            }
            sendCommand('/api/command/find_node', { query, multi_match: true }, `FIND_NODE "${query}"`);
            break;

        // =============================================================
        // Lifecycle Layer
        // =============================================================
        case 'launch_app':
            const launchPkg = document.getElementById('app-package').value;
            if (!launchPkg) {
                addLog('error', '请输入应用包名');
                return;
            }
            sendCommand('/api/command/launch_app', { package: launchPkg }, `LAUNCH_APP ${launchPkg}`);
            break;

        case 'stop_app':
            const stopPkg = document.getElementById('app-package').value;
            if (!stopPkg) {
                addLog('error', '请输入应用包名');
                return;
            }
            sendCommand('/api/command/stop_app', { package: stopPkg }, `STOP_APP ${stopPkg}`);
            break;

        // =============================================================
        // Media Layer
        // =============================================================
        case 'screenshot':
            takeScreenshot();
            break;
    }
}

/**
 * 处理连接
 */
async function handleConnect() {
    const host = document.getElementById('host').value;
    const port = parseInt(document.getElementById('port').value);

    if (!host || !port) {
        addLog('error', '请输入主机地址和端口');
        return;
    }

    addLog('info', `正在连接到 ${host}:${port}...`);

    try {
        const response = await fetch('/api/connect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ host, port })
        });

        const result = await response.json();

        if (result.success) {
            state.connected = true;
            updateConnectionUI(true);
            addLog('success', result.message);
        } else {
            addLog('error', result.message);
        }
    } catch (error) {
        addLog('error', `连接失败: ${error.message}`);
    }
}

/**
 * 处理断开连接
 */
async function handleDisconnect() {
    addLog('info', '正在断开连接...');

    try {
        const response = await fetch('/api/disconnect', {
            method: 'POST'
        });

        const result = await response.json();

        if (result.success) {
            state.connected = false;
            updateConnectionUI(false);
            addLog('success', result.message);
        } else {
            addLog('error', result.message);
        }
    } catch (error) {
        addLog('error', `断开失败: ${error.message}`);
    }
}

/**
 * 发送命令
 */
async function sendCommand(endpoint, params, displayName) {
    if (!state.connected) {
        addLog('error', '未连接到设备');
        return;
    }

    addLog('command', `-> ${displayName}`);
    state.stats.sent++;
    updateStats();

    try {
        const response = await fetch(endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(params)
        });

        const result = await response.json();

        if (result.success) {
            state.stats.success++;
            addLog('response', `<- ${result.message}`);

            // 显示详细响应
            if (result.response) {
                displayResponse(result.response);
            }
        } else {
            state.stats.failed++;
            addLog('error', `X ${result.message}`);
        }
    } catch (error) {
        state.stats.failed++;
        addLog('error', `X 请求失败: ${error.message}`);
    }

    updateStats();
}

/**
 * 显示响应详情
 */
function displayResponse(response) {
    if (response.package !== undefined) {
        addLog('response', `   Package: ${response.package}`);
        addLog('response', `   Activity: ${response.activity}`);
    }
    if (response.state_name !== undefined) {
        addLog('response', `   State: ${response.state_name} (${response.state})`);
    }
    if (response.width !== undefined) {
        addLog('response', `   Size: ${response.width}x${response.height} @${response.density}dpi`);
    }
    if (response.results !== undefined) {
        addLog('response', `   Found: ${response.count} nodes`);
        response.results.forEach((r, i) => {
            if (Array.isArray(r) && r.length === 2) {
                addLog('response', `   [${i}] (${r[0]}, ${r[1]})`);
            } else if (Array.isArray(r) && r.length === 4) {
                addLog('response', `   [${i}] bounds: (${r[0]},${r[1]})-(${r[2]},${r[3]})`);
            }
        });
    }
    if (response.length !== undefined && response.data === undefined) {
        addLog('response', `   Response: ${response.length} bytes`);
    }
}

/**
 * 检查连接状态
 */
async function checkConnectionStatus() {
    try {
        const response = await fetch('/api/status');
        const status = await response.json();

        if (status.connected !== state.connected) {
            state.connected = status.connected;
            updateConnectionUI(status.connected);

            if (!status.connected) {
                addLog('error', '连接已丢失');
            }
        }
    } catch (error) {
        // 静默失败
    }
}

/**
 * 更新连接状态 UI
 */
function updateConnectionUI(connected) {
    if (connected) {
        connectionStatus.textContent = '已连接';
        connectionStatus.className = 'status-connected';
        connectBtn.disabled = true;
        disconnectBtn.disabled = false;

        document.querySelectorAll('.btn-cmd').forEach(btn => {
            btn.disabled = false;
        });
    } else {
        connectionStatus.textContent = '未连接';
        connectionStatus.className = 'status-disconnected';
        connectBtn.disabled = false;
        disconnectBtn.disabled = true;

        document.querySelectorAll('.btn-cmd').forEach(btn => {
            btn.disabled = true;
        });
    }
}

/**
 * 添加日志
 */
function addLog(type, message) {
    const now = new Date();
    const time = now.toTimeString().split(' ')[0];

    const entry = document.createElement('div');
    entry.className = `log-entry log-${type}`;

    const timeSpan = document.createElement('span');
    timeSpan.className = 'log-time';
    timeSpan.textContent = time;

    const messageSpan = document.createElement('span');
    messageSpan.className = 'log-message';
    messageSpan.textContent = message;

    entry.appendChild(timeSpan);
    entry.appendChild(messageSpan);

    logContainer.appendChild(entry);
    logContainer.scrollTop = logContainer.scrollHeight;

    // 限制日志条目数量
    while (logContainer.children.length > 500) {
        logContainer.removeChild(logContainer.firstChild);
    }
}

/**
 * 清空日志
 */
function clearLog() {
    logContainer.innerHTML = '';
    addLog('info', '日志已清空');
}

/**
 * 更新统计信息
 */
function updateStats() {
    statsSent.textContent = state.stats.sent;
    statsSuccess.textContent = state.stats.success;
    statsFailed.textContent = state.stats.failed;
}

/**
 * 截图功能
 */
async function takeScreenshot() {
    if (!state.connected) {
        addLog('error', '未连接到设备');
        return;
    }

    addLog('command', '-> SCREENSHOT');
    state.stats.sent++;
    updateStats();

    try {
        const response = await fetch('/api/command/screenshot', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });

        const result = await response.json();

        if (result.success) {
            state.stats.success++;
            addLog('response', `<- ${result.message}`);

            // 显示截图预览
            if (result.response && result.response.image) {
                showScreenshotPreview(result.response.image, result.response.size);
            }
        } else {
            state.stats.failed++;
            addLog('error', `X ${result.message}`);
        }
    } catch (error) {
        state.stats.failed++;
        addLog('error', `X 请求失败: ${error.message}`);
    }

    updateStats();
}

/**
 * 显示截图预览
 */
function showScreenshotPreview(base64Image, size) {
    const preview = document.getElementById('screenshot-preview');
    const image = document.getElementById('screenshot-image');
    const closeBtn = document.getElementById('btn-close-preview');

    // 设置图片源 (JPEG 格式)
    image.src = 'data:image/jpeg;base64,' + base64Image;

    // 显示预览区
    preview.style.display = 'block';

    // 绑定关闭按钮
    closeBtn.onclick = function() {
        preview.style.display = 'none';
    };

    addLog('info', `截图大小: ${(size / 1024).toFixed(1)} KB`);
}
