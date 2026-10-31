package org.congcong.controlmanager.dto.rewrite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 把多条固定目标改写路由合并为一张改写表和一条引用它的路由。
 */
@Data
public class RewriteMergeRequest {

    /** 被合并的路由，顺序即条目顺序。 */
    @NotEmpty(message = "请选择要合并的路由")
    private List<Long> routeIds;

    @NotBlank(message = "改写表名称不能为空")
    @Size(max = 64, message = "改写表名称长度不能超过64个字符")
    private String tableName;

    @NotBlank(message = "路由名称不能为空")
    @Size(max = 64, message = "路由名称长度不能超过64个字符")
    private String routeName;

    /** true 时只返回预览，不写入。 */
    private boolean dryRun = true;
}
