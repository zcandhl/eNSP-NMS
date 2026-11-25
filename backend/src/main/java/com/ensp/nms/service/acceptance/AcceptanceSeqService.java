package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceSeq;
import com.ensp.nms.repository.AcceptanceSeqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验收批次单号序列生成。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

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
