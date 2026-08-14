<template>
  <div class="xterm-terminal-container">
    <div class="terminal-header">
      <span class="terminal-title">
        <el-icon><Connection /></el-icon>
        {{ connected ? deviceName : '未连接' }}
      </span>
      <div class="terminal-controls">
        <span :class="['status-badge', connected ? 'connected' : 'disconnected']">
          {{ connected ? '已连接' : '未连接' }}
        </span>
        <el-button
          v-if="!connected"
          type="primary"
          size="small"
          :loading="connecting"
          @click="connect"
          :disabled="!deviceId"
        >
          连接
        </el-button>
        <el-button
          v-else
          type="danger"
          size="small"
          @click="disconnect"
        >
          断开
        </el-button>
      </div>
    </div>
    <div 
      ref="terminalRef" 
      class="terminal-body"
      @click="focusTerminal"
    ></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { Terminal } from 'xterm'
import { FitAddon } from 'xterm-addon-fit'
import { WebLinksAddon } from 'xterm-addon-web-links'
import { Connection } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import 'xterm/css/xterm.css'

const props = defineProps({
  deviceId: {
    type: Number,
    default: null
  },
  deviceName: {
    type: String,
    default: ''
  },
  /** 挂载后自动发起 SSH 连接（告警弹层等值班场景） */
  autoConnect: {
    type: Boolean,
    default: false
  }
})

const terminalRef = ref(null)
const terminal = ref(null)
const fitAddon = ref(null)
const webSocket = ref(null)
const connected = ref(false)
const connecting = ref(false)
const resizeObserver = ref(null)

let term = null
let fit = null

const initTerminal = () => {
  term = new Terminal({
    theme: {
      background: '#000000',
      foreground: '#00ff00',
      cursor: '#00ff00',
      cursorAccent: '#000000',
      selectionBackground: 'rgba(0, 255, 0, 0.3)',
      black: '#000000',
      red: '#ff5555',
      green: '#50fa7b',
      yellow: '#f1fa8c',
      blue: '#bd93f9',
      magenta: '#ff79c6',
      cyan: '#8be9fd',
      white: '#f8f8f2',
      brightBlack: '#6272a4',
      brightRed: '#ff6e67',
      brightGreen: '#5af78e',
      brightYellow: '#f4f99d',
      brightBlue: '#caa9fa',
      brightMagenta: '#ff92d0',
      brightCyan: '#9aedfe',
      brightWhite: '#e9e9f4'
    },
    fontFamily: '"Consolas", "Monaco", "Courier New", monospace',
    fontSize: 14,
    lineHeight: 1.5,
    cursorBlink: true,
    cursorStyle: 'block',
    allowTransparency: true
  })

  fit = new FitAddon()
  term.loadAddon(fit)
  term.loadAddon(new WebLinksAddon())

  term.onData((data) => {
    if (webSocket.value && connected.value) {
      webSocket.value.send(JSON.stringify({
        type: 'input',
        data: data
      }))
    }
  })

  nextTick(() => {
    if (terminalRef.value) {
      term.open(terminalRef.value)
      fit.fit()
      writeWelcome()
    }
  })

  let fitRaf = 0
  resizeObserver.value = new ResizeObserver(() => {
    if (!fit) return
    if (fitRaf) cancelAnimationFrame(fitRaf)
    fitRaf = requestAnimationFrame(() => {
      fitRaf = 0
      try {
        const before = `${term.cols}x${term.rows}`
        fit.fit()
        const after = `${term.cols}x${term.rows}`
        if (before !== after) notifyPtyResize()
      } catch (e) {
        /* ignore */
      }
    })
  })

  if (terminalRef.value) {
    resizeObserver.value.observe(terminalRef.value)
  }
}

const notifyPtyResize = () => {
  if (!term || !webSocket.value || !connected.value) return
  if (webSocket.value.readyState !== WebSocket.OPEN) return
  try {
    webSocket.value.send(JSON.stringify({
      type: 'resize',
      cols: term.cols,
      rows: term.rows
    }))
  } catch (e) {
    /* ignore */
  }
}

const writeWelcome = () => {
  if (term) {
    term.write('\x1b[1;32mWelcome to eNSP WebSSH Terminal\x1b[0m\r\n')
    term.write('Click "Connect" to start a session\r\n\r\n')
  }
}

