package com.ensp.nms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "performance_data")
public class PerformanceData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    @JsonIgnore
    private Device device;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "memory_usage")
    private Double memoryUsage;

    @Column(name = "memory_total")
    private Long memoryTotal;

    @Column(name = "memory_used")
    private Long memoryUsed;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "fan_status")
    private String fanStatus;

    @Column(name = "power_status")
    private String powerStatus;

    @Column(name = "port_index")
    private Integer portIndex;

    @Column(name = "port_name")
    private String portName;

    @Column(name = "if_in_octets")
    private Long ifInOctets;

    @Column(name = "if_out_octets")
    private Long ifOutOctets;

    @Column(name = "if_in_rate")
    private Double ifInRate;

    @Column(name = "if_out_rate")
    private Double ifOutRate;

    @Column(name = "port_errors_in")
    private Long portErrorsIn;

    @Column(name = "port_errors_out")
    private Long portErrorsOut;

    @Column(name = "port_discards_in")
    private Long portDiscardsIn;

    @Column(name = "port_discards_out")
    private Long portDiscardsOut;

    /** ifOperStatus: up / down / unknown（来自 SNMP，非流量启发式） */
    @Column(name = "port_oper_status", length = 20)
    private String portOperStatus;

    /**
     * CPU 指标来源：snmp=真采，simulated=仿真回退，unknown=未标注（历史数据）。
     * AIOps 基线/阈值告警应优先忽略 simulated。
     */
    @Column(name = "cpu_source", length = 20)
    private String cpuSource;

    /** 内存指标来源：snmp / simulated / unknown */
    @Column(name = "memory_source", length = 20)
    private String memorySource;

    public static final String SOURCE_SNMP = "snmp";
    public static final String SOURCE_SIMULATED = "simulated";
    public static final String SOURCE_UNKNOWN = "unknown";

    public PerformanceData() {
    }

    public PerformanceData(Device device, LocalDateTime timestamp) {
        this.device = device;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    // 暴露 deviceId 用于 JSON 序列化（device 字段本身被 @JsonIgnore）
    public Long getDeviceId() {
        return device != null ? device.getId() : null;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(Double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public Double getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(Double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public Long getMemoryTotal() {
        return memoryTotal;
    }

    public void setMemoryTotal(Long memoryTotal) {
        this.memoryTotal = memoryTotal;
    }

    public Long getMemoryUsed() {
        return memoryUsed;
    }

    public void setMemoryUsed(Long memoryUsed) {
        this.memoryUsed = memoryUsed;
    }

    public Integer getPortIndex() {
        return portIndex;
    }

    public void setPortIndex(Integer portIndex) {
        this.portIndex = portIndex;
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public Long getIfInOctets() {
        return ifInOctets;
    }

    public void setIfInOctets(Long ifInOctets) {
        this.ifInOctets = ifInOctets;
    }

    public Long getIfOutOctets() {
        return ifOutOctets;
    }

    public void setIfOutOctets(Long ifOutOctets) {
        this.ifOutOctets = ifOutOctets;
    }

    public Double getIfInRate() {
        return ifInRate;
    }

    public void setIfInRate(Double ifInRate) {
        this.ifInRate = ifInRate;
    }

    public Double getIfOutRate() {
        return ifOutRate;
    }

    public void setIfOutRate(Double ifOutRate) {
        this.ifOutRate = ifOutRate;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getFanStatus() {
        return fanStatus;
    }

    public void setFanStatus(String fanStatus) {
        this.fanStatus = fanStatus;
    }

    public String getPowerStatus() {
        return powerStatus;
    }

    public void setPowerStatus(String powerStatus) {
        this.powerStatus = powerStatus;
    }

    public Long getPortErrorsIn() {
        return portErrorsIn;
    }

    public void setPortErrorsIn(Long portErrorsIn) {
        this.portErrorsIn = portErrorsIn;
    }

    public Long getPortErrorsOut() {
        return portErrorsOut;
    }

    public void setPortErrorsOut(Long portErrorsOut) {
        this.portErrorsOut = portErrorsOut;
    }

    public Long getPortDiscardsIn() {
        return portDiscardsIn;
    }

    public void setPortDiscardsIn(Long portDiscardsIn) {
        this.portDiscardsIn = portDiscardsIn;
    }

    public Long getPortDiscardsOut() {
        return portDiscardsOut;
    }

    public void setPortDiscardsOut(Long portDiscardsOut) {
        this.portDiscardsOut = portDiscardsOut;
    }

    public String getPortOperStatus() {
        return portOperStatus;
    }

    public void setPortOperStatus(String portOperStatus) {
        this.portOperStatus = portOperStatus;
    }

    public String getCpuSource() {
        return cpuSource;
    }

    public void setCpuSource(String cpuSource) {
        this.cpuSource = cpuSource;
    }

    public String getMemorySource() {
        return memorySource;
    }

    public void setMemorySource(String memorySource) {
        this.memorySource = memorySource;
    }

    /** 综合来源：两端均为 snmp → snmp；任一仿真 → mixed/simulated */
    public String getMetricSourceSummary() {
        boolean cpuSim = SOURCE_SIMULATED.equalsIgnoreCase(cpuSource);
        boolean memSim = SOURCE_SIMULATED.equalsIgnoreCase(memorySource);
        boolean cpuSnmp = SOURCE_SNMP.equalsIgnoreCase(cpuSource);
        boolean memSnmp = SOURCE_SNMP.equalsIgnoreCase(memorySource);
        if (cpuSim && memSim) {
            return SOURCE_SIMULATED;
        }
        if (cpuSnmp && memSnmp) {
            return SOURCE_SNMP;
        }
        if (cpuSim || memSim) {
            return "mixed";
        }
        return SOURCE_UNKNOWN;
    }

    public boolean isCpuSimulated() {
        return SOURCE_SIMULATED.equalsIgnoreCase(cpuSource);
    }

    public boolean isMemorySimulated() {
        return SOURCE_SIMULATED.equalsIgnoreCase(memorySource);
    }
}
