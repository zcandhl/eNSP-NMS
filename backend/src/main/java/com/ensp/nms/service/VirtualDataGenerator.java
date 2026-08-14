package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class VirtualDataGenerator {

    private final Random random = new Random();
    
    private final Map<Long, DeviceVirtualState> deviceStates = new ConcurrentHashMap<>();

    public static class DeviceVirtualState {
        public double baseCpu;
        public double baseMemory;
        public double baseTemperature;
        public double lastCpu;
        public double lastMemory;
        public double lastTemperature;
        public long lastPortInBytes;
        public long lastPortOutBytes;
        public long portErrorsIn;
        public long portErrorsOut;
        public long portDiscardsIn;
        public long portDiscardsOut;
        public boolean isAnomalyActive;
        public int anomalyDuration;
        public String anomalyType;
    }

    public Map<String, Object> generateSystemData(Device device, LocalDateTime time) {
        DeviceVirtualState state = getOrCreateState(device);
        
        Map<String, Object> data = new HashMap<>();
        
        int hour = time.getHour();
        DayOfWeek dayOfWeek = time.getDayOfWeek();
        boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        boolean isPeakHour = (hour >= 9 && hour <= 11) || (hour >= 14 && hour <= 16);
        boolean isNight = hour >= 22 || hour <= 6;
        
        double cpu = calculateCpu(state, isPeakHour, isNight, isWeekend);
        double memory = calculateMemory(state, cpu, isPeakHour, isNight);
        double temperature = calculateTemperature(state, cpu, isPeakHour);
        String fanStatus = calculateFanStatus(temperature);
        String powerStatus = "normal";
        
        data.put("cpuUsage", cpu);
        data.put("memoryUsage", memory);
        data.put("memoryTotal", 256L * 1024 * 1024);
        data.put("memoryUsed", (long) (256L * 1024 * 1024 * memory / 100));
        data.put("temperature", temperature);
        data.put("fanStatus", fanStatus);
        data.put("powerStatus", powerStatus);
        
        state.lastCpu = cpu;
        state.lastMemory = memory;
        state.lastTemperature = temperature;
        
        return data;
    }

    public Map<String, Object> generatePortData(Device device, Integer portIndex, LocalDateTime time) {
        DeviceVirtualState state = getOrCreateState(device);
        
        Map<String, Object> data = new HashMap<>();
        
        int hour = time.getHour();
        boolean isPeakHour = (hour >= 9 && hour <= 11) || (hour >= 14 && hour <= 16);
        
        long baseIn = 10000000 + random.nextInt(50000000);
        long baseOut = 10000000 + random.nextInt(50000000);
        
        if (isPeakHour) {
            baseIn = (long) (baseIn * 1.8);
            baseOut = (long) (baseOut * 1.8);
        }
        
        long portIn = state.lastPortInBytes + baseIn + random.nextInt(10000000);
        long portOut = state.lastPortOutBytes + baseOut + random.nextInt(10000000);
        
        double inRate = baseIn / 30.0;
        double outRate = baseOut / 30.0;
        
        long errorsIn = state.portErrorsIn + (random.nextDouble() < 0.01 ? random.nextInt(5) : 0);
        long errorsOut = state.portErrorsOut + (random.nextDouble() < 0.01 ? random.nextInt(5) : 0);
        long discardsIn = state.portDiscardsIn + (random.nextDouble() < 0.005 ? random.nextInt(3) : 0);
        long discardsOut = state.portDiscardsOut + (random.nextDouble() < 0.005 ? random.nextInt(3) : 0);
        
        data.put("ifInOctets", portIn);
        data.put("ifOutOctets", portOut);
        data.put("ifInRate", inRate);
        data.put("ifOutRate", outRate);
        data.put("portErrorsIn", errorsIn);
        data.put("portErrorsOut", errorsOut);
        data.put("portDiscardsIn", discardsIn);
        data.put("portDiscardsOut", discardsOut);
        
        state.lastPortInBytes = portIn;
        state.lastPortOutBytes = portOut;
        state.portErrorsIn = errorsIn;
        state.portErrorsOut = errorsOut;
        state.portDiscardsIn = discardsIn;
        state.portDiscardsOut = discardsOut;
        
        return data;
    }

    private double calculateCpu(DeviceVirtualState state, boolean isPeakHour, boolean isNight, boolean isWeekend) {
        double base = state.baseCpu;
        double fluctuation = random.nextGaussian() * 5;
        
        if (state.isAnomalyActive && "cpu".equals(state.anomalyType)) {
            state.anomalyDuration--;
            if (state.anomalyDuration <= 0) {
                state.isAnomalyActive = false;
                state.anomalyType = null;
            }
            return Math.min(100, base + 40 + fluctuation);
        }
        
        if (random.nextDouble() < 0.02) {
            state.isAnomalyActive = true;
            state.anomalyType = "cpu";
            state.anomalyDuration = 5 + random.nextInt(10);
            return Math.min(100, base + 45 + fluctuation);
        }
        
        double peakBonus = isPeakHour ? 15 + random.nextDouble() * 10 : 0;
        double nightReduction = isNight ? -10 - random.nextDouble() * 10 : 0;
        double weekendReduction = isWeekend ? -8 - random.nextDouble() * 8 : 0;
        
        double cpu = base + fluctuation + peakBonus + nightReduction + weekendReduction;
        return Math.max(5, Math.min(95, cpu));
    }

    private double calculateMemory(DeviceVirtualState state, double cpu, boolean isPeakHour, boolean isNight) {
        double base = state.baseMemory;
        double correlation = (cpu - state.baseCpu) * 0.3;
        double fluctuation = random.nextGaussian() * 4;
        
        double peakBonus = isPeakHour ? 8 + random.nextDouble() * 5 : 0;
        double nightReduction = isNight ? -5 - random.nextDouble() * 5 : 0;
        
        double memory = base + correlation + fluctuation + peakBonus + nightReduction;
        return Math.max(20, Math.min(95, memory));
    }

    private double calculateTemperature(DeviceVirtualState state, double cpu, boolean isPeakHour) {
        double base = state.baseTemperature;
        double cpuInfluence = (cpu - 30) * 0.3;
        double fluctuation = random.nextGaussian() * 2;
        
        double temp = base + cpuInfluence + fluctuation;
        
        if (state.isAnomalyActive && "temperature".equals(state.anomalyType)) {
            state.anomalyDuration--;
            if (state.anomalyDuration <= 0) {
                state.isAnomalyActive = false;
                state.anomalyType = null;
            }
            return Math.min(85, temp + 20);
        }
        
        if (random.nextDouble() < 0.01) {
            state.isAnomalyActive = true;
            state.anomalyType = "temperature";
            state.anomalyDuration = 8 + random.nextInt(12);
            return Math.min(85, temp + 25);
        }
        
        return Math.max(25, Math.min(75, temp));
    }

    private String calculateFanStatus(double temperature) {
        if (temperature > 65) {
            return "high";
        } else if (temperature > 50) {
            return "medium";
        }
        return "normal";
    }

    private DeviceVirtualState getOrCreateState(Device device) {
        return deviceStates.computeIfAbsent(device.getId(), id -> {
            DeviceVirtualState state = new DeviceVirtualState();
            state.baseCpu = 25 + random.nextDouble() * 15;
            state.baseMemory = 40 + random.nextDouble() * 20;
            state.baseTemperature = 32 + random.nextDouble() * 8;
            state.lastCpu = state.baseCpu;
            state.lastMemory = state.baseMemory;
            state.lastTemperature = state.baseTemperature;
            state.lastPortInBytes = random.nextLong(1000000000L);
            state.lastPortOutBytes = random.nextLong(1000000000L);
            state.portErrorsIn = 0L;
            state.portErrorsOut = 0L;
            state.portDiscardsIn = 0L;
            state.portDiscardsOut = 0L;
            state.isAnomalyActive = false;
            return state;
        });
    }
}
