package org.congcong.controlmanager.dto.rewrite;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RewriteTableView {
    private Long id;
    private String name;
    private String notes;
    private List<RewriteEntryDTO> entries;
    /** 引用该改写表的路由数量。 */
    private long routeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
