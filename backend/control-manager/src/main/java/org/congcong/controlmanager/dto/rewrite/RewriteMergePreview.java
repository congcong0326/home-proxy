package org.congcong.controlmanager.dto.rewrite;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并预览。{@link BindingChange#getAfter()} 中的 null 代表新建的改写表路由。
 */
@Data
public class RewriteMergePreview {
    private List<RewriteEntryDTO> entries = new ArrayList<>();
    private List<BindingChange> bindings = new ArrayList<>();
    /** 执行后新建的改写表与路由 ID；预览时为空。 */
    private Long tableId;
    private Long routeId;

    @Data
    public static class BindingChange {
        private Long inboundId;
        private String inboundName;
        /** 从 1 开始的绑定组序号。 */
        private int bindingIndex;
        private List<Long> userIds;
        private List<Long> before;
        private List<Long> after;
        /** 夹在被合并路由之间的其他路由：合并后它们与改写条目的先后关系会改变。 */
        private List<String> reorderedRoutes = new ArrayList<>();
        /** 该绑定原本没有、合并后会获得的服务（被合并路由名）。 */
        private List<String> gainedRoutes = new ArrayList<>();
    }
}
