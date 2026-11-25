package com.ensp.nms.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备发现任务进度与候选集合状态。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Data
public class DiscoverJobStatus {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private String jobId;
    private Status status = Status.RUNNING;
    private int total;
    private int scanned;
    private int found;
    private String message;
    private String network;
    private String community;
    private Integer snmpPort;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private List<DiscoverCandidate> candidates = Collections.synchronizedList(new ArrayList<>());

    public void addCandidate(DiscoverCandidate candidate) {
        candidates.add(candidate);
        found = candidates.size();
    }
}
