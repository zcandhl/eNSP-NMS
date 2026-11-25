package com.ensp.nms.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH/Telnet 命令与配置下发、配置回显采集。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Slf4j
@Service
public class SshClient {

    public static final String PROTOCOL_SSH = "ssh";
    public static final String PROTOCOL_TELNET = "telnet";

    // 通道/命令超时保持收紧：不可达设备不该拖死整批验收
    private static final int CHANNEL_TIMEOUT = 8000;

    @Value("${nms.ssh.connect-timeout-ms:8000}")
    private int connectTimeoutMs = 8000;

    @Value("${nms.ssh.tcp-probe-ms:3000}")
    private int tcpProbeMs = 3000;

    @Value("${nms.telnet.auth-timeout-ms:6000}")
    private int telnetAuthTimeoutMs = 6000;

    private static final int COMMAND_READ_TIMEOUT_MS = 5000;
    private static final int CONFIG_READ_TIMEOUT_MS = 60000;
    private static final int CONFIG_IDLE_MS = 1500;

    public record CommandBatchResult(String summary, int successCount, int failCount) {
        public boolean ok() {
            return failCount == 0;
        }
    }

    private static String eol(String protocol) {
        return isTelnet(protocol) ? "\r\n" : "\n";
    }

    public static boolean isTelnet(String protocol) {
        return protocol != null && PROTOCOL_TELNET.equalsIgnoreCase(protocol.trim());
    }

    public static int defaultPort(String protocol) {
        return isTelnet(protocol) ? 23 : 22;
    }

    private static String label(String protocol) {
        return isTelnet(protocol) ? "Telnet" : "SSH";
    }

    public String executeCommand(String host, int port, String username, String password, String command) throws Exception {
        return executeCommand(host, port, username, password, command, PROTOCOL_SSH);
    }

