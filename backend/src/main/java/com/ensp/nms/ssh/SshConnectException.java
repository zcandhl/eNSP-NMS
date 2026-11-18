package com.ensp.nms.ssh;

/**
 * SSH 连接失败（网络/端口/协商/认证层面）。
 * <p>消息为面向运维人员的中文描述，可直接展示在前端提示与审计日志里；
 * 原始 JSch 异常通过 cause 保留，日志打印时仍可回溯底层堆栈。</p>
 */
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
