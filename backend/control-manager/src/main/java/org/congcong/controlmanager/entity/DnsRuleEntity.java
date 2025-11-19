package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.congcong.common.enums.DnsMatchType;
import org.congcong.common.enums.DnsRecordType;
import org.congcong.common.enums.DnsRuleAction;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "dns_rule")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsRuleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 域名匹配模式：
     *  - EXACT: 例如 "my-next-cloud."
     *  - SUFFIX: 例如 ".example.com"
     */
    @Column(name = "domain_pattern", nullable = false, length = 255)
    private String domainPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private DnsMatchType matchType;

    /**
     * 记录类型，目前只支持 A / AAAA
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 10)
    private DnsRecordType recordType;

    /**
     * 本地返回的 IPv4 地址（LOCAL_ANSWER + A 时使用）
     */
    @Column(name = "answer_ipv4", length = 64)
    private String answerIpv4;

    /**
     * 本地返回的 IPv6 地址（LOCAL_ANSWER + AAAA 时使用）
     */
    @Column(name = "answer_ipv6", length = 128)
    private String answerIpv6;

    /**
     * TTL（秒）
     */
    @Column(name = "ttl_seconds", nullable = false)
    private Integer ttlSeconds;

    /**
     * 动作：LOCAL_ANSWER / FORWARD / BLOCK
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private DnsRuleAction action;

    /**
     * 规则优先级，数字越小越优先
     */
    @Column(name = "priority", nullable = false)
    private Integer priority;

    /**
     * 是否启用
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /**
     * 备注
     */
    @Column(name = "remark", length = 255)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}
