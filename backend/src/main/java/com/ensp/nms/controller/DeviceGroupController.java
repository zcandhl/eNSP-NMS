package com.ensp.nms.controller;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceGroup;
import com.ensp.nms.service.DeviceGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/device-groups")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeviceGroupController {

    private final DeviceGroupService deviceGroupService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(deviceGroupService.listWithCounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return deviceGroupService.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/devices")
    public ResponseEntity<List<Device>> devices(@PathVariable Long id) {
        return ResponseEntity.ok(deviceGroupService.listDevices(id));
    }

    @PostMapping
    public ResponseEntity<DeviceGroup> create(@RequestBody DeviceGroup group) {
        return ResponseEntity.ok(deviceGroupService.create(group));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeviceGroup> update(@PathVariable Long id, @RequestBody DeviceGroup group) {
        return ResponseEntity.ok(deviceGroupService.update(id, group));
    }

    @PutMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> setMembers(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<?> raw = (List<?>) body.get("deviceIds");
        List<Long> deviceIds = new java.util.ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Number n) {
                    deviceIds.add(n.longValue());
                } else if (o != null) {
                    deviceIds.add(Long.parseLong(o.toString()));
                }
            }
        }
        return ResponseEntity.ok(deviceGroupService.setMembers(id, deviceIds));
    }

    @PostMapping("/{id}/members/preview")
    public ResponseEntity<Map<String, Object>> previewMembers(@PathVariable Long id,
                                                              @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<?> raw = (List<?>) body.get("deviceIds");
        List<Long> deviceIds = new java.util.ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Number n) {
                    deviceIds.add(n.longValue());
                } else if (o != null) {
                    deviceIds.add(Long.parseLong(o.toString()));
                }
            }
        }
        return ResponseEntity.ok(deviceGroupService.previewSetMembers(id, deviceIds));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        deviceGroupService.delete(id);
        return ResponseEntity.ok().build();
    }
}
