package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceBatch;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import com.ensp.nms.service.llm.LlmClient;
import com.ensp.nms.service.llm.LlmSettingsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验收报告 AI 摘要与状态归纳。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceAiService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SYSTEM_PROMPT = """
            你是资深网络工程验收专家，负责审阅网络验收批次快照并撰写报告摘要素材。
            硬规则：
            1) 只能基于给定的批次数据作答，禁止编造未出现的数据；数据不足时明确说明缺什么。
            2) 输出严格分三段，用以下标记开头，标记独占一行：
               [执行摘要]
               [根因分析]
               [整改建议]
            3) [执行摘要]：2-4 句话概括本批次验收范围、整体通过情况与结论倾向（通过/有条件通过/不通过）。
            4) [根因分析]：针对每个 FAIL/FINDING/SKIPPED 项逐条分析最可能的原因，每条一行，
               行首引用检查项编号（如 A-02），最多 5 条；全部通过则写"未发现异常项"。
            5) [整改建议]：与根因一一对应给出可操作的整改措施，每条一行，最多 5 条；无问题则写"无需整改"。
            6) 不要输出 Markdown 语法（不用 **、#、-、1.），直接输出纯文本行。
            """;

    private static final int RAW_TRUNCATE = 700;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final LlmSettingsService settingsService;
    private final LlmClient llmClient;
    private final AcceptanceBatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, CachedSummary> cache = new ConcurrentHashMap<>();

    public Map<String, Object> summary(String batchId) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在：" + batchId));
        if (batch.getItemsJson() == null || batch.getItemsJson().isBlank()) {
            throw new IllegalArgumentException("批次尚无检查数据，无法生成摘要");
        }

        String cacheKey = batchId + "|" + batch.getStatus() + "|" + batch.getFinishedAt();
        CachedSummary hit = cache.get(batchId);
        if (hit != null && hit.key().equals(cacheKey)
                && System.currentTimeMillis() - hit.at() < CACHE_TTL_MS) {
            return hit.data();
        }

        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();
        if (!cfg.enabled()) {
            throw new IllegalStateException("AI 助手未启用：请在悬浮助手「AI 设置」中启用本地模型（Ollama）或 OpenAI 兼容 API");
        }

        String digest = buildDigest(batch);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", "以下是验收批次 " + batch.getBatchId()
                        + "（报告编号 " + batch.getNo() + "，状态 " + batch.getStatus() + "）的检查快照，请生成摘要：\n\n"
                        + digest));

        String raw;
        try {
            raw = llmClient.chat(cfg.provider(), cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    cfg.temperature(), cfg.timeoutSeconds(), messages);
        } catch (Exception e) {
            log.warn("验收批次 {} AI 摘要生成失败: {}", batchId, e.getMessage());
            throw new IllegalStateException("AI 调用失败：" + e.getMessage(), e);
        }

        Map<String, Object> out = parseSections(raw);
        out.put("batchId", batch.getBatchId());
        out.put("no", batch.getNo());
        out.put("batchStatus", batch.getStatus());
        out.put("provider", cfg.provider());
        out.put("model", cfg.model());
        out.put("generatedAt", LocalDateTime.now().format(TS));
        out.put("disclaimer", "本摘要由 AI（" + cfg.model() + "）基于批次快照生成，仅供参考，不作为验收判定依据");

        cache.put(batchId, new CachedSummary(cacheKey, System.currentTimeMillis(), out));
        return out;
    }

    public Map<String, Object> status() {
        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("provider", cfg.provider());
        m.put("model", cfg.model());
        m.put("providerLabel", "openai_compatible".equalsIgnoreCase(cfg.provider()) || "openai".equalsIgnoreCase(cfg.provider())
                ? "OpenAI 兼容 API（云端）"
                : "本地模型（Ollama）");
        return m;
    }

    private String buildDigest(AcceptanceBatch batch) {
        List<Map<String, Object>> items = fromJson(batch.getItemsJson());
        StringBuilder sb = new StringBuilder();
        sb.append("批次状态：").append(batch.getStatus());
        if (batch.getStatusMessage() != null && !batch.getStatusMessage().isBlank()) {
            sb.append("（").append(batch.getStatusMessage()).append("）");
        }
        sb.append("，共 ").append(items.size()).append(" 个检查项。\n\n");
        for (Map<String, Object> item : items) {
            sb.append("── ").append(str(item.get("code"))).append(" ").append(str(item.get("title")))
                    .append(" ｜ 判定：").append(str(item.get("status"))).append("\n");
            String msg = str(item.get("message"));
            if (!msg.isBlank()) {
                sb.append("判定说明：").append(msg).append("\n");
            }
            Object metrics = item.get("metrics");
            if (metrics != null && !String.valueOf(metrics).equals("{}")) {
                sb.append("关键指标：").append(compact(metrics)).append("\n");
            }
            Object findings = item.get("findings");
            if (findings instanceof List<?> fList && !fList.isEmpty()) {
                sb.append("问题清单：\n");
                for (Object f : fList) {
                    sb.append("  ").append(compact(f)).append("\n");
                }
            }
            String raw = str(item.get("rawOutput"));
            if (!raw.isBlank()) {
                sb.append("原始回显（截断）：\n").append(truncate(raw, RAW_TRUNCATE)).append("\n");
            }
            sb.append("\n");
        }
        return truncate(sb.toString(), 16000);
    }

    private Map<String, Object> parseSections(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        String summary = section(raw, "执行摘要");
        String causes = section(raw, "根因分析");
        String fixes = section(raw, "整改建议");
        // 模型漏掉标记时兜底：整体当执行摘要
        if (summary.isBlank() && causes.isBlank() && fixes.isBlank()) {
            summary = raw.trim();
        }
        out.put("summary", summary.trim());
        out.put("rootCauses", toLines(causes));
        out.put("suggestions", toLines(fixes));
        return out;
    }

    private static String section(String raw, String tag) {
        Pattern p = Pattern.compile("(?s)\\[" + tag + "\\]\\s*(.*?)(?=\\[(?:执行摘要|根因分析|整改建议)\\]|$)");
        Matcher m = p.matcher(raw == null ? "" : raw);
        return m.find() ? m.group(1).trim() : "";
    }

    private static List<String> toLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        for (String line : text.split("\n")) {
            String t = line.replaceFirst("^[\\d]+[.、）)]?\\s*", "").trim();
            if (t.isEmpty() || t.equals("未发现异常项") || t.equals("无需整改")) {
                if (!t.isEmpty()) {
                    lines.add(t);
                }
                continue;
            }
            lines.add(t);
        }
        return lines;
    }

    private List<Map<String, Object>> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String compact(Object o) {
        String s = String.valueOf(o);
        return s.replace("\"", "").replace("{", " ").replace("}", " ").replace(",", "，").trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "\n…（已截断）";
    }

    private record CachedSummary(String key, long at, Map<String, Object> data) { }
}
