package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceGroup;
import com.ensp.nms.repository.DeviceGroupRepository;
import com.ensp.nms.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceGroupService {

    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceRepository deviceRepository;

    public List<DeviceGroup> listAll() {
        return deviceGroupRepository.findAll();
    }

    public Optional<DeviceGroup> getById(Long id) {
        return deviceGroupRepository.findById(id);
    }

    @Transactional
    public DeviceGroup create(DeviceGroup group) {
        return deviceGroupRepository.save(group);
    }

    @Transactional
    public DeviceGroup update(Long id, DeviceGroup incoming) {
        DeviceGroup existing = deviceGroupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("分组不存在"));
        existing.setName(incoming.getName());
        existing.setDescription(incoming.getDescription());
        return deviceGroupRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        List<Device> members = deviceRepository.findByGroupId(id);
        for (Device d : members) {
            d.setGroupId(null);
            deviceRepository.save(d);
        }
        deviceGroupRepository.deleteById(id);
    }

    public List<Device> listDevices(Long groupId) {
        return deviceRepository.findByGroupId(groupId);
    }

    /** 预览成员变更：哪些设备将从其他分组迁入 */
    public Map<String, Object> previewSetMembers(Long groupId, List<Long> deviceIds) {
        if (!deviceGroupRepository.existsById(groupId)) {
            throw new RuntimeException("分组不存在");
        }
        List<Long> targetIds = deviceIds != null ? deviceIds : List.of();
        List<Map<String, Object>> moving = new ArrayList<>();
        Map<Long, String> groupNames = deviceGroupRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(DeviceGroup::getId, DeviceGroup::getName, (a, b) -> a));

        for (Long deviceId : targetIds) {
            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) {
                continue;
            }
            if (device.getGroupId() != null && !device.getGroupId().equals(groupId)) {
                Map<String, Object> row = new HashMap<>();
                row.put("deviceId", device.getId());
                row.put("deviceName", device.getName());
                row.put("fromGroupId", device.getGroupId());
                row.put("fromGroupName", groupNames.getOrDefault(device.getGroupId(), "分组#" + device.getGroupId()));
                moving.add(row);
            }
        }

        List<Map<String, Object>> removing = new ArrayList<>();
        for (Device d : deviceRepository.findByGroupId(groupId)) {
            if (!targetIds.contains(d.getId())) {
                Map<String, Object> row = new HashMap<>();
                row.put("deviceId", d.getId());
                row.put("deviceName", d.getName());
                removing.add(row);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("groupId", groupId);
        result.put("movingFromOtherGroups", moving);
        result.put("removing", removing);
        return result;
    }

    /** 将指定设备划入分组；原成员若不在列表中则移出分组 */
    @Transactional
    public Map<String, Object> setMembers(Long groupId, List<Long> deviceIds) {
        if (!deviceGroupRepository.existsById(groupId)) {
            throw new RuntimeException("分组不存在");
        }
        List<Long> targetIds = deviceIds != null ? deviceIds : List.of();
        // 移出不在新名单中的原成员
        for (Device d : deviceRepository.findByGroupId(groupId)) {
            if (!targetIds.contains(d.getId())) {
                d.setGroupId(null);
                deviceRepository.save(d);
            }
        }
        int updated = 0;
        for (Long deviceId : targetIds) {
            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) {
                continue;
            }
            device.setGroupId(groupId);
            deviceRepository.save(device);
            updated++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("groupId", groupId);
        result.put("updated", updated);
        result.put("deviceCount", deviceRepository.findByGroupId(groupId).size());
        return result;
    }

    public List<Map<String, Object>> listWithCounts() {
        return deviceGroupRepository.findAll().stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("description", g.getDescription());
            m.put("createdAt", g.getCreatedAt());
            m.put("deviceCount", deviceRepository.findByGroupId(g.getId()).size());
            return m;
        }).toList();
    }
}
