package org.congcong.controlmanager.entity.mail;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.controlmanager.enums.MailSendStatus;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mail_send_log", indexes = {
        @Index(name = "idx_send_log_biz_key_created", columnList = "biz_key, created_at")
})
public class MailSendLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biz_key", nullable = false, length = 128)
    private String bizKey;

    @Column(name = "gateway_id")
    private Long gatewayId;

    @Column(name = "to_list", length = 1024)
    private String toList;

    @Column(name = "cc_list", length = 1024)
    private String ccList;

    @Column(name = "bcc_list", length = 1024)
    private String bccList;

    @Column(length = 255)
    private String subject;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "content_size")
    private Integer contentSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MailSendStatus status;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
