package com.ensp.nms.service.llm;

import com.ensp.nms.service.aiops.AiopsPlaybookService;
import com.ensp.nms.service.aiops.OpsAssistantService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAssistantService {

    private static final Pattern TOOLS_JSON = Pattern.compile(
            "(?s)\\{\\s*\"tools\"\\s*:\\s*\\[.*?\\]\\s*\\}\\s*$");
    private static final Pattern FENCE_JSON = Pattern.compile(
            "(?s)```(?:json)?\\s*(\\{.*?\"tools\"\\s*:\\s*\\[.*?\\].*?\\})\\s*```");
    private static final Pattern INLINE_TOOLS = Pattern.compile(
            "(?s)\\{\\s*\"tools\"\\s*:\\s*\\[.*?\\]\\s*\\}");

    private static final String SYSTEM_BASE = """
            你是 eNSP NMS 运维作业助手。先服从「识别到的意图」，再基于证据作答。
            硬规则：
            1) 结论必须基于「意图」「网管上下文」和「工具证据」；证据不足就说清楚缺什么（如需选定设备），禁止套话。
            2) 正文最多 5 行：意图确认（半句）→ 结论 → 1～3 条依据 → 下一步。
            3) 禁止声称已改配/已下发/已回滚。写操作只能作为需确认工具。
            4) 输出两段：A) 中文结论正文  B) 单独一行 JSON：
               {"tools":[{"name":"...","args":{...},"label":"...","needConfirm":true/false}]}
               无工具也输出 {"tools":[]}。
            5) 白名单工具：inspect, refresh_device, refresh_offline, ack_noise, dispose_incident,
               backup, restore_latest, pull_live_config, ack_alarm,
               get_device_summary, get_topo_neighbors, get_perf_snapshot,
               get_config_diff_summary, list_active_alarms_for_device, run_path_hint,
               navigate_hint, search_devices, get_network_overview,
               explain_cli_output, suggest_config_commands,
               get_alarm_detail, list_config_backups, get_backup_schedule_status,
               ping_check, probe_device, traceroute_hint,
               highlight_topology_nodes, open_workbench_event,
               get_interface_brief, run_show_command, get_config_compliance_score
            6) 焦点可选：无设备也可回答（通用步骤/全网态势）；有设备时优先用设备证据，勿编造未选定设备的状态。
            每次最多 3 个工具。只读证据已自动跑过则不必重复。
            """;

    private final AtomicLong parseAttempts = new AtomicLong();
    private final AtomicLong parseSuccesses = new AtomicLong();

    private final LlmSettingsService settingsService;
    private final LlmClient llmClient;
    private final NmsContextBuilder contextBuilder;
    private final OpsAssistantService opsAssistantService;
    private final AiopsPlaybookService playbookService;
    private final LlmToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public Map<String, Object> chat(String question, Long deviceId, Long alarmId, String pagePath,
                                    List<Map<String, String>> history) {
        String q = question == null ? "" : question.trim();
        Map<String, Object> nmsCtx = contextBuilder.build(deviceId, alarmId, pagePath);
        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();

        if (!cfg.enabled()) {
            return withMeta(opsAssistantService.ask(q, deviceId, alarmId), "rules", true,
                    "LLM 未启用，已使用规则助手。可在悬浮助手设置中启用 Ollama 或 OpenAI 兼容 API。",
                    nmsCtx, deviceId, alarmId, q);
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", buildSystemPrompt(cfg, nmsCtx)));
            if (history != null) {
                for (Map<String, String> h : history) {
                    if (h == null) continue;
                    String role = h.getOrDefault("role", "user");
                    String content = h.getOrDefault("content", "");
                    if (content.isBlank()) continue;
                    if (!"user".equals(role) && !"assistant".equals(role)) {
                        role = "user";
                    }
                    messages.add(Map.of("role", role, "content", content));
                }
            }
            messages.add(Map.of("role", "user", "content",
                    q.isBlank() ? "请根据当前网管上下文给出运维摘要，并给出可在当前页确认执行的工具。" : q));

            String raw = llmClient.chat(
                    cfg.provider(), cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    cfg.temperature(), cfg.timeoutSeconds(), messages);

            ParsedAnswer parsed = parseAnswerAndTools(raw);
            List<Map<String, Object>> tools = sanitizeTools(parsed.tools(), nmsCtx, deviceId, alarmId);
            String toolsSource = "llm";
            if (tools.isEmpty()) {
                tools = scenarioTemplateTools(nmsCtx, deviceId, alarmId);
                toolsSource = tools.isEmpty() ? "none" : "scenario";
            }
            if (tools.isEmpty()) {
                tools = sanitizeTools(heuristicTools(q, nmsCtx, deviceId, alarmId), nmsCtx, deviceId, alarmId);
                toolsSource = tools.isEmpty() ? toolsSource : "heuristic";
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("question", q);
            result.put("title", "LLM 运维建议");
            result.put("answer", parsed.answer());
            result.put("links", List.of());
            result.put("suggestedActions", toActionButtons(tools));
            result.put("proposedTools", tools);
            result.put("tools", tools);
            result.put("actions", toActionButtons(tools));
            result.put("toolsSource", toolsSource);
            result.put("toolsParseStats", Map.of(
                    "attempts", parseAttempts.get(),
                    "successes", parseSuccesses.get(),
                    "lastParsedOk", parsed.parsedOk()
            ));
            result.put("evidence", List.of(
                    Map.of("type", "context", "summary",
                            "已注入健康分/收敛事件/根因/焦点对象" + (pagePath != null ? "，页面=" + pagePath : ""))
            ));
            result.put("context", nmsCtx);
            result.put("autoChange", false);
            result.put("disclaimer", "回答由大模型生成。工具需你确认后才会执行；不会自动改配。");
            result.put("provider", cfg.provider());
            result.put("fallback", false);
            result.put("model", cfg.model());
            return result;
        } catch (Exception e) {
            log.warn("LLM chat failed, fallback to rules: {}", e.getMessage());
            Map<String, Object> fallback = opsAssistantService.ask(q, deviceId, alarmId);
            String note = "LLM 调用失败（" + e.getMessage() + "），已回退规则助手。";
            return withMeta(fallback, "rules", true, note, nmsCtx, deviceId, alarmId, q);
        }
    }

    /**
     * 意图优先作业闭环：识别意图 → 按意图采证据 → 结论与可确认动作。
     */
    public Map<String, Object> assist(String question, Long deviceId, Long alarmId, String pagePath,
                                      List<Map<String, String>> history) {
        String q = question == null ? "" : question.trim();
        Map<String, Object> nmsCtx = contextBuilder.build(deviceId, alarmId, pagePath);
        Long ctxDevice = coalesceDeviceId(nmsCtx, deviceId);
        Long ctxAlarm = coalesceAlarmId(nmsCtx, alarmId);
        AssistIntent intent = classifyIntent(q, nmsCtx, ctxDevice, ctxAlarm);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", q);
        out.put("mode", "assist");
        out.put("intent", intent.toMap());
        out.put("context", nmsCtx);
        out.put("autoChange", false);
        out.put("disclaimer", "意图优先；可选定设备加深诊断，不选也可提问。");

        List<Map<String, Object>> readPlan = buildEvidencePlan(intent, q, nmsCtx, ctxDevice, ctxAlarm);
        List<Map<String, Object>> toolRuns = new ArrayList<>();
        StringBuilder evidence = new StringBuilder();
        for (Map<String, Object> t : readPlan) {
            String name = String.valueOf(t.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = t.get("args") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            args.put("source", "assist-auto");
            if (ctxDevice != null) args.putIfAbsent("deviceId", ctxDevice);
            if (ctxAlarm != null) args.putIfAbsent("alarmId", ctxAlarm);
            try {
                Map<String, Object> run = toolExecutor.execute(name, args, true);
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("tool", name);
                brief.put("label", t.getOrDefault("label", defaultLabel(name)));
                brief.put("ok", run.get("ok"));
                brief.put("message", run.getOrDefault("message", run.get("error")));
                if (run.get("detail") instanceof Map<?, ?> dm) {
                    brief.put("detail", dm);
                }
                toolRuns.add(brief);
                evidence.append("- ").append(name).append(": ")
                        .append(run.getOrDefault("message", run.get("error"))).append('\n');
            } catch (Exception e) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("tool", name);
                brief.put("label", defaultLabel(name));
                brief.put("ok", false);
                brief.put("message", e.getMessage());
                toolRuns.add(brief);
                evidence.append("- ").append(name).append(" 失败: ").append(e.getMessage()).append('\n');
            }
        }

        List<Map<String, Object>> writeTools = intent.allowsPlaybookWrites()
                ? playbookWriteTools(nmsCtx, ctxDevice, ctxAlarm) : List.of();
        out.put("toolRuns", toolRuns);

        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();
        if (!cfg.enabled()) {
            out.putAll(rulesVerdictFromEvidence(intent, q, nmsCtx, toolRuns, writeTools, ctxDevice, ctxAlarm));
            out.put("provider", "rules");
            out.put("fallback", true);
            return out;
        }

        try {
            Map<String, Object> ctxWithEvidence = new LinkedHashMap<>(nmsCtx);
            ctxWithEvidence.put("intent", intent.toMap());
            ctxWithEvidence.put("toolRuns", toolRuns.stream().limit(5).toList());
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", buildSystemPrompt(cfg, ctxWithEvidence)));
            messages.add(Map.of("role", "user", "content",
                    "【意图】" + intent.code() + " · " + intent.label()
                            + "\n【焦点】" + (ctxDevice != null ? ("设备#" + ctxDevice) : "未选定设备")
                            + (ctxAlarm != null ? (" / 告警#" + ctxAlarm) : "")
                            + "\n" + (q.isBlank() ? "按当前意图处理" : q)
                            + "\n\n【已采集证据】\n"
                            + (evidence.isEmpty() ? "（无设备焦点时可为通用建议）" : evidence)
                            + "\n无焦点时给通用可执行建议，并可选提示用户稍后选定设备加深；有焦点时必须结合证据。"
                            + "给出最多 3 个工具。"));

            String raw = llmClient.chat(
                    cfg.provider(), cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    cfg.temperature(), Math.min(90, cfg.timeoutSeconds()), messages);
            ParsedAnswer parsed = parseAnswerAndTools(raw);
            List<Map<String, Object>> tools = new ArrayList<>(sanitizeTools(parsed.tools(), nmsCtx, ctxDevice, ctxAlarm));
            for (Map<String, Object> w : writeTools) {
                String wn = String.valueOf(w.get("name"));
                if (tools.stream().noneMatch(t -> wn.equals(String.valueOf(t.get("name"))))) {
                    tools.add(w);
                }
            }
            if (tools.size() > 5) {
                tools = new ArrayList<>(tools.subList(0, 5));
            }

            String answer = parsed.answer() == null ? "" : parsed.answer().trim();
            if (answer.isBlank()) {
                answer = rulesVerdictText(intent, toolRuns);
            }
            tools = mergeTools(tools, nextStepTools(nmsCtx, ctxDevice, ctxAlarm), 5);
            List<Map<String, Object>> actions = toActionButtons(tools);
            out.put("title", "作业结论");
            out.put("answer", answer);
            out.put("proposedTools", tools);
            out.put("suggestedActions", actions);
            // 兼容前端旧字段名
            out.put("tools", tools);
            out.put("actions", actions);
            out.put("toolsSource", "assist-intent");
            out.put("provider", cfg.provider());
            out.put("model", cfg.model());
            out.put("fallback", false);
            out.put("evidence", List.of(
                    Map.of("type", "intent", "summary", intent.label()),
                    Map.of("type", "toolRuns", "summary", "已按意图执行 " + toolRuns.size() + " 项只读采集")
            ));
            return out;
        } catch (Exception e) {
            log.warn("assist LLM failed, rules verdict: {}", e.getMessage());
            out.putAll(rulesVerdictFromEvidence(intent, q, nmsCtx, toolRuns, writeTools, ctxDevice, ctxAlarm));
            out.put("provider", "rules");
            out.put("fallback", true);
            String note = "LLM 失败（" + e.getMessage() + "），已用证据规则结论。";
            out.put("answer", note + "\n\n" + out.getOrDefault("answer", ""));
            return out;
        }
    }

    /** 意图分类：决定后续证据与是否必须有设备/告警焦点。 */
    private AssistIntent classifyIntent(String q, Map<String, Object> nmsCtx, Long deviceId, Long alarmId) {
        String blob = (q == null ? "" : q).toLowerCase(Locale.ROOT);
        String scenario = nmsCtx.get("scenario") != null ? String.valueOf(nmsCtx.get("scenario")) : "";

        if (blob.contains("配置命令") || blob.contains("命令建议") || blob.contains("怎么配")
                || blob.contains("如何配置") || blob.contains("cli 建议") || blob.contains("cli建议")
                || ((blob.contains("vlan") || blob.contains("ospf") || blob.contains("acl"))
                && (blob.contains("配") || blob.contains("命令") || blob.contains("cli")))) {
            return AssistIntent.CONFIG_COMMANDS;
        }
        if (blob.contains("配置差异") || blob.contains("备份") || blob.contains("回滚")
                || blob.contains("running") || "CONFIG".equals(scenario)
                || (blob.contains("配置") && !blob.contains("配置命令"))) {
            return AssistIntent.CONFIG_RISK;
        }
        if (blob.contains("性能") || blob.contains("cpu") || blob.contains("内存") || blob.contains("温度")
                || "PERFORMANCE".equals(scenario)) {
            return AssistIntent.PERFORMANCE;
        }
        if (blob.contains("路径") || blob.contains("path") || (blob.contains("从") && blob.contains("到"))) {
            return AssistIntent.PATH;
        }
        if (blob.contains("邻居") || blob.contains("拓扑") || blob.contains("链路")) {
            return AssistIntent.TOPOLOGY;
        }
        if (blob.contains("搜索设备") || blob.contains("查找设备") || blob.contains("哪台设备")
                || blob.startsWith("搜索") || blob.startsWith("找设备")) {
            return AssistIntent.SEARCH_DEVICE;
        }
        if (blob.contains("告警") || blob.contains("事件") || blob.contains("处置") || blob.contains("噪音")
                || alarmId != null) {
            return AssistIntent.ALARM_HANDLE;
        }
        if (blob.contains("态势") || blob.contains("健康") || blob.contains("全网") || blob.contains("巡检")
                || blob.contains("概览") || blob.contains("优先风险")) {
            return AssistIntent.NETWORK_OVERVIEW;
        }
        if (deviceId != null || alarmId != null) {
            return AssistIntent.DEVICE_DIAGNOSE;
        }
        if (blob.isBlank() || blob.contains("诊断") || blob.contains("分析") || blob.contains("怎么了")) {
            // 无焦点的泛化诊断 → 全网；有焦点上面已覆盖
            return AssistIntent.NETWORK_OVERVIEW;
        }
        // 未识别的闲聊/其它：仍给全网态势，但标记为 GENERAL
        return AssistIntent.GENERAL;
    }

    private static String extractSearchKeyword(String q) {
        if (q == null || q.isBlank()) return "";
        String s = q.replaceAll("(?i)(配置命令|命令建议|怎么配|如何配置|配置|备份|回滚|性能|cpu|内存|拓扑|邻居|路径|诊断|分析|请|帮我|一下)", " ")
                .replaceAll("[\\p{Punct}&&[^._\\-]]+", " ")
                .trim();
        if (s.isBlank()) return "";
        String[] parts = s.split("\\s+");
        for (String p : parts) {
            if (p.length() >= 2) return p;
        }
        return s.length() <= 32 ? s : s.substring(0, 32);
    }

    private List<Map<String, Object>> buildEvidencePlan(AssistIntent intent, String q,
                                                        Map<String, Object> nmsCtx,
                                                        Long ctxDevice, Long ctxAlarm) {
        List<Map<String, Object>> plan = new ArrayList<>();
        String blob = (q == null ? "" : q).toLowerCase(Locale.ROOT);
        boolean hasDevice = ctxDevice != null;

        switch (intent) {
            case NETWORK_OVERVIEW, GENERAL -> {
                plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                plan.add(tool("inspect", Map.of(), "重算关联与根因", false));
            }
            case SEARCH_DEVICE -> {
                String kw = extractSearchKeyword(q);
                if (kw.length() < 2) kw = "R";
                plan.add(tool("search_devices", Map.of("keyword", kw), "搜索设备", false));
            }
            case CONFIG_COMMANDS -> {
                // 无设备也可给通用华为 eNSP 命令模板
                Map<String, Object> args = new LinkedHashMap<>();
                if (hasDevice) args.put("deviceId", ctxDevice);
                args.put("intent", blob.contains("vlan") ? "vlan"
                        : (blob.contains("ospf") ? "ospf"
                        : (blob.contains("acl") ? "acl" : "general")));
                plan.add(tool("suggest_config_commands", args, hasDevice ? "配置命令建议" : "通用配置命令", false));
                if (hasDevice) {
                    plan.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
                }
            }
            case CONFIG_RISK -> {
                if (hasDevice) {
                    plan.add(tool("get_config_compliance_score", Map.of("deviceId", ctxDevice), "配置合规分", false));
                    plan.add(tool("list_config_backups", Map.of("deviceId", ctxDevice), "备份列表", false));
                    plan.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
                } else {
                    // 无设备：给通用命令 + 引导去配置页，不拦截提问
                    plan.add(tool("suggest_config_commands", Map.of("intent", "diagnose"), "通用核查命令", false));
                    plan.add(tool("navigate_hint", Map.of("target", "配置"), "打开配置管理", false));
                }
            }
            case PERFORMANCE -> {
                if (hasDevice) {
                    plan.add(tool("get_perf_snapshot", Map.of("deviceId", ctxDevice), "性能快照", false));
                    plan.add(tool("get_interface_brief", Map.of("deviceId", ctxDevice), "接口摘要", false));
                    plan.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
                } else {
                    plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                    plan.add(tool("navigate_hint", Map.of("target", "性能"), "打开性能页", false));
                }
            }
            case TOPOLOGY -> {
                if (hasDevice) {
                    plan.add(tool("get_topo_neighbors", Map.of("deviceId", ctxDevice), "拓扑邻居", false));
                    plan.add(tool("get_interface_brief", Map.of("deviceId", ctxDevice), "接口摘要", false));
                    plan.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
                } else {
                    plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                    plan.add(tool("navigate_hint", Map.of("target", "拓扑"), "打开拓扑页", false));
                }
            }
            case PATH -> {
                if (hasDevice) {
                    plan.add(tool("run_path_hint", Map.of("deviceId", ctxDevice), "路径提示", false));
                    plan.add(tool("get_topo_neighbors", Map.of("deviceId", ctxDevice), "拓扑邻居", false));
                } else {
                    plan.add(tool("navigate_hint", Map.of("target", "拓扑"), "打开拓扑选端点", false));
                    plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                }
            }
            case ALARM_HANDLE, DEVICE_DIAGNOSE -> {
                if (hasDevice) {
                    plan.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
                    plan.add(tool("list_active_alarms_for_device", Map.of("deviceId", ctxDevice), "活动告警", false));
                    plan.add(tool("ping_check", Map.of("deviceId", ctxDevice), "连通性探测", false));
                } else if (ctxAlarm != null) {
                    plan.add(tool("get_alarm_detail", Map.of("alarmId", ctxAlarm), "告警详情", false));
                    plan.add(tool("inspect", Map.of(), "重算关联与根因", false));
                    plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                } else {
                    // 无焦点也能问告警/诊断：走全网
                    plan.add(tool("get_network_overview", Map.of(), "网络态势", false));
                    plan.add(tool("inspect", Map.of(), "重算关联与根因", false));
                }
            }
        }

        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> t : plan) {
            uniq.putIfAbsent(String.valueOf(t.get("name")), t);
        }
        return new ArrayList<>(uniq.values()).stream().limit(3).toList();
    }

    private List<Map<String, Object>> playbookWriteTools(Map<String, Object> nmsCtx, Long deviceId, Long alarmId) {
        try {
            String scenario = nmsCtx.get("scenario") != null ? String.valueOf(nmsCtx.get("scenario")) : "GENERIC";
            boolean closed = Boolean.TRUE.equals(nmsCtx.get("focusClosed"));
            List<Map<String, Object>> rec = playbookService.buildRecommendedTools(scenario, deviceId, alarmId, closed);
            List<Map<String, Object>> writes = new ArrayList<>();
            for (Map<String, Object> t : rec) {
                String name = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT);
                if (!LlmToolExecutor.READ_ONLY.contains(name)) {
                    writes.add(t);
                }
                if (writes.size() >= 3) break;
            }
            return sanitizeTools(writes, nmsCtx, deviceId, alarmId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> rulesVerdictFromEvidence(AssistIntent intent, String q, Map<String, Object> nmsCtx,
                                                         List<Map<String, Object>> toolRuns,
                                                         List<Map<String, Object>> writeTools,
                                                         Long deviceId, Long alarmId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "证据结论");
        m.put("answer", rulesVerdictText(intent, toolRuns));
        List<Map<String, Object>> tools = writeTools == null ? new ArrayList<>() : new ArrayList<>(writeTools);
        if (tools.isEmpty()) {
            tools = new ArrayList<>(sanitizeTools(heuristicTools(q, nmsCtx, deviceId, alarmId), nmsCtx, deviceId, alarmId)
                    .stream()
                    .filter(t -> !LlmToolExecutor.READ_ONLY.contains(String.valueOf(t.get("name")).toLowerCase(Locale.ROOT))
                            || "inspect".equals(String.valueOf(t.get("name")))
                            || "suggest_config_commands".equals(String.valueOf(t.get("name")))
                            || "navigate_hint".equals(String.valueOf(t.get("name")))
                            || "search_devices".equals(String.valueOf(t.get("name")))
                            || "ping_check".equals(String.valueOf(t.get("name")))
                            || "get_device_summary".equals(String.valueOf(t.get("name")))
                            || "dispose_incident".equals(String.valueOf(t.get("name")))
                            || "open_workbench_event".equals(String.valueOf(t.get("name"))))
                    .limit(4)
                    .toList());
        }
        tools = mergeTools(tools, nextStepTools(nmsCtx, deviceId, alarmId), 5);
        List<Map<String, Object>> actions = toActionButtons(tools);
        m.put("proposedTools", tools);
        m.put("suggestedActions", actions);
        m.put("tools", tools);
        m.put("actions", actions);
        m.put("toolsSource", "rules-intent");
        return m;
    }

    /** 按焦点给出可点击的下一步（写操作 needConfirm=true） */
    private List<Map<String, Object>> nextStepTools(Map<String, Object> nmsCtx, Long deviceId, Long alarmId) {
        List<Map<String, Object>> tools = new ArrayList<>();
        boolean closed = Boolean.TRUE.equals(nmsCtx != null ? nmsCtx.get("focusClosed") : null);
        if (deviceId != null) {
            tools.add(tool("ping_check", Map.of("deviceId", deviceId), "连通性检测", false));
            tools.add(tool("get_device_summary", Map.of("deviceId", deviceId), "设备摘要", false));
            tools.add(tool("get_interface_brief", Map.of("deviceId", deviceId), "接口状态", false));
            if (!closed) {
                tools.add(tool("backup", Map.of("deviceId", deviceId), "备份配置", true));
                tools.add(tool("refresh_device", Map.of("deviceId", deviceId), "刷新状态", true));
            }
        }
        if (alarmId != null) {
            tools.add(tool("get_alarm_detail", Map.of("alarmId", alarmId), "告警详情", false));
            if (!closed) {
                tools.add(tool("dispose_incident", Map.of("alarmId", alarmId), "处置事件", true));
                tools.add(tool("ack_alarm", Map.of("alarmId", alarmId), "确认告警", true));
            }
            tools.add(tool("open_workbench_event", Map.of("alarmId", alarmId), "打开作业台", false));
        }
        if (deviceId == null && alarmId == null) {
            tools.add(tool("get_network_overview", Map.of(), "网络态势", false));
            tools.add(tool("inspect", Map.of(), "重算关联与根因", false));
            tools.add(tool("navigate_hint", Map.of("target", "告警"), "打开告警页", false));
        }
        return sanitizeTools(tools, nmsCtx, deviceId, alarmId).stream().limit(5).toList();
    }

    private static List<Map<String, Object>> mergeTools(List<Map<String, Object>> primary,
                                                        List<Map<String, Object>> secondary,
                                                        int limit) {
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        if (primary != null) {
            for (Map<String, Object> t : primary) {
                if (t == null || t.get("name") == null) continue;
                uniq.putIfAbsent(String.valueOf(t.get("name")), t);
            }
        }
        if (secondary != null) {
            for (Map<String, Object> t : secondary) {
                if (t == null || t.get("name") == null) continue;
                uniq.putIfAbsent(String.valueOf(t.get("name")), t);
                if (uniq.size() >= limit) break;
            }
        }
        return new ArrayList<>(uniq.values()).stream().limit(limit).toList();
    }

    private static String rulesVerdictText(AssistIntent intent, List<Map<String, Object>> toolRuns) {
        StringBuilder sb = new StringBuilder();
        sb.append("意图：「").append(intent.label()).append("」。\n");
        if (toolRuns == null || toolRuns.isEmpty()) {
            sb.append("暂无工具证据；仍可按通用运维建议继续，或选定设备后加深诊断。");
            return sb.toString();
        }
        sb.append("基于已采集证据：\n");
        int i = 0;
        for (Map<String, Object> tr : toolRuns) {
            if (i++ >= 4) break;
            sb.append(i).append(") ")
                    .append(tr.getOrDefault("label", tr.get("tool")))
                    .append(" — ")
                    .append(tr.getOrDefault("message", "-"))
                    .append('\n');
        }
        if (intent == AssistIntent.CONFIG_COMMANDS) {
            sb.append("以上为命令建议（未选定设备时为通用模板）；选定设备后可结合该设备状态细化。");
        } else if (intent.prefersDevice()) {
            sb.append("若需设备级差异/备份/性能数值，可选定设备后再问一次。");
        } else {
            sb.append("请从下方动作中选择下一步（写操作需确认）。");
        }
        return sb.toString().trim();
    }

    /** 助手意图：决定证据采集；设备焦点为可选加深，不是提问门槛。 */
    private enum AssistIntent {
        NETWORK_OVERVIEW("NETWORK_OVERVIEW", "全网态势", false, false),
        DEVICE_DIAGNOSE("DEVICE_DIAGNOSE", "设备诊断", true, true),
        ALARM_HANDLE("ALARM_HANDLE", "告警处置", false, true),
        CONFIG_COMMANDS("CONFIG_COMMANDS", "配置命令建议", true, false),
        CONFIG_RISK("CONFIG_RISK", "配置风险/备份", true, true),
        PERFORMANCE("PERFORMANCE", "性能诊断", true, false),
        TOPOLOGY("TOPOLOGY", "拓扑邻居", true, false),
        PATH("PATH", "路径分析", true, false),
        SEARCH_DEVICE("SEARCH_DEVICE", "搜索设备", false, false),
        GENERAL("GENERAL", "综合询问", false, false);

        private final String code;
        private final String label;
        /** 有设备时证据更准，但不阻止无设备提问 */
        private final boolean prefersDevice;
        private final boolean allowsPlaybookWrites;

        AssistIntent(String code, String label, boolean prefersDevice, boolean allowsPlaybookWrites) {
            this.code = code;
            this.label = label;
            this.prefersDevice = prefersDevice;
            this.allowsPlaybookWrites = allowsPlaybookWrites;
        }

        String code() { return code; }
        String label() { return label; }
        boolean prefersDevice() { return prefersDevice; }
        boolean allowsPlaybookWrites() { return allowsPlaybookWrites; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("label", label);
            m.put("prefersDevice", prefersDevice);
            m.put("needsDevice", false); // 兼容旧前端：不再强制
            m.put("optionalFocus", true);
            return m;
        }
    }

    public Map<String, Object> executeTool(String name, Map<String, Object> args, boolean confirmed) {
        return toolExecutor.execute(name, args, confirmed);
    }

    /**
     * 无人值守专用规划：只返回 SAFE 工具列表；失败时 ok=false 由调用方回退规则。
     */
    public Map<String, Object> planForUnattended(Long alarmId, Long deviceId, int maxSteps) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarmId);
        out.put("deviceId", deviceId);
        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();
        if (!cfg.enabled()) {
            out.put("ok", false);
            out.put("source", "disabled");
            out.put("error", "LLM 未启用");
            out.put("tools", List.of());
            return out;
        }
        Map<String, Object> nmsCtx = contextBuilder.build(deviceId, alarmId, "/aiops/automation");
        int limit = Math.max(1, maxSteps);
        String system = """
                你是 eNSP NMS 无人值守规划器。
                你必须只输出一行合法 JSON，禁止 markdown 围栏，禁止解释性散文，禁止 <think> 标签。
                格式严格为：
                {"reason":"一句话理由","tools":[{"name":"工具名","args":{"alarmId":%s,"deviceId":%s},"label":"文案"}]}
                硬性规则：
                1) 仅允许：inspect, pull_live_config, refresh_device, refresh_offline, ack_noise, dispose_incident, ack_alarm,
                   get_device_summary, get_topo_neighbors, get_perf_snapshot, get_config_diff_summary,
                   list_active_alarms_for_device, run_path_hint
                2) 禁止：backup, restore_latest 及任何改配工具
                3) tools 至少 1 个、最多 %d 个，按执行顺序
                4) 阅知类提示优先 ack_alarm；离线优先 refresh_device；常规故障 dispose_incident 或 ack_noise
                5) 不要声称已恢复
                若上下文含 recommendedTools，优先选用其中的 name。
                """.formatted(
                alarmId != null ? alarmId : "null",
                deviceId != null ? deviceId : "null",
                limit);
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content",
                    system + "\n【上下文】\n" + objectMapper.writeValueAsString(nmsCtx)));
            messages.add(Map.of("role", "user", "content",
                    "输出一行 JSON 计划，不要其它文字。示例："
                            + "{\"reason\":\"离线先刷新\",\"tools\":[{\"name\":\"refresh_device\",\"args\":{\"deviceId\":1},\"label\":\"刷新设备\"}]}"));
            String raw = llmClient.chat(
                    cfg.provider(), cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    Math.min(0.2, cfg.temperature()), Math.min(60, cfg.timeoutSeconds()), messages);
            raw = stripThinkBlocks(raw);
            ParsedAnswer parsed = parseAnswerAndTools(raw);
            List<Map<String, Object>> tools = filterUnattendedSafe(
                    sanitizeTools(parsed.tools(), nmsCtx, deviceId, alarmId), limit);
            if (tools.isEmpty()) {
                // 本地模型常只返回散文：用场景推荐工具对齐，仍记为 LLM 路径（带对齐标记）
                tools = filterUnattendedSafe(scenarioTemplateTools(nmsCtx, deviceId, alarmId), limit);
                if (!tools.isEmpty()) {
                    out.put("ok", true);
                    out.put("source", "llm");
                    out.put("toolsAligned", true);
                    String reason = parsed.answer() != null && !parsed.answer().isBlank()
                            ? parsed.answer().trim()
                            : "LLM 未输出合法 tools，已对齐场景推荐工具";
                    out.put("reason", truncateReason(reason));
                    out.put("tools", tools);
                    return out;
                }
                out.put("ok", false);
                out.put("source", "llm_empty");
                out.put("error", "LLM 未给出可用工具");
                out.put("reason", parsed.answer());
                out.put("tools", List.of());
                return out;
            }
            out.put("ok", true);
            out.put("source", "llm");
            out.put("toolsAligned", false);
            String reason = "LLM 规划";
            if (parsed.answer() != null && !parsed.answer().isBlank()) {
                reason = parsed.answer().trim();
            }
            try {
                String jsonPart = extractJsonObject(raw);
                if (jsonPart != null) {
                    JsonNode root = objectMapper.readTree(jsonPart);
                    if (root.has("reason") && !root.get("reason").asText("").isBlank()) {
                        reason = root.get("reason").asText();
                    }
                }
            } catch (Exception ignored) { /* ignore */ }
            out.put("reason", truncateReason(reason));
            out.put("tools", tools);
            return out;
        } catch (Exception e) {
            log.warn("planForUnattended failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("source", "llm_error");
            out.put("error", e.getMessage());
            out.put("tools", List.of());
            return out;
        }
    }

    private static String stripThinkBlocks(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?is)<think>[\\s\\S]*?</think>", "")
                .replaceAll("(?is)<thinking>[\\s\\S]*?</thinking>", "")
                .trim();
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return null;
    }

    private static String truncateReason(String s) {
        if (s == null) return null;
        String t = s.replace('\n', ' ').trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    private static final Set<String> UNATTENDED_SAFE = Set.of(
            "inspect", "pull_live_config", "refresh_device", "refresh_offline",
            "ack_noise", "dispose_incident", "ack_alarm",
            "get_device_summary", "get_topo_neighbors", "get_perf_snapshot",
            "get_config_diff_summary", "list_active_alarms_for_device", "run_path_hint",
            "navigate_hint", "search_devices", "get_network_overview",
            "explain_cli_output", "suggest_config_commands",
            "get_alarm_detail", "list_config_backups", "get_backup_schedule_status",
            "ping_check", "probe_device", "traceroute_hint",
            "get_interface_brief", "run_show_command", "get_config_compliance_score"
    );

    private List<Map<String, Object>> filterUnattendedSafe(List<Map<String, Object>> tools, int limit) {
        if (tools == null || tools.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            if (t == null) continue;
            String n = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT);
            if (!UNATTENDED_SAFE.contains(n)) continue;
            out.add(t);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public Map<String, Object> testConnection() {
        LlmSettingsService.ResolvedSettings cfg = settingsService.resolve();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", cfg.provider());
        result.put("model", cfg.model());
        result.put("baseUrl", cfg.baseUrl());
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "user", "content", "请只回复：ok")
            );
            String answer = llmClient.chat(
                    cfg.provider(), cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    0.1, Math.min(30, cfg.timeoutSeconds()), messages);
            result.put("ok", true);
            result.put("reply", answer != null && answer.length() > 80 ? answer.substring(0, 80) : answer);
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private String buildSystemPrompt(LlmSettingsService.ResolvedSettings cfg, Map<String, Object> nmsCtx) {
        StringBuilder sb = new StringBuilder(SYSTEM_BASE);
        if (cfg.systemPromptExtra() != null && !cfg.systemPromptExtra().isBlank()) {
            sb.append("\n额外说明：\n").append(cfg.systemPromptExtra().trim()).append("\n");
        }
        try {
            sb.append("\n【网管上下文 JSON】\n");
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(nmsCtx));
        } catch (Exception e) {
            sb.append("\n【网管上下文】").append(nmsCtx);
        }
        return sb.toString();
    }

    private Map<String, Object> withMeta(Map<String, Object> base, String provider, boolean fallback, String note,
                                         Map<String, Object> nmsCtx, Long deviceId, Long alarmId, String q) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.put("provider", provider);
        result.put("fallback", fallback);
        result.put("autoChange", false);
        result.put("links", List.of());
        if (note != null && !note.isBlank()) {
            String answer = String.valueOf(result.getOrDefault("answer", ""));
            result.put("answer", note + "\n\n" + answer);
        }
        List<Map<String, Object>> tools = scenarioTemplateTools(nmsCtx, deviceId, alarmId);
        if (tools.isEmpty()) {
            tools = sanitizeTools(heuristicTools(q, nmsCtx, deviceId, alarmId), nmsCtx, deviceId, alarmId);
        }
        result.put("proposedTools", tools);
        result.put("suggestedActions", toActionButtons(tools));
        result.put("toolsSource", "scenario");
        if (nmsCtx != null) {
            result.put("context", nmsCtx);
        }
        return result;
    }

    private record ParsedAnswer(String answer, List<Map<String, Object>> tools, boolean parsedOk) {}

    private ParsedAnswer parseAnswerAndTools(String raw) {
        parseAttempts.incrementAndGet();
        if (raw == null || raw.isBlank()) {
            return new ParsedAnswer("（无回答）", List.of(), false);
        }
        String text = raw.trim();
        List<Map<String, Object>> tools = List.of();
        boolean parsedOk = false;

        Matcher fence = FENCE_JSON.matcher(text);
        if (fence.find()) {
            tools = parseToolsJson(fence.group(1));
            parsedOk = !tools.isEmpty() || fence.group(1).contains("\"tools\"");
            text = (text.substring(0, fence.start()) + text.substring(fence.end())).trim();
        } else {
            int idx = text.lastIndexOf("{\"tools\"");
            if (idx < 0) {
                idx = text.lastIndexOf("{ \"tools\"");
            }
            if (idx >= 0) {
                String jsonPart = extractBalancedJson(text.substring(idx));
                tools = parseToolsJson(jsonPart);
                parsedOk = jsonPart.contains("\"tools\"");
                if (parsedOk) {
                    text = text.substring(0, idx).trim();
                }
            } else {
                Matcher m = TOOLS_JSON.matcher(text);
                if (m.find()) {
                    tools = parseToolsJson(m.group());
                    parsedOk = true;
                    text = text.substring(0, m.start()).trim();
                } else {
                    Matcher inline = INLINE_TOOLS.matcher(text);
                    if (inline.find()) {
                        tools = parseToolsJson(inline.group());
                        parsedOk = true;
                        text = (text.substring(0, inline.start()) + text.substring(inline.end())).trim();
                    }
                }
            }
        }
        if (parsedOk) {
            parseSuccesses.incrementAndGet();
        }
        if (text.isBlank()) {
            text = "已生成可在当前页确认执行的处置建议。";
        }
        return new ParsedAnswer(text, tools, parsedOk);
    }

    /** 从起始 { 提取平衡的 JSON 对象（容忍尾部杂文） */
    private String extractBalancedJson(String s) {
        if (s == null || s.isBlank()) return "";
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(0, i + 1);
                }
            }
        }
        int end = s.lastIndexOf('}');
        return end > 0 ? s.substring(0, end + 1) : s;
    }

    private List<Map<String, Object>> scenarioTemplateTools(Map<String, Object> nmsCtx,
                                                            Long deviceId, Long alarmId) {
        Long ctxDevice = coalesceDeviceId(nmsCtx, deviceId);
        Long ctxAlarm = coalesceAlarmId(nmsCtx, alarmId);
        String scenario = null;
        if (nmsCtx != null) {
            Object focus = nmsCtx.get("focusAlarm");
            if (focus instanceof Map<?, ?> fm && fm.get("scenario") != null) {
                scenario = String.valueOf(fm.get("scenario"));
            }
            if (scenario == null && nmsCtx.get("scenario") != null) {
                scenario = String.valueOf(nmsCtx.get("scenario"));
            }
            Object recommended = nmsCtx.get("recommendedTools");
            if (recommended instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> fromCtx = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        fromCtx.add(copy);
                    }
                }
                List<Map<String, Object>> sanitized = sanitizeTools(fromCtx, nmsCtx, ctxDevice, ctxAlarm);
                if (!sanitized.isEmpty()) {
                    return sanitized;
                }
            }
        }
        try {
            boolean closed = isFocusClosed(nmsCtx);
            List<Map<String, Object>> recommended = playbookService.buildRecommendedTools(
                    scenario, ctxDevice, ctxAlarm, closed);
            return sanitizeTools(recommended, nmsCtx, ctxDevice, ctxAlarm);
        } catch (Exception e) {
            log.debug("场景模板工具失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 仅 CLEARED 视为已关闭；ACK=处理中，仍可标准处置 */
    private boolean isFocusClosed(Map<String, Object> nmsCtx) {
        if (nmsCtx == null) return false;
        if (Boolean.TRUE.equals(nmsCtx.get("focusClosed"))) return true;
        Object focus = nmsCtx.get("focusAlarm");
        if (focus instanceof Map<?, ?> fm) {
            if (Boolean.TRUE.equals(fm.get("closed"))) return true;
            Object st = fm.get("status");
            return st != null && "CLEARED".equalsIgnoreCase(String.valueOf(st));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToolsJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.get("tools");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(arr, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.debug("解析 tools JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> sanitizeTools(List<Map<String, Object>> raw,
                                                    Map<String, Object> nmsCtx,
                                                    Long deviceId, Long alarmId) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Long ctxDevice = coalesceDeviceId(nmsCtx, deviceId);
        Long ctxAlarm = coalesceAlarmId(nmsCtx, alarmId);
        boolean closed = isFocusClosed(nmsCtx);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : raw) {
            if (t == null) continue;
            String name = String.valueOf(t.getOrDefault("name", t.getOrDefault("id", ""))).trim().toLowerCase(Locale.ROOT);
            if (!LlmToolExecutor.ALLOWED.contains(name)) {
                continue;
            }
            // 已关闭：不再推送标准处置 / 确认噪音 / 确认本告警
            if (closed && ("dispose_incident".equals(name) || "ack_noise".equals(name) || "ack_alarm".equals(name))) {
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            Object rawArgs = t.get("args");
            if (rawArgs instanceof Map<?, ?> am) {
                for (Map.Entry<?, ?> e : am.entrySet()) {
                    if (e.getKey() != null) {
                        args.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
            if (!args.containsKey("deviceId") && ctxDevice != null) {
                args.put("deviceId", ctxDevice);
            }
            if (!args.containsKey("alarmId") && ctxAlarm != null) {
                args.put("alarmId", ctxAlarm);
            }
            if (needsDevice(name) && toLong(args.get("deviceId")) == null) {
                continue;
            }
            if (needsAlarm(name) && toLong(args.get("alarmId")) == null) {
                continue;
            }
            boolean needConfirm = !LlmToolExecutor.READ_ONLY.contains(name);
            if (t.get("needConfirm") instanceof Boolean b) {
                needConfirm = b || needConfirm;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("args", args);
            tool.put("label", t.get("label") != null && !String.valueOf(t.get("label")).isBlank()
                    ? String.valueOf(t.get("label")) : defaultLabel(name));
            tool.put("needConfirm", needConfirm);
            out.add(tool);
            if (out.size() >= 3) break;
        }
        return out;
    }

    private List<Map<String, Object>> heuristicTools(String q, Map<String, Object> nmsCtx,
                                                     Long deviceId, Long alarmId) {
        String blob = (q == null ? "" : q).toLowerCase(Locale.ROOT);
        Long ctxDevice = coalesceDeviceId(nmsCtx, deviceId);
        Long ctxAlarm = coalesceAlarmId(nmsCtx, alarmId);
        List<Map<String, Object>> tools = new ArrayList<>();

        if (ctxAlarm != null && (blob.contains("处置") || blob.contains("处理") || blob.contains("怎么")
                || blob.contains("连带") || blob.contains("收敛") || blob.isBlank() || blob.contains("建议"))) {
            Map<String, Object> disposeArgs = new LinkedHashMap<>();
            disposeArgs.put("alarmId", ctxAlarm);
            if (ctxDevice != null) {
                disposeArgs.put("deviceId", ctxDevice);
            }
            tools.add(tool("dispose_incident", disposeArgs, "标准处置本事件", true));
        }
        if (blob.contains("噪音") || blob.contains("连带") || blob.contains("确认告警") || blob.contains("收敛")) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (ctxDevice != null) args.put("deviceId", ctxDevice);
            if (ctxAlarm != null) args.put("alarmId", ctxAlarm);
            tools.add(tool("ack_noise", args, "确认噪音告警", true));
        }
        if (ctxAlarm != null && (blob.contains("确认本") || blob.contains("确认告警"))) {
            tools.add(tool("ack_alarm", Map.of("alarmId", ctxAlarm), "确认本告警", true));
        }
        if (ctxDevice != null && (blob.contains("刷新") || blob.contains("离线") || blob.contains("连通") || blob.contains("ping"))) {
            tools.add(tool("refresh_device", Map.of("deviceId", ctxDevice), "刷新设备状态", true));
        }
        if (blob.contains("离线") && ctxDevice == null) {
            tools.add(tool("refresh_offline", Map.of(), "批量刷新离线设备", true));
        }
        if (ctxDevice != null && (blob.contains("备份") || blob.contains("配置"))) {
            tools.add(tool("backup", Map.of("deviceId", ctxDevice), "备份当前配置", true));
        }
        if (ctxDevice != null && (blob.contains("回滚") || blob.contains("恢复配置"))) {
            tools.add(tool("restore_latest", Map.of("deviceId", ctxDevice), "回滚最新备份", true));
        }
        if (ctxDevice != null && (blob.contains("running") || blob.contains("看一下") || blob.contains("查看配置")
                || blob.contains("拉取"))) {
            tools.add(tool("pull_live_config", Map.of("deviceId", ctxDevice, "configType", "running"),
                    "拉取 running 配置", false));
        }
        if (ctxDevice != null && (blob.contains("邻居") || blob.contains("拓扑") || blob.contains("链路"))) {
            tools.add(tool("get_topo_neighbors", Map.of("deviceId", ctxDevice), "拓扑邻居", false));
        }
        if (ctxDevice != null && (blob.contains("性能") || blob.contains("cpu") || blob.contains("内存") || blob.contains("异常"))) {
            tools.add(tool("get_perf_snapshot", Map.of("deviceId", ctxDevice), "性能快照", false));
        }
        if (ctxDevice != null && (blob.contains("差异") || blob.contains("对比配置") || blob.contains("配置风险"))) {
            tools.add(tool("get_config_diff_summary", Map.of("deviceId", ctxDevice), "配置差异摘要", false));
        }
        if (ctxDevice != null && (blob.contains("活动告警") || blob.contains("设备告警") || blob.contains("有哪些告警"))) {
            tools.add(tool("list_active_alarms_for_device", Map.of("deviceId", ctxDevice), "活动告警", false));
        }
        if (ctxDevice != null && (blob.contains("诊断") || blob.contains("设备摘要") || blob.contains("这台设备"))) {
            tools.add(tool("get_device_summary", Map.of("deviceId", ctxDevice), "设备摘要", false));
        }
        if (ctxDevice != null && (blob.contains("路径") || blob.contains("最短路"))) {
            tools.add(tool("run_path_hint", Map.of("deviceId", ctxDevice), "路径提示", false));
        }
        if (blob.contains("去") || blob.contains("跳转") || blob.contains("打开") || blob.contains("导航")) {
            String target = blob.contains("告警") ? "告警"
                    : (blob.contains("拓扑") ? "拓扑"
                    : (blob.contains("性能") ? "性能"
                    : (blob.contains("配置") ? "配置"
                    : (blob.contains("设备") ? "设备" : "概览"))));
            tools.add(tool("navigate_hint", Map.of("target", target), "页面导航", false));
        }
        if (blob.contains("搜索") || blob.contains("查找设备") || blob.contains("哪台")) {
            String kw = q == null ? "" : q.replaceAll("(?i)(搜索|查找|设备|哪台)", " ").trim();
            if (kw.length() > 1) {
                tools.add(tool("search_devices", Map.of("keyword", kw), "搜索设备", false));
            }
        }
        if (blob.contains("态势") || blob.contains("健康") || blob.contains("概览") || blob.contains("全网") || blob.contains("巡检")) {
            tools.add(tool("get_network_overview", Map.of(), "网络态势", false));
            tools.add(tool("inspect", Map.of(), "重算关联与根因", false));
        }
        if (blob.contains("终端") || blob.contains("cli") || blob.contains("命令输出") || blob.contains("报错")) {
            tools.add(tool("explain_cli_output", Map.of("output", q == null ? "" : q), "解释终端输出", false));
        }
        if (blob.contains("配置建议") || blob.contains("怎么配") || blob.contains("命令建议") || blob.contains("vlan")
                || blob.contains("ospf") || (ctxDevice != null && blob.contains("配置"))) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (ctxDevice != null) args.put("deviceId", ctxDevice);
            args.put("intent", blob.contains("vlan") ? "vlan" : (blob.contains("ospf") ? "ospf" : "general"));
            tools.add(tool("suggest_config_commands", args, "配置命令建议", false));
        }
        if (tools.isEmpty() || blob.contains("巡检") || blob.contains("摘要") || blob.contains("根因")) {
            tools.add(0, tool("inspect", Map.of(), "重算关联与根因", false));
        }

        // 去重并限制 3
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> t : tools) {
            uniq.putIfAbsent(String.valueOf(t.get("name")), t);
        }
        return new ArrayList<>(uniq.values()).stream().limit(3).toList();
    }

    private static Map<String, Object> tool(String name, Map<String, Object> args, String label, boolean needConfirm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("args", args != null ? args : Map.of());
        m.put("label", label);
        m.put("needConfirm", needConfirm);
        return m;
    }

    private List<Map<String, Object>> toActionButtons(List<Map<String, Object>> tools) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", t.get("name"));
            a.put("name", t.get("name"));
            a.put("label", t.get("label"));
            a.put("needConfirm", t.get("needConfirm"));
            a.put("args", t.get("args"));
            actions.add(a);
        }
        return actions;
    }

    private static String defaultLabel(String name) {
        return switch (name) {
            case "inspect" -> "重算关联与根因";
            case "refresh_device" -> "刷新设备状态";
            case "refresh_offline" -> "批量刷新离线设备";
            case "ack_noise" -> "确认噪音告警";
            case "dispose_incident" -> "标准处置本事件";
            case "backup" -> "备份当前配置";
            case "restore_latest" -> "回滚最新备份";
            case "pull_live_config" -> "拉取 running 配置";
            case "ack_alarm" -> "确认本告警";
            case "get_device_summary" -> "设备摘要";
            case "get_topo_neighbors" -> "拓扑邻居";
            case "get_perf_snapshot" -> "性能快照";
            case "get_config_diff_summary" -> "配置差异摘要";
            case "list_active_alarms_for_device" -> "活动告警";
            case "run_path_hint" -> "路径提示";
            case "navigate_hint" -> "页面导航";
            case "search_devices" -> "搜索设备";
            case "get_network_overview" -> "网络态势";
            case "explain_cli_output" -> "解释终端输出";
            case "suggest_config_commands" -> "配置命令建议";
            case "get_alarm_detail" -> "告警详情";
            case "list_config_backups" -> "备份列表";
            case "get_backup_schedule_status" -> "计划备份状态";
            case "ping_check", "probe_device" -> "连通性探测";
            case "traceroute_hint" -> "路径/跳数提示";
            case "highlight_topology_nodes" -> "拓扑高亮";
            case "open_workbench_event" -> "打开工作台事件";
            case "get_interface_brief" -> "接口摘要";
            case "run_show_command" -> "受控 show";
            case "get_config_compliance_score" -> "配置合规分";
            default -> name;
        };
    }

    private static boolean needsDevice(String name) {
        return "refresh_device".equals(name)
                || "backup".equals(name)
                || "restore_latest".equals(name)
                || "pull_live_config".equals(name)
                || "get_device_summary".equals(name)
                || "get_topo_neighbors".equals(name)
                || "get_perf_snapshot".equals(name)
                || "get_config_diff_summary".equals(name)
                || "list_active_alarms_for_device".equals(name)
                || "run_path_hint".equals(name)
                || "list_config_backups".equals(name)
                || "get_backup_schedule_status".equals(name)
                || "ping_check".equals(name)
                || "probe_device".equals(name)
                || "traceroute_hint".equals(name)
                || "get_interface_brief".equals(name)
                || "run_show_command".equals(name)
                || "get_config_compliance_score".equals(name);
    }

    private static boolean needsAlarm(String name) {
        return "dispose_incident".equals(name)
                || "ack_alarm".equals(name)
                || "get_alarm_detail".equals(name);
    }

    private static Long coalesceDeviceId(Map<String, Object> nmsCtx, Long deviceId) {
        if (deviceId != null) return deviceId;
        if (nmsCtx == null) return null;
        Object fd = nmsCtx.get("focusDevice");
        if (fd instanceof Map<?, ?> m) {
            return toLong(m.get("id"));
        }
        Object fa = nmsCtx.get("focusAlarm");
        if (fa instanceof Map<?, ?> m) {
            return toLong(m.get("deviceId"));
        }
        return null;
    }

    private static Long coalesceAlarmId(Map<String, Object> nmsCtx, Long alarmId) {
        if (alarmId != null) return alarmId;
        if (nmsCtx == null) return null;
        Object fa = nmsCtx.get("focusAlarm");
        if (fa instanceof Map<?, ?> m) {
            return toLong(m.get("id"));
        }
        return null;
    }

    private static Long toLong(Object v) {
        if (v == null || "".equals(v)) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
