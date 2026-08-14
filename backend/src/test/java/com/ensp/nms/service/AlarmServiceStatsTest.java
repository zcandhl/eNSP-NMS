package com.ensp.nms.service;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlarmServiceStatsTest {

    @Mock
    private AlarmRepository alarmRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceIpAliasService deviceIpAliasService;

    @InjectMocks
    private AlarmService alarmService;

    @Test
    void getAlarmStatsUsesAggregations() {
        when(alarmRepository.countByOccurredAtBetween(any(), any())).thenReturn(3L);
        when(alarmRepository.countGroupBySeverity()).thenReturn(List.of(
                new Object[]{Alarm.Severity.CRITICAL, 2L},
                new Object[]{Alarm.Severity.MAJOR, 1L}
        ));
        when(alarmRepository.countByTrapType()).thenReturn(List.of());
        when(alarmRepository.countByDeviceIpGroupByStatus(Alarm.Status.ACTIVE)).thenReturn(List.of());
        when(alarmRepository.countGroupByHourSince(any())).thenReturn(List.of());
        when(alarmRepository.countGroupByStatus()).thenReturn(List.of(
                new Object[]{Alarm.Status.ACTIVE, 5L},
                new Object[]{Alarm.Status.ACKNOWLEDGED, 2L}
        ));
        when(alarmRepository.countByStatusGroupBySeverity(Alarm.Status.ACTIVE)).thenReturn(List.of(
                new Object[]{Alarm.Severity.CRITICAL, 1L},
                new Object[]{Alarm.Severity.WARNING, 3L}
        ));
        when(alarmRepository.countByStatusAndOccurredAtBefore(any(), any())).thenReturn(0L);

        Map<String, Object> stats = alarmService.getAlarmStats();

        assertEquals(7L, stats.get("total"));
        assertEquals(5L, stats.get("activeCount"));
        assertEquals(1L, stats.get("criticalActiveCount"));
        assertEquals(3L, stats.get("majorActiveCount"));
        assertNotNull(stats.get("hourlyTrend"));
        assertEquals(24, ((List<?>) stats.get("hourlyTrend")).size());
    }
}
