package org.congcong.controlmanager.dto.rewrite;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 按行导入改写条目：{@code 域名模式 host:port [备注]}，空行和以 # 开头的行忽略。
 */
@Data
public class RewriteTableImportRequest {

    @NotNull(message = "导入内容不能为空")
    private String text;

    /** true 时替换现有条目，false 时追加；已存在的域名模式按导入内容更新。 */
    private boolean replace;
}