    public String executeCommand(String host, int port, String username, String password, String command,
                                 String protocol) throws Exception {
        CommandBatchResult result = executeConfigCommands(host, port, username, password,
                List.of(command), "执行命令", null, protocol);
        return result.summary();
    }

    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel) throws Exception {
        return executeConfigCommands(host, port, username, password, rawCommands, actionLabel, null, PROTOCOL_SSH);
    }

    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel,
                                                    java.util.function.BiConsumer<Integer, Integer> progressCallback) throws Exception {
        return executeConfigCommands(host, port, username, password, rawCommands, actionLabel, progressCallback, PROTOCOL_SSH);
    }

    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel,
                                                    java.util.function.BiConsumer<Integer, Integer> progressCallback,
                                                    String protocol) throws Exception {
        List<String> commands = filterConfigCommands(rawCommands);
        // 防御：命令被过滤光时显式报错，绝不静默返回"成功: 0, 失败: 0"
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的命令（传入 "
                    + (rawCommands == null ? 0 : rawCommands.size()) + " 行，全部为空/注释/提示符行）");
        }
        StringBuilder result = new StringBuilder();
        result.append("开始").append(actionLabel != null ? actionLabel : "配置").append("...\n");

        ShellTransport transport = null;
        int successCount = 0;
        int failCount = 0;
        int total = Math.max(commands.size(), 1);
        String lineEnd = eol(protocol);

        try {
            if (progressCallback != null) {
                progressCallback.accept(0, total);
            }
            transport = prepare(host, port, username, password, protocol);
            InputStream in = transport.in();
            OutputStream out = transport.out();

            int done = 0;
            for (String command : commands) {
                try {
                    log.info("{} 执行: {}", label(protocol), command);
                    out.write((command + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String output = readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS);
                    // save等命令可能出现 [Y/N] 确认
                    if (isConfirmPrompt(output)) {
                        out.write(("Y" + lineEnd).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        output = readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS);
                    }
                    if (isCommandFailed(output)) {
                        failCount++;
                        result.append("✗ ").append(command).append("\n");
                        log.warn("命令失败: {} => {}", command, truncate(output, 200));
                    } else {
                        successCount++;
                        result.append("✓ ").append(command).append("\n");
                    }
                } catch (Exception e) {
                    failCount++;
                    result.append("✗ ").append(command).append(": ").append(e.getMessage()).append("\n");
                    log.error("命令异常: {} - {}", command, e.getMessage());
                }
                done++;
                if (progressCallback != null) {
                    progressCallback.accept(done, total);
                }
            }

            result.append("\n").append(actionLabel != null ? actionLabel : "配置")
                    .append("完成！成功: ").append(successCount)
                    .append(", 失败: ").append(failCount).append("\n");
            return new CommandBatchResult(result.toString(), successCount, failCount);
        } finally {
            closeQuietly(transport);
        }
    }

    public String executeDisplayCommand(String host, int port, String username, String password, String command) throws Exception {
        return executeDisplayCommand(host, port, username, password, command, PROTOCOL_SSH);
    }

    public String executeDisplayCommand(String host, int port, String username, String password, String command,
                                        String protocol) throws Exception {
        ShellTransport transport = null;
        try {
            transport = prepare(host, port, username, password, protocol);
            InputStream in = transport.in();
            OutputStream out = transport.out();

            out.write((command + eol(protocol)).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String result = readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS * 2);
            result = autoPageThrough(in, out, result);
            int startIdx = indexOfIgnoreCase(result, command);
            if (startIdx < 0) {
                startIdx = 0;
            }
            return result.substring(startIdx).trim();
        } finally {
            closeQuietly(transport);
        }
    }

    public String getHuaweiConfig(String host, int port, String username, String password, boolean startup) throws Exception {
        return getHuaweiConfig(host, port, username, password, startup, PROTOCOL_SSH);
    }

    public String getHuaweiConfig(String host, int port, String username, String password, boolean startup,
                                  String protocol) throws Exception {
        ShellTransport transport = null;
        try {
            transport = prepare(host, port, username, password, protocol);
            InputStream in = transport.in();
            OutputStream out = transport.out();

            String command = startup ? "display saved-configuration" : "display current-configuration";
            out.write((command + eol(protocol)).getBytes(StandardCharsets.UTF_8));
            out.flush();

            String result = readUntilPromptOrIdle(in, CONFIG_READ_TIMEOUT_MS, CONFIG_IDLE_MS);
            // 分页未关时会出现 ---- More ----，这里兜底翻页
            result = autoPageThrough(in, out, result);
            int startIdx = indexOfIgnoreCase(result, command);
            if (startIdx < 0) {
                startIdx = indexOfIgnoreCase(result, startup ? "saved-configuration" : "current-configuration");
            }
            if (startIdx < 0) {
                startIdx = 0;
            }
            String content = result.substring(startIdx).trim();
            // 防御：设备刚启动/命令未生效时可能只回一行提示符，不能当作有效配置入库
            if (content.length() < 40 || !content.contains("#")) {
                String hint = content.isEmpty() ? "无任何回显" : truncate(content, 120);
                throw new SshConnectException(host, port > 0 ? port : defaultPort(protocol),
                        "读取配置失败：设备未返回有效配置内容（" + hint
                                + "）。设备可能在重启中或会话未进入命令行视图", null);
            }
            return content;
        } finally {
            closeQuietly(transport);
        }
    }

    public boolean testConnection(String host, int port, String username, String password) {
        return testConnection(host, port, username, password, PROTOCOL_SSH);
    }

    public boolean testConnection(String host, int port, String username, String password, String protocol) {
        try {
            executeCommand(host, port, username, password, "display version", protocol);
            return true;
        } catch (Exception e) {
            log.error("{} 连接失败: {}:{}", label(protocol), host, port, e);
            return false;
        }
    }

    private ShellTransport prepare(String host, int port, String username, String password, String protocol) throws Exception {
        ShellTransport transport = open(host, port, username, password, protocol);
        try {
            Thread.sleep(800);
            drainAvailable(transport.in());
            OutputStream out = transport.out();
            out.write(("screen-length 0 temporary" + eol(protocol)).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // AC6005的用户视图不支持该命令，失败不影响后续操作
            // 分页已在VTY视图用screen-length 0关闭，这里只做尽力而为
            readUntilPrompt(transport.in(), COMMAND_READ_TIMEOUT_MS);
            return transport;
        } catch (Exception e) {
            closeQuietly(transport);
            throw e;
        }
    }

    public ShellTransport openInteractive(String host, int port, String username, String password,
                                          String protocol) throws Exception {
        ShellTransport transport = open(host, port, username, password, protocol);
        Socket socket = transport.socket();
        if (socket != null) {
            socket.setSoTimeout(0);
        }
        return transport;
    }

    private ShellTransport open(String host, int port, String username, String password, String protocol) throws Exception {
        return isTelnet(protocol)
                ? openTelnet(host, port > 0 ? port : 23, username, password)
                : openSsh(host, port > 0 ? port : 22, username, password);
    }

    private ShellTransport openSsh(String host, int port, String username, String password) throws JSchException {
        probeTcp(host, port, PROTOCOL_SSH);

        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password");
        session.setConfig("kex", "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521");
        session.setConfig("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
        session.setConfig("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
        session.setConfig("mac.s2c", "hmac-md5,hmac-sha1,hmac-sha2-256");
        session.setConfig("mac.c2s", "hmac-md5,hmac-sha1,hmac-sha2-256");
        try {
            session.connect(connectTimeoutMs);
        } catch (JSchException e) {
            String friendly = explain(e);
            log.warn("SSH 建连失败 {}:{}({}): {}", host, port, username, friendly);
            throw new SshConnectException(host, port, friendly, e);
        }

        ChannelShell channel = null;
        try {
            channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType("vt100");
            channel.setPty(true);
            channel.connect(CHANNEL_TIMEOUT);
        } catch (Exception e) {
            closeSsh(null, null, channel, session);
            throw new SshConnectException(host, port, "SSH 通道打开失败：" + explain(e), e);
        }

        final ChannelShell finalChannel = channel;
        final Session finalSession = session;
        final InputStream in;
        final OutputStream out;
        try {
            in = channel.getInputStream();
            out = channel.getOutputStream();
        } catch (Exception e) {
            closeSsh(null, null, finalChannel, finalSession);
            throw new SshConnectException(host, port, "SSH 通道读写流获取失败：" + e.getMessage(), e);
        }
        return new ShellTransport() {
            @Override
            public InputStream in() {
                return in;
            }

            @Override
            public OutputStream out() {
                return out;
            }

            @Override
            public void close() {
                closeSsh(in, out, finalChannel, finalSession);
            }
        };
    }

    private ShellTransport openTelnet(String host, int port, String username, String password) throws Exception {
        probeTcp(host, port, PROTOCOL_TELNET);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), Math.max(tcpProbeMs, 1000));
            socket.setSoTimeout(Math.max(telnetAuthTimeoutMs, 3000));
            socket.setTcpNoDelay(true);
            final OutputStream rawOut = socket.getOutputStream();
            final TelnetInputStream in = new TelnetInputStream(socket.getInputStream(), rawOut);

            String banner = readFor(in, 2500);
            // AC6005在上一个会话刚断开时可能不主动推banner，补发回车唤醒登录提示
            if (banner == null || banner.isBlank()) {
                rawOut.write("\r\n".getBytes(StandardCharsets.UTF_8));
                rawOut.flush();
                banner = readFor(in, 2500);
            }
            log.debug("Telnet banner {}:{} => {}", host, port, truncate(banner, 120));

            if (containsAny(banner, "Username", "username", "Login:", "login:")) {
                rawOut.write((username + "\r\n").getBytes(StandardCharsets.UTF_8));
                rawOut.flush();
                String userResp = readFor(in, Math.min(telnetAuthTimeoutMs, 4000));
                if (isAuthFailed(userResp + banner)) {
                    throw new SshConnectException(host, port, telnetAuthMessage(host, port), null);
                }
            }

            rawOut.write((password + "\r\n").getBytes(StandardCharsets.UTF_8));
            rawOut.flush();

            String afterPass = readFor(in, Math.min(telnetAuthTimeoutMs, 5000));
            String joined = banner + afterPass;
            if (isAuthFailed(joined)) {
                throw new SshConnectException(host, port, telnetAuthMessage(host, port), null);
            }
            if (!containsAny(joined, "<", "[")) {
                // 再给一次机会：有些镜像回显较慢
                joined += readFor(in, 2500);
                if (!containsAny(joined, "<", "[")) {
                    throw new SshConnectException(host, port,
                            "Telnet 登录后未进入命令行：设备可能仍在启动，或该账号无命令行权限", null);
                }
            }
            // 进入后把读超时降到命令级，避免单条命令卡太久
            socket.setSoTimeout(COMMAND_READ_TIMEOUT_MS);
            // 登录成功即协商阶段结束：复位解析状态，防止残留的未完成协商吞掉后续普通字符
            in.resetState();

            final Socket finalSocket = socket;
            return new ShellTransport() {
                @Override
                public InputStream in() {
                    return in;
                }

                @Override
                public OutputStream out() {
                    return rawOut;
                }

                @Override
                public Socket socket() {
                    return finalSocket;
                }

                @Override
                public void close() {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                    }
                    try {
                        rawOut.close();
                    } catch (Exception ignore) {
                    }
                    try {
                        finalSocket.close();
                    } catch (Exception ignore) {
                    }
                }
            };
        } catch (SshConnectException e) {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            throw e;
        } catch (Exception e) {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            throw new SshConnectException(host, port, "Telnet 连接失败：" + e.getMessage(), e);
        }
    }

    private static String telnetAuthMessage(String host, int port) {
        return "Telnet 认证失败：用户名或密码错误，或该账号未配置 telnet 服务类型"
                + "（需在 AAA 中执行 local-user <用户名> service-type telnet）。目标 " + host + ":" + port;
    }

    private static boolean isAuthFailed(String text) {
        if (text == null) {
            return false;
        }
        String u = text.toUpperCase();
        return u.contains("AUTHENTICATION FAIL") || u.contains("LOGIN FAILED")
                || u.contains("AUTHENTICATION FAILED") || u.contains("ERROR: AUTHENTICATION");
    }

    private static boolean containsAny(String src, String... needles) {
        if (src == null) {
            return false;
        }
        for (String n : needles) {
            if (src.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String readFor(InputStream in, int millis) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < millis) {
            int avail = in.available();
            if (avail > 0) {
                int len = in.read(buffer, 0, Math.min(buffer.length, avail));
                if (len > 0) {
                    sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                }
            } else {
                Thread.sleep(50);
            }
        }
        return sb.toString();
    }

    private String autoPageThrough(InputStream in, OutputStream out, String first) throws Exception {
        StringBuilder all = new StringBuilder(first);
        String chunk = first;
        int guard = 0;
        while (chunk.contains("---- More ----") && guard++ < 300) {
            out.write(" ".getBytes(StandardCharsets.UTF_8));
            out.flush();
            chunk = readFor(in, 900);
            all.append(chunk);
        }
        // 去掉翻页提示行，避免污染入库内容
        return all.toString().replaceAll("----\\s*[Mm]ore\\s*----", "");
    }

    private void probeTcp(String host, int port, String protocol) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), Math.max(tcpProbeMs, 500));
        } catch (IOException e) {
            String name = isTelnet(protocol) ? "Telnet" : "SSH";
            String hint = isTelnet(protocol)
                    ? "设备未启动、管理地址不通或 telnet 服务未开启"
                    : "设备未启动、管理地址不通或 SSH 未开启";
            String msg = String.format("%s 端口不可达：%s:%d 无法建立 TCP 连接（%s）", name, host, port, hint);
            log.warn(msg + " - " + e.getMessage());
            throw new SshConnectException(host, port, msg, e);
        }
    }

    public static String explain(Throwable e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String lower = raw.toLowerCase();
        if (lower.contains("timeout: socket is not established")
                || lower.contains("read timed out")
                || lower.contains("sockettimeout")
                || lower.contains("timeout")) {
            return "SSH 协商超时：TCP 已连通，但设备未在超时时间内完成 SSH 握手"
                    + "（常见于设备正在重启/CPU 繁忙、SSH 服务未启用、或缺少 RSA 本地密钥）";
        }
        if (lower.contains("connection refused")) {
            return "端口被拒绝：设备未开启对应服务（stelnet server enable / telnet server enable）或端口不是该值";
        }
        if (lower.contains("auth fail") || lower.contains("auth cancel")) {
            return "认证失败：用户名或密码错误，或该用户未配置对应服务类型";
        }
        if (lower.contains("algorithm negotiation fail")) {
            return "SSH 算法协商失败：设备 SSH 版本过旧，需开放旧版 kex/cipher";
        }
        if (lower.contains("no route to host") || lower.contains("network is unreachable")) {
            return "设备网络不可达：请检查管理地址与路由";
        }
        if (lower.contains("connection reset")) {
            return "连接被设备重置：设备会话数已满或服务异常重启";
        }
        return "连接失败：" + raw;
    }

    public static List<String> prepareRestoreCommands(String content) {
        List<String> raw = new ArrayList<>();
        if (content != null) {
            for (String line : content.split("\n", -1)) {
                // 备份文本里的display回显行不是配置命令，恢复时必须剔除
                String t = line.trim().toLowerCase();
                if (t.startsWith("display ") || t.equals("display")) {
                    continue;
                }
                raw.add(line);
            }
        }
        List<String> filtered = filterConfigCommands(raw);
        // 去掉文末return/save/Y，避免未进system-view就return，或重复save
        while (!filtered.isEmpty()) {
            String last = filtered.get(filtered.size() - 1).trim().toLowerCase();
            if (last.equals("return") || last.equals("save") || last.equals("y") || last.equals("n")
                    || last.equals("yes") || last.equals("no")) {
                filtered.remove(filtered.size() - 1);
            } else {
                break;
            }
        }
        List<String> out = new ArrayList<>();
        boolean hasSysView = false;
        for (String c : filtered) {
            String t = c.trim().toLowerCase();
            if (t.equals("system-view") || t.equals("sys")) {
                hasSysView = true;
                break;
            }
        }
        if (!hasSysView) {
            out.add("system-view");
        }
        out.addAll(filtered);
        out.add("return");
        out.add("save");
        return out;
    }

    static List<String> filterConfigCommands(List<String> rawCommands) {
        List<String> commands = new ArrayList<>();
        if (rawCommands == null) {
            return commands;
        }
        for (String raw : rawCommands) {
            if (raw == null) {
                continue;
            }
            String command = raw.trim();
            if (command.isEmpty()) {
                continue;
            }
            // 提示符/注释/分页
            if (command.startsWith("#") || command.startsWith("<") || command.startsWith("[")
                    || command.equalsIgnoreCase("More") || command.startsWith("----")) {
                continue;
            }
            String lower = command.toLowerCase();
            // 跳过明显非配置头
            if (lower.startsWith("using ") || lower.startsWith("now time")
                    || lower.contains("configuration in this view")) {
                continue;
            }
            // 注意：display命令不在这里过滤——它可能是用户要执行的正常查询
            //。备份文本里的display回显行由
            // prepareRestoreCommands在恢复场景下专门剔除。
            // quit必须保留，否则interface/ospf等视图无法退出
            commands.add(command);
        }
        return commands;
    }

    private static boolean endsWithPrompt(StringBuilder sb) {
        String s = sb.toString();
        String stripped = s.stripTrailing();
        if (stripped.isEmpty()) {
            return false;
        }
        String tail = stripped.length() > 40 ? stripped.substring(stripped.length() - 40) : stripped;
        String tailUpper = tail.toUpperCase();
        // 确认提示也视为“可读完”，由上层发送Y
        if (tailUpper.contains("[Y/N]") || tailUpper.contains("[YES/NO]") || tailUpper.contains("(Y/N)")) {
            return true;
        }
        int n = stripped.length();
        for (int i = n - 1; i >= 0 && i >= n - 40; i--) {
            char c = stripped.charAt(i);
            if (c == '>' || c == ']') {
                // 避免把 [Y/N] 里的 ] 误判为视图提示符
                return true;
            }
            if (c != ' ' && c != '\r' && c != '\n' && c != '\t') {
                break;
            }
        }
        return false;
    }

    static boolean isConfirmPrompt(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String u = output.toUpperCase();
        return u.contains("[Y/N]") || u.contains("[YES/NO]") || u.contains("(Y/N)");
    }

    static boolean isCommandFailed(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String lower = t.toLowerCase();
            if (lower.startsWith("error:") || lower.startsWith("error：")) {
                return true;
            }
            if (lower.contains("unrecognized command")
                    || lower.contains("wrong parameter")
                    || lower.contains("incomplete command")
                    || lower.contains("ambiguous command")
                    || lower.contains("too many parameters")) {
                return true;
            }
            if (t.startsWith("错误") || t.contains("未识别的命令") || t.contains("命令错误")) {
                return true;
            }
        }
        return false;
    }

    private static String readUntilPrompt(InputStream in, int timeoutMs) throws Exception {
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[4096];
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (in.available() > 0) {
                int len = in.read(buffer);
                if (len > 0) {
                    output.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    if (endsWithPrompt(output)) {
                        return output.toString();
                    }
                }
            }
            Thread.sleep(40);
        }
        return output.toString();
    }

    private static String readUntilPromptOrIdle(InputStream in, int timeoutMs, int idleMs) throws Exception {
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[8192];
        long start = System.currentTimeMillis();
        long lastData = start;
        while (System.currentTimeMillis() - start < timeoutMs) {
            boolean got = false;
            while (in.available() > 0) {
                int len = in.read(buffer);
                if (len > 0) {
                    got = true;
                    lastData = System.currentTimeMillis();
                    output.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    if (endsWithPrompt(output) && output.length() > 80) {
                        return output.toString();
                    }
                }
            }
            if (!got && output.length() > 0 && System.currentTimeMillis() - lastData >= idleMs) {
                return output.toString();
            }
            Thread.sleep(50);
        }
        return output.toString();
    }

    private static void drainAvailable(InputStream in) throws Exception {
        byte[] buffer = new byte[4096];
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 500) {
            if (in.available() > 0) {
                //noinspection ResultOfMethodCallIgnored
                in.read(buffer);
            } else {
                Thread.sleep(30);
            }
        }
    }

    private static int indexOfIgnoreCase(String src, String needle) {
        if (src == null || needle == null) {
            return -1;
        }
        return src.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void closeQuietly(ShellTransport transport) {
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static void closeSsh(InputStream in, OutputStream out, ChannelShell channel, Session session) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignore) {
            }
        }
        if (channel != null) {
            try {
                channel.disconnect();
            } catch (Exception ignore) {
            }
        }
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

