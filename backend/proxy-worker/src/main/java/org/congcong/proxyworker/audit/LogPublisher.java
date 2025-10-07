package org.congcong.proxyworker.audit;

import org.congcong.proxyworker.audit.dto.AuthLog;
import org.congcong.proxyworker.audit.dto.AccessLog;

/**
 * 日志发布接口
 * 将日志对象放入内存队列进行异步发送。
 */
public interface LogPublisher {
    /**
     * 发布认证日志（异步入队）。
     */
    void publishAuth(AuthLog log);

    /**
     * 发布访问日志（异步入队）。
     */
    void publishAccess(AccessLog log);

    /**
     * 关闭发布器，释放资源。
     */
    void shutdown();
}