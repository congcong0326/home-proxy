package org.congcong.controlmanager.repository.mail;

import org.congcong.controlmanager.entity.mail.MailSendLog;
import org.congcong.controlmanager.enums.MailSendStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MailSendLogRepository extends JpaRepository<MailSendLog, Long> {
    @Query("SELECT l FROM MailSendLog l WHERE " +
            "(:bizKey IS NULL OR l.bizKey = :bizKey) AND " +
            "(:status IS NULL OR l.status = :status) AND " +
            "(:startAt IS NULL OR l.createdAt >= :startAt) AND " +
            "(:endAt IS NULL OR l.createdAt <= :endAt)")
    Page<MailSendLog> queryLogs(@Param("bizKey") String bizKey,
                                @Param("status") MailSendStatus status,
                                @Param("startAt") LocalDateTime startAt,
                                @Param("endAt") LocalDateTime endAt,
                                Pageable pageable);

    Optional<MailSendLog> findTopByBizKeyAndRequestIdOrderByIdDesc(String bizKey, String requestId);
}
