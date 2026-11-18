package com.ensp.nms.controller;

import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.AcceptanceProject;
import com.ensp.nms.repository.AcceptanceProjectRepository;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.UserService;
import com.ensp.nms.service.acceptance.AcceptanceAiService;
import com.ensp.nms.service.acceptance.AcceptanceCheckTypes;
import com.ensp.nms.service.acceptance.AcceptancePlanService;
import com.ensp.nms.service.acceptance.AcceptanceProjectService;
import com.ensp.nms.service.acceptance.AcceptanceReportService;
import com.ensp.nms.service.acceptance.AcceptanceRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 验收工作台 API（与前端 api/acceptance.js 契约一一对应）。
 */
@RestController
@RequestMapping("/api/acceptance")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AcceptanceController {

    private final AcceptanceProjectRepository projectRepository;
    private final com.ensp.nms.repository.DeviceRepository deviceRepository;
    private final com.ensp.nms.repository.TopologyLinkRepository topologyLinkRepository;
    private final AcceptanceProjectService projectService;
    private final AcceptancePlanService planService;
    private final AcceptanceRunService runService;
    private final AcceptanceReportService reportService;
    private final AcceptanceAiService aiService;
    private final UserService userService;

    // ── 成员（负责人下拉数据源：绑定系统账号，非自由文本） ──

    /**
     * 返回在职（active）用户的 username + realName。
     * 检查项的 owner 存 username；前端用 realName 做展示，登录人自己的项会打"我"标。
     */
    @GetMapping("/members")
    public ResponseEntity<List<Map<String, Object>>> members() {
        List<Map<String, Object>> out = new ArrayList<>();
        userService.getAllUsers().stream()
                .filter(u -> u.getStatus() == null || "active".equals(u.getStatus()))
                .forEach(u -> out.add(Map.of(
                        "username", u.getUsername(),
                        "realName", u.getRealName() == null ? u.getUsername() : u.getRealName())));
        return ResponseEntity.ok(out);
    }

    // ── 项目 ──

    /**
     * 工程列表：deviceCount 不再取手工填写值，改为按工程真实纳管设备（Device.projectId）统计。
     * linkCount = 该工程设备之间的组网链路数（两端都属本工程），工程页据此判断"组网是否已完成"。
     */
    @GetMapping("/projects")
    public ResponseEntity<List<Map<String, Object>>> listProjects() {
        // 一次取全量设备 / 链路，在内存里按工程聚合，避免逐工程打库
        Map<Long, java.util.Set<Long>> devicesByProject = new java.util.HashMap<>();
        for (com.ensp.nms.entity.Device d : deviceRepository.findAll()) {
            if (d.getId() == null) continue;
            Long pid = d.getProjectId() == null ? -1L : d.getProjectId();
            devicesByProject.computeIfAbsent(pid, k -> new java.util.HashSet<>()).add(d.getId());
        }
        Map<Long, Integer> linkCountByProject = new java.util.HashMap<>();
        for (com.ensp.nms.entity.TopologyLink l : topologyLinkRepository.findAll()) {
            if (l.getSourceNodeId() == null || l.getTargetNodeId() == null) continue;
            for (Map.Entry<Long, java.util.Set<Long>> e : devicesByProject.entrySet()) {
                java.util.Set<Long> ids = e.getValue();
                if (ids.contains(l.getSourceNodeId()) && ids.contains(l.getTargetNodeId())) {
                    linkCountByProject.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (AcceptanceProject p : projectRepository.findAll()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("building", p.getBuilding());
            m.put("owner", p.getOwner());
            m.put("contractor", p.getContractor());
            m.put("acceptanceOrg", p.getAcceptanceOrg());
            m.put("note", p.getNote());
            m.put("createdAt", p.getCreatedAt());
            m.put("deviceCount", (int) deviceRepository.countByProjectId(p.getId()));
            m.put("linkCount", linkCountByProject.getOrDefault(p.getId(), 0));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/projects")
    public ResponseEntity<AcceptanceProject> createProject(@RequestBody AcceptanceProject project) {
        return ResponseEntity.ok(projectService.create(project));
    }

    @PutMapping("/projects/{id}")
    public ResponseEntity<AcceptanceProject> updateProject(@PathVariable Long id,
                                                           @RequestBody AcceptanceProject patch) {
        return ResponseEntity.ok(projectService.update(id, patch));
    }

    /** 删除工程：名下方案与历史批次级联删除（快照随工程失效，删除前前端有确认弹窗） */
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── 方案 ──

    /** 新建方案：projectId 必填；template=true（默认）带标准六项检查模板 */
    @PostMapping("/plans")
    public ResponseEntity<AcceptancePlan> createPlan(@RequestBody Map<String, Object> payload) {
        String owner = currentUsername();
        return ResponseEntity.ok(planService.create(payload, owner));
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long id) {
        planService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> listPlans(@RequestParam(required = false) Long projectId) {
        List<com.ensp.nms.entity.AcceptancePlan> plans = projectId == null
                ? planService.listAll()
                : planService.findByProjectId(projectId);
        return ResponseEntity.ok(plans.stream().map(planService::toView).toList());
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long id) {
        return planService.get(id)
                .map(plan -> ResponseEntity.ok(planService.toView(plan)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/plans/{id}")
    public ResponseEntity<AcceptancePlan> savePlan(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(planService.save(id, payload));
    }

    // ── 检查原语 schema ──

    /** 当前登录账号 username（JWT principal），新建方案时作为检查项负责人初始值 */
    private static String currentUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof String s && !s.isBlank() && !"anonymousUser".equals(s)) {
            return s;
        }
        return principal == null ? "player4" : String.valueOf(principal);
    }

    @GetMapping("/check-types")
    public ResponseEntity<List<Map<String, Object>>> checkTypes() {
        return ResponseEntity.ok(AcceptanceCheckTypes.ALL);
    }

    // ── 试运行 / 运行 / 批次 ──

    @PostMapping("/plans/{planId}/checks/{checkId}/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(@PathVariable Long planId,
                                                      @PathVariable Long checkId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Long deviceId = body == null ? null : toLong(body.get("deviceId"));
        return ResponseEntity.ok(runService.dryRun(planId, checkId, deviceId));
    }

    @PostMapping("/plans/{planId}/run")
    public ResponseEntity<Map<String, Object>> runPlan(@PathVariable Long planId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        List<Long> deviceIds = null;
        if (body != null && body.get("deviceIds") instanceof List<?> list) {
            deviceIds = list.stream().map(AcceptanceController::toLong).filter(x -> x != null).toList();
        }
        return ResponseEntity.ok(runService.runPlan(planId, deviceIds));
    }

    @GetMapping("/batches")
    public ResponseEntity<List<Map<String, Object>>> listBatches(@RequestParam Long planId) {
        return ResponseEntity.ok(runService.listBatches(planId));
    }

    /** 全局视角：跨方案查询所有运行中的验收批次（含方案名/工程名与实时进度） */
    @GetMapping("/batches/running")
    public ResponseEntity<List<Map<String, Object>>> listRunningBatches() {
        return ResponseEntity.ok(runService.listRunningBatches());
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<Map<String, Object>> getBatch(@PathVariable String batchId) {
        return ResponseEntity.ok(runService.getBatch(batchId));
    }

    @PostMapping("/batches/{batchId}/items/{checkId}/confirm")
    public ResponseEntity<Map<String, Object>> confirmItem(@PathVariable String batchId,
                                                           @PathVariable Long checkId,
                                                           @RequestBody Map<String, Object> body) {
        String decision = String.valueOf(body.getOrDefault("decision", "execute"));
        String note = body.get("note") == null ? "" : String.valueOf(body.get("note"));
        String operator = body.get("operator") == null ? "" : String.valueOf(body.get("operator"));
        return ResponseEntity.ok(runService.confirmItem(batchId, checkId, decision, note, operator));
    }

    @PostMapping("/batches/{batchId}/stop")
    public ResponseEntity<Map<String, Object>> stopBatch(@PathVariable String batchId) {
        return ResponseEntity.ok(runService.stopBatch(batchId));
    }

    @PostMapping("/batches/{batchId}/void")
    public ResponseEntity<Map<String, Object>> voidBatch(@PathVariable String batchId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        return ResponseEntity.ok(runService.voidBatch(batchId, reason));
    }

    @DeleteMapping("/batches/{batchId}")
    public ResponseEntity<Map<String, Object>> deleteBatch(@PathVariable String batchId) {
        runService.deleteBatch(batchId);
        return ResponseEntity.ok(Map.of("deleted", true, "batchId", batchId));
    }

    // ── AI 摘要（AI 只建议、不裁决：结果仅供参考，不进验收结论） ──

    /** AI 通道状态：前端用于按钮置灰与提示 */
    @GetMapping("/ai/status")
    public ResponseEntity<Map<String, Object>> aiStatus() {
        return ResponseEntity.ok(aiService.status());
    }

    /** 生成批次 AI 摘要（执行摘要/根因分析/整改建议） */
    @PostMapping("/batches/{batchId}/ai-summary")
    public ResponseEntity<Map<String, Object>> aiSummary(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(aiService.summary(batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // AI 未启用/调用失败：503 明确告知，不阻塞验收主流程
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── 报告 ──

    @PostMapping("/batches/{batchId}/report")
    public ResponseEntity<Map<String, Object>> generateReport(@PathVariable String batchId) {
        return ResponseEntity.ok(reportService.generate(batchId));
    }

    @GetMapping("/reports/{reportId}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String reportId) {
        AcceptanceReportService.ReportEntry entry = reportService.find(reportId);
        if (entry == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("报告不存在或已过期".getBytes());
        }
        String encodedName = URLEncoder.encode(entry.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(entry.name().endsWith(".docx")
                        ? MediaType.parseMediaType(AcceptanceReportService.DOCX_MIME)
                        : MediaType.parseMediaType("text/plain;charset=utf-8"))
                .body(entry.content());
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
