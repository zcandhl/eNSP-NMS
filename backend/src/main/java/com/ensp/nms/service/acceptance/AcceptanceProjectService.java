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
 * 验收项目创建、更新与删除。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

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
