package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    /**
     * 数据列表
     */
    private List<T> items;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码
     */
    private int page;

    /**
     * 每页大小
     */
    private int pageSize;

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int pageSize) {
        return new PageResponse<>(items, total, page, pageSize);
    }
}