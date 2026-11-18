package com.ensp.nms.ssh;

import com.jcraft.jsch.JSchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 SSH 底层异常的中文翻译：前端直接把这段文字展示给运维，
 * 必须能区分「设备慢/重启中」与「密码错」「端口不通」等确定性问题。
 */
class SshClientExplainTest {

    @Test
    @DisplayName("socket is not established 应翻译成协商超时，而不是照抄英文")
    void translateHandshakeTimeout() {
        String msg = SshClient.explain(new JSchException("timeout: socket is not established"));
        assertTrue(msg.contains("协商超时"), msg);
        assertTrue(msg.contains("握手"), msg);
        assertFalse(msg.contains("socket is not established"), msg);
    }

    @Test
    @DisplayName("Read timed out 同属握手超时")
    void translateReadTimedOut() {
        String msg = SshClient.explain(new JSchException("Session.connect: java.net.SocketTimeoutException: Read timed out"));
        assertTrue(msg.contains("协商超时"), msg);
    }

    @Test
    @DisplayName("认证失败不能被当成超时")
    void translateAuthFail() {
        String msg = SshClient.explain(new JSchException("Auth fail"));
        assertTrue(msg.contains("认证失败"), msg);
        assertFalse(msg.contains("超时"), msg);
    }

    @Test
    @DisplayName("端口拒绝要提示检查 stelnet server enable")
    void translateRefused() {
        String msg = SshClient.explain(new JSchException("java.net.ConnectException: Connection refused"));
        assertTrue(msg.contains("端口被拒绝"), msg);
        assertTrue(msg.contains("stelnet"), msg);
    }

    @Test
    @DisplayName("算法协商失败单独识别")
    void translateAlgorithm() {
        String msg = SshClient.explain(new JSchException("Algorithm negotiation fail"));
        assertTrue(msg.contains("算法协商失败"), msg);
    }

    @Test
    @DisplayName("未知异常保留原文，不吞信息")
    void translateUnknown() {
        String msg = SshClient.explain(new JSchException("something unexpected"));
        assertTrue(msg.contains("something unexpected"), msg);
    }
}
