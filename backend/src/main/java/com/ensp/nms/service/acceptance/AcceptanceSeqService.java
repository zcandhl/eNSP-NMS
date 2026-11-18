package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceSeq;
import com.ensp.nms.repository.AcceptanceSeqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验收序号发号器：持久化自增，只增不减。
 *
 * 首次启用时用传入的 initFrom（历史批次里已有的最大序号）打底，
 * 之后与库中现存数据无关——删除批次不会让号段回退。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceSeqService {

    public static final String SCOPE_BATCH = "acceptance.batch";

    private final AcceptanceSeqRepository seqRepository;

    @Transactional
    public synchronized long next(String scope, long initFrom) {
        AcceptanceSeq seq = seqRepository.lockByScope(scope).orElse(null);
        if (seq == null) {
            seq = new AcceptanceSeq();
            seq.setScope(scope);
            seq.setValue(Math.max(0, initFrom));
        }
        seq.setValue(seq.getValue() + 1);
        seqRepository.saveAndFlush(seq);
        return seq.getValue();
    }
}
