package com.ensp.nms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "aiops_unattended_step", indexes = {
        @Index(name = "idx_unattended_step_run", columnList = "run_id")
})
public class AiopsUnattendedStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Integer seq;

    @Column(length = 64, nullable = false)
    private String tool;

    @Column(name = "args_json", columnDefinition = "TEXT")
    private String argsJson;

    @Column(nullable = false)
    private Boolean ok = false;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
