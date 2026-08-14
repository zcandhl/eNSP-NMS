package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 配置合规基线（实验室 MVP）：固定规则对 running/备份文本评分，非完整策略引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigComplianceService {

    private final DeviceConfigService deviceConfigService;
    private final DeviceRepository deviceRepository;

    public record RuleDef(String code, String name, String description, String severity) {}

    private static final List<RuleDef> RULES = List.of(
            new RuleDef("HAS_SYSNAME", "必须配置系统名", "配置中应包含 sysname", "high"),
            new RuleDef("NO_TELNET", "禁止 Telnet 入站", "不应启用 protocol inbound telnet（建议 SSH）", "high"),
            new RuleDef("SSH_PREFERRED", "启用 SSH/STelnet", "应启用 stelnet/ssh server 或 protocol inbound ssh", "medium"),
            new RuleDef("NO_FTP_SERVER", "关闭 FTP 服务", "不应启用 ftp server（明文风险）", "medium"),
            new RuleDef("HAS_AAA_OR_LOCAL", "存在本地/AAA 认证痕迹", "建议配置 local-user 或 aaa", "low")
    );

    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleDef r : RULES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("name", r.name());
            m.put("description", r.description());
            m.put("severity", r.severity());
            out.add(m);
        }
        return out;
    }

    /**
     * @param source live | backup（默认优先 live，失败回退最新备份）
     */
    public Map<String, Object> evaluate(Long deviceId, String source) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        String usedSource = source != null ? source.trim().toLowerCase(Locale.ROOT) : "auto";
        String content = null;
        String contentSource = null;
        String error = null;

        if ("backup".equals(usedSource)) {
            content = latestBackupContent(deviceId);
            contentSource = content != null ? "backup" : null;
        } else if ("live".equals(usedSource)) {
            try {
                content = deviceConfigService.pullLiveConfig(deviceId, "running");
                contentSource = "live";
            } catch (Exception e) {
                error = e.getMessage();
            }
        } else {
            try {
                content = deviceConfigService.pullLiveConfig(deviceId, "running");
                contentSource = "live";
            } catch (Exception e) {
                error = e.getMessage();
                content = latestBackupContent(deviceId);
                contentSource = content != null ? "backup" : null;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", deviceId);
        out.put("deviceName", device.getName());
        out.put("contentSource", contentSource);
        if (content == null || content.isBlank()) {
            out.put("score", 0);
            out.put("level", "unknown");
            out.put("passed", 0);
            out.put("failed", RULES.size());
            out.put("rules", List.of());
            out.put("message", error != null ? ("无法获取配置：" + error) : "无可用配置文本");
            return out;
        }

        String normalized = content.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> ruleResults = new ArrayList<>();
        int passed = 0;
        int weightSum = 0;
        int weightPass = 0;
        for (RuleDef rule : RULES) {
            boolean ok = evaluateRule(rule.code(), normalized, content);
            int weight = weightOf(rule.severity());
            weightSum += weight;
            if (ok) {
                passed++;
                weightPass += weight;
            }
            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("code", rule.code());
            rr.put("name", rule.name());
            rr.put("description", rule.description());
            rr.put("severity", rule.severity());
            rr.put("passed", ok);
            ruleResults.add(rr);
        }

        int score = weightSum == 0 ? 100 : (int) Math.round(100.0 * weightPass / weightSum);
        String level;
        if (score >= 90) level = "compliant";
        else if (score >= 70) level = "mostly";
        else if (score >= 40) level = "drift";
        else level = "non_compliant";

        out.put("score", score);
        out.put("level", level);
        out.put("passed", passed);
        out.put("failed", RULES.size() - passed);
        out.put("rules", ruleResults);
        out.put("message", "合规分 " + score + "（通过 " + passed + "/" + RULES.size() + "）");
        if (error != null && "backup".equals(contentSource)) {
            out.put("liveError", error);
        }
        return out;
    }

    private String latestBackupContent(Long deviceId) {
        return deviceConfigService.getConfigSummariesByDeviceId(deviceId).stream()
                .findFirst()
                .flatMap(s -> deviceConfigService.getConfigById(s.getId()))
                .map(c -> c.getContent())
                .orElse(null);
    }

    private static int weightOf(String severity) {
        return switch (severity != null ? severity : "") {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    private static boolean evaluateRule(String code, String normalized, String raw) {
        return switch (code) {
            case "HAS_SYSNAME" -> Pattern.compile("(?m)^\\s*sysname\\s+\\S+", Pattern.CASE_INSENSITIVE)
                    .matcher(raw).find();
            case "NO_TELNET" -> {
                boolean hasTelnet = normalized.contains("protocol inbound telnet");
                boolean undone = normalized.contains("undo protocol inbound telnet");
                yield !hasTelnet || undone;
            }
            case "SSH_PREFERRED" -> normalized.contains("stelnet server enable")
                    || normalized.contains("ssh server")
                    || normalized.contains("protocol inbound ssh");
            case "NO_FTP_SERVER" -> {
                boolean enabled = normalized.contains("ftp server enable")
                        || (normalized.contains("ftp server") && !normalized.contains("undo ftp server"));
                boolean undone = normalized.contains("undo ftp server");
                yield undone || !enabled;
            }
            case "HAS_AAA_OR_LOCAL" -> normalized.contains("local-user ")
                    || normalized.contains("aaa ")
                    || normalized.contains("authentication-mode");
            default -> true;
        };
    }
}