const connect = (opts = {}) => {
  const silentBusy = !!opts.silentBusy
  if (!props.deviceId) {
    ElMessage.warning('请先选择设备')
    return
  }

  if (connected.value || connecting.value) {
    if (!silentBusy) {
      ElMessage.warning('已经在连接中或已连接')
    }
    return
  }

  connecting.value = true
  
  const token = localStorage.getItem('ensp_nms_token')
  if (!token) {
    ElMessage.error('未登录，请先登录')
    connecting.value = false
    return
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.host}/ws/ssh?token=${encodeURIComponent(token)}`
  
  try {
    webSocket.value = new WebSocket(wsUrl)
    
    const connectionTimeout = setTimeout(() => {
      if (connecting.value) {
        ElMessage.error('连接超时，请检查网络和后端服务')
        if (webSocket.value) {
          webSocket.value.close()
        }
        connecting.value = false
      }
    }, 15000)
    
    webSocket.value.onopen = () => {
      clearTimeout(connectionTimeout)
      
      if (term) {
        term.clear()
        term.write('\x1b[1;33m正在建立SSH会话...\x1b[0m\r\n')
      }
      
      webSocket.value.send(JSON.stringify({
        type: 'connect',
        deviceId: props.deviceId
      }))
    }
    
    webSocket.value.onmessage = (event) => {
      const raw = typeof event.data === 'string' ? event.data : ''
      if (!raw) return

      try {
        const message = JSON.parse(raw)

        switch (message.type) {
          case 'output': {
            if (term && message.data != null && message.data !== '') {
              term.write(message.data)
            }
            break
          }
          case 'connected':
            connecting.value = false
            connected.value = true
            if (term) {
              term.write('\r\n\x1b[1;32m✓ SSH连接成功！\x1b[0m\r\n\r\n')
              term.focus()
            }
            try {
              if (fit) fit.fit()
              notifyPtyResize()
            } catch (e) { /* ignore */ }
            ElMessage.success('连接成功')
            break
          case 'error':
            connecting.value = false
            connected.value = false
            if (term) {
              term.write(`\r\n\x1b[1;31m✗ 错误: ${message.message || message.data || '未知错误'}\x1b[0m\r\n`)
            }
            ElMessage.error(message.message || message.data || '连接失败')
            break
          default:
            console.warn('未知 WebSSH 消息类型:', message.type)
        }
      } catch (e) {
        console.error('解析WebSocket消息失败', e, raw.slice(0, 200))
      }
    }
    
    webSocket.value.onclose = () => {
      connected.value = false
      connecting.value = false
      if (term) {
        term.write('\r\n\x1b[1;31mDisconnected\x1b[0m\r\n')
      }
    }
    
    webSocket.value.onerror = (error) => {
      console.error('WebSocket error', error)
      ElMessage.error('WebSocket连接失败')
      connecting.value = false
    }
  } catch (error) {
    console.error('Failed to create WebSocket', error)
    ElMessage.error('创建连接失败')
    connecting.value = false
  }
}

const disconnect = () => {
  if (webSocket.value) {
    webSocket.value.close()
    webSocket.value = null
  }
  connected.value = false
  connecting.value = false
}

const focusTerminal = () => {
  if (term) {
    term.focus()
  }
}

onMounted(() => {
  initTerminal()
  if (props.autoConnect && props.deviceId) {
    nextTick(() => {
      // 等 dialog / layout 完成后再 fit + 连接
      setTimeout(() => {
        if (fit) fit.fit()
        connect({ silentBusy: true })
      }, 80)
    })
  }
})

onBeforeUnmount(() => {
  if (webSocket.value) {
    webSocket.value.close()
  }
  if (resizeObserver.value && terminalRef.value) {
    resizeObserver.value.unobserve(terminalRef.value)
  }
  if (term) {
    term.dispose()
  }
})

watch(() => props.deviceId, async (newVal, oldVal) => {
  if (newVal === oldVal) return
  disconnect()
  if (props.autoConnect && newVal) {
    await nextTick()
    connect({ silentBusy: true })
  }
})

defineExpose({
  connected
})
</script>

<style scoped>
.xterm-terminal-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid #333;
  border-radius: 4px;
  overflow: hidden;
  background: #000;
}

.terminal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: linear-gradient(180deg, #4a4a4a 0%, #333 100%);
  border-bottom: 1px solid #222;
}

.terminal-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}

.terminal-controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-badge {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}

.status-badge.connected {
  background: #4CAF50;
  color: white;
}

.status-badge.disconnected {
  background: #9E9E9E;
  color: white;
}

.terminal-body {
  flex: 1;
  padding: 10px;
  overflow: hidden;
  cursor: text;
  min-height: 0;
}

.terminal-body:hover {
  background: rgba(0, 255, 0, 0.02);
}

:deep(.xterm) {
  height: 100%;
  padding: 8px;
}

:deep(.xterm-viewport) {
  overflow-y: auto;
}

:deep(.xterm:focus) {
  outline: none;
}
</style>
