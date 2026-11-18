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
 * 批次僵尸回收：服务启动时把残留的 RUNNING 批次标记为 INTERRUPTED。
 *
 * 执行批次靠进程内的守护线程，进程一停线程就没了，状态却留在库里。
 * 不回收的话前端永远显示“运行中”，报告也永远出不来。
 *
 * 保留全部已跑完的项快照；等人工确认的 PENDING 项仍可继续确认，
 * 确认齐了批次会正常收敛为 DONE。
 */
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
