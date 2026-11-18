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
 * 设备命令行通道客户端。
 * <p>支持两种远程管理协议：</p>
 * <ul>
 *   <li>{@code ssh}（stelnet）：默认，适用于 AR/S5700 等 eNSP 全功能镜像</li>
 *   <li>{@code telnet}：用于 AC6005 等 SSH 实现异常的镜像。实测 AC6005 V200R007
 *       在 eNSP 中 TCP 22 可连、banner 可收，但 KEXINIT 后服务端不再响应，
 *       因此 AC 类设备统一走 telnet，由 {@code Device.managementProtocol} 指定</li>
 * </ul>
 * <p>上层业务（备份、下发、只读查询）共用同一套会话编排逻辑，仅底层传输不同。</p>
 */
@Slf4j
@Service
public class SshClient {

    public static final String PROTOCOL_SSH = "ssh";
    public static final String PROTOCOL_TELNET = "telnet";

    // 通道/命令超时保持收紧：不可达设备不该拖死整批验收
    private static final int CHANNEL_TIMEOUT = 8000;

    /**
     * SSH 协商超时（TCP 建连 + kex + 认证）。
     * <p>eNSP 里设备启动或 CPU 繁忙时，kex 阶段可能长时间无响应，
     * 4s 会把「设备慢」误判成「不可达」；改为可配置，默认放宽到 8s。</p>
     */
    @Value("${nms.ssh.connect-timeout-ms:8000}")
    private int connectTimeoutMs = 8000;

    /** 建连前先探一次 TCP 端口，用于区分「端口不通」与「握手超时」，给出不同提示 */
    @Value("${nms.ssh.tcp-probe-ms:3000}")
    private int tcpProbeMs = 3000;

    /** Telnet 登录阶段的读超时 */
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

    /** 统一的行尾：SSH 通道用 \n，telnet 必须 \r\n */
    private static String eol(String protocol) {
        return isTelnet(protocol) ? "\r\n" : "\n";
    }

    public static boolean isTelnet(String protocol) {
        return protocol != null && PROTOCOL_TELNET.equalsIgnoreCase(protocol.trim());
    }

    /** 按协议给出默认端口 */
    public static int defaultPort(String protocol) {
        return isTelnet(protocol) ? 23 : 22;
    }

    private static String label(String protocol) {
        return isTelnet(protocol) ? "Telnet" : "SSH";
    }

    // ==================== 对外业务方法（带协议重载） ====================

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

