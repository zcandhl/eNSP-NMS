package com.ensp.nms.service;

import com.ensp.nms.config.NmsAlarmProperties;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.event.AlarmCreatedEvent;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.snmp.TrapOidClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class AlarmService {

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceIpAliasService deviceIpAliasService;

    @Autowired
    private PerformanceAlertRepository performanceAlertRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private NmsAlarmProperties alarmProperties;

    public boolean isAckClosesType(String trapType) {
        return alarmProperties != null && alarmProperties.isAckClosesType(trapType);
    }

    /** 是否阅知关闭：trapType 白名单、INFO 级别，或标题命中常见阅知事件 */
    public boolean isAckClosesAlarm(Alarm alarm) {
        if (alarm == null) {
            return false;
        }
        // 明确排除需跟进类（即使误标 INFO 也不阅知关闭）
        String type = alarm.getTrapType() != null ? alarm.getTrapType().trim() : "";
        String title = alarm.getTitle() != null ? alarm.getTitle().trim() : "";
        if (type.contains("认证失败") || title.contains("认证失败")
                || "DEVICE_OFFLINE".equals(type) || title.contains("设备离线")
                || type.contains("冷启动") || title.contains("冷启动")
                || type.contains("CPU") || type.contains("流量异常")
                || type.contains("BFD断开") || type.contains("linkDown")
                || type.contains("链路告警-接口断开") || type.contains("接口断开")) {
            return false;
        }
        if (isAckClosesType(type)) {
            return true;
        }
        if (alarm.getSeverity() == Alarm.Severity.INFO) {
            return true;
        }
        if (title.isBlank()) {
            return false;
        }
        return title.contains("用户登录/退出")
                || title.startsWith("用户登录")
                || title.startsWith("用户退出")
                || title.contains("OSPF产生LSA")
                || title.contains("OSPF LSA达到MaxAge")
                || title.contains("STP")
                || title.contains("华为接口状态变更")
                || title.contains("802.1X")
                || title.contains("热启动")
                || title.contains("接口恢复")
                || title.contains("配置保存");
    }

    public void enrichAckCloses(Alarm alarm) {
        if (alarm == null) {
            return;
        }
        alarm.setAckCloses(isAckClosesAlarm(alarm));
    }

    public void enrichAckCloses(List<Alarm> alarms) {
        if (alarms == null || alarms.isEmpty()) {
            return;
        }
        for (Alarm a : alarms) {
            enrichAckCloses(a);
        }
    }

    public Alarm createAlarm(String deviceIp, String title, String description,
                             Alarm.Severity severity, String rawData, String trapType) {
        return createAlarmFromTrap(deviceIp, null, null, title, description, severity, rawData, trapType);
    }

    /**
     * Trap 告警：管理 IP → 接口别名(IP) → sysName；
     * 命中后 deviceIp 一律写设备管理 IP（非 Trap 接口地址 / Cloud 源地址）。
     * 同设备+类型+标题在抑制窗口内重复时合并计数，避免抖动刷屏。
     */
    @Transactional
    public Alarm createAlarmFromTrap(String peerIp, String agentIp, String sysName,
                                     String title, String description,
                                     Alarm.Severity severity, String rawData, String trapType) {
        String peer = normalizeDeviceIp(peerIp);
        String agent = normalizeDeviceIp(agentIp);

        Optional<Device> matched = resolveManagedDevice(peer, agent, sysName);
        String deviceIp = matched.map(d -> normalizeDeviceIp(d.getIpAddress()))
                .orElse(firstNonBlank(agent, peer));
        String safeTitle = title != null ? title : "告警";
        String safeTrapType = trapType;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusMinutes(TRAP_SUPPRESS_WINDOW_MINUTES);
        List<Alarm.Status> openStatuses = List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED);
        List<Alarm> similar = alarmRepository.findSimilarRecent(
                deviceIp, safeTrapType, safeTitle, openStatuses, since, PageRequest.of(0, 1));
        if (!similar.isEmpty()) {
            Alarm existing = similar.get(0);
            int count = existing.getRepeatCount() == null ? 1 : existing.getRepeatCount();
            existing.setRepeatCount(count + 1);
            existing.setLastOccurredAt(now);
            if (severity != null && (existing.getSeverity() == null
                    || severity.ordinal() < existing.getSeverity().ordinal())) {
                // CRITICAL=0 is more severe than INFO — ordinal lower = more severe for our enum order
                // Actually enum order: CRITICAL, MAJOR, MINOR, WARNING, INFO, CLEARED - lower ordinal = more severe
                existing.setSeverity(severity);
            }
            String baseDesc = existing.getDescription() != null ? existing.getDescription() : "";
            // 去掉旧的重复标记再追加
            String cleaned = baseDesc.replaceAll("(?s)\\n?\\[重复 \\d+ 次.*?\\]\\s*$", "");
            existing.setDescription(cleaned + String.format("%n[重复 %d 次，最近 %s]",
                    existing.getRepeatCount(), now));
            if (rawData != null && !rawData.isBlank()) {
                existing.setRawData(rawData);
            }
            log.info("Trap 抑制合并: deviceIp={}, type={}, title={}, repeat={}",
                    deviceIp, safeTrapType, safeTitle, existing.getRepeatCount());
            Alarm saved = alarmRepository.save(existing);
            eventPublisher.publishEvent(new AlarmCreatedEvent(
                    saved.getId(), saved.getDeviceId(), true));
            return saved;
        }

        Alarm alarm = new Alarm();
        if (matched.isPresent()) {
            Device device = matched.get();
            alarm.setDevice(device);
            alarm.setDeviceIp(normalizeDeviceIp(device.getIpAddress()));
            log.info("Trap 告警已关联设备 {} (管理IP={}), peer={}, agent={}, sysName={}",
                    device.getName(), device.getIpAddress(), peer, agent, sysName);
        } else {
            alarm.setDeviceIp(deviceIp);
            log.warn("Trap 告警未匹配纳管设备 peer={}, agent={}, sysName={}，deviceIp={}",
                    peer, agent, sysName, deviceIp);
        }

        alarm.setTitle(safeTitle);
        alarm.setDescription(appendManagedIpHint(description, matched.orElse(null)));
        alarm.setSeverity(severity);
        alarm.setStatus(Alarm.Status.ACTIVE);
        alarm.setRawData(rawData);
        alarm.setTrapOid(trapType);
        alarm.setTrapType(trapType);
        alarm.setOccurredAt(now);
        alarm.setLastOccurredAt(now);
        alarm.setRepeatCount(1);

        Alarm saved = alarmRepository.save(alarm);
        eventPublisher.publishEvent(new AlarmCreatedEvent(
                saved.getId(), saved.getDeviceId(), false));
        return saved;
    }

    /** Trap 抖动抑制窗口（分钟） */
    public static final int TRAP_SUPPRESS_WINDOW_MINUTES = 5;

    private Optional<Device> resolveManagedDevice(String peerIp, String agentIp, String sysName) {
        // 1) 管理 IP 精确命中
        if (agentIp != null && !agentIp.isBlank()) {
            Optional<Device> byAgent = deviceRepository.findByIpAddress(agentIp);
            if (byAgent.isPresent()) {
                return byAgent;
            }
        }
        if (peerIp != null && !peerIp.isBlank()) {
            Optional<Device> byPeer = deviceRepository.findByIpAddress(peerIp);
            if (byPeer.isPresent()) {
                return byPeer;
            }
        }

        // 2) 接口/环回别名（如 Trap 带 10.1.1.1，纳管为 10.10.10.4）
        if (agentIp != null && !agentIp.isBlank()) {
            Optional<Device> byAlias = deviceIpAliasService.findDeviceByAnyIp(agentIp);
            if (byAlias.isPresent()) {
                return byAlias;
            }
        }
        if (peerIp != null && !peerIp.isBlank()) {
            Optional<Device> byAlias = deviceIpAliasService.findDeviceByAnyIp(peerIp);
            if (byAlias.isPresent()) {
                return byAlias;
            }
        }

        // 3) sysName
        Optional<Device> byName = findDeviceBySysName(sysName);
        if (byName.isPresent()) {
            return byName;
        }

        // 4) 别名未缓存时，现场 SNMP 采 ipAddrTable 再匹配（教学拓扑设备少，可接受）
        if (agentIp != null && !agentIp.isBlank()) {
            Optional<Device> refreshed = deviceIpAliasService.resolveByRefreshingAliases(agentIp);
            if (refreshed.isPresent()) {
                return refreshed;
            }
        }
        if (peerIp != null && !peerIp.isBlank()) {
            return deviceIpAliasService.resolveByRefreshingAliases(peerIp);
        }
        return Optional.empty();
    }

    private Optional<Device> findDeviceBySysName(String sysName) {
        if (sysName == null || sysName.isBlank()) {
            return Optional.empty();
        }
        String name = sysName.trim();
        Optional<Device> exact = deviceRepository.findFirstByNameIgnoreCase(name);
        if (exact.isPresent()) {
            return exact;
        }
        List<Device> contains = deviceRepository.findByNameContainingIgnoreCase(name);
        if (contains.size() == 1) {
            return Optional.of(contains.get(0));
        }
        // 设备名含 IP 后缀如 "SW1-192.168.1.1"：sysName 与名称互相包含时取唯一
        List<Device> all = deviceRepository.findAll();
        List<Device> fuzzy = new java.util.ArrayList<>();
        for (Device d : all) {
            if (d.getName() == null) continue;
            String dn = d.getName();
            if (dn.equalsIgnoreCase(name) || dn.contains(name) || name.contains(dn)) {
                fuzzy.add(d);
            }
        }
        if (fuzzy.size() == 1) {
            return Optional.of(fuzzy.get(0));
        }
        if (fuzzy.size() > 1) {
            log.warn("sysName={} 匹配到多个设备，放弃名称关联", name);
        }
        return Optional.empty();
    }

    private String appendManagedIpHint(String description, Device device) {
        if (device == null) {
            return description != null ? description : "";
        }
        String mgmt = normalizeDeviceIp(device.getIpAddress());
        String name = device.getName();
        String base = description != null ? description : "";
        if (mgmt != null && !base.contains("纳管管理IP") && !base.contains("管理IP:")) {
            StringBuilder sb = new StringBuilder(base);
            if (!sb.isEmpty() && !sb.toString().endsWith("\n")) {
                sb.append('\n');
            }
            if (!base.contains("【报文】") && !base.contains("【摘要】")) {
                // 旧格式兜底
                sb.append("管理IP: ").append(mgmt);
                if (name != null) {
                    sb.append("; 纳管设备: ").append(name);
                }
            } else {
                sb.append("纳管管理IP: ").append(mgmt).append('\n');
                if (name != null && !name.isBlank()) {
                    sb.append("纳管设备: ").append(name);
                }
            }
            return sb.toString().trim();
        }
        return base;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** 去掉 udp:、端口、空白，统一为纯 IP */
    public static String normalizeDeviceIp(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;
        if (s.regionMatches(true, 0, "udp:", 0, 4)) {
            s = s.substring(4).trim();
        }
        // /192.0.2.1/161 or 192.0.2.1/161
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        // [2001:db8::1]:162 简化不处理，常见为 IPv4
        int bracket = s.indexOf(']');
        if (s.startsWith("[") && bracket > 0) {
            s = s.substring(1, bracket);
        }
        return s.trim();
    }

    /** 未分页列表上限，避免全表加载 */
    public static final int UNBOUNDED_LIST_LIMIT = 500;

    /**
     * @deprecated 请使用 {@link #getAlarmsPage(Pageable)} 或 {@link #queryAlarms}；
     *             本方法仅返回最近 {@value #UNBOUNDED_LIST_LIMIT} 条。
     */
    @Deprecated
    @Transactional
    public List<Alarm> getAllAlarms() {
        log.warn("getAllAlarms() 已限制为最近 {} 条，请改用分页接口 /api/alarms/query", UNBOUNDED_LIST_LIMIT);
        Pageable pageable = PageRequest.of(0, UNBOUNDED_LIST_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<Alarm> alarms = alarmRepository.findAllByOrderByOccurredAtDesc(pageable).getContent();
        enrichDeviceLinks(alarms);
        return alarms;
    }

    public Page<Alarm> getAlarmsPage(Pageable pageable) {
        return alarmRepository.findAll(pageable);
    }

    /**
     * 告警列表筛选分页：状态/级别/关键字/时间范围。
     */
    @Transactional
    public Page<Alarm> queryAlarms(List<Alarm.Status> statuses,
                                   Alarm.Severity severity,
                                   String keyword,
                                   LocalDateTime from,
                                   LocalDateTime to,
                                   Pageable pageable) {
        return queryAlarms(statuses, severity, keyword, from, to, false, 30, pageable);
    }

    /**
     * @param overdueOnly 仅未确认且超过 overdueMinutes 仍 ACTIVE 的告警
     */
    @Transactional
    public Page<Alarm> queryAlarms(List<Alarm.Status> statuses,
                                   Alarm.Severity severity,
                                   String keyword,
                                   LocalDateTime from,
                                   LocalDateTime to,
                                   boolean overdueOnly,
                                   int overdueMinutes,
                                   Pageable pageable) {
        return queryAlarms(statuses, severity, keyword, from, to, overdueOnly, overdueMinutes, null, pageable);
    }

    @Transactional
    public Page<Alarm> queryAlarms(List<Alarm.Status> statuses,
                                   Alarm.Severity severity,
                                   String keyword,
                                   LocalDateTime from,
                                   LocalDateTime to,
                                   boolean overdueOnly,
                                   int overdueMinutes,
                                   Long deviceId,
                                   Pageable pageable) {
        final LocalDateTime overdueBefore = overdueOnly
                ? LocalDateTime.now().minusMinutes(Math.max(overdueMinutes, 1))
                : null;
        final List<Alarm.Status> effectiveStatuses = overdueOnly
                ? List.of(Alarm.Status.ACTIVE)
                : statuses;
        final String deviceIpFilter;
        if (deviceId != null) {
            deviceIpFilter = deviceRepository.findById(deviceId)
                    .map(Device::getIpAddress)
                    .map(AlarmService::normalizeDeviceIp)
                    .orElse(null);
        } else {
            deviceIpFilter = null;
        }

        org.springframework.data.jpa.domain.Specification<Alarm> spec = (root, query, cb) -> {
            java.util.ArrayList<jakarta.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
            jakarta.persistence.criteria.Join<Alarm, Device> deviceJoin =
                    root.join("device", jakarta.persistence.criteria.JoinType.LEFT);

            if (effectiveStatuses != null && !effectiveStatuses.isEmpty()) {
                preds.add(root.get("status").in(effectiveStatuses));
            }
            if (severity != null) {
                if (severity == Alarm.Severity.MAJOR) {
                    preds.add(root.get("severity").in(Alarm.Severity.MAJOR, Alarm.Severity.WARNING));
                } else {
                    preds.add(cb.equal(root.get("severity"), severity));
                }
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            if (overdueBefore != null) {
                preds.add(cb.lessThan(root.get("occurredAt"), overdueBefore));
            }
            if (deviceId != null) {
                // 仅：本设备关联告警，或尚未绑定设备但 IP 与本设备管理 IP 一致的孤儿告警。
                // 勿用「任意 deviceIp=管理IP」——会把其它设备上同 IP 字符串的告警一并拉进来，导致拓扑与告警管理不一致。
                if (deviceIpFilter != null && !deviceIpFilter.isBlank()) {
                    preds.add(cb.or(
                            cb.equal(deviceJoin.get("id"), deviceId),
                            cb.and(
                                    cb.isNull(root.get("deviceId")),
                                    cb.equal(root.get("deviceIp"), deviceIpFilter)
                            )
                    ));
                } else {
                    preds.add(cb.equal(deviceJoin.get("id"), deviceId));
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("deviceIp"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("title"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("trapType"), "")), like),
                        cb.like(cb.lower(cb.coalesce(deviceJoin.get("name"), "")), like)
                ));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Alarm> page = alarmRepository.findAll(spec, pageable);
        enrichDeviceLinks(page.getContent());
        refineGenericTrapTypes(page.getContent());
        enrichAckCloses(page.getContent());
        for (Alarm alarm : page.getContent()) {
            if (alarm.getDevice() != null) {
                alarm.getDevice().getName();
            }
        }
        return page;
    }

    /** 解析 1h / 6h / 24h / 7d → 起始时间 */
    public static LocalDateTime resolveTimeRangeStart(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return switch (timeRange.trim()) {
            case "1h" -> now.minusHours(1);
            case "6h" -> now.minusHours(6);
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            case "all" -> null;
            default -> null;
        };
    }

    @Transactional
    public List<Alarm> getAlarmsByStatus(Alarm.Status status) {
        log.warn("getAlarmsByStatus({}) 已限制为最近 {} 条，请改用分页接口", status, UNBOUNDED_LIST_LIMIT);
        Pageable pageable = PageRequest.of(0, UNBOUNDED_LIST_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<Alarm> alarms = alarmRepository.findByStatusOrderByOccurredAtDesc(status, pageable).getContent();
        enrichDeviceLinks(alarms);
        enrichAckCloses(alarms);
        return alarms;
    }

    public Page<Alarm> getAlarmsByStatusPage(Alarm.Status status, Pageable pageable) {
        Page<Alarm> page = alarmRepository.findByStatusOrderByOccurredAtDesc(status, pageable);
        enrichAckCloses(page.getContent());
        return page;
    }

    @Transactional
    public Optional<Alarm> getAlarmById(Long id) {
        Optional<Alarm> opt = alarmRepository.findById(id);
        opt.ifPresent(a -> {
            refineGenericTrapTypes(List.of(a));
            enrichAckCloses(a);
        });
        return opt;
    }

    /**
     * 历史「通用Trap告警」纠偏类型/标题，并重写堆砌式描述为运维可读文案。
     */
    private void refineGenericTrapTypes(List<Alarm> alarms) {
        if (alarms == null || alarms.isEmpty()) {
            return;
        }
        for (Alarm alarm : alarms) {
            String oid = TrapOidClassifier.extractTrapOid(alarm.getRawData());
            if (oid == null || oid.isBlank()) {
                oid = TrapOidClassifier.normalizeOid(alarm.getTrapOid());
            }
            TrapOidClassifier.Classified c = TrapOidClassifier.classify(oid);

            boolean dirty = false;
            if (c != null && TrapOidClassifier.needsRefine(alarm.getTrapType())) {
                alarm.setTrapType(c.type());
                dirty = true;
                String title = alarm.getTitle();
                if (title == null || title.isBlank() || title.startsWith("设备告警")
                        || title.startsWith("未识别Trap") || "告警".equals(title)) {
                    alarm.setTitle(c.title());
                }
            }
            if (c != null && (alarm.getTrapOid() == null || alarm.getTrapOid().isBlank()
                    || TrapOidClassifier.needsRefine(alarm.getTrapOid()))) {
                alarm.setTrapOid(oid);
                dirty = true;
            }

            // 描述纠偏：旧分号堆砌 / 误标 v3用户 / 仍写通用类型
            if (alarm.getRawData() != null && !alarm.getRawData().isBlank()
                    && TrapOidClassifier.needsDescriptionRebuild(alarm.getDescription())) {
                String repeat = TrapOidClassifier.extractRepeatSuffix(alarm.getDescription());
                String peer = null;
                String agent = null;
                // 尽量从旧描述里抠源地址
                if (alarm.getDescription() != null) {
                    java.util.regex.Matcher pm = java.util.regex.Pattern
                            .compile("Trap源地址[：:]\\s*([^;\\n]+)")
                            .matcher(alarm.getDescription());
                    if (pm.find()) {
                        peer = pm.group(1).trim();
                    }
                    java.util.regex.Matcher am = java.util.regex.Pattern
                            .compile("Agent地址[：:]\\s*([^;\\n]+)")
                            .matcher(alarm.getDescription());
                    if (am.find()) {
                        agent = am.group(1).trim();
                    }
                }
                if (peer == null) {
                    peer = alarm.getDeviceIp();
                }
                String rebuilt = TrapOidClassifier.buildReadableDescription(
                        alarm.getRawData(),
                        c,
                        peer,
                        agent,
                        normalizeDeviceIp(alarm.getDeviceIp()),
                        alarm.getDevice() != null ? alarm.getDevice().getName() : null);
                if (rebuilt != null && !rebuilt.isBlank()) {
                    if (repeat != null && !repeat.isBlank()) {
                        rebuilt = rebuilt + "\n\n" + repeat;
                    }
                    alarm.setDescription(rebuilt);
                    dirty = true;
                }
            }

            if (dirty) {
                alarmRepository.save(alarm);
                log.info("已纠偏告警展示 id={} type={}", alarm.getId(), alarm.getTrapType());
            }
        }
    }

    @Transactional
    public List<Alarm> getAlarmsByDeviceId(Long deviceId) {
        Pageable pageable = PageRequest.of(0, UNBOUNDED_LIST_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return getAlarmsByDeviceIdPage(deviceId, pageable).getContent();
    }

    @Transactional
    public Page<Alarm> getAlarmsByDeviceIdPage(Long deviceId, Pageable pageable) {
        return queryAlarms(null, null, null, null, null, false, 30, deviceId, pageable);
    }

    /** 对历史告警：按 IP / 描述中的 Agent·sysName 重匹配，并把 deviceIp 纠正为管理 IP */
    private void enrichDeviceLinks(List<Alarm> alarms) {
        if (alarms == null || alarms.isEmpty()) return;
        for (Alarm alarm : alarms) {
            String normalized = normalizeDeviceIp(alarm.getDeviceIp());
            boolean dirty = false;

            if (normalized != null && !normalized.equals(alarm.getDeviceIp())) {
                alarm.setDeviceIp(normalized);
                dirty = true;
            }

            Optional<Device> device = Optional.ofNullable(alarm.getDevice());
            if (device.isEmpty() && normalized != null && !normalized.isBlank()) {
                device = deviceRepository.findByIpAddress(normalized);
            }
            if (device.isEmpty() && normalized != null && !normalized.isBlank()) {
                device = deviceIpAliasService.findDeviceByAnyIp(normalized);
            }
            if (device.isEmpty()) {
                String agentHint = extractLabeledIp(alarm.getDescription(), "Agent地址");
                String peerHint = extractLabeledIp(alarm.getDescription(), "Trap源地址");
                if (agentHint == null) {
                    agentHint = extractLabeledIp(alarm.getDescription(), "设备IP");
                }
                if (agentHint == null) {
                    agentHint = extractOidValue(alarm.getRawData(), "1.3.6.1.6.3.18.1.3");
                }
                String sysName = extractLabeledValue(alarm.getDescription(), "设备名称");
                if (sysName == null) {
                    sysName = extractOidValue(alarm.getRawData(), "1.3.6.1.2.1.1.5.0");
                }
                device = resolveManagedDevice(
                        firstNonBlank(peerHint, normalized),
                        firstNonBlank(agentHint, normalized),
                        sysName
                );
            }
            // 仍是接口 IP（如 10.1.1.1）时：现场采别名再纠正
            if (device.isEmpty() && normalized != null && !normalized.isBlank()) {
                device = deviceIpAliasService.resolveByRefreshingAliases(normalized);
            }

            if (device.isPresent()) {
                Device d = device.get();
                String mgmtIp = normalizeDeviceIp(d.getIpAddress());
                if (alarm.getDevice() == null || !d.getId().equals(alarm.getDevice().getId())) {
                    alarm.setDevice(d);
                    dirty = true;
                }
                if (mgmtIp != null && !mgmtIp.equals(alarm.getDeviceIp())) {
                    alarm.setDeviceIp(mgmtIp);
                    dirty = true;
                    log.info("历史告警 #{} deviceIp 已纠正为管理IP {}", alarm.getId(), mgmtIp);
                }
            }

            if (dirty) {
                alarmRepository.save(alarm);
            }
        }
    }

    private String extractLabeledIp(String text, String label) {
        String v = extractLabeledValue(text, label);
        return normalizeDeviceIp(v);
    }

    private String extractLabeledValue(String text, String label) {
        if (text == null || label == null) return null;
        // 描述格式: "设备名称: xxx; Agent地址: yyy; "
        String[] patterns = {
                label + "[：:]\\s*([^\\n,;|}]+)",
                "\"" + label + "\"\\s*[：:=]\\s*\"?([^\\n\",;}]+)"
        };
        for (String p : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    /** 从 Trap 原始 VB 文本中提取 OID 对应值，例如 "1.3.6.1.2.1.1.5.0 = SW1" */
    private String extractOidValue(String rawData, String oidHint) {
        if (rawData == null || oidHint == null || rawData.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m).*?" + java.util.regex.Pattern.quote(oidHint) + "[^=\\n]*=\\s*([^\\n]+)")
                .matcher(rawData);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    public List<Alarm> getAlarmsBySeverity(Alarm.Severity severity) {
        log.warn("getAlarmsBySeverity({}) 已限制为最近 {} 条", severity, UNBOUNDED_LIST_LIMIT);
        Pageable pageable = PageRequest.of(0, UNBOUNDED_LIST_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return alarmRepository.findBySeverityOrderByOccurredAtDesc(severity, pageable).getContent();
    }

    public List<Alarm> getRecentAlarms(LocalDateTime since) {
        return alarmRepository.findByOccurredAtAfterOrderByOccurredAtDesc(since);
    }

    @Transactional
    public Optional<Alarm> acknowledgeAlarm(Long id, String acknowledgedBy) {
        return acknowledgeAlarm(id, acknowledgedBy, null);
    }

    @Transactional
    public Optional<Alarm> acknowledgeAlarm(Long id, String acknowledgedBy, String note) {
        return alarmRepository.findById(id).map(alarm -> {
            refineGenericTrapTypes(List.of(alarm));
            String by = acknowledgedBy != null && !acknowledgedBy.isBlank() ? acknowledgedBy : "system";

            if (alarm.getStatus() == Alarm.Status.ACTIVE) {
                alarm.setStatus(Alarm.Status.ACKNOWLEDGED);
                alarm.setAcknowledgedAt(LocalDateTime.now());
                alarm.setAcknowledgedBy(by);
                if (note != null && !note.isBlank()) {
                    alarm.setAcknowledgeNote(note.trim());
                }
                Alarm saved = alarmRepository.save(alarm);
                syncLinkedPerformanceAlert(saved, "acknowledge");

                // 阅知类：确认即办结
                if (isAckClosesAlarm(saved)) {
                    Optional<Alarm> closed = clearAlarm(saved.getId(), buildAckCloseNote(note));
                    Alarm result = closed.orElse(saved);
                    enrichAckCloses(result);
                    return result;
                }
                enrichAckCloses(saved);
                return saved;
            }

            // 历史上已确认、尚未关闭的阅知类：再次确认/清理时直接办结
            if (alarm.getStatus() == Alarm.Status.ACKNOWLEDGED && isAckClosesAlarm(alarm)) {
                if (alarm.getAcknowledgedBy() == null || alarm.getAcknowledgedBy().isBlank()) {
                    alarm.setAcknowledgedBy(by);
                }
                if (note != null && !note.isBlank()
                        && (alarm.getAcknowledgeNote() == null || alarm.getAcknowledgeNote().isBlank())) {
                    alarm.setAcknowledgeNote(note.trim());
                    alarmRepository.save(alarm);
                }
                Optional<Alarm> closed = clearAlarm(alarm.getId(), buildAckCloseNote(note));
                Alarm result = closed.orElse(alarm);
                enrichAckCloses(result);
                return result;
            }

            enrichAckCloses(alarm);
            return alarm;
        });
    }

    /**
     * 关闭所有未结案的阅知类（ACTIVE/ACKNOWLEDGED）。
     * REQUIRES_NEW：独立提交，避免被工作台外层只读/回滚事务吞掉。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int closeOpenAckClosesAlarms(String acknowledgedBy, String note) {
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        int n = 0;
        String by = acknowledgedBy != null && !acknowledgedBy.isBlank() ? acknowledgedBy : "system";
        String clearNote = buildAckCloseNote(note != null ? note : "阅知类自动办结");
        for (Alarm a : open) {
            if (a == null || a.getId() == null) {
                continue;
            }
            refineGenericTrapTypes(List.of(a));
            if (!isAckClosesAlarm(a)) {
                continue;
            }
            if (a.getStatus() == Alarm.Status.ACTIVE) {
                a.setStatus(Alarm.Status.ACKNOWLEDGED);
                a.setAcknowledgedAt(LocalDateTime.now());
                a.setAcknowledgedBy(by);
                if (note != null && !note.isBlank()) {
                    a.setAcknowledgeNote(note.trim());
                } else if (a.getAcknowledgeNote() == null || a.getAcknowledgeNote().isBlank()) {
                    a.setAcknowledgeNote("阅知关闭");
                }
                alarmRepository.save(a);
            } else if (a.getAcknowledgedBy() == null || a.getAcknowledgedBy().isBlank()) {
                a.setAcknowledgedBy(by);
                alarmRepository.save(a);
            }
            Optional<Alarm> closed = clearAlarm(a.getId(), clearNote);
            if (closed.isPresent() && closed.get().getStatus() == Alarm.Status.CLEARED) {
                n++;
            }
        }
        if (n > 0) {
            log.info("阅知类批量办结 {} 条", n);
        }
        return n;
    }

    private static String buildAckCloseNote(String ackNote) {
        if (ackNote != null && !ackNote.isBlank()) {
            return "阅知关闭：" + ackNote.trim();
        }
        return "阅知关闭";
    }

    @Transactional
    public int batchAcknowledge(List<Long> ids, String acknowledgedBy) {
        return batchAcknowledge(ids, acknowledgedBy, null);
    }

    @Transactional
    public int batchAcknowledge(List<Long> ids, String acknowledgedBy, String note) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        String by = acknowledgedBy != null && !acknowledgedBy.isBlank() ? acknowledgedBy : "system";
        for (Long id : ids) {
            Optional<Alarm> before = alarmRepository.findById(id);
            if (before.isPresent() && before.get().getStatus() == Alarm.Status.ACTIVE) {
                acknowledgeAlarm(id, by, note);
                n++;
            }
        }
        return n;
    }

    @Transactional
    public Optional<Alarm> clearAlarm(Long id) {
        return clearAlarm(id, null);
    }

    @Transactional
    public Optional<Alarm> clearAlarm(Long id, String clearNote) {
        return alarmRepository.findById(id).map(alarm -> {
            alarm.setStatus(Alarm.Status.CLEARED);
            alarm.setClearedAt(LocalDateTime.now());
            if (clearNote != null && !clearNote.isBlank()) {
                alarm.setClearNote(clearNote.trim());
            } else if (alarm.getClearNote() == null || alarm.getClearNote().isBlank()) {
                alarm.setClearNote("人工关闭");
            }
            Alarm saved = alarmRepository.save(alarm);
            syncLinkedPerformanceAlert(saved, "clear");
            return saved;
        });
    }

    /**
     * 网管探测确认设备离线后，确保存在未关闭的 DEVICE_OFFLINE 告警。
     * @param bumpIfExists true=在线→离线刚切换时合并计数；false=已有未关闭告警则直接返回（供稳态离线补缺）
     */
    @Transactional
    public Optional<Alarm> ensureDeviceOfflineAlarm(Device device, String probeMethod, boolean bumpIfExists) {
        if (device == null || device.getId() == null) {
            return Optional.empty();
        }
        String ip = normalizeDeviceIp(device.getIpAddress());
        String title = "设备离线: " + (device.getName() != null ? device.getName() : ip);
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        for (Alarm a : open) {
            boolean sameDevice = Objects.equals(a.getDeviceId(), device.getId());
            boolean sameIp = ip != null && !ip.isBlank() && ip.equals(normalizeDeviceIp(a.getDeviceIp()));
            // 仅匹配整机离线告警，不用 linkDown 等接口告警替代
            if ((sameDevice || sameIp) && isDeviceOfflineAlarm(a)) {
                if (!bumpIfExists) {
                    return Optional.of(a);
                }
                LocalDateTime now = LocalDateTime.now();
                int count = a.getRepeatCount() == null ? 1 : a.getRepeatCount();
                a.setRepeatCount(count + 1);
                a.setLastOccurredAt(now);
                String probe = probeMethod != null ? probeMethod : "-";
                String base = a.getDescription() != null ? a.getDescription() : "";
                String cleaned = base.replaceAll("(?s)\\n?\\[重复 \\d+ 次.*?\\]\\s*$", "");
                a.setDescription(cleaned + String.format("%n[重复 %d 次，最近 %s，探测=%s]",
                        a.getRepeatCount(), now, probe));
                Alarm saved = alarmRepository.save(a);
                log.info("设备离线告警已合并 deviceId={} alarmId={} repeat={}",
                        device.getId(), saved.getId(), saved.getRepeatCount());
                return Optional.of(saved);
            }
        }

        String probe = probeMethod != null && !probeMethod.isBlank() ? probeMethod : "probe";
        String desc = String.format(
                "网管探测确认设备不可达（连续失败）。设备=%s，管理IP=%s，探测方式=%s。请检查 eNSP 设备电源/连线或 SNMP/ICMP 可达性。",
                device.getName(), ip != null ? ip : "-", probe.toUpperCase(Locale.ROOT));
        Alarm created = createAlarmFromTrap(
                ip,
                ip,
                device.getName(),
                title,
                desc,
                Alarm.Severity.CRITICAL,
                "probe=" + probe,
                "DEVICE_OFFLINE");
        log.warn("已创建设备离线告警 deviceId={} alarmId={} probe={}",
                device.getId(), created.getId(), probe);
        return Optional.of(created);
    }

    /**
     * 对设备做 SNMP ifOperStatus 轮询时发现「曾 up → 现 down」：在本设备上生成接口断开告警。
     * 数据来自该设备自身 SNMP，不是拓扑猜测，也不是伪造 Trap。
     */
    @Transactional
    public Optional<Alarm> raiseInterfaceDownFromSnmpPoll(Device device, int ifIndex, String ifName) {
        if (device == null || device.getId() == null) {
            return Optional.empty();
        }
        String ip = normalizeDeviceIp(device.getIpAddress());
        String portLabel = (ifName != null && !ifName.isBlank()) ? ifName.trim() : ("ifIndex=" + ifIndex);
        String title = "接口断开告警 · " + portLabel;
        String desc = String.format(
                "SNMP 轮询检测到本端接口运行状态由 up 变为 down。设备=%s，管理IP=%s，接口=%s，ifIndex=%d。"
                        + "来源=SNMP-POLL(ifOperStatus)，非拓扑构造。若随后收到 linkDown Trap 将合并为同一类告警。",
                device.getName(), ip != null ? ip : "-", portLabel, ifIndex);

        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        for (Alarm a : open) {
            boolean sameDevice = Objects.equals(a.getDeviceId(), device.getId())
                    || (ip != null && ip.equals(normalizeDeviceIp(a.getDeviceIp())));
            if (!sameDevice || !isLinkDownAlarm(a)) {
                continue;
            }
            if (sameInterfaceHint(a, ifName, String.valueOf(ifIndex))) {
                LocalDateTime now = LocalDateTime.now();
                int count = a.getRepeatCount() == null ? 1 : a.getRepeatCount();
                a.setRepeatCount(count + 1);
                a.setLastOccurredAt(now);
                alarmRepository.save(a);
                return Optional.of(a);
            }
        }

        Alarm created = createAlarmFromTrap(
                ip, ip, device.getName(),
                title, desc,
                Alarm.Severity.CRITICAL,
                "source=SNMP-POLL;ifOperStatus=down;ifIndex=" + ifIndex
                        + (ifName != null ? ";ifName=" + ifName : ""),
                "链路告警-接口断开");
        log.warn("SNMP 轮询接口 down → 告警 device={} if={} alarmId={}",
                device.getName(), portLabel, created.getId());
        return Optional.of(created);
    }

    /**
     * 接口恢复（linkUp Trap 或 SNMP 轮询见到 up）后，关闭同设备同接口未关闭的 linkDown 告警。
     * @return 清除条数
     */
    @Transactional
    public int clearLinkDownAlarmsOnLinkUp(Long deviceId, String deviceIp, String ifName, String ifIndex) {
        return clearLinkDownAlarmsOnLinkUp(deviceId, deviceIp, ifName, ifIndex,
                "接口已恢复（linkUp Trap 或 SNMP ifOperStatus=up），关闭对应链路断开告警");
    }

    @Transactional
    public int clearLinkDownAlarmsOnLinkUp(Long deviceId, String deviceIp, String ifName, String ifIndex, String clearNote) {
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        String ip = normalizeDeviceIp(deviceIp);
        String note = (clearNote != null && !clearNote.isBlank())
                ? clearNote.trim()
                : "接口已恢复，关闭对应链路断开告警";
        int n = 0;
        for (Alarm a : open) {
            boolean sameDevice = deviceId != null && Objects.equals(a.getDeviceId(), deviceId);
            boolean sameIp = ip != null && !ip.isBlank()
                    && ip.equals(normalizeDeviceIp(a.getDeviceIp()));
            if (!sameDevice && !sameIp) {
                continue;
            }
            if (!isLinkDownAlarm(a)) {
                continue;
            }
            if (!sameInterfaceHint(a, ifName, ifIndex)) {
                continue;
            }
            if (clearAlarm(a.getId(), note).isPresent()) {
                n++;
            }
        }
        if (n > 0) {
            log.info("linkUp 已关闭 {} 条链路断开告警 deviceId={} ifName={} ifIndex={}",
                    n, deviceId, ifName, ifIndex);
        }
        return n;
    }

    /**
     * 恢复 Trap → 需关闭的故障 trapType 子串列表（设备维度，含 ACTIVE/ACKNOWLEDGED）。
     * 链路断开单独走 clearLinkDownAlarmsOnLinkUp（需接口匹配）。
     */
    private static final Map<String, List<String>> RECOVERY_FAULT_TYPE_MARKERS = Map.ofEntries(
            Map.entry("链路告警-BFD恢复", List.of("链路告警-BFD断开", "BFD断开", "BFD会话状态断开")),
            Map.entry("硬件告警-电源恢复", List.of("硬件告警-电源异常", "硬件告警-电源故障", "电源运行异常", "电源模块故障")),
            Map.entry("硬件告警-风扇恢复", List.of("硬件告警-风扇故障", "风扇模块故障")),
            Map.entry("性能告警-负载恢复", List.of("性能告警-CPU过载", "性能告警-内存过载", "CPU过载", "内存过载")),
            Map.entry("路由告警-OSPF恢复", List.of("路由告警-OSPF触发", "OSPF路由告警触发"))
    );

    /**
     * 收到恢复类 Trap 后：关闭同设备对应故障告警（含处理中），并返回清除条数。
     * 不处理「链路告警-接口恢复」（接口口级匹配见 {@link #clearLinkDownAlarmsOnLinkUp}）。
     */
    @Transactional
    public int clearFaultAlarmsOnRecoveryTrap(Long deviceId, String deviceIp, String recoveryTrapType, String clearNote) {
        if (recoveryTrapType == null || recoveryTrapType.isBlank()) {
            return 0;
        }
        List<String> markers = RECOVERY_FAULT_TYPE_MARKERS.get(recoveryTrapType.trim());
        if (markers == null || markers.isEmpty()) {
            // 宽松：类型名含「恢复」且有成对「故障/异常/过载/断开/触发」时，用通用启发式
            markers = inferFaultMarkersFromRecoveryType(recoveryTrapType);
        }
        if (markers == null || markers.isEmpty()) {
            return 0;
        }
        String note = (clearNote != null && !clearNote.isBlank())
                ? clearNote.trim()
                : ("收到恢复事件「" + recoveryTrapType + "」，自动关闭对应故障告警");
        return clearOpenAlarmsMatchingMarkers(deviceId, deviceIp, markers, note);
    }

    /** 是否为「应写库留痕后立即办结」的瞬时恢复通知 */
    public boolean isTransientRecoveryNotice(String trapType, String title) {
        String type = trapType != null ? trapType.trim() : "";
        String t = title != null ? title.trim() : "";
        if (RECOVERY_FAULT_TYPE_MARKERS.containsKey(type)) {
            return true;
        }
        if (type.contains("接口恢复") || type.contains("BFD恢复") || type.contains("负载恢复")
                || type.contains("电源恢复") || type.contains("风扇恢复") || type.contains("OSPF恢复")) {
            return true;
        }
        return t.contains("接口恢复") || (t.contains("BFD") && t.contains("恢复"))
                || t.contains("负载恢复") || t.contains("故障恢复正常");
    }

    private static List<String> inferFaultMarkersFromRecoveryType(String recoveryType) {
        String r = recoveryType.trim();
        if (!r.contains("恢复")) {
            return List.of();
        }
        // 「硬件告警-电源恢复」→ 尝试「电源」相关故障（已在 map 覆盖）；通用兜底
        if (r.contains("BFD")) {
            return List.of("BFD断开", "链路告警-BFD断开");
        }
        if (r.contains("电源")) {
            return List.of("电源异常", "电源故障");
        }
        if (r.contains("风扇")) {
            return List.of("风扇故障");
        }
        if (r.contains("负载") || r.contains("CPU") || r.contains("内存")) {
            return List.of("CPU过载", "内存过载");
        }
        if (r.contains("OSPF")) {
            return List.of("OSPF触发", "路由告警-OSPF触发");
        }
        return List.of();
    }

    @Transactional
    public int clearOpenAlarmsMatchingMarkers(Long deviceId, String deviceIp,
                                              List<String> typeOrTitleMarkers, String clearNote) {
        if (typeOrTitleMarkers == null || typeOrTitleMarkers.isEmpty()) {
            return 0;
        }
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        String ip = normalizeDeviceIp(deviceIp);
        String note = (clearNote != null && !clearNote.isBlank()) ? clearNote.trim() : "条件已恢复，自动关闭";
        int n = 0;
        for (Alarm a : open) {
            boolean sameDevice = deviceId != null && Objects.equals(a.getDeviceId(), deviceId);
            boolean sameIp = ip != null && !ip.isBlank()
                    && ip.equals(normalizeDeviceIp(a.getDeviceIp()));
            if (!sameDevice && !sameIp) {
                continue;
            }
            if (!matchesAnyMarker(a, typeOrTitleMarkers)) {
                continue;
            }
            if (clearAlarm(a.getId(), note).isPresent()) {
                n++;
            }
        }
        if (n > 0) {
            log.info("恢复闭环已关闭 {} 条告警 deviceId={} markers={}", n, deviceId, typeOrTitleMarkers);
        }
        return n;
    }

    private static boolean matchesAnyMarker(Alarm a, List<String> markers) {
        String type = a.getTrapType() != null ? a.getTrapType() : "";
        String title = a.getTitle() != null ? a.getTitle() : "";
        for (String m : markers) {
            if (m == null || m.isBlank()) continue;
            if (type.contains(m) || title.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清除基线异常（指标回落后）。rawData 存 metric（cpu/memory）。
     */
    @Transactional
    public int clearBaselineAnomalyAlarms(Long deviceId, String metric, String clearNote) {
        if (deviceId == null) {
            return 0;
        }
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        String note = (clearNote != null && !clearNote.isBlank())
                ? clearNote.trim()
                : "基线检测指标已回落正常，自动关闭";
        int n = 0;
        for (Alarm a : open) {
            if (!Objects.equals(a.getDeviceId(), deviceId)) {
                continue;
            }
            boolean baseline = "BASELINE_ANOMALY".equalsIgnoreCase(a.getTrapType())
                    || (a.getTitle() != null && a.getTitle().startsWith("基线异常"));
            if (!baseline) {
                continue;
            }
            if (metric != null && !metric.isBlank()) {
                String raw = a.getRawData() != null ? a.getRawData() : "";
                String title = a.getTitle() != null ? a.getTitle() : "";
                boolean metricMatch = metric.equalsIgnoreCase(raw)
                        || ("cpu".equalsIgnoreCase(metric) && title.contains("CPU"))
                        || ("memory".equalsIgnoreCase(metric) && title.contains("内存"));
                if (!metricMatch) {
                    continue;
                }
            }
            if (clearAlarm(a.getId(), note).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 网管阈值回落时：同步清除 Trap 侧 CPU/内存过载残留（双轨）。
     */
    @Transactional
    public int clearTrapPerformanceOverloadOnMetricRecovery(Long deviceId, String deviceIp, String metric) {
        List<String> markers;
        if ("cpu".equalsIgnoreCase(metric)) {
            markers = List.of("性能告警-CPU过载", "CPU过载", "CPU利用率");
        } else if ("memory".equalsIgnoreCase(metric) || "mem".equalsIgnoreCase(metric)) {
            markers = List.of("性能告警-内存过载", "内存过载");
        } else {
            markers = List.of("性能告警-CPU过载", "性能告警-内存过载", "CPU过载", "内存过载");
        }
        return clearOpenAlarmsMatchingMarkers(deviceId, deviceIp, markers,
                "网管性能指标已回落阈值内，自动关闭 Trap 过载告警");
    }

    private static boolean isLinkDownAlarm(Alarm a) {
        if (a == null) {
            return false;
        }
        String type = a.getTrapType() != null ? a.getTrapType() : "";
        String title = a.getTitle() != null ? a.getTitle() : "";
        return type.contains("接口断开") || type.contains("LinkDown") || type.contains("链路断开")
                || "linkDown".equalsIgnoreCase(type)
                || title.contains("接口断开") || title.contains("链路断开")
                || title.toLowerCase(Locale.ROOT).contains("linkdown");
    }

    /**
     * 判断未关闭的 linkDown 是否与本次恢复事件为同一接口。
     * 无接口线索时同设备全清；有线索时做宽松匹配（ifIndex / ifName 别名）。
     */
    private static boolean sameInterfaceHint(Alarm a, String ifName, String ifIndex) {
        boolean hasHint = (ifName != null && !ifName.isBlank()) || (ifIndex != null && !ifIndex.isBlank());
        if (!hasHint) {
            return true;
        }
        String blob = ((a.getTitle() != null ? a.getTitle() : "") + " "
                + (a.getDescription() != null ? a.getDescription() : "") + " "
                + (a.getRawData() != null ? a.getRawData() : "")).toLowerCase(Locale.ROOT);

        if (ifIndex != null && !ifIndex.isBlank()) {
            String idx = ifIndex.trim();
            if (idx.matches("\\d+")) {
                if (blob.contains("ifindex=" + idx)
                        || blob.contains("ifindex:" + idx)
                        || blob.contains("ifindex " + idx)
                        || (blob.contains("接口索引") && blob.contains(idx))
                        || blob.contains(".2.2.1.8." + idx)
                        || blob.contains(".2.2.1.2." + idx)
                        || blob.contains(".2.2.1.1." + idx)) {
                    return true;
                }
            }
        }

        if (ifName != null && !ifName.isBlank()) {
            String name = ifName.trim().toLowerCase(Locale.ROOT);
            if (blob.contains(name)) {
                return true;
            }
            // GigabitEthernet0/0/1 ↔ GE0/0/1 ↔ Gi0/0/1 等常见缩写
            for (String alias : interfaceNameAliases(name)) {
                if (blob.contains(alias)) {
                    return true;
                }
            }
        }

        // 线索无法对上时：不误清其它口；由「无线索」或 SNMP 稳态对账再处理
        return false;
    }

    private static List<String> interfaceNameAliases(String name) {
        List<String> aliases = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return aliases;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        aliases.add(n);
        // 去空格
        aliases.add(n.replace(" ", ""));
        // GigabitEthernet → ge / gi
        if (n.startsWith("gigabitethernet")) {
            String rest = n.substring("gigabitethernet".length());
            aliases.add("ge" + rest);
            aliases.add("gi" + rest);
            aliases.add("gigabitethernet" + rest);
        } else if (n.startsWith("ge") && n.length() > 2 && !Character.isLetter(n.charAt(2))) {
            String rest = n.substring(2);
            aliases.add("gigabitethernet" + rest);
            aliases.add("gi" + rest);
        } else if (n.startsWith("gi") && n.length() > 2 && !Character.isLetter(n.charAt(2))) {
            String rest = n.substring(2);
            aliases.add("gigabitethernet" + rest);
            aliases.add("ge" + rest);
        }
        // XGigabitEthernet / 10GE
        if (n.startsWith("xgigabitethernet")) {
            String rest = n.substring("xgigabitethernet".length());
            aliases.add("xge" + rest);
            aliases.add("10ge" + rest);
        } else if (n.startsWith("xge")) {
            String rest = n.substring(3);
            aliases.add("xgigabitethernet" + rest);
        }
        // Ethernet → eth
        if (n.startsWith("ethernet") && !n.startsWith("ethernet0/0/0")) {
            // keep simple
            String rest = n.substring("ethernet".length());
            aliases.add("eth" + rest);
        }
        return aliases;
    }

    /**
     * SNMP 稳态对账：接口当前为 up 时，关闭该口未结案的 linkDown（含「处理中」），
     * 不依赖 wasDown→nowUp 边沿，避免漏 Trap / 进程重启后残留。
     */
    @Transactional
    public int reconcileOpenLinkDownWhileIfUp(Long deviceId, String deviceIp, String ifName, String ifIndex) {
        return clearLinkDownAlarmsOnLinkUp(deviceId, deviceIp, ifName, ifIndex,
                "SNMP 探测接口已 up，自动关闭残留链路断开告警");
    }

    /**
     * 设备恢复在线后，清除该设备上未关闭的连通性/离线类告警。
     * @return 清除条数
     */
    @Transactional
    public int clearConnectivityAlarmsOnRecovery(Long deviceId, String deviceIp) {
        return clearConnectivityAlarmsOnRecovery(deviceId, deviceIp, null);
    }

    @Transactional
    public int clearConnectivityAlarmsOnRecovery(Long deviceId, String deviceIp, String clearNote) {
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        String ip = normalizeDeviceIp(deviceIp);
        String note = (clearNote != null && !clearNote.isBlank())
                ? clearNote.trim()
                : "网管探测确认设备已恢复";
        int n = 0;
        for (Alarm a : open) {
            boolean sameDevice = deviceId != null && Objects.equals(a.getDeviceId(), deviceId);
            boolean sameIp = ip != null && !ip.isBlank()
                    && ip.equals(normalizeDeviceIp(a.getDeviceIp()));
            if (!sameDevice && !sameIp) {
                continue;
            }
            if (!isConnectivityAlarm(a)) {
                continue;
            }
            if (clearAlarm(a.getId(), note).isPresent()) {
                n++;
            }
        }
        // 设备重新可达时，冷启动多为重启后遗留提示，一并办结避免长期占「处理中」
        n += clearOpenAlarmsMatchingMarkers(deviceId, deviceIp,
                List.of("系统告警-冷启动", "设备冷启动", "冷启动"),
                "设备已恢复在线，冷启动提示自动办结");
        return n;
    }

    private static boolean isDeviceOfflineAlarm(Alarm a) {
        if (a == null) return false;
        if ("DEVICE_OFFLINE".equalsIgnoreCase(a.getTrapType())) {
            return true;
        }
        String title = a.getTitle() != null ? a.getTitle() : "";
        return title.contains("设备离线") || title.toLowerCase(Locale.ROOT).contains("device offline");
    }

    /**
     * 仅整机可达性/离线类告警。禁止匹配 linkDown 等接口告警（含 "down" 子串），
     * 否则设备仍在线时的轮询恢复会误清链路断开告警。
     */
    private static boolean isConnectivityAlarm(Alarm a) {
        return isDeviceOfflineAlarm(a);
    }

    @Transactional
    public int batchClear(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (clearAlarm(id).isPresent()) {
                n++;
            }
        }
        return n;
    }

    @Transactional
    public boolean deleteAlarm(Long id) {
        if (alarmRepository.existsById(id)) {
            alarmRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (deleteAlarm(id)) {
                n++;
            }
        }
        return n;
    }

    public Map<String, Object> getAlarmStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime weekStart = now.minusDays(7);
        LocalDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();

        long todayCount = alarmRepository.countByOccurredAtBetween(todayStart, now);
        long weekCount = alarmRepository.countByOccurredAtBetween(weekStart, now);
        long monthCount = alarmRepository.countByOccurredAtBetween(monthStart, now);

        // 级别分布：一次 GROUP BY 代替多次 countBySeverity
        Map<Alarm.Severity, Long> severityCounts = toEnumCountMap(
                alarmRepository.countGroupBySeverity(), Alarm.Severity.class);
        long criticalCount = severityCounts.getOrDefault(Alarm.Severity.CRITICAL, 0L);
        long majorCount = severityCounts.getOrDefault(Alarm.Severity.MAJOR, 0L)
                + severityCounts.getOrDefault(Alarm.Severity.WARNING, 0L);
        long minorCount = severityCounts.getOrDefault(Alarm.Severity.MINOR, 0L);
        long infoCount = severityCounts.getOrDefault(Alarm.Severity.INFO, 0L);

        List<Object[]> trapTypeStats = alarmRepository.countByTrapType();
        Map<String, Long> trapTypeMap = new java.util.LinkedHashMap<>();
        for (Object[] row : trapTypeStats) {
            if (row[0] != null) {
                trapTypeMap.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }

        List<Object[]> deviceStats = alarmRepository.countByDeviceIpGroupByStatus(Alarm.Status.ACTIVE);
        Map<String, Long> topDevices = new java.util.LinkedHashMap<>();
        int top = 0;
        for (Object[] row : deviceStats) {
            if (row[0] != null && top < 5) {
                topDevices.put(row[0].toString(), ((Number) row[1]).longValue());
                top++;
            }
        }

        // 近 24 小时按小时聚合（DB GROUP BY，不再加载全部告警到内存）
        LocalDateTime trendStart = now.minusHours(23).withMinute(0).withSecond(0).withNano(0);
        List<Object[]> hourlyRows = alarmRepository.countGroupByHourSince(trendStart);
        Map<String, Long> hourlyMap = new java.util.HashMap<>();
        for (Object[] row : hourlyRows) {
            if (row == null || row.length < 5 || row[0] == null) continue;
            int y = ((Number) row[0]).intValue();
            int mo = ((Number) row[1]).intValue();
            int d = ((Number) row[2]).intValue();
            int h = ((Number) row[3]).intValue();
            String key = String.format("%04d-%02d-%02d-%02d", y, mo, d, h);
            hourlyMap.put(key, ((Number) row[4]).longValue());
        }
        java.util.List<Map<String, Object>> hourlyTrend = new java.util.ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            LocalDateTime slot = trendStart.plusHours(i);
            String key = String.format("%04d-%02d-%02d-%02d",
                    slot.getYear(), slot.getMonthValue(), slot.getDayOfMonth(), slot.getHour());
            hourlyTrend.add(Map.of(
                    "hour", String.format("%02d:00", slot.getHour()),
                    "count", hourlyMap.getOrDefault(key, 0L)
            ));
        }

        // 状态分布：一次 GROUP BY
        Map<Alarm.Status, Long> statusCounts = toEnumCountMap(
                alarmRepository.countGroupByStatus(), Alarm.Status.class);
        long activeCount = statusCounts.getOrDefault(Alarm.Status.ACTIVE, 0L);
        long acknowledgedCount = statusCounts.getOrDefault(Alarm.Status.ACKNOWLEDGED, 0L);
        long clearedCount = statusCounts.getOrDefault(Alarm.Status.CLEARED, 0L);
        long total = statusCounts.values().stream().mapToLong(Long::longValue).sum();

        Map<Alarm.Severity, Long> activeSeverity = toEnumCountMap(
                alarmRepository.countByStatusGroupBySeverity(Alarm.Status.ACTIVE), Alarm.Severity.class);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", total);
        result.put("activeCount", activeCount);
        result.put("acknowledgedCount", acknowledgedCount);
        result.put("clearedCount", clearedCount);
        result.put("criticalActiveCount", activeSeverity.getOrDefault(Alarm.Severity.CRITICAL, 0L));
        result.put("majorActiveCount",
                activeSeverity.getOrDefault(Alarm.Severity.MAJOR, 0L)
                        + activeSeverity.getOrDefault(Alarm.Severity.WARNING, 0L));
        result.put("overdueActiveCount",
                alarmRepository.countByStatusAndOccurredAtBefore(
                        Alarm.Status.ACTIVE, now.minusMinutes(30)));
        result.put("overdueMinutes", 30);
        result.put("todayCount", todayCount);
        result.put("weekCount", weekCount);
        result.put("monthCount", monthCount);
        result.put("severityStats", Map.of(
                "critical", criticalCount,
                "major", majorCount,
                "minor", minorCount,
                "info", infoCount
        ));
        result.put("trapTypeStats", trapTypeMap);
        result.put("topDevices", topDevices);
        result.put("hourlyTrend", hourlyTrend);
        return result;
    }

    private static <E extends Enum<E>> Map<E, Long> toEnumCountMap(List<Object[]> rows, Class<E> type) {
        Map<E, Long> map = new EnumMap<>(type);
        if (rows == null) return map;
        for (Object[] row : rows) {
            if (row == null || row[0] == null) continue;
            E key;
            if (type.isInstance(row[0])) {
                key = type.cast(row[0]);
            } else {
                key = Enum.valueOf(type, row[0].toString());
            }
            map.put(key, ((Number) row[1]).longValue());
        }
        return map;
    }

    /**
     * 性能阈值告警同步到统一告警表，供告警中心 / 拓扑告警 Tab 展示。
     */
    @Transactional
    public void syncPerformanceThresholdAlarm(Device device, String metric, String level,
                                              String message, Double value, Double threshold) {
        if (device == null || metric == null) {
            return;
        }
        List<Alarm.Status> open = List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED);
        List<Alarm> existing = alarmRepository.findPerformanceAlarms(device.getId(), metric, open);
        Alarm.Severity severity = "danger".equals(level) ? Alarm.Severity.CRITICAL : Alarm.Severity.MAJOR;

        if (!existing.isEmpty()) {
            Alarm alarm = existing.get(0);
            alarm.setSeverity(severity);
            alarm.setTitle(message);
            alarm.setDescription(String.format("当前值=%.1f, 阈值=%.1f", value, threshold));
            if (alarm.getStatus() == Alarm.Status.CLEARED) {
                alarm.setStatus(Alarm.Status.ACTIVE);
                alarm.setClearedAt(null);
            }
            alarmRepository.save(alarm);
            return;
        }

        Alarm alarm = new Alarm();
        alarm.setDevice(device);
        alarm.setDeviceIp(device.getIpAddress());
        alarm.setTitle(message);
        alarm.setDescription(String.format("当前值=%.1f, 阈值=%.1f", value, threshold));
        alarm.setSeverity(severity);
        alarm.setStatus(Alarm.Status.ACTIVE);
        alarm.setTrapType("PERFORMANCE");
        alarm.setTrapOid("PERFORMANCE");
        alarm.setRawData(metric);
        alarm.setOccurredAt(LocalDateTime.now());
        alarmRepository.save(alarm);
    }

    @Transactional
    public void clearPerformanceThresholdAlarm(Device device, String metric) {
        if (device == null) {
            return;
        }
        clearPerformanceThresholdAlarm(device.getId(), metric);
    }

    @Transactional
    public void clearPerformanceThresholdAlarm(Long deviceId, String metric) {
        if (deviceId == null || metric == null) {
            return;
        }
        List<Alarm.Status> open = List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED);
        List<Alarm> existing = alarmRepository.findPerformanceAlarms(deviceId, metric, open);
        for (Alarm alarm : existing) {
            alarm.setStatus(Alarm.Status.CLEARED);
            alarm.setClearedAt(LocalDateTime.now());
            if (alarm.getClearNote() == null || alarm.getClearNote().isBlank()) {
                alarm.setClearNote("网管性能指标已回落阈值内");
            }
            alarmRepository.save(alarm);
        }
        // 双轨：同步清 Trap 侧过载告警，避免 PERFORMANCE 已关但 Trap 过载仍「处理中」
        try {
            Device d = deviceRepository.findById(deviceId).orElse(null);
            String ip = d != null ? d.getIpAddress() : null;
            clearTrapPerformanceOverloadOnMetricRecovery(deviceId, ip, metric);
        } catch (Exception e) {
            log.debug("同步清除 Trap 过载告警失败 deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    /** 性能页确认阈值告警时，同步统一告警为已确认 */
    @Transactional
    public void acknowledgePerformanceThresholdAlarm(Long deviceId, String metric, String acknowledgedBy) {
        if (deviceId == null || metric == null) {
            return;
        }
        List<Alarm> existing = alarmRepository.findPerformanceAlarms(
                deviceId, metric, List.of(Alarm.Status.ACTIVE));
        String by = acknowledgedBy != null && !acknowledgedBy.isBlank() ? acknowledgedBy : "system";
        for (Alarm alarm : existing) {
            alarm.setStatus(Alarm.Status.ACKNOWLEDGED);
            alarm.setAcknowledgedAt(LocalDateTime.now());
            alarm.setAcknowledgedBy(by);
            alarmRepository.save(alarm);
        }
    }

    /**
     * 告警中心处置 PERFORMANCE 类型时，回写 performance_alerts，避免双轨状态不一致。
     * rawData 存 metric（cpu/memory）。
     */
    private void syncLinkedPerformanceAlert(Alarm alarm, String action) {
        if (alarm == null || !"PERFORMANCE".equalsIgnoreCase(alarm.getTrapType())) {
            return;
        }
        Long deviceId = alarm.getDeviceId();
        String metric = alarm.getRawData();
        if (deviceId == null || metric == null || metric.isBlank()) {
            return;
        }
        List<PerformanceAlert> linked = performanceAlertRepository.findLatestActiveAlerts(deviceId, metric.trim());
        if (linked == null || linked.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (PerformanceAlert pa : linked) {
            if ("acknowledge".equals(action)) {
                if ("active".equals(pa.getStatus())) {
                    pa.setStatus("acknowledged");
                    pa.setAcknowledgedAt(now);
                    performanceAlertRepository.save(pa);
                }
            } else if ("clear".equals(action)) {
                if (!"resolved".equals(pa.getStatus())) {
                    pa.setStatus("resolved");
                    pa.setResolvedAt(now);
                    performanceAlertRepository.save(pa);
                }
            }
        }
        log.debug("已同步 PerformanceAlert: deviceId={}, metric={}, action={}", deviceId, metric, action);
    }
}
