package com.ensp.nms.snmp;

import com.ensp.nms.config.SnmpTrapProperties;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.TopologyDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.StateReference;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一接收 SNMPv1 Trap / SNMPv2c Trap&amp;Inform / SNMPv3 Trap&amp;Inform，
 * 解析后写入告警。v3 需在 snmp.trap.v3.users 配置与设备一致的 USM 用户。
 */
@Slf4j
@Service
public class SnmpTrapReceiver implements CommandLineRunner {

    private final AlarmService alarmService;
    private final TopologyDiscoveryService topologyDiscoveryService;
    private final SnmpTrapProperties trapProperties;

    private Snmp snmp;
    private DefaultUdpTransportMapping transport;
    private volatile boolean running = false;

    @Autowired
    public SnmpTrapReceiver(AlarmService alarmService,
                            TopologyDiscoveryService topologyDiscoveryService,
                            SnmpTrapProperties trapProperties) {
        this.alarmService = alarmService;
        this.topologyDiscoveryService = topologyDiscoveryService;
        this.trapProperties = trapProperties;
    }

    @Override
    public void run(String... args) {
        startTrapReceiver();
    }

    public void startTrapReceiver() {
        int trapPort = Math.max(1, trapProperties.getPort());
        try {
            log.info("Starting SNMP Trap receiver on port {} (v1/v2c/v3)...", trapPort);
            log.info("Make sure no other process is using port {} and firewall allows incoming traffic", trapPort);

            Address listenAddress = GenericAddress.parse("udp:0.0.0.0/" + trapPort);
            transport = new DefaultUdpTransportMapping((UdpAddress) listenAddress);

            // 显式 MessageDispatcher，保证 v1/v2c/v3 三模型都注册
            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv1());
            dispatcher.addMessageProcessingModel(new MPv2c());

            boolean v3Enabled = trapProperties.getV3() != null && trapProperties.getV3().isEnabled();
            if (v3Enabled) {
                SecurityProtocols.getInstance().addDefaultProtocols();
                OctetString localEngineId = new OctetString(MPv3.createLocalEngineID());
                USM usm = new USM(SecurityProtocols.getInstance(), localEngineId, 0);
                SecurityModels.getInstance().addSecurityModel(usm);
                dispatcher.addMessageProcessingModel(new MPv3(usm));
                log.info("SNMPv3 (USM) message model registered, localEngineID={}", localEngineId.toHexString());
            } else {
                log.warn("SNMPv3 Trap 接收已关闭（snmp.trap.v3.enabled=false），仅识别 v1/v2c");
            }

            snmp = new Snmp(dispatcher, transport);
            if (v3Enabled) {
                registerV3Users(snmp);
            }

            snmp.addCommandResponder(this::processTrap);

            transport.listen();
            running = true;
            log.info("SNMP Trap receiver started on {} — versions: v1 + v2c{}",
                    listenAddress, v3Enabled ? " + v3" : "");
        } catch (IOException e) {
            log.error("Failed to start SNMP Trap receiver: {}", e.getMessage(), e);
            log.error("Please check:");
            log.error("1. No other process is using port {}", trapPort);
            log.error("2. You have administrator privileges (ports < 1024 require admin)");
            log.error("3. Windows Firewall is not blocking port {}", trapPort);
        }
    }

    private void registerV3Users(Snmp snmpSession) {
        List<SnmpTrapProperties.User> users = trapProperties.getV3().getUsers();
        if (users == null || users.isEmpty()) {
            log.warn("SNMPv3 已启用但未配置 snmp.trap.v3.users，v3 Trap/Inform 将无法认证解密。"
                    + "请在 application.yml 配置 username/auth/priv 与设备一致。");
            return;
        }
        USM usm = snmpSession.getUSM();
        if (usm == null) {
            log.error("USM 未初始化，无法注册 v3 用户");
            return;
        }
        int n = 0;
        for (SnmpTrapProperties.User u : users) {
            if (u == null || u.getUsername() == null || u.getUsername().isBlank()) {
                continue;
            }
            try {
                UsmUser usmUser = buildUsmUser(u);
                usm.addUser(usmUser);
                n++;
                log.info("Registered SNMPv3 USM user='{}' auth={} priv={}",
                        u.getUsername(),
                        blankToNone(u.getAuthProtocol()),
                        blankToNone(u.getPrivProtocol()));
            } catch (Exception e) {
                log.error("注册 SNMPv3 用户 '{}' 失败: {}", u.getUsername(), e.getMessage());
            }
        }
        log.info("SNMPv3 USM users registered: {}", n);
    }

    private UsmUser buildUsmUser(SnmpTrapProperties.User u) {
        OctetString user = new OctetString(u.getUsername().trim());
        OID authProto = resolveAuthProtocol(u.getAuthProtocol());
        OID privProto = resolvePrivProtocol(u.getPrivProtocol());
        OctetString authPass = toPass(u.getAuthPassword(), authProto != null);
        OctetString privPass = toPass(u.getPrivPassword(), privProto != null);
        return new UsmUser(user, authProto, authPass, privProto, privPass);
    }

    private static OctetString toPass(String pass, boolean required) {
        if (!required) {
            return null;
        }
        return new OctetString(pass != null ? pass : "");
    }

    private static String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "none" : s.trim();
    }

    private OID resolveAuthProtocol(String name) {
        if (name == null || name.isBlank() || "none".equalsIgnoreCase(name) || "noauth".equalsIgnoreCase(name)) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (n) {
            case "MD5", "AUTHMD5" -> AuthMD5.ID;
            case "SHA", "SHA1", "AUTHSHA" -> AuthSHA.ID;
            default -> {
                log.warn("未知 auth-protocol '{}'，回退 SHA（支持 none/MD5/SHA）", name);
                yield AuthSHA.ID;
            }
        };
    }

    private OID resolvePrivProtocol(String name) {
        if (name == null || name.isBlank() || "none".equalsIgnoreCase(name) || "nopriv".equalsIgnoreCase(name)) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (n) {
            case "DES", "PRIVDES" -> PrivDES.ID;
            case "AES", "AES128", "PRIVAES128" -> PrivAES128.ID;
            case "AES192", "PRIVAES192" -> PrivAES192.ID;
            case "AES256", "PRIVAES256" -> PrivAES256.ID;
            default -> {
                log.warn("未知 priv-protocol '{}'，回退 AES128", name);
                yield PrivAES128.ID;
            }
        };
    }

    private void processTrap(CommandResponderEvent event) {
        log.info("========================================");
        log.info("Received SNMP Trap/Inform from: {}", event.getPeerAddress());
        log.info("========================================");

        PDU pdu = event.getPDU();
        if (pdu == null) {
            log.warn("Received Trap with null PDU（若为 v3，请检查 USM 用户/引擎ID/密码是否匹配）");
            return;
        }

        try {
            String snmpVersion = resolveSnmpVersion(event, pdu);
            String pduTypeName = pduTypeName(pdu.getType());
            log.info("SNMP {} {} securityModel={} securityLevel={} securityName={}",
                    snmpVersion, pduTypeName,
                    event.getSecurityModel(),
                    event.getSecurityLevel(),
                    event.getSecurityName() != null
                            ? new String(event.getSecurityName(), StandardCharsets.UTF_8) : "-");

            // Inform 必须应答，否则设备会重传
            if (pdu.getType() == PDU.INFORM) {
                acknowledgeInform(event, pdu);
            }

            String peerIp = extractIpAddress(event.getPeerAddress());

            List<? extends VariableBinding> vbList = pdu.getVariableBindings();
            Map<String, String> trapData = new HashMap<>();
            trapData.put("peerIp", peerIp);
            trapData.put("snmpVersion", snmpVersion);
            trapData.put("pduType", pduTypeName);
            String securityName = null;
            if (event.getSecurityName() != null && event.getSecurityName().length > 0) {
                securityName = new String(event.getSecurityName(), StandardCharsets.UTF_8);
                trapData.put("securityName", securityName);
            }

            StringBuilder rawData = new StringBuilder();
            rawData.append("snmpVersion=").append(snmpVersion)
                    .append(" pduType=").append(pduTypeName);
            if (securityName != null && !securityName.isBlank()) {
                rawData.append(" securityName=").append(securityName);
            }
            rawData.append('\n');

            log.info("Variable Bindings ({}):", vbList.size());
            for (VariableBinding vb : vbList) {
                String oid = vb.getOid().toString();
                String value = vb.getVariable() != null ? vb.getVariable().toString() : "";
                log.info("  {} = {}", oid, value);
                rawData.append(oid).append(" = ").append(value).append("\n");
                parseTrapVariable(oid, value, trapData);
            }

            // SNMPv1：agent-addr + genericTrap（eNSP 大量发 v1，无 snmpTrapOID VB）
            try {
                if (pdu instanceof PDUv1 v1) {
                    trapData.put("snmpVersion", "v1");
                    Address agentAddr = v1.getAgentAddress();
                    if (agentAddr != null) {
                        String v1Agent = extractIpAddress(agentAddr);
                        if (isUsableDeviceIp(v1Agent)) {
                            trapData.put("agentAddress", v1Agent);
                        }
                    }
                    int generic = v1.getGenericTrap();
                    int specific = v1.getSpecificTrap();
                    trapData.put("genericTrap", String.valueOf(generic));
                    trapData.put("specificTrap", String.valueOf(specific));
                    if (v1.getEnterprise() != null) {
                        trapData.put("enterpriseOid", v1.getEnterprise().toString());
                    }
                    // 仅当 VB 中没有 snmpTrapOID 时，用 v1 genericTrap 补齐标准 Trap
                    // generic=6(enterpriseSpecific) 不强行拼 OID，避免误分类/抑制合并导致“告警消失”
                    if (!trapData.containsKey("trapOid") && generic >= 0 && generic <= 5) {
                        String mappedOid = mapV1GenericTrapToOid(generic, specific, null);
                        if (mappedOid != null) {
                            trapData.put("trapOid", mappedOid);
                            log.info("SNMPv1 genericTrap={} → trapOid={}", generic, mappedOid);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析 SNMPv1 PDU 扩展字段失败: {}", e.getMessage());
            }

            // 逻辑设备 IP：优先真实 agent，过滤 0.0.0.0；再回退任意非空，最后用 peer
            String agentIp = firstUsableIp(
                    trapData.get("snmpTrapAddress"),
                    trapData.get("agentAddress"),
                    extractIpFromString(trapData.getOrDefault("agentAddressRaw", ""))
            );
            if (agentIp == null) {
                agentIp = firstNonBlank(
                        trapData.get("snmpTrapAddress"),
                        trapData.get("agentAddress"),
                        extractIpFromString(trapData.getOrDefault("agentAddressRaw", ""))
                );
            }
            trapData.put("agentIp", agentIp != null ? agentIp : "");
            
            TrapInfo info = parseTrapInfo(trapData);
            enrichLinkAlarmTitle(info, trapData);
            
            String description = buildHumanReadableDescription(
                    peerIp, agentIp, trapData, info, rawData.toString());
            
            try {
                Alarm saved = alarmService.createAlarmFromTrap(
                        peerIp,
                        agentIp,
                        trapData.get("sysName"),
                        info.title,
                        description,
                        info.severity,
                        rawData.toString(),
                        info.type
                );
                log.info("Alarm created: id={} title={} type={} severity={} peer={} agent={} sysName={} trapOid={} ifName={} ifIndex={}",
                        saved != null ? saved.getId() : null,
                        info.title, info.type, info.severity, peerIp, agentIp,
                        trapData.get("sysName"), trapData.get("trapOid"),
                        trapData.get("ifName"), trapData.get("ifIndex"));

                // 恢复类 Trap：关闭对应故障告警（含处理中），瞬时恢复通知写库后自动办结
                if (saved != null && alarmService.isTransientRecoveryNotice(info.type, info.title)) {
                    try {
                        int cleared;
                        if (info.type != null && info.type.contains("接口恢复")) {
                            cleared = alarmService.clearLinkDownAlarmsOnLinkUp(
                                    saved.getDeviceId(),
                                    saved.getDeviceIp(),
                                    firstNonBlank(trapData.get("ifName"), trapData.get("ifDescr")),
                                    trapData.get("ifIndex"));
                        } else {
                            cleared = alarmService.clearFaultAlarmsOnRecoveryTrap(
                                    saved.getDeviceId(),
                                    saved.getDeviceIp(),
                                    info.type,
                                    "收到恢复 Trap「" + info.type + "」，自动关闭对应故障告警");
                            // 负载恢复时顺带清网管 PERFORMANCE 双轨
                            if (info.type != null && info.type.contains("负载恢复")) {
                                if (saved.getDeviceId() != null) {
                                    alarmService.clearPerformanceThresholdAlarm(saved.getDeviceId(), "cpu");
                                    alarmService.clearPerformanceThresholdAlarm(saved.getDeviceId(), "memory");
                                }
                            }
                        }
                        if (cleared > 0) {
                            log.info("恢复 Trap 已闭环清除 {} 条故障告警 type={}", cleared, info.type);
                        }
                        if (saved.getStatus() != Alarm.Status.CLEARED) {
                            alarmService.clearAlarm(saved.getId(),
                                    "恢复通知自动办结（已尝试关闭对应故障告警 " + cleared + " 条）");
                        }
                    } catch (Exception clearEx) {
                        log.warn("恢复 Trap 闭环清除失败 type={}: {}", info.type, clearEx.getMessage());
                    }
                }
            } catch (Exception createEx) {
                log.error("写入 Trap 告警失败 title={} type={} peer={} agent={}: {}",
                        info.title, info.type, peerIp, agentIp, createEx.getMessage(), createEx);
                throw createEx;
            }
            log.info("========================================");
            
            String trapOid = trapData.get("trapOid");
            String topoIp = firstNonBlank(agentIp, peerIp);
            if (trapOid != null && (trapOid.contains("1.0.8802") || trapOid.contains("1.3.6.1.4.1.2011.5.25.134"))) {
                log.info("检测到LLDP相关Trap，开始处理拓扑更新...");
                processLLDPTrap(topoIp, trapData);
            }
            
        } catch (Exception e) {
            log.error("Error processing Trap: {}", e.getMessage(), e);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** 识别 Trap 报文版本：v1 / v2c / v3 */
    private String resolveSnmpVersion(CommandResponderEvent event, PDU pdu) {
        if (pdu instanceof PDUv1) {
            return "v1";
        }
        if (pdu instanceof ScopedPDU) {
            return "v3";
        }
        int mpm = event.getMessageProcessingModel();
        if (mpm == MessageProcessingModel.MPv3) {
            return "v3";
        }
        if (mpm == MessageProcessingModel.MPv2c) {
            return "v2c";
        }
        if (mpm == MessageProcessingModel.MPv1) {
            return "v1";
        }
        // SecurityModel: 1=v1, 2=v2c, 3=USM(v3)
        int sm = event.getSecurityModel();
        if (sm == 3) {
            return "v3";
        }
        if (sm == 2) {
            return "v2c";
        }
        if (sm == 1) {
            return "v1";
        }
        return "v2c";
    }

    private static String pduTypeName(int type) {
        return switch (type) {
            case PDU.TRAP -> "TRAP(v2)";
            case PDU.V1TRAP -> "TRAP(v1)";
            case PDU.INFORM -> "INFORM";
            case PDU.REPORT -> "REPORT";
            case PDU.RESPONSE -> "RESPONSE";
            default -> "PDU(" + type + ")";
        };
    }

    /** SNMPv2c/v3 Inform 必须回 RESPONSE，否则设备反复重传 */
    private void acknowledgeInform(CommandResponderEvent event, PDU request) {
        try {
            PDU response = new PDU(request);
            response.setType(PDU.RESPONSE);
            response.setErrorStatus(PDU.noError);
            response.setErrorIndex(0);
            StatusInformation statusInformation = new StatusInformation();
            StateReference ref = event.getStateReference();
            event.getMessageDispatcher().returnResponsePdu(
                    event.getMessageProcessingModel(),
                    event.getSecurityModel(),
                    event.getSecurityName(),
                    event.getSecurityLevel(),
                    response,
                    event.getMaxSizeResponsePDU(),
                    ref,
                    statusInformation);
            log.info("已应答 INFORM");
        } catch (MessageException e) {
            log.warn("应答 INFORM 失败: {}", e.getMessage());
        }
    }

    /** ifOperStatus: 1=up, 2=down（兼容 INTEGER: 前缀与英文） */
    private boolean isOperDown(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("integer:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        }
        return "2".equals(s) || s.contains("down");
    }

    private boolean isOperUp(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("integer:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        }
        return "1".equals(s) || (s.contains("up") && !s.contains("down"));
    }
    
    private void processLLDPTrap(String deviceIp, Map<String, String> trapData) {
        try {
            log.info("开始处理LLDP Trap，所有数据: {}", trapData);
            
            String neighborIp = extractNeighborIpFromLLDP(trapData);
            String localPort = trapData.get("lldpPort");
            String neighborPort = trapData.get("lldpRemains");
            
            // 尝试从华为私有LLDP MIB获取
            if (neighborIp == null || neighborIp.isEmpty()) {
                neighborIp = extractHuaweiLLDPNeighborIp(trapData);
                localPort = extractHuaweiLLDPLocalPort(trapData);
                neighborPort = extractHuaweiLLDPNeighborPort(trapData);
            }
            
            if (neighborIp != null && !neighborIp.isEmpty()) {
                log.info("从LLDP Trap提取邻居信息: 设备={}, 邻居={}, 本地端口={}, 邻居端口={}", 
                        deviceIp, neighborIp, localPort, neighborPort);
                
                topologyDiscoveryService.updateLinkFromLLDP(deviceIp, neighborIp, localPort, neighborPort);
            } else {
                log.warn("无法从LLDP Trap提取邻居IP地址，尝试手动添加或使用其他方式获取拓扑信息");
            }
        } catch (Exception e) {
            log.error("处理LLDP Trap失败: {}", e.getMessage(), e);
        }
    }
    
    private String extractHuaweiLLDPNeighborIp(Map<String, String> trapData) {
        // 尝试从各种可能的OID中提取邻居IP
        for (Map.Entry<String, String> entry : trapData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            // 检查OID是否包含华为LLDP相关标识
            if (key.contains("134") || key.contains("231")) {
                if (isValidIp(value)) {
                    log.info("从华为LLDP OID {} 提取到邻居IP: {}", key, value);
                    return value;
                }
                // 尝试从value中提取IP
                String extractedIp = extractIpFromString(value);
                if (extractedIp != null) {
                    log.info("从华为LLDP OID {} 的值 {} 中提取到IP: {}", key, value, extractedIp);
                    return extractedIp;
                }
            }
        }
        
        // 尝试通过sysName匹配
        String sysName = trapData.get("sysName");
        if (sysName != null) {
            log.info("尝试通过sysName {} 匹配邻居设备", sysName);
            topologyDiscoveryService.findDeviceByName(sysName).ifPresent(device -> {
                log.info("通过sysName匹配到设备IP: {}", device.getIpAddress());
                // 这里可以设置一个临时值或返回
            });
        }
        
        return null;
    }
    
    private String extractHuaweiLLDPLocalPort(Map<String, String> trapData) {
        for (Map.Entry<String, String> entry : trapData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            if ((key.contains("134") || key.contains("231")) && 
                (value.toLowerCase().contains("gigabit") || value.toLowerCase().contains("ethernet") || 
                 value.toLowerCase().contains("port") || value.toLowerCase().contains("if"))) {
                log.info("从华为LLDP OID {} 提取到本地端口: {}", key, value);
                return value;
            }
        }
        
        String ifName = trapData.get("ifName");
        if (ifName != null) {
            return ifName;
        }
        
        String ifDescr = trapData.get("ifDescr");
        if (ifDescr != null) {
            return ifDescr;
        }
        
        return "Unknown";
    }
    
    private String extractHuaweiLLDPNeighborPort(Map<String, String> trapData) {
        return "Unknown"; // 简化，后续可增强
    }
    
    private String extractIpFromString(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 简单的IP提取逻辑
        String[] parts = text.split("[^0-9.]");
        for (String part : parts) {
            if (isValidIp(part)) {
                return part;
            }
        }
        
        return null;
    }
    
    private String extractNeighborIpFromLLDP(Map<String, String> trapData) {
        if (trapData.containsKey("userIp") && !trapData.get("userIp").isEmpty()) {
            String userIp = trapData.get("userIp");
            if (isValidIp(userIp)) {
                log.debug("从userIp提取邻居IP: {}", userIp);
                return userIp;
            }
        }
        
        if (trapData.containsKey("ospfNbrIpAddr") && !trapData.get("ospfNbrIpAddr").isEmpty()) {
            String ospfNbrIp = trapData.get("ospfNbrIpAddr");
            if (isValidIp(ospfNbrIp)) {
                log.debug("从ospfNbrIpAddr提取邻居IP: {}", ospfNbrIp);
                return ospfNbrIp;
            }
        }
        
        String lldpRemains = trapData.get("lldpRemains");
        if (lldpRemains != null && isValidIp(lldpRemains)) {
            log.debug("从lldpRemains提取邻居IP: {}", lldpRemains);
            return lldpRemains;
        }
        
        String lldpPort = trapData.get("lldpPort");
        if (lldpPort != null && isValidIp(lldpPort)) {
            log.debug("从lldpPort提取邻居IP: {}", lldpPort);
            return lldpPort;
        }
        
        return null;
    }
    
    private boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        String[] parts = ip.split("[.\\s/]");
        if (parts.length == 4) {
            try {
                int count = 0;
                for (String part : parts) {
                    int num = Integer.parseInt(part.trim());
                    if (num >= 0 && num <= 255) {
                        count++;
                    }
                }
                if (count == 4) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        
        String regex = "\\b((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b";
        return ip.matches(regex);
    }
    
    private void parseTrapVariable(String oid, String value, Map<String, String> trapData) {
        if (oid == null) return;

        // SNMPv2 snmpTrapAddress — 设备真实 IP，常不同于 UDP 报文源（Cloud/NAT）
        if (oid.contains("1.3.6.1.6.3.18.1.3")) {
            String ip = AlarmService.normalizeDeviceIp(value);
            if (ip == null || ip.isBlank()) {
                ip = extractIpFromString(value);
            }
            if (ip != null && !ip.isBlank()) {
                trapData.put("snmpTrapAddress", ip);
                trapData.put("agentAddress", ip);
            } else {
                trapData.put("agentAddressRaw", value);
            }
        }

        // snmpTrapOID（规范化 value，去掉 OID: 前缀等）
        if (oid.contains("1.3.6.1.6.3.1.1.4.1.0")) {
            trapData.put("trapOid", normalizeOid(value));
        }
        
        if (oid.contains("1.3.6.1.2.1.1.5.0") || oid.endsWith(".1.3.6.1.2.1.1.5.0")) {
            trapData.put("sysName", value);
        }
        // 部分 Trap 用 sysName 短 OID 片段
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.1\\.5\\.0$") || oid.contains(".1.2.1.1.5.0")) {
            trapData.put("sysName", value);
        }
        
        if (oid.contains("1.3.6.1.2.1.1.3.0")) {
            trapData.put("sysUptime", value);
        }
        
        if (oid.contains("1.3.6.1.2.1.1.1.0")) {
            trapData.put("sysDescr", value);
        }
        
        if (oid.contains("1.3.6.1.2.1.1.6.0")) {
            trapData.put("sysLocation", value);
        }
        
        // 精确匹配 ifEntry 列，避免 1.2.1.2.2.1.1 误匹配 1.2.1.2.2.1.10(ifInOctets) 等
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.1(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.1(\\..+)?$")) {
            putIfIndex(trapData, value, oid);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.2(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.2(\\..+)?$")) {
            trapData.put("ifDescr", value);
            putIfIndexFromOidSuffix(trapData, oid);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.7(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.7(\\..+)?$")) {
            trapData.put("ifAdminStatus", value);
            putIfIndexFromOidSuffix(trapData, oid);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.8(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.8(\\..+)?$")) {
            trapData.put("ifOperStatus", value);
            putIfIndexFromOidSuffix(trapData, oid);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.3(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.2\\.2\\.1\\.3(\\..+)?$")) {
            trapData.put("ifType", value);
            putIfIndexFromOidSuffix(trapData, oid);
        }
        // ifName: IF-MIB ifXTable 1.3.6.1.2.1.31.1.1.1.1.<ifIndex>
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.31\\.1\\.1\\.1\\.1\\.\\d+$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.31\\.1\\.1\\.1\\.1\\.\\d+$")) {
            trapData.put("ifName", value);
            putIfIndexFromOidSuffix(trapData, oid);
        }

        if (oid.contains("1.3.6.1.4.1.2011.5.25.207.1.2.1.1.2")) {
            trapData.put("userType", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.207.1.2.1.1.3")) {
            trapData.put("userName", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.207.1.2.1.1.4")) {
            trapData.put("userTerminal", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.207.1.2.1.1.5")) {
            trapData.put("userIp", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.4.1.1.5")) {
            trapData.put("cpuUsage", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.4.1.1.7")) {
            trapData.put("memUsage", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.31.1.1.1.6")) {
            trapData.put("ifInOctets", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.31.1.1.1.10")) {
            trapData.put("ifOutOctets", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.201.1.12")) {
            trapData.put("arpAttackIp", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.201.1.13")) {
            trapData.put("arpAttackMac", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.202.1.4")) {
            trapData.put("configChangedBy", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.202.1.5")) {
            trapData.put("configChangedFrom", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.202.1.6")) {
            trapData.put("configChangedTo", value);
        }
        
        if (oid.contains("1.0.8802.1.1.2.1.2.2.0")) {
            trapData.put("lldpPort", value);
        }
        
        if (oid.contains("1.0.8802.1.1.2.1.2.3.0")) {
            trapData.put("lldpRemains", value);
        }
        
        if (oid.contains("1.0.8802.1.1.2.1.2.4.0")) {
            trapData.put("lldpRemAge", value);
        }
        
        if (oid.contains("1.0.8802.1.1.2.1.2.5.0")) {
            trapData.put("lldpRemFrames", value);
        }
        
        // OSPF Router-ID（带/不带 .0）
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.14\\.1\\.1(\\.0)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.14\\.1\\.1(\\.0)?$")
                || oid.contains("1.3.6.1.2.1.14.1.1.0")) {
            trapData.put("ospfRouterId", value);
        }

        // OSPF 邻居表列（实例后缀可变；用列号精确匹配，避免 14.7.1.12 误匹配 14.7.1.1/2）
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.1(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.1(\\..+)?$")) {
            trapData.put("ospfNbrIpAddr", value);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.2(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.2(\\..+)?$")) {
            trapData.put("ospfNbrState", value);
        }
        if (oid.matches(".*\\.1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.12(\\..+)?$")
                || oid.matches("^1\\.3\\.6\\.1\\.2\\.1\\.14\\.7\\.1\\.12(\\..+)?$")) {
            trapData.put("ospfNbrEvents", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.155.7.1.1.1")) {
            trapData.put("hwOspfNbrState", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.155.7.1.2.1")) {
            trapData.put("hwOspfNbrIfName", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.155.30.2.0")) {
            trapData.put("ospfNbrAuthFailReason", value);
        }

        // OSPF LSDB 表：Trap 常带长实例后缀，按列 OID 前缀匹配
        if (oid.contains("1.3.6.1.2.1.14.4.1.1.")) {
            trapData.put("ospfLsdbAreaId", value);
        }
        if (oid.contains("1.3.6.1.2.1.14.4.1.2.")) {
            trapData.put("ospfLsdbType", value);
        }
        if (oid.contains("1.3.6.1.2.1.14.4.1.3.")) {
            trapData.put("ospfLsdbLsId", value);
        }
        if (oid.contains("1.3.6.1.2.1.14.4.1.4.")) {
            trapData.put("ospfLsdbRouterId", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.155.3.1.1.1")) {
            trapData.put("hwOspfNbrFullState", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.155.3.1.2.1")) {
            trapData.put("hwOspfNbrIfName", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.42.4.1.19.1.1.0")) {
            trapData.put("hwIfVlanIndex", value);
        }
        
        if (oid.contains("1.3.6.1.4.1.2011.5.25.42.4.1.20.1.1.0.1")
                || oid.contains("1.3.6.1.4.1.2011.5.25.42.4.1.20")) {
            trapData.put("hwIfOperStatus", value);
        }
    }

    private void putIfIndex(Map<String, String> trapData, String value, String oid) {
        String idx = value != null ? value.trim() : "";
        // SNMP4J 有时把 Integer32 打成 "INTEGER: 5"
        if (idx.toUpperCase().startsWith("INTEGER:")) {
            idx = idx.substring(idx.indexOf(':') + 1).trim();
        }
        if (!idx.isBlank() && idx.matches("\\d+")) {
            trapData.put("ifIndex", idx);
            return;
        }
        putIfIndexFromOidSuffix(trapData, oid);
    }

    private void putIfIndexFromOidSuffix(Map<String, String> trapData, String oid) {
        if (trapData.containsKey("ifIndex") || oid == null) {
            return;
        }
        int dot = oid.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < oid.length()) {
            String suffix = oid.substring(dot + 1).trim();
            if (suffix.matches("\\d+")) {
                trapData.put("ifIndex", suffix);
            }
        }
    }
    
    /**
     * SNMPv1 generic-trap → 标准 snmpTrapOID（仅 0–5；enterpriseSpecific 不在此拼造）。
     */
    private String mapV1GenericTrapToOid(int generic, int specific, org.snmp4j.smi.OID enterprise) {
        return switch (generic) {
            case 0 -> "1.3.6.1.6.3.1.1.5.1";
            case 1 -> "1.3.6.1.6.3.1.1.5.2";
            case 2 -> "1.3.6.1.6.3.1.1.5.3"; // linkDown
            case 3 -> "1.3.6.1.6.3.1.1.5.4"; // linkUp
            case 4 -> "1.3.6.1.6.3.1.1.5.5";
            case 5 -> "1.3.6.1.6.3.1.1.5.6";
            default -> null;
        };
    }

    /** 标准化 OID 字符串，便于精确匹配 */
    private String normalizeOid(String oid) {
        if (oid == null) {
            return "";
        }
        String s = oid.trim();
        if (s.startsWith("oid=") || s.startsWith("OID:")) {
            int i = s.indexOf('=');
            if (i < 0) {
                i = s.indexOf(':');
            }
            if (i >= 0 && i + 1 < s.length()) {
                s = s.substring(i + 1).trim();
            }
        }
        // 去掉可能的引号/空格
        s = s.replace("\"", "").replace("'", "").replace(" ", "");
        return s;
    }

    private boolean oidIs(String trapOid, String expected) {
        String n = normalizeOid(trapOid);
        String e = normalizeOid(expected);
        return n.equals(e) || n.endsWith("." + e) || n.endsWith(e);
    }

    private boolean isUsableDeviceIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String n = AlarmService.normalizeDeviceIp(ip);
        if (n == null || n.isBlank() || "unknown".equalsIgnoreCase(n)) {
            return false;
        }
        return !"0.0.0.0".equals(n) && !"127.0.0.1".equals(n) && !n.startsWith("127.");
    }

    private String firstUsableIp(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (isUsableDeviceIp(v)) {
                return AlarmService.normalizeDeviceIp(v);
            }
        }
        return null;
    }

    private TrapInfo parseTrapInfo(Map<String, String> trapData) {
        TrapInfo info = new TrapInfo();
        String trapOid = trapData.get("trapOid");

        if (trapOid == null || trapOid.isBlank()) {
            // 无 OID：仅按 v1 genericTrap 识别标准链路事件（不再用 ifOperStatus 猜测，避免误伤）
            String generic = trapData.get("genericTrap");
            if ("2".equals(generic)) {
                info.title = "接口断开告警";
                info.severity = Alarm.Severity.CRITICAL;
                info.type = "链路告警-接口断开";
                return info;
            }
            if ("3".equals(generic)) {
                info.title = "接口恢复通知";
                info.severity = Alarm.Severity.INFO;
                info.type = "链路告警-接口恢复";
                return info;
            }
            return info;
        }

        // 标准 snmpTraps：精确匹配，避免 contains 把 ifType(1.2.1.2.2.1.3) 误判成 LinkUp
        if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.1")) {
            info.title = "设备冷启动";
            info.severity = Alarm.Severity.MAJOR;
            info.type = "系统告警-冷启动";
        } else if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.2")) {
            info.title = "设备热启动";
            info.severity = Alarm.Severity.MAJOR;
            info.type = "系统告警-热启动";
        } else if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.3")) {
            info.title = "接口断开告警";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "链路告警-接口断开";
        } else if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.4")) {
            info.title = "接口恢复通知";
            info.severity = Alarm.Severity.INFO;
            info.type = "链路告警-接口恢复";
        } else if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.5")) {
            info.title = "认证失败告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "安全告警-认证失败";
        } else if (oidIs(trapOid, "1.3.6.1.6.3.1.1.5.6")) {
            info.title = "EGP邻居丢失";
            info.severity = Alarm.Severity.MAJOR;
            info.type = "路由告警-邻居丢失";
        } else if (trapOid.contains("1.3.6.1.4.1.9")) {
            info.title = "思科设备告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "厂商告警-思科";
        } else if (isOspfTrapOid(trapOid)) {
            // OSPF-MIB 标准通知：精确映射，避免落入「通用Trap告警」
            applyOspfTrapInfo(info, trapOid, trapData);
        } else if (trapOid.contains("1.3.6.1.2.1.15")) {
            info.title = "BGP邻居状态变更";
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-BGP状态变更";
        } else if (trapOid.contains("1.3.6.1.4.1.2636.4.5")) {
            info.title = "Juniper设备告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "厂商告警-Juniper";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.1.1")) {
            info.title = "系统启动完成";
            info.severity = Alarm.Severity.INFO;
            info.type = "系统告警-启动完成";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.1.2")) {
            info.title = "设备整机重启";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "系统告警-设备重启";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.2.2")) {
            info.title = "单板运行异常故障";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "硬件告警-单板异常";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.2.3")) {
            info.title = "单板热插拔事件";
            info.severity = Alarm.Severity.INFO;
            info.type = "硬件告警-单板插拔";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.3.2")) {
            info.title = "电源运行异常";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "硬件告警-电源异常";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.3.3")) {
            info.title = "电源模块故障";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "硬件告警-电源故障";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.3.4")) {
            info.title = "电源故障恢复正常";
            info.severity = Alarm.Severity.INFO;
            info.type = "硬件告警-电源恢复";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.3.5")) {
            info.title = "风扇模块故障";
            info.severity = Alarm.Severity.WARNING;
            info.type = "硬件告警-风扇故障";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.3.6")) {
            info.title = "风扇故障恢复正常";
            info.severity = Alarm.Severity.INFO;
            info.type = "硬件告警-风扇恢复";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.4.1.2")) {
            info.title = "CPU使用率过高";
            info.severity = Alarm.Severity.WARNING;
            info.type = "性能告警-CPU过载";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.4.1.3")) {
            info.title = "内存使用率过高";
            info.severity = Alarm.Severity.WARNING;
            info.type = "性能告警-内存过载";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.4.1.4")) {
            info.title = "CPU内存负载恢复正常";
            info.severity = Alarm.Severity.INFO;
            info.type = "性能告警-负载恢复";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.42.4.2.1")) {
            // 华为接口状态变更：按 Trap 中携带的 operStatus 判定断开/恢复（真实 VB，非拓扑构造）
            String oper = firstNonBlank(trapData.get("ifOperStatus"), trapData.get("hwIfOperStatus"));
            if (isOperDown(oper)) {
                info.title = "接口断开告警";
                info.severity = Alarm.Severity.CRITICAL;
                info.type = "链路告警-接口断开";
            } else if (isOperUp(oper)) {
                info.title = "接口恢复通知";
                info.severity = Alarm.Severity.INFO;
                info.type = "链路告警-接口恢复";
            } else {
                info.title = "华为接口状态变更";
                info.severity = Alarm.Severity.WARNING;
                info.type = "接口告警-华为接口变更";
            }
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.114.1.1")) {
            info.title = "NTP服务器不可达";
            info.severity = Alarm.Severity.WARNING;
            info.type = "服务告警-NTP同步失败";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.155.3.1.1")) {
            info.title = "OSPF路由告警触发";
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF触发";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.155.3.1.2")) {
            info.title = "OSPF路由告警恢复";
            info.severity = Alarm.Severity.INFO;
            info.type = "路由告警-OSPF恢复";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.191.1.1")) {
            info.title = "CPU负载告警标识";
            info.severity = Alarm.Severity.WARNING;
            info.type = "性能告警-CPU标识";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.191.3.1")) {
            info.title = "CPU利用率阈值越限";
            info.severity = Alarm.Severity.WARNING;
            info.type = "性能告警-CPU阈值";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.201.1.2")) {
            info.title = "DHCP地址池用户上限告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "服务告警-DHCP用户超限";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.201.1.11")) {
            info.title = "ARP攻击异常告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "安全告警-ARP攻击";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.202.1.2")) {
            info.title = "设备配置文件变更告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "配置告警-配置变更";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.202.1.3")) {
            info.title = "设备配置保存成功";
            info.severity = Alarm.Severity.INFO;
            info.type = "配置告警-配置保存";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.207.1.2")) {
            info.title = "用户登录设备";
            info.severity = Alarm.Severity.INFO;
            info.type = "用户管理-用户登录";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.207.1.3")) {
            info.title = "用户退出设备登录";
            info.severity = Alarm.Severity.INFO;
            info.type = "用户管理-用户退出";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.207.2.2")) {
            info.title = "用户登录/退出综合事件";
            info.severity = Alarm.Severity.INFO;
            info.type = "用户管理-登录退出";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.31.1.1.6")) {
            info.title = "端口接口流量异常";
            info.severity = Alarm.Severity.WARNING;
            info.type = "性能告警-流量异常";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.47.3.1")) {
            info.title = "BFD会话状态断开";
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "链路告警-BFD断开";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.5.25.47.3.2")) {
            info.title = "BFD会话状态恢复";
            info.severity = Alarm.Severity.INFO;
            info.type = "链路告警-BFD恢复";
        } else if (trapOid.contains("1.3.6.1.4.1.2011.10.2.26.1")) {
            info.title = "设备堆叠状态变更";
            info.severity = Alarm.Severity.WARNING;
            info.type = "系统告警-堆叠变更";
        } else if (trapOid.contains("1.3.6.1.4.1.2011")) {
            info.title = "华为设备事件";
            info.severity = Alarm.Severity.WARNING;
            info.type = "厂商事件-华为";
        } else if (trapOid.contains("1.0.8802.1.1.2.0.0.1")) {
            info.title = "802.1X 认证事件";
            info.severity = Alarm.Severity.INFO;
            info.type = "安全告警-802.1X认证";
        } else if (trapOid.contains("1.3.6.1.2.1.17.0.2")) {
            info.title = "STP 拓扑变更告警";
            info.severity = Alarm.Severity.WARNING;
            info.type = "交换告警-STP拓扑变更";
        } else {
            info.title = "未识别Trap · " + shortOidTail(trapOid);
            info.severity = Alarm.Severity.WARNING;
            info.type = "通用Trap告警";
        }
        
        return info;
    }

    private static boolean isOspfTrapOid(String trapOid) {
        String n = trapOid == null ? "" : trapOid.trim();
        return n.contains("1.3.6.1.2.1.14.16.2.");
    }

    /**
     * OSPF-MIB 通知（RFC 4750）：1.3.6.1.2.1.14.16.2.*
     * 例：.13 = ospfMaxAgeLsa（你提供的这条）。
     */
    private void applyOspfTrapInfo(TrapInfo info, String trapOid, Map<String, String> trapData) {
        String lsId = firstNonBlank(trapData.get("ospfLsdbLsId"));
        String routerId = firstNonBlank(trapData.get("ospfRouterId"), trapData.get("ospfLsdbRouterId"));
        String suffix = "";
        if (lsId != null) {
            suffix = " · LSID=" + lsId;
        } else if (routerId != null) {
            suffix = " · RouterId=" + routerId;
        }

        if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.2")) {
            info.title = "OSPF邻居状态变更" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF邻居状态变更";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.3")) {
            info.title = "OSPF虚连接邻居状态变更" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF虚连接邻居";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.4")) {
            info.title = "OSPF接口配置错误" + suffix;
            info.severity = Alarm.Severity.MAJOR;
            info.type = "路由告警-OSPF接口配置错误";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.6")) {
            info.title = "OSPF接口认证失败" + suffix;
            info.severity = Alarm.Severity.MAJOR;
            info.type = "路由告警-OSPF认证失败";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.8")) {
            info.title = "OSPF接口收到错误报文" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF错误报文";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.10")) {
            info.title = "OSPF报文重传" + suffix;
            info.severity = Alarm.Severity.INFO;
            info.type = "路由告警-OSPF重传";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.12")) {
            info.title = "OSPF产生LSA" + suffix;
            info.severity = Alarm.Severity.INFO;
            info.type = "路由告警-OSPF产生LSA";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.13")) {
            info.title = "OSPF LSA达到MaxAge" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF-LSA-MaxAge";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.14")) {
            info.title = "OSPF链路状态库溢出" + suffix;
            info.severity = Alarm.Severity.CRITICAL;
            info.type = "路由告警-OSPF-LSDB溢出";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.15")) {
            info.title = "OSPF链路状态库接近溢出" + suffix;
            info.severity = Alarm.Severity.MAJOR;
            info.type = "路由告警-OSPF-LSDB将满";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.16")) {
            info.title = "OSPF接口状态变更" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF接口状态变更";
        } else if (oidIs(trapOid, "1.3.6.1.2.1.14.16.2.1")) {
            info.title = "OSPF虚连接接口状态变更" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF虚连接接口";
        } else {
            info.title = "OSPF路由事件" + suffix;
            info.severity = Alarm.Severity.WARNING;
            info.type = "路由告警-OSPF";
        }
    }

    private static String shortOidTail(String oid) {
        if (oid == null || oid.isBlank()) {
            return "-";
        }
        String n = oid.trim();
        int idx = n.lastIndexOf('.');
        if (idx > 0 && idx + 1 < n.length()) {
            // 取末两段，便于对照 snmpTrapOID
            String last = n.substring(idx + 1);
            String head = n.substring(0, idx);
            int idx2 = head.lastIndexOf('.');
            if (idx2 >= 0) {
                return head.substring(idx2 + 1) + "." + last;
            }
            return last;
        }
        return n;
    }
    
    private String buildHumanReadableDescription(String peerIp, String agentIp,
                                                 Map<String, String> trapData, TrapInfo info,
                                                 String rawData) {
        TrapOidClassifier.Classified classified = null;
        if (info != null && info.type != null && !TrapOidClassifier.needsRefine(info.type)) {
            classified = new TrapOidClassifier.Classified(
                    info.title != null ? info.title : "Trap事件",
                    info.type,
                    info.severity != null ? info.severity : Alarm.Severity.WARNING);
        } else if (trapData != null && trapData.get("trapOid") != null) {
            classified = TrapOidClassifier.classify(trapData.get("trapOid"));
        }
        String built = TrapOidClassifier.buildReadableDescription(
                rawData, classified, peerIp, agentIp, null, null);
        if (built != null && !built.isBlank()) {
            return built;
        }
        // 兜底
        return (info != null && info.title != null ? info.title : "Trap事件")
                + (peerIp != null ? "，来源 " + peerIp : "");
    }
    
    private String parseOspfNbrState(String state) {
        try {
            int stateVal = Integer.parseInt(state.trim());
            return switch (stateVal) {
                case 1 -> "未启动(Down)";
                case 2 -> "尝试(Attempt)";
                case 3 -> "初始化(Init)";
                case 4 -> "双向通信(2-Way)";
                case 5 -> "交换启动(ExStart)";
                case 6 -> "交换中(Exchange)";
                case 7 -> "加载中(Loading)";
                case 8 -> "完全邻接(Full)";
                default -> "未知(" + state + ")";
            };
        } catch (Exception e) {
            return state;
        }
    }

    private String parseOspfLsdbType(String type) {
        if (type == null) {
            return "-";
        }
        try {
            int t = Integer.parseInt(type.trim());
            return switch (t) {
                case 1 -> "Router-LSA(1)";
                case 2 -> "Network-LSA(2)";
                case 3 -> "Summary-LSA(3)";
                case 4 -> "ASBR-Summary-LSA(4)";
                case 5 -> "AS-External-LSA(5)";
                case 7 -> "NSSA-LSA(7)";
                default -> "类型(" + t + ")";
            };
        } catch (Exception e) {
            return type;
        }
    }
    
    private String parseHuaweiIfStatus(String status) {
        try {
            int statusVal = Integer.parseInt(status);
            switch (statusVal) {
                case 0: return "正常(up)";
                case 1: return "故障(down)";
                case 2: return "异常(abnormal)";
                default: return "未知(" + status + ")";
            }
        } catch (Exception e) {
            return status;
        }
    }
    
    private String parseInterfaceAdminStatus(String status) {
        switch (status) {
            case "1": return "开启(up)";
            case "2": return "关闭(down)";
            case "3": return "测试(testing)";
            default: return "未知(" + status + ")";
        }
    }
    
    private String parseInterfaceOperStatus(String status) {
        switch (status) {
            case "1": return "正常(up)";
            case "2": return "断开(down)";
            case "3": return "测试中(testing)";
            case "4": return "未知(unknown)";
            case "5": return "休眠(dormant)";
            case "6": return "未配置(notPresent)";
            case "7": return "下层链路断开(lowerLayerDown)";
            default: return "未知(" + status + ")";
        }
    }
    
    private String parseInterfaceType(String type) {
        switch (type) {
            case "1": return "其他";
            case "2": return "常规接口";
            case "6": return "以太网";
            case "7": return "ATM";
            case "9": return "令牌环";
            case "11": return "PPP";
            case "16": return "软件回环";
            case "23": return "PPPoE";
            case "53": return "虚拟接口";
            case "117": return "千兆以太网";
            default: return "类型" + type;
        }
    }
    
    private String formatUptime(String timeticks) {
        try {
            long ticks = Long.parseLong(timeticks);
            long seconds = ticks / 100;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;
            
            seconds = seconds % 60;
            minutes = minutes % 60;
            hours = hours % 24;
            
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append("天");
            if (hours > 0) sb.append(hours).append("小时");
            if (minutes > 0) sb.append(minutes).append("分");
            if (seconds > 0) sb.append(seconds).append("秒");
            
            return sb.length() > 0 ? sb.toString() : "0秒";
        } catch (Exception e) {
            return timeticks;
        }
    }
    
    /** 链路类告警标题附带接口，避免同设备多接口被 5 分钟抑制合并成一条而“看不见” */
    private void enrichLinkAlarmTitle(TrapInfo info, Map<String, String> trapData) {
        if (info == null || info.type == null || !info.type.startsWith("链路告警")) {
            return;
        }
        String ifName = firstNonBlank(trapData.get("ifName"), trapData.get("ifDescr"));
        String ifIndex = trapData.get("ifIndex");
        String suffix;
        if (ifName != null && !ifName.isBlank()) {
            suffix = ifName.trim();
            if (ifIndex != null && !ifIndex.isBlank()) {
                suffix = suffix + "(ifIndex=" + ifIndex.trim() + ")";
            }
        } else if (ifIndex != null && !ifIndex.isBlank()) {
            suffix = "ifIndex=" + ifIndex.trim();
        } else {
            return;
        }
        if (info.title != null && !info.title.contains(suffix)) {
            info.title = info.title + " · " + suffix;
        }
    }

    private static class TrapInfo {
        String title = "设备告警";
        Alarm.Severity severity = Alarm.Severity.WARNING;
        String type = "通用告警";
    }

    private String extractIpAddress(Address address) {
        if (address == null) {
            return "unknown";
        }
        try {
            if (address instanceof IpAddress) {
                java.net.InetAddress inet = ((IpAddress) address).getInetAddress();
                if (inet != null) {
                    return AlarmService.normalizeDeviceIp(inet.getHostAddress());
                }
            }
        } catch (Exception e) {
            log.warn("解析 Trap 源地址失败: {}", e.getMessage());
        }
        return AlarmService.normalizeDeviceIp(address.toString());
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping SNMP Trap receiver...");
        running = false;
        try {
            if (snmp != null) {
                snmp.close();
            }
            if (transport != null) {
                transport.close();
            }
            log.info("SNMP Trap receiver stopped");
        } catch (IOException e) {
            log.error("Error stopping SNMP Trap receiver: {}", e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getTrapPort() {
        return trapProperties != null ? trapProperties.getPort() : 162;
    }
}
