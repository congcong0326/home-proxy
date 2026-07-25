package org.congcong.proxyworker.server;

import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.factory.ProxyServerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ProxyContext {


    private final Map<Long, AbstractProxyServer> servers = new HashMap<>();
    private final Map<Long, InboundConfig> configs = new HashMap<>();
    private final AtomicInteger activeConnectionCount = new AtomicInteger(0);

    private ProxyContext() {}

    private static class Holder {
        private static final ProxyContext INSTANCE = new ProxyContext();
    }

    public static ProxyContext getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized ConfigApplyResult refresh(List<InboundConfig> inboundConfigs) {
        List<InboundConfig> previous = new ArrayList<>(configs.values());
        java.util.concurrent.atomic.AtomicReference<Long> activeInboundId = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            apply(inboundConfigs, activeInboundId::set);
            return ConfigApplyResult.applied();
        } catch (Exception error) {
            log.error("代理配置应用失败，正在恢复上一份运行配置", error);
            closeAll();
            try {
                apply(previous, ignored -> { });
            } catch (Exception rollbackError) {
                log.error("代理配置回滚失败", rollbackError);
                error.addSuppressed(rollbackError);
            }
            return ConfigApplyResult.failure(activeInboundId.get(), error);
        }
    }

    private void apply(List<InboundConfig> inboundConfigs, java.util.function.Consumer<Long> activeInboundConsumer) throws Exception {
        Map<Long, InboundConfig> newConfigs = new HashMap<>();
        if (inboundConfigs != null) {
            for (InboundConfig cfg : inboundConfigs) {
                if (cfg != null && cfg.getId() != null) {
                    newConfigs.put(cfg.getId(), cfg);
                }
            }
        }

        // 1) 关闭并移除下线的服务
        for (Iterator<Map.Entry<Long, AbstractProxyServer>> it = servers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, AbstractProxyServer> entry = it.next();
            Long id = entry.getKey();
            if (!newConfigs.containsKey(id)) {
                AbstractProxyServer server = entry.getValue();
                try {
                    server.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                it.remove();
                configs.remove(id);
            }
        }

        // 2) 更新变更的服务（关闭旧的，启动新的）与新增的服务
        for (Map.Entry<Long, InboundConfig> e : newConfigs.entrySet()) {
            Long id = e.getKey();
            InboundConfig newCfg = e.getValue();
            InboundConfig oldCfg = configs.get(id);
            AbstractProxyServer existing = servers.get(id);

            boolean needsRestart = (oldCfg == null) || !Objects.equals(oldCfg, newCfg);
            if (needsRestart) {
                activeInboundConsumer.accept(id);
                // 关闭旧服务
                if (existing != null && existing.isRunning()) {
                    try {
                        existing.close();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                // 启动新服务
                AbstractProxyServer newServer = ProxyServerFactory.create(newCfg);
                newServer.start();
                servers.put(id, newServer);
                configs.put(id, newCfg);
            } else {
                log.info("配置未变更");
            }
        }
    }

    public synchronized void closeAll() {
        for (AbstractProxyServer proxyServer : servers.values()) {
            try {
                proxyServer.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        servers.clear();
        configs.clear();
    }

    public synchronized int getRunningServerCount() {
        int count = 0;
        for (AbstractProxyServer server : servers.values()) {
            if (server != null && server.isRunning()) {
                count++;
            }
        }
        return count;
    }

    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }

    public void incrementActiveConnectionCount() {
        activeConnectionCount.incrementAndGet();
    }

    public void decrementActiveConnectionCount() {
        activeConnectionCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    void resetActiveConnectionCountForTest() {
        activeConnectionCount.set(0);
    }



}
