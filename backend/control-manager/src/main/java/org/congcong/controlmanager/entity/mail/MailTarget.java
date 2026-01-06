package org.congcong.controlmanager.entity.mail;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mail_target")
public class MailTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biz_key", nullable = false, length = 128, unique = true)
    private String bizKey;

    @Column(name = "to_list", nullable = false, length = 1024)
    private String toList;

    @Column(name = "cc_list", length = 1024)
    private String ccList;

    @Column(name = "bcc_list", length = 1024)
    private String bccList;

    @Column(name = "gateway_id")
    private Long gatewayId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
