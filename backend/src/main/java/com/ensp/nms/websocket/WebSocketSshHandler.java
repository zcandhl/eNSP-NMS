package com.ensp.nms.websocket;

import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.DeviceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSshHandler extends TextWebSocketHandler {

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, SshSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            switch (type) {
                case "connect":
                    handleConnect(session, json);
                    break;
                case "input":
                    handleInput(session, json);
                    break;
                case "resize":
                    handleResize(session, json);
                    break;
                default:
                    log.warn("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to handle message", e);
            sendError(session, "处理消息失败: " + e.getMessage());
        }
    }

    private void handleConnect(WebSocketSession session, JsonNode json) throws Exception {
        Long deviceId = json.get("deviceId").asLong();
        Device device = deviceRepository.findById(deviceId).orElse(null);

        if (device == null) {
            sendError(session, "设备不存在");
            return;
        }

        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            sendError(session, "设备SSH凭证未配置，请先在设备管理中配置SSH用户名和密码");
            return;
        }

        try {
            log.info("开始创建SSH会话到设备: {}", device.getName());
            SshSession sshSession = new SshSession(session, device, device.getSshPassword());
            sshSession.connect();
            sessions.put(session.getId(), sshSession);
            log.info("SSH连接成功: {}", device.getName());
            
            sendConnected(session, device.getName());
        } catch (Exception e) {
            log.error("SSH连接失败: {}", e.getMessage(), e);
            sendError(session, "SSH连接失败: " + e.getMessage());
        }
    }
    
    private void sendConnected(WebSocketSession session, String deviceName) throws Exception {
        Map<String, String> msg = new java.util.HashMap<>();
        msg.put("type", "connected");
        msg.put("deviceName", deviceName != null ? deviceName : "");
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
    }

    private void handleInput(WebSocketSession session, JsonNode json) throws Exception {
        String data = json.has("data") ? json.get("data").asText() : "";
        SshSession sshSession = sessions.get(session.getId());

        if (sshSession != null) {
            try {
                sshSession.sendCommand(data);
            } catch (Exception e) {
                log.error("发送命令失败: {}", e.getMessage());
                sendError(session, "发送命令失败: " + e.getMessage());
            }
        }
    }

    private void handleResize(WebSocketSession session, JsonNode json) {
        SshSession sshSession = sessions.get(session.getId());
        if (sshSession == null) return;
        int cols = json.has("cols") ? json.get("cols").asInt(120) : 120;
        int rows = json.has("rows") ? json.get("rows").asInt(40) : 40;
        sshSession.resizePty(cols, rows);
    }

    private void sendError(WebSocketSession session, String message) throws Exception {
        Map<String, String> msg = new java.util.HashMap<>();
        msg.put("type", "error");
        msg.put("message", message != null ? message : "未知错误");
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {}", session.getId());
        SshSession sshSession = sessions.remove(session.getId());
        if (sshSession != null) {
            sshSession.disconnect();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error", exception);
        SshSession sshSession = sessions.remove(session.getId());
        if (sshSession != null) {
            sshSession.disconnect();
        }
    }
}
