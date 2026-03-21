package org.congcong.controlmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.congcong.common.dto.RuleSetItemDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data
@Entity
@Table(name = "rule_set_payload")
public class RuleSetPayloadEntity {

    @Id
    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false, columnDefinition = "JSON")
    private List<RuleSetItemDTO> items;
}
