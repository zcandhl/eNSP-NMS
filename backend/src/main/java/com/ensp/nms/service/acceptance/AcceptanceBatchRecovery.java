package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceBatch;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常中断验收批次的启动恢复处理。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Slf4j
@Component
@RequiredArgsConstructor
public class AcceptanceBatchRecovery {

    private final AcceptanceBatchRepository batchRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOrphanBatches() {
        List<AcceptanceBatch> running = batchRepository.findByStatus("RUNNING");
        if (running.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AcceptanceBatch batch : running) {
            batch.setStatus("INTERRUPTED");
            batch.setFinishedAt(now);
            batch.setStatusMessage("服务重启导致执行中断，已完成的检查项快照保留；"
                    + "如需完整结论请重新发起验收，等待人工确认的项仍可继续确认。");
        }
        batchRepository.saveAll(running);
        log.warn("启动回收：{} 个残留批次已标记为中断（{}）", running.size(),
                running.stream().map(AcceptanceBatch::getBatchId).toList());
    }
}
