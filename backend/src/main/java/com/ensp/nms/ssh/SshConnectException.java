package com.ensp.nms.ssh;

/**
 * SSH 连接异常，携带主机与端口信息。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

public class SshConnectException extends RuntimeException {

    private final String host;
    private final int port;

    public SshConnectException(String host, int port, String message, Throwable cause) {
        super(message, cause);
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
