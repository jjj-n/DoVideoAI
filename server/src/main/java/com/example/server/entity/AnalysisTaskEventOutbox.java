package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_task_event_outbox")
public class AnalysisTaskEventOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String eventKey;
    private String eventPayload;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String claimToken;
    private LocalDateTime claimedUntil;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
}
