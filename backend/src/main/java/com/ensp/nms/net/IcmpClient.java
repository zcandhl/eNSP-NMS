package com.ensp.nms.net;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * ICMP / Ping 可达性探测（虚拟 PC 等无 SNMP 设备）。
 */
@Slf4j
@Component
public class IcmpClient {

    private static final int DEFAULT_TIMEOUT_MS = 2000;

    public boolean ping(String ip) {
        return ping(ip, DEFAULT_TIMEOUT_MS);
    }

    public boolean ping(String ip, int timeoutMs) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String host = ip.trim();
        int timeout = Math.max(500, Math.min(timeoutMs, 10000));

        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isReachable(timeout)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("InetAddress.isReachable 失败 {}: {}", host, e.getMessage());
        }

        return pingViaOs(host, timeout);
    }

    private boolean pingViaOs(String host, int timeoutMs) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        int timeoutSec = Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
        ProcessBuilder pb = windows
                ? new ProcessBuilder("ping", "-n", "1", "-w", String.valueOf(timeoutMs), host)
                : new ProcessBuilder("ping", "-c", "1", "-W", String.valueOf(timeoutSec), host);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs + 1500L, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int code = process.exitValue();
            String text = output.toString().toLowerCase();
            if (code == 0) {
                return true;
            }
            // 部分 Windows 区域设置下 exit code 不可靠，再看输出关键字
            return text.contains("ttl=") || text.contains("字节=") || text.contains("bytes=");
        } catch (Exception e) {
            log.debug("系统 ping 失败 {}: {}", host, e.getMessage());
            return false;
        }
    }
}
