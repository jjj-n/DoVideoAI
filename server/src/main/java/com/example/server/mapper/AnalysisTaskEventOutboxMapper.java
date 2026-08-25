package com.example.server.mapper;

import com.example.server.entity.AnalysisTaskEventOutbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnalysisTaskEventOutboxMapper {

    @Insert("""
            INSERT INTO analysis_task_event_outbox(media_id, event_key, event_payload)
            VALUES(#{mediaId}, #{eventKey}, #{eventPayload})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalysisTaskEventOutbox event);

    @Select("""
            SELECT id
            FROM analysis_task_event_outbox
            WHERE published_at IS NULL
              AND next_attempt_at <= #{now}
              AND (claim_token IS NULL OR claimed_until IS NULL OR claimed_until < #{now})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> findPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE analysis_task_event_outbox
            SET claim_token = #{claimToken}, claimed_until = #{claimedUntil}
            WHERE id = #{id}
              AND published_at IS NULL
              AND next_attempt_at <= #{now}
              AND (claim_token IS NULL OR claimed_until IS NULL OR claimed_until < #{now})
            """)
    int claim(@Param("id") Long id,
              @Param("claimToken") String claimToken,
              @Param("now") LocalDateTime now,
              @Param("claimedUntil") LocalDateTime claimedUntil);

    @Select("""
            SELECT id,
                   event_key AS eventKey,
                   event_payload AS eventPayload,
                   attempt_count AS attemptCount,
                   claim_token AS claimToken
            FROM analysis_task_event_outbox
            WHERE id = #{id} AND claim_token = #{claimToken} AND published_at IS NULL
            """)
    AnalysisTaskEventOutbox findClaimed(@Param("id") Long id,
                                        @Param("claimToken") String claimToken);

    @Update("""
            UPDATE analysis_task_event_outbox
            SET published_at = #{publishedAt}, claim_token = NULL, claimed_until = NULL, last_error = NULL
            WHERE id = #{id} AND claim_token = #{claimToken} AND published_at IS NULL
            """)
    int markPublished(@Param("id") Long id,
                      @Param("claimToken") String claimToken,
                      @Param("publishedAt") LocalDateTime publishedAt);

    @Update("""
            UPDATE analysis_task_event_outbox
            SET attempt_count = attempt_count + 1,
                next_attempt_at = #{nextAttemptAt},
                last_error = #{lastError},
                claim_token = NULL,
                claimed_until = NULL
            WHERE id = #{id} AND claim_token = #{claimToken} AND published_at IS NULL
            """)
    int markFailed(@Param("id") Long id,
                   @Param("claimToken") String claimToken,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("lastError") String lastError);

    @Delete("""
            DELETE FROM analysis_task_event_outbox
            WHERE published_at IS NOT NULL AND published_at < #{cutoff}
            """)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);

    @Delete("DELETE FROM analysis_task_event_outbox WHERE media_id = #{mediaId}")
    int deleteByMediaId(@Param("mediaId") Long mediaId);
}
