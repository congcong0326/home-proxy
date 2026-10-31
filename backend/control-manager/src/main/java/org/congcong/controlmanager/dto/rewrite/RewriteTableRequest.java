package org.congcong.controlmanager.dto.rewrite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建／更新改写表请求，更新时整表替换条目列表。
 */
@Data
public class RewriteTableRequest {

    @NotBlank(message = "改写表名称不能为空")
    @Size(max = 64, message = "改写表名称长度不能超过64个字符")
    private String name;

    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String notes;

    @Valid
    private List<RewriteEntryDTO> entries = new ArrayList<>();
}
