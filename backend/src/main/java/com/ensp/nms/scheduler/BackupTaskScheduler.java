package com.ensp.nms.scheduler;

import com.ensp.nms.entity.BackupSchedule;
import com.ensp.nms.service.BackupScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BackupTaskScheduler {

    private final BackupScheduleService backupScheduleService;

    @Scheduled(cron = "0 * * * * ?") // 每分钟检查一次
    public void runScheduledBackups() {
        List<BackupSchedule> activeSchedules = backupScheduleService.getActiveSchedules();
        LocalDateTime now = LocalDateTime.now();

        for (BackupSchedule schedule : activeSchedules) {
            if (shouldExecuteNow(schedule, now)) {
                try {
                    backupScheduleService.executeSchedule(schedule);
                } catch (Exception e) {
                    log.error("Failed to execute schedule for device {}", schedule.getDeviceId(), e);
                }
            }
        }
    }

    boolean shouldExecuteNow(BackupSchedule schedule, LocalDateTime now) {
        if (schedule.getScheduleTime() == null || schedule.getScheduleTime().isBlank()) {
            return false;
        }

        try {
            LocalTime scheduledTime = LocalTime.parse(schedule.getScheduleTime().trim());
            if (now.getHour() != scheduledTime.getHour() || now.getMinute() != scheduledTime.getMinute()) {
                return false;
            }
        } catch (Exception e) {
            log.error("Invalid schedule time format: {}", schedule.getScheduleTime());
            return false;
        }

        // 同一分钟内已执行过则跳过，避免 cron 抖动重复跑
        if (alreadyRanThisMinute(schedule.getLastRun(), now)) {
            return false;
        }

        String type = schedule.getScheduleType() == null ? "daily" : schedule.getScheduleType().trim().toLowerCase();
        return switch (type) {
            case "weekly" -> matchesWeekly(schedule, now);
            case "monthly" -> matchesMonthly(schedule, now);
            default -> true; // daily
        };
    }

    private static boolean alreadyRanThisMinute(LocalDateTime lastRun, LocalDateTime now) {
        if (lastRun == null) {
            return false;
        }
        return lastRun.getYear() == now.getYear()
                && lastRun.getDayOfYear() == now.getDayOfYear()
                && lastRun.getHour() == now.getHour()
                && lastRun.getMinute() == now.getMinute();
    }

    private static boolean matchesWeekly(BackupSchedule schedule, LocalDateTime now) {
        int expected = schedule.getDayOfWeek() != null ? schedule.getDayOfWeek() : 1; // 默认周一
        if (expected < 1 || expected > 7) {
            expected = 1;
        }
        return now.getDayOfWeek().getValue() == expected;
    }

    private static boolean matchesMonthly(BackupSchedule schedule, LocalDateTime now) {
        int expected = schedule.getDayOfMonth() != null ? schedule.getDayOfMonth() : 1;
        if (expected < 1) {
            expected = 1;
        }
        int lastDay = now.toLocalDate().lengthOfMonth();
        int day = Math.min(expected, lastDay); // 31 日在 2 月落到月末
        return now.getDayOfMonth() == day;
    }
}
