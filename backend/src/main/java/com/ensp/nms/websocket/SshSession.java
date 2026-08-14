package com.ensp.nms.websocket;

import com.ensp.nms.entity.Device;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class SshSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebSocketSession webSocketSession;
    private final Device device;
    private final String sshPassword;
    private Session sshSession;
    private ChannelShell channel;
    private OutputStream sshOut;
    private InputStream sshIn;
    private Thread outputThread;
    private Thread senderThread;
    private volatile boolean running = true;
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(200);

    public SshSession(WebSocketSession webSocketSession, Device device, String sshPassword) {
        this.webSocketSession = webSocketSession;
        this.device = device;
        this.sshPassword = sshPassword;
    }

    public void connect() throws Exception {
        log.info("开始连接SSH: {}:{}", device.getIpAddress(),
                device.getSshPort() != null ? device.getSshPort() : 22);

        try {
            JSch jsch = new JSch();
            sshSession = jsch.getSession(
                    device.getSshUsername(),
                    device.getIpAddress(),
                    device.getSshPort() != null ? device.getSshPort() : 22
            );
            sshSession.setPassword(sshPassword);
            sshSession.setConfig("StrictHostKeyChecking", "no");
            sshSession.setConfig("PreferredAuthentications", "password");
            sshSession.setConfig("kex", "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521");
            sshSession.setConfig("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
            sshSession.setConfig("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
            sshSession.setConfig("mac.s2c", "hmac-md5,hmac-sha1,hmac-sha2-256");
            sshSession.setConfig("mac.c2s", "hmac-md5,hmac-sha1,hmac-sha2-256");

            log.info("正在建立SSH会话...");
            sshSession.connect(20000);

            log.info("SSH会话已建立，打开Shell通道...");
            channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("vt100", 120, 40, 0, 0);
            channel.setPty(true);

            sshIn = channel.getInputStream();
            sshOut = channel.getOutputStream();

            log.info("连接Shell通道...");
            channel.connect(10000);

            // 不在此处强发 screen-length，避免回显与半包打乱首屏；由用户自行配置更稳妥
            log.info("启动输出读取线程...");
            startOutputThread();

            log.info("SSH连接完成");
        } catch (Exception e) {
            log.error("SSH连接失败: {}", e.getMessage(), e);
            disconnect();
            throw e;
        }
    }

    private void startOutputThread() {
        outputThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        int len = sshIn.read(buffer);
                        if (len > 0) {
                            String data = new String(buffer, 0, len, StandardCharsets.UTF_8);
                            queueOutput(data);
                        } else if (len == -1) {
                            break;
                        }
                    } catch (IOException e) {
                        log.error("读取SSH输出失败: {}", e.getMessage());
                        if (running) {
                            queueError("连接断开: " + e.getMessage());
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("SSH输出线程异常: {}", e.getMessage());
            }
        }, "ssh-output-" + device.getId());
        outputThread.setDaemon(true);
        outputThread.start();

        senderThread = new Thread(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    String message = messageQueue.take();
                    try {
                        flushToWebSocket(message);
                    } catch (IOException e) {
                        log.error("发送消息到WebSocket失败: {}", e.getMessage());
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ssh-sender-" + device.getId());
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void queueOutput(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            messageQueue.offer(buildJson("output", data));
        } catch (Exception e) {
            log.error("Failed to queue output: {}", e.getMessage());
        }
    }

    private void queueError(String message) {
        try {
            messageQueue.offer(buildJson("error", message));
        } catch (Exception e) {
            log.error("Failed to queue error: {}", e.getMessage());
        }
    }

    /** 使用 Jackson 序列化，正确转义 ESC/控制字符，避免前端 JSON.parse 失败 */
    private String buildJson(String type, String payload) throws IOException {
        Map<String, String> msg = new HashMap<>();
        msg.put("type", type);
        if ("error".equals(type)) {
            msg.put("message", payload != null ? payload : "");
            msg.put("data", "");
        } else {
            msg.put("data", payload != null ? payload : "");
        }
        return MAPPER.writeValueAsString(msg);
    }

    public void sendCommand(String command) throws IOException {
        if (sshOut != null && command != null) {
            sshOut.write(command.getBytes(StandardCharsets.UTF_8));
            sshOut.flush();
        }
    }

    /** 同步远端 PTY 行列，随 Web 终端窗口缩放 */
    public void resizePty(int cols, int rows) {
        if (channel == null || !channel.isConnected()) {
            return;
        }
        int c = Math.max(20, Math.min(cols, 500));
        int r = Math.max(5, Math.min(rows, 200));
        try {
            channel.setPtySize(c, r, c * 8, r * 16);
            log.debug("PTY resized to {}x{}", c, r);
        } catch (Exception e) {
            log.warn("PTY resize failed: {}", e.getMessage());
        }
    }

    private synchronized void flushToWebSocket(String jsonMessage) throws IOException {
        if (webSocketSession.isOpen() && jsonMessage != null) {
            try {
                webSocketSession.sendMessage(new TextMessage(jsonMessage));
            } catch (IllegalStateException e) {
                log.warn("WebSocket send failed, session may be closing: {}", e.getMessage());
            }
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (outputThread != null) {
                outputThread.interrupt();
                outputThread.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (senderThread != null) {
                senderThread.interrupt();
                senderThread.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (sshIn != null) sshIn.close();
        } catch (IOException e) { /* ignore */ }
        try {
            if (sshOut != null) sshOut.close();
        } catch (IOException e) { /* ignore */ }
        if (channel != null) channel.disconnect();
        if (sshSession != null) sshSession.disconnect();
        log.info("SSH session disconnected for device: {}", device.getName());
    }
}
