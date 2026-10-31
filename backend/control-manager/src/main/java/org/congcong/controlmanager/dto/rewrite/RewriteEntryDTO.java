package org.congcong.controlmanager.dto.rewrite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 改写表条目。请求中列表顺序即匹配顺序；响应中的 id 跨保存保持稳定，用于生成合成路由 ID。
 */
@Data
public class RewriteEntryDTO {
    private Long id;

    @NotBlank(message = "域名模式不能为空")
    @Size(max = 255, message = "域名模式长度不能超过255个字符")
    private String domainPattern;

    @NotBlank(message = "改写目标不能为空")
    @Size(max = 255, message = "改写目标长度不能超过255个字符")
    private String targetHost;

    @NotNull(message = "改写端口不能为空")
    private Integer targetPort;

    private Boolean enabled = true;

    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String notes;
}
