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
 * 验收计划增删改查、检查项解析与视图转换。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

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

    @Transactional
    public void delete(Long id) {
        AcceptancePlan plan = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
        batchRepository.deleteByPlanId(id);
        planRepository.delete(plan);
    }

    public Map<String, Object> toView(AcceptancePlan plan) {
        Map<String, Object> m = objectMapper.convertValue(plan, new TypeReference<Map<String, Object>>() {});
        m.remove("checksJson");
        m.put("checks", parseChecks(plan.getChecksJson()));
        return m;
    }

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