    /**
     * 批量下发配置命令：统一会话、等待提示符、统计成败。
     *
     * @param progressCallback 可选，参数为 (已完成命令数, 总命令数)
     */
    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel,
                                                    java.util.function.BiConsumer<Integer, Integer> progressCallback,
                                                    String protocol) throws Exception {
        List<String> commands = filterConfigCommands(rawCommands);
        // 防御：命令被过滤光（例如传了空内容）时显式报错，绝不静默返回"成功: 0, 失败: 0"
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
                    // save 等命令可能出现 [Y/N] 确认
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

    /**
     * 执行一条 display 类查询命令并返回原始输出（独立会话，screen-length 0，自动截去回显噪声）。
     * 供拓扑发现等只读采集使用，不做任何配置变更。
     */
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

    // ==================== 会话建立 ====================

    /** 建立通道并完成会话初始化（关分页 + 清场），失败时确保底层连接被释放 */
    private ShellTransport prepare(String host, int port, String username, String password, String protocol) throws Exception {
        ShellTransport transport = open(host, port, username, password, protocol);
        try {
            Thread.sleep(800);
            drainAvailable(transport.in());
            OutputStream out = transport.out();
            out.write(("screen-length 0 temporary" + eol(protocol)).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // AC6005 的用户视图不支持该命令（要求必须在 VTY 视图），失败不影响后续操作；
            // 分页已在 VTY 视图用 screen-length 0 关闭，这里只做尽力而为
            readUntilPrompt(transport.in(), COMMAND_READ_TIMEOUT_MS);
            return transport;
        } catch (Exception e) {
            closeQuietly(transport);
            throw e;
        }
    }

    /**
     * 建立交互式会话（登录 + 进入命令行），供 Web 终端（WebSocket）复用。
     * telnet 协议时已处理 IAC 协商与登录流程，返回的流即为过滤后的干净数据流。
     * <p>交互式会话关闭读超时：终端空闲是常态，命令级的 5s 超时会在用户停止输入后
     * 把长连接误判为断开；断连由 read 返回 -1 或 socket 关闭感知。</p>
     */
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

    /**
     * Telnet 会话：自行处理 IAC 协商字节，避免引入额外依赖。
     * <p>VRP 会主动协商 WILL ECHO / WILL SGA / DO TTYPE，这里统一拒绝，
     * 只保留最朴素的「发命令、读回显」能力。</p>
     */
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
            // AC6005 在上一个会话刚断开时可能不主动推 banner，补发回车唤醒登录提示
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

    /**
     * 遇到 ---- More ---- 时补发空格翻页，直到配置输出完整。
     * <p>只根据「最新一段回显」判断是否还有下一页，避免历史 More 文本导致死循环。</p>
     */
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

    /** 先探 TCP 端口：端口不通直接给出「不可达」结论，避免和握手超时混淆 */
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

    /**
     * 把底层异常翻译成运维能直接照着排查的中文提示。
     * 注意：JSch 的 "timeout: socket is not established" 实际含义是
     * 「TCP 已连通，但 SSH 协商（kex/认证）在超时时间内没走完」，并不只是 "socket 没建立"。
     */
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

    /**
     * 将 display 备份文本整理为可交互下发的命令序列：
     * 去掉注释/回显噪声，补 system-view，去掉文末 return/save（由本方法统一追加）。
     */
    public static List<String> prepareRestoreCommands(String content) {
        List<String> raw = new ArrayList<>();
        if (content != null) {
            for (String line : content.split("\n", -1)) {
                // 备份文本里的 display 回显行不是配置命令，恢复时必须剔除
                String t = line.trim().toLowerCase();
                if (t.startsWith("display ") || t.equals("display")) {
                    continue;
                }
                raw.add(line);
            }
        }
        List<String> filtered = filterConfigCommands(raw);
        // 去掉文末 return/save/Y，避免未进 system-view 就 return，或重复 save
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
            // 跳过明显非配置头（备份文本里的元信息行）
            if (lower.startsWith("using ") || lower.startsWith("now time")
                    || lower.contains("configuration in this view")) {
                continue;
            }
            // 注意：display 命令不在这里过滤——它可能是用户要执行的正常查询
            // （验收巡检/只读巡检/WebSSH）。备份文本里的 display 回显行由
            // prepareRestoreCommands 在恢复场景下专门剔除。
            // quit 必须保留，否则 interface/ospf 等视图无法退出
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
        // 确认提示也视为“可读完”，由上层发送 Y
        if (tailUpper.contains("[Y/N]") || tailUpper.contains("[YES/NO]") || tailUpper.contains("(Y/N)")) {
            return true;
        }
        int n = stripped.length();
        for (int i = n - 1; i >= 0 && i >= n - 40; i--) {
            char c = stripped.charAt(i);
            if (c == '>' || c == ']') {
                // 避免把 [Y/N] 里的 ] 误判为视图提示符（上面已处理含 Y/N 的情况）
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

    /** 按华为 VRP 典型错误行判断，避免 description 等文本误伤 */
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

    /** 拉配置：等到提示符，或在空闲一段时间后认为输出结束 */
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

    /** 统一通道抽象：SSH 的 JSch 通道与 telnet 的 Socket 都归到这里 */
    public interface ShellTransport {
        InputStream in();

        OutputStream out();

        /** telnet 通道返回底层 socket（用于交互式会话调整超时），SSH 通道返回 null */
        default Socket socket() {
            return null;
        }

        void close();
    }

    /**
     * Telnet 输入流：过滤 IAC 协商字节。
     * <p>VRP 会主动发 WILL ECHO / WILL SGA / DO TTYPE 等，这里一律拒绝（DONT/WONT），
     * 避免回显与子协商干扰命令回读。SB...SE 子协商整段丢弃。</p>
     * <p>实现要点：内部维护「过滤后」的缓冲队列。{@link #available()} 只报告过滤后可读的
     * 字节数——若直接透传底层 available()，会把尚未解析完的 IAC 字节计入，上层按
     * 「有数据」调 read() 时，read() 可能阻塞等待 IAC 序列的后续字节（跨 TCP 分段），
     * 触发 socket 读超时（实测 AC6005 会话建立时随机出现 "Read timed out"）。</p>
     */
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
        /** 过滤 IAC 后待读的字节队列 */
        private final java.util.ArrayDeque<Integer> buf = new java.util.ArrayDeque<>();

        // IAC 解析状态机：不完整的协商序列只挂起状态，绝不阻塞等下一个字节
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

        /**
         * 批量读必须重写为「读到即返回」的部分读语义。
         * <p>父类默认实现会阻塞攒满 len 字节才返回；交互式终端的命令回显通常只有几十到
         * 几百字节，永远攒不满 4096 的缓冲，导致 Web 终端输出线程无限阻塞、界面无回显
         * （实测 AC6005 display version 回显 468 字节滞留不吐）。这里阻塞取第一字节，
         * 再把已缓冲的字节一次性带出，不凑满。</p>
         */
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

        /** 喂一个原始字节进状态机：普通字符进缓冲，协商序列就地响应/丢弃 */
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
                        // NOP/GA 等其他命令直接忽略
                        state = ST_NORMAL;
                    }
                }
                case ST_OPT -> {
                    // 静默忽略协商，不回复 DONT/WONT。
                    // 实测 AC6005 的 telnet 服务收到客户端拒绝响应（DONT/WONT）后不再进入正常
                    // 会话（命令有回显但读不到）；完全静默（不做任何应答）反而一切正常。
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

        /** 非阻塞读原始流一个字节；无数据返回 -2（区别于流结束的 -1） */
        private int tryReadNonBlocking() throws IOException {
            if (in.available() > 0) {
                return in.read();
            }
            return -2;
        }

        /**
         * 会话建立完成后复位解析状态。部分镜像（AC6005）的协商串可能不完整或畸形，
         * 若状态卡在 IAC/SB 中间，后续普通字符会被当作协商体吞掉，表现为「命令有回显但读不到」。
         * 登录完成后协商阶段已结束，未完成的协商片段直接丢弃。
         */
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
            // 关键：只做非阻塞读取，IAC 序列不完整时状态机挂起等待，绝不在此阻塞。
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
