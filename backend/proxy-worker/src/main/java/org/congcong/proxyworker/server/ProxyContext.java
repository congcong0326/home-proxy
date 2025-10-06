package org.congcong.proxyworker.server;

import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.factory.ProxyServerFactory;

import java.util.*;

@Slf4j
public class ProxyContext {


    private final Map<Long, ProxyServer> servers = new HashMap<>();
    private final Map<Long, InboundConfig> configs = new HashMap<>();

    private ProxyContext() {}

    private static class Holder {
        private static final ProxyContext INSTANCE = new ProxyContext();
    }

    public static ProxyContext getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void refresh(List<InboundConfig> inboundConfigs) {
        Map<Long, InboundConfig> newConfigs = new HashMap<>();
        if (inboundConfigs != null) {
            for (InboundConfig cfg : inboundConfigs) {
                if (cfg != null && cfg.getId() != null) {
                    newConfigs.put(cfg.getId(), cfg);
                }
            }
        }

        // 1) 关闭并移除下线的服务
        for (Iterator<Map.Entry<Long, ProxyServer>> it = servers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, ProxyServer> entry = it.next();
            Long id = entry.getKey();
            if (!newConfigs.containsKey(id)) {
                ProxyServer server = entry.getValue();
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
            ProxyServer existing = servers.get(id);

            boolean needsRestart = (oldCfg == null) || !Objects.equals(oldCfg, newCfg);
            if (needsRestart) {
                // 关闭旧服务
                if (existing != null && existing.isRunning()) {
                    try {
                        existing.close();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                // 启动新服务
                ProxyServer newServer = ProxyServerFactory.create(newCfg);
                try {
                    newServer.start();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    continue;
                }
                servers.put(id, newServer);
                configs.put(id, newCfg);
            } else {
                log.info("配置未变更");
            }
        }
    }

    public synchronized void closeAll() {
        for (ProxyServer proxyServer : servers.values()) {
            try {
                proxyServer.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        servers.clear();
        configs.clear();
    }




}
