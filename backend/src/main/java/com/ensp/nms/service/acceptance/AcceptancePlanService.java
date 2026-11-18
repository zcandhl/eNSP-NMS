package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import com.ensp.nms.repository.AcceptancePlanRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 验收方案 CRUD：方案即数据。检查项列表整体以 JSON 存库，改方案不改代码。
 */
@Service
@RequiredArgsConstructor
public class AcceptancePlanService {

    private final AcceptancePlanRepository planRepository;
    private final AcceptanceBatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    public List<AcceptancePlan> listAll() {
        return planRepository.findAll();
    }

    public List<AcceptancePlan> findByProjectId(Long projectId) {
        return planRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    public java.util.Optional<AcceptancePlan> get(Long id) {
        return planRepository.findById(id);
    }

    /**
     * 新建方案。
     *
     * @param payload projectId 必填；name/version/reportNoRule 可选；
     *                template=true（默认）时带标准六项检查模板，owner 绑定当前登录账号
     * @param owner   当前登录账号 username（检查项负责人初始值；可被 payload.owner 覆盖）
     */
    @SuppressWarnings("unchecked")
    public AcceptancePlan create(Map<String, Object> payload, String owner) {
        Object projectIdObj = payload.get("projectId");
        if (projectIdObj == null) {
            throw new IllegalArgumentException("新建方案必须指定所属工程 projectId");
        }
        long projectId = Long.parseLong(String.valueOf(projectIdObj));
        String name = payload.get("name") == null ? "未命名验收方案" : String.valueOf(payload.get("name"));
        String version = payload.get("version") == null ? "1.0" : String.valueOf(payload.get("version"));
        boolean template = payload.get("template") == null || Boolean.parseBoolean(String.valueOf(payload.get("template")));
        String checkOwner = payload.get("owner") == null ? owner : String.valueOf(payload.get("owner"));

        AcceptancePlan plan = new AcceptancePlan();
        plan.setProjectId(projectId);
        plan.setName(name);
        plan.setVersion(version);
        plan.setStatus("draft");
        if (template) {
            try {
                plan.setChecksJson(objectMapper.writeValueAsString(AcceptanceDefaults.standardChecks(checkOwner)));
            } catch (Exception e) {
                throw new IllegalStateException("标准检查模板序列化失败：" + e.getMessage(), e);
            }
        }
        return planRepository.save(plan);
    }

    /** 删除方案并连带清掉它的历史批次（快照随方案一起失效） */
    @Transactional
    public void delete(Long id) {
        AcceptancePlan plan = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
        batchRepository.deleteByPlanId(id);
        planRepository.delete(plan);
    }

    /**
     * 实体 → 前端视图：checksJson 解析为 checks 数组。
     * 前端契约直接读写 checks，不感知存储细节；解析失败按空数组处理，不让页面白屏。
     */
    public Map<String, Object> toView(AcceptancePlan plan) {
        Map<String, Object> m = objectMapper.convertValue(plan, new TypeReference<Map<String, Object>>() {});
        m.remove("checksJson");
        m.put("checks", parseChecks(plan.getChecksJson()));
        return m;
    }

    /** checksJson → List；容错：空/坏 JSON 返回空列表 */
    public List<Map<String, Object>> parseChecks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 保存：name / status / reportNoRule / checks 均可改，checks 序列化为 JSON */
    @SuppressWarnings("unchecked")
    public AcceptancePlan save(Long id, Map<String, Object> payload) {
        AcceptancePlan plan = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
        if (payload.containsKey("name") && payload.get("name") != null) {
            plan.setName(String.valueOf(payload.get("name")));
        }
        if (payload.containsKey("status") && payload.get("status") != null) {
            plan.setStatus(String.valueOf(payload.get("status")));
        }
        if (payload.containsKey("reportNoRule") && payload.get("reportNoRule") != null) {
            plan.setReportNoRule(String.valueOf(payload.get("reportNoRule")));
        }
        if (payload.containsKey("version") && payload.get("version") != null) {
            plan.setVersion(String.valueOf(payload.get("version")));
        }
        Object checks = payload.get("checks");
        if (checks instanceof List<?> list) {
            try {
                plan.setChecksJson(objectMapper.writeValueAsString(list));
            } catch (Exception e) {
                throw new IllegalArgumentException("检查项序列化失败：" + e.getMessage(), e);
            }
        }
        return planRepository.save(plan);
    }
}