/** 统一通道抽象：SSH的JSch通道与telnet的Socket都归到这里 */
    public interface ShellTransport {
        InputStream in();

        OutputStream out();

        default Socket socket() {
            return null;
        }

        void close();
    }

    private static class TelnetInputStream extends InputStream {
        private static final int IAC = 255;
        private static final int DONT = 254;
        private static final int DO = 253;
        private static final int WONT = 252;
        private static final int WILL = 251;
        private static final int SB = 250;
        private static final int SE = 240;

        private final InputStream in;
        private final OutputStream out;
        private final java.util.ArrayDeque<Integer> buf = new java.util.ArrayDeque<>();

        // IAC解析状态机：不完整的协商序列只挂起状态，绝不阻塞等下一个字节
        private static final int ST_NORMAL = 0;
        private static final int ST_IAC = 1;
        private static final int ST_OPT = 2;
        private static final int ST_SB = 3;
        private static final int ST_SB_IAC = 4;
        private int state = ST_NORMAL;
        private int pendingCmd;

        TelnetInputStream(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public int read() throws IOException {
            while (true) {
                Integer b = buf.poll();
                if (b != null) {
                    return b;
                }
                int raw = in.read();
                if (raw < 0) {
                    return -1;
                }
                feed(raw);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            b[off] = (byte) first;
            int count = 1;
            while (count < len) {
                Integer next = buf.poll();
                if (next == null) {
                    break;
                }
                b[off + count] = (byte) (int) next;
                count++;
            }
            return count;
        }

        private void feed(int b) {
            switch (state) {
                case ST_NORMAL -> {
                    if (b == IAC) {
                        state = ST_IAC;
                    } else {
                        buf.add(b);
                    }
                }
                case ST_IAC -> {
                    if (b == IAC) {
                        buf.add(IAC);
                        state = ST_NORMAL;
                    } else if (b == WILL || b == WONT || b == DO || b == DONT) {
                        pendingCmd = b;
                        state = ST_OPT;
                    } else if (b == SB) {
                        state = ST_SB;
                    } else {
                        // NOP/GA等其他命令直接忽略
                        state = ST_NORMAL;
                    }
                }
                case ST_OPT -> {
                    // 静默忽略协商，不回复DONT/WONT。
                    // 实测AC6005的telnet服务收到客户端拒绝响应（DONT/WONT）后不再进入正常
                    // 会话；完全静默反而一切正常。
                    state = ST_NORMAL;
                }
                case ST_SB -> {
                    if (b == IAC) {
                        state = ST_SB_IAC;
                    }
                    // 子协商体其他字节整段丢弃
                }
                case ST_SB_IAC -> {
                    if (b == SE) {
                        state = ST_NORMAL;
                    } else if (b == IAC) {
                        state = ST_SB_IAC;
                    } else {
                        state = ST_SB;
                    }
                }
                default -> state = ST_NORMAL;
            }
        }

        private int tryReadNonBlocking() throws IOException {
            if (in.available() > 0) {
                return in.read();
            }
            return -2;
        }

        void resetState() {
            state = ST_NORMAL;
            buf.clear();
        }

        private void respond(int cmd, int opt) {
            try {
                out.write(new byte[]{(byte) IAC, (byte) cmd, (byte) opt});
                out.flush();
            } catch (IOException ignore) {
                // 协商失败不影响主流程
            }
        }

        @Override
        public int available() throws IOException {
            // 先把底层已到达的字节全部解析进过滤缓冲，再报告过滤后的可读量。
            // 关键：只做非阻塞读取，IAC序列不完整时状态机挂起等待，绝不在此阻塞。
            while (true) {
                int raw = tryReadNonBlocking();
                if (raw < 0) {
                    break;
                }
                feed(raw);
            }
            return buf.size();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
