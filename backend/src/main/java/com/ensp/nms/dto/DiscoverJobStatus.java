package com.ensp.nms.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
