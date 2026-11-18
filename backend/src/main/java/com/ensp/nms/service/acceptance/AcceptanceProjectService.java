package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.AcceptanceProject;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import com.ensp.nms.repository.AcceptancePlanRepository;
import com.ensp.nms.repository.AcceptanceProjectRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 验收工程业务：创建 / 更新 / 级联删除。
 *
 * 工程是验收数据的根：删除工程必须连带删掉它名下的设备（含其告警/拓扑/性能数据）
 * 与方案、批次，否则会留下"孤儿设备/孤儿批次"，
 * 报告导出时按 planId 反查会查到悬空数据，设备列表也会出现悬空归属。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceProjectService {

    private final AcceptanceProjectRepository projectRepository;
    private final AcceptancePlanRepository planRepository;
    private final AcceptanceBatchRepository batchRepository;
    private final AcceptancePlanService planService;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;

    public AcceptanceProject create(AcceptanceProject project) {
        if (project.getName() == null || project.getName().isBlank()) {
            throw new IllegalArgumentException("工程名称不能为空");
        }
        if (project.getDeviceCount() == null) {
            project.setDeviceCount(0);
        }
        return projectRepository.save(project);
    }

    public AcceptanceProject update(Long id, AcceptanceProject patch) {
        AcceptanceProject project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("工程不存在"));
        if (patch.getName() != null && !patch.getName().isBlank()) {
            project.setName(patch.getName());
        }
        if (patch.getBuilding() != null) {
            project.setBuilding(patch.getBuilding());
        }
        if (patch.getOwner() != null) {
            project.setOwner(patch.getOwner());
        }
        if (patch.getContractor() != null) {
            project.setContractor(patch.getContractor());
        }
        if (patch.getAcceptanceOrg() != null) {
            project.setAcceptanceOrg(patch.getAcceptanceOrg());
        }
        if (patch.getDeviceCount() != null) {
            project.setDeviceCount(patch.getDeviceCount());
        }
        if (patch.getNote() != null) {
            project.setNote(patch.getNote());
        }
        return projectRepository.save(project);
    }

    /**
     * 级联删除：工程 → 名下设备（含其告警/拓扑/端口/配置/性能数据）→ 方案 → 历史批次。
     * 设备走 DeviceService.delete 的完整级联，与手动删除单台设备的行为完全一致。
     */
    @Transactional
    public void delete(Long id) {
        AcceptanceProject project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("工程不存在"));
        List<Device> devices = deviceRepository.findByProjectId(id);
        for (Device device : devices) {
            deviceService.deleteDevice(device.getId());
        }
        List<AcceptancePlan> plans = planService.findByProjectId(id);
        for (AcceptancePlan plan : plans) {
            batchRepository.deleteByPlanId(plan.getId());
            planRepository.delete(plan);
        }
        projectRepository.delete(project);
    }
}
