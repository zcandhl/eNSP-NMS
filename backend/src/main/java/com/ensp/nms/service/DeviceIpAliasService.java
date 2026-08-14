package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceIpAlias;
import com.ensp.nms.repository.DeviceIpAliasRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.snmp.SnmpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 维护「接口 IP → 纳管设备」映射。Trap 常带 Vlanif/业务口地址，而非管理环回。
 */
@Slf4j
@Service
public class DeviceIpAliasService {

    @Autowired
    private DeviceIpAliasRepository aliasRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private SnmpClient snmpClient;

    /** 避免告警列表批量 enrich 时对同一批接口 IP 反复 SNMP 扫描 */
    private volatile long lastFullRefreshMs = 0;
    private static final long FULL_REFRESH_COOLDOWN_MS = 60_000;

    public Optional<Device> findDeviceByAnyIp(String rawIp) {
        String ip = AlarmService.normalizeDeviceIp(rawIp);
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        Optional<Device> byMgmt = deviceRepository.findByIpAddress(ip);
        if (byMgmt.isPresent()) {
            return byMgmt;
        }
        return aliasRepository.findByIpAddress(ip)
                .flatMap(a -> deviceRepository.findById(a.getDeviceId()));
    }

    /**
     * 别名表未命中时：对各设备做一次 ipAddrTable 刷新，再按目标 IP 查找。
     * 60 秒内只全量刷新一次，避免告警列表批量纠正时反复扫网。
     */
    @Transactional
    public Optional<Device> resolveByRefreshingAliases(String rawIp) {
        String ip = AlarmService.normalizeDeviceIp(rawIp);
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        Optional<Device> cached = findDeviceByAnyIp(ip);
        if (cached.isPresent()) {
            return cached;
        }

        long now = System.currentTimeMillis();
        if (now - lastFullRefreshMs >= FULL_REFRESH_COOLDOWN_MS) {
            lastFullRefreshMs = now;
            List<Device> devices = deviceRepository.findAll();
            for (Device device : devices) {
                try {
                    refreshDeviceAliases(device);
                } catch (Exception e) {
                    log.debug("刷新设备 {} 别名失败: {}", device.getName(), e.getMessage());
                }
                Optional<Device> hit = findDeviceByAnyIp(ip);
                if (hit.isPresent()) {
                    log.info("经 ipAddrTable 将接口 IP {} 映射到管理设备 {} ({})",
                            ip, hit.get().getName(), hit.get().getIpAddress());
                    return hit;
                }
            }
        }

        return findDeviceByAnyIp(ip);
    }

    @Transactional
    public void refreshDeviceAliases(Device device) {
        if (device == null || device.getId() == null || device.getIpAddress() == null) {
            return;
        }
        String mgmtIp = AlarmService.normalizeDeviceIp(device.getIpAddress());
        int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
        String community = (device.getSnmpCommunity() != null && !device.getSnmpCommunity().isBlank())
                ? device.getSnmpCommunity() : "public";

        List<String> learned = snmpClient.getIpAddrTable(mgmtIp, port, community);
        Set<String> ips = new HashSet<>();
        if (mgmtIp != null && !mgmtIp.isBlank()) {
            ips.add(mgmtIp);
        }
        for (String learnedIp : learned) {
            String n = AlarmService.normalizeDeviceIp(learnedIp);
            if (n != null && !n.isBlank()) {
                ips.add(n);
            }
        }

        // 先清本设备旧别名，再写入（管理 IP 不进别名表，避免与 device.ip 重复）
        aliasRepository.deleteByDeviceId(device.getId());
        aliasRepository.flush();
        for (String aliasIp : ips) {
            if (aliasIp.equals(mgmtIp)) {
                continue;
            }
            // 该接口 IP 若已被其它设备占用，改归属到当前设备（以本次 SNMP 为准）
            Optional<DeviceIpAlias> existing = aliasRepository.findByIpAddress(aliasIp);
            if (existing.isPresent()) {
                DeviceIpAlias row = existing.get();
                if (!row.getDeviceId().equals(device.getId())) {
                    log.info("接口 IP {} 归属从设备#{} 改为 #{} ({})",
                            aliasIp, row.getDeviceId(), device.getId(), device.getName());
                    row.setDeviceId(device.getId());
                    aliasRepository.save(row);
                }
                continue;
            }
            DeviceIpAlias row = new DeviceIpAlias();
            row.setDeviceId(device.getId());
            row.setIpAddress(aliasIp);
            aliasRepository.save(row);
        }
        log.debug("设备 {} 接口别名已更新: {}", device.getName(), ips);
    }
}
