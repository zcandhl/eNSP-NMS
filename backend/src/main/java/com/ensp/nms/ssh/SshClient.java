package com.ensp.nms.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SshClient {

    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int CHANNEL_TIMEOUT = 20000;
    private static final int COMMAND_READ_TIMEOUT_MS = 5000;
    private static final int CONFIG_READ_TIMEOUT_MS = 60000;
    private static final int CONFIG_IDLE_MS = 1500;

    public record CommandBatchResult(String summary, int successCount, int failCount) {
        public boolean ok() {
            return failCount == 0;
        }
    }

    public String executeCommand(String host, int port, String username, String password, String command) throws Exception {
        CommandBatchResult result = executeConfigCommands(host, port, username, password,
                List.of(command), "执行命令");
        return result.summary();
    }

    /**
     * 批量下发配置命令：统一会话、等待提示符、统计成败。
     * @param progressCallback 可选，参数为 (已完成命令数, 总命令数)
     */
    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel) throws Exception {
        return executeConfigCommands(host, port, username, password, rawCommands, actionLabel, null);
    }

    public CommandBatchResult executeConfigCommands(String host, int port, String username, String password,
                                                    List<String> rawCommands, String actionLabel,
                                                    java.util.function.BiConsumer<Integer, Integer> progressCallback) throws Exception {
        List<String> commands = filterConfigCommands(rawCommands);
        StringBuilder result = new StringBuilder();
        result.append("开始").append(actionLabel != null ? actionLabel : "配置").append("...\n");

        Session session = null;
        ChannelShell channel = null;
        InputStream in = null;
        OutputStream out = null;
        int successCount = 0;
        int failCount = 0;
        int total = Math.max(commands.size(), 1);

        try {
            if (progressCallback != null) {
                progressCallback.accept(0, total);
            }
            session = openSession(host, port, username, password);
            channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType("vt100");
            channel.setPty(true);
            in = channel.getInputStream();
            out = channel.getOutputStream();
            channel.connect(CHANNEL_TIMEOUT);

            Thread.sleep(800);
            drainAvailable(in);
            out.write("screen-length 0 temporary\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS);

            int done = 0;
            for (String command : commands) {
                try {
                    log.info("SSH 执行: {}", command);
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String output = readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS);
                    // save 等命令可能出现 [Y/N] 确认
                    if (isConfirmPrompt(output)) {
                        out.write("Y\n".getBytes(StandardCharsets.UTF_8));
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
            closeQuietly(in, out, channel, session);
        }
    }

    public String getHuaweiConfig(String host, int port, String username, String password, boolean startup) throws Exception {
        Session session = null;
        ChannelShell channel = null;
        InputStream in = null;
        OutputStream out = null;

        try {
            session = openSession(host, port, username, password);
            channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType("dumb");
            channel.setPty(true);
            in = channel.getInputStream();
            out = channel.getOutputStream();
            channel.connect(CHANNEL_TIMEOUT);

            Thread.sleep(800);
            drainAvailable(in);
            out.write("screen-length 0 temporary\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            readUntilPrompt(in, COMMAND_READ_TIMEOUT_MS);

            String command = startup ? "display saved-configuration" : "display current-configuration";
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String result = readUntilPromptOrIdle(in, CONFIG_READ_TIMEOUT_MS, CONFIG_IDLE_MS);
            int startIdx = indexOfIgnoreCase(result, command);
            if (startIdx < 0) {
                startIdx = indexOfIgnoreCase(result, startup ? "saved-configuration" : "current-configuration");
            }
            if (startIdx < 0) {
                startIdx = 0;
            }
            return result.substring(startIdx).trim();
        } finally {
            closeQuietly(in, out, channel, session);
        }
    }

    public boolean testConnection(String host, int port, String username, String password) {
        try {
            executeCommand(host, port, username, password, "display version");
            return true;
        } catch (Exception e) {
            log.error("SSH 连接失败: {}:{}", host, port, e);
            return false;
        }
    }

    private Session openSession(String host, int port, String username, String password) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port > 0 ? port : 22);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password");
        session.setConfig("kex", "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521");
        session.setConfig("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
        session.setConfig("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc,aes192-cbc,aes256-cbc");
        session.setConfig("mac.s2c", "hmac-md5,hmac-sha1,hmac-sha2-256");
        session.setConfig("mac.c2s", "hmac-md5,hmac-sha1,hmac-sha2-256");
        session.connect(CONNECTION_TIMEOUT);
        return session;
    }

    /**
     * 将 display 备份文本整理为可交互下发的命令序列：
     * 去掉注释/回显噪声，补 system-view，去掉文末 return/save（由本方法统一追加）。
     */
    public static List<String> prepareRestoreCommands(String content) {
        List<String> raw = new ArrayList<>();
        if (content != null) {
            for (String line : content.split("\n", -1)) {
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
            // 备份回显里的 display 命令行
            if (lower.startsWith("display current-configuration")
                    || lower.startsWith("display saved-configuration")
                    || lower.startsWith("display this")
                    || lower.equals("display")
                    || lower.startsWith("display ")) {
                continue;
            }
            // 跳过明显非配置头
            if (lower.startsWith("using ") || lower.startsWith("now time")
                    || lower.contains("configuration in this view")) {
                continue;
            }
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

    private static void closeQuietly(InputStream in, OutputStream out, ChannelShell channel, Session session) {
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
}
