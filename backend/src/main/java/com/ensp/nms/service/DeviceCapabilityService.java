package com.ensp.nms.service;

import com.ensp.nms.device.DeviceType;
import com.ensp.nms.device.MonitorMode;
import com.ensp.nms.entity.Device;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class DeviceCapabilityService {

    public Map<String, Boolean> resolveCapabilities(Device device) {
        DeviceType type = DeviceType.fromCode(device != null ? device.getDeviceType() : null);
        MonitorMode mode = MonitorMode.fromCode(device != null ? device.getMonitorMode() : null);
        boolean hasSsh = device != null
                && device.getSshUsername() != null && !device.getSshUsername().isBlank();

        boolean snmp;
        boolean icmp = true;
        boolean performance;
        boolean topologyDiscover;
        boolean configBackup = hasSsh;
        boolean webssh = hasSsh;

        if (type == DeviceType.PC || mode == MonitorMode.ICMP) {
            snmp = false;
            performance = false;
            topologyDiscover = false;
            if (type == DeviceType.PC) {
                configBackup = false;
                webssh = false;
            }
        } else {
            snmp = true;
            performance = true;
            topologyDiscover = type == DeviceType.ROUTER
                    || type == DeviceType.SWITCH
                    || type == DeviceType.FIREWALL
                    || type == DeviceType.AC
                    || type == DeviceType.SERVER
                    || type == DeviceType.OTHER;
            if (type == DeviceType.AP) {
                topologyDiscover = false;
            }
        }

        Map<String, Boolean> caps = new LinkedHashMap<>();
        caps.put("snmp", snmp);
        caps.put("icmp", icmp);
        caps.put("performance", performance);
        caps.put("topologyDiscover", topologyDiscover);
        caps.put("configBackup", configBackup);
        caps.put("webssh", webssh);
        return caps;
    }

    public void enrich(Device device) {
        if (device == null) {
            return;
        }
        normalize(device);
        device.setCapabilities(resolveCapabilities(device));
    }

    public void normalize(Device device) {
        if (device == null) {
            return;
        }
        DeviceType type = DeviceType.fromCode(device.getDeviceType());
        MonitorMode mode = MonitorMode.fromCode(device.getMonitorMode());

        if (device.getDeviceType() == null || device.getDeviceType().isBlank()) {
            type = DeviceType.OTHER;
        }
        if (device.getMonitorMode() == null || device.getMonitorMode().isBlank()) {
            mode = type == DeviceType.PC ? MonitorMode.ICMP : MonitorMode.AUTO;
        }
        if (type == DeviceType.PC) {
            mode = MonitorMode.ICMP;
        }

        device.setDeviceType(type.getCode());
        device.setMonitorMode(mode.getCode());
    }

    /**
     * 根据名称 / sysDescr / sysObjectID 启发式识别设备类型。
     */
    public DeviceType detectType(String name, String sysDescr, String sysObjectId, String model) {
        String blob = ((name == null ? "" : name) + " "
                + (sysDescr == null ? "" : sysDescr) + " "
                + (sysObjectId == null ? "" : sysObjectId) + " "
                + (model == null ? "" : model)).toLowerCase(Locale.ROOT);

        if (blob.contains("usg") || blob.contains("firewall") || blob.contains("防火墙")
                || blob.contains("asa") || blob.contains("fortigate")) {
            return DeviceType.FIREWALL;
        }
        if (blob.contains("access point") || blob.contains("wireless ap")
                || blob.contains(" fat ap") || blob.contains("huawei ap")) {
            return DeviceType.AP;
        }
        if (blob.contains("wac") || blob.contains("wlan ac") || blob.contains("access controller")
                || blob.contains("无线控制器")) {
            return DeviceType.AC;
        }
        if (blob.contains("router") || blob.contains("路由") || blob.contains("ar22")
                || blob.contains("ar32") || blob.contains("ar12") || blob.contains("ne40")
                || blob.contains("ne20")) {
            return DeviceType.ROUTER;
        }
        if (blob.contains("switch") || blob.contains("交换") || blob.contains("s57")
                || blob.contains("s67") || blob.contains("s37") || blob.contains("ce68")
                || blob.contains("quidway")) {
            return DeviceType.SWITCH;
        }
        if (blob.contains("server") || blob.contains("服务器") || blob.contains("linux")
                || blob.contains("windows server")) {
            return DeviceType.SERVER;
        }
        if (containsWord(blob, "pc") || blob.contains("workstation")
                || blob.contains("终端") || blob.contains("desktop")) {
            return DeviceType.PC;
        }
        return DeviceType.OTHER;
    }

    private boolean containsWord(String text, String word) {
        return text.matches(".*\\b" + word + "\\b.*");
    }

    public String detectTypeCode(String name, String sysDescr, String sysObjectId, String model) {
        return detectType(name, sysDescr, sysObjectId, model).getCode();
    }
}
