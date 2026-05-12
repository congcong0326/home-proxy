import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiService } from '../services/api';
import { UserTrafficStats, WolConfig, PcStatus } from '../types/dashboard';
import { DiskHost, DiskInfo } from '../types/disk';
import { formatBytes } from '../utils/format';

interface PanelState {
  traffic: UserTrafficStats[];
  wolConfigs: WolConfig[];
  pcStatuses: PcStatus[];
  hosts: DiskHost[];
  disks: DiskInfo[];
  loading: boolean;
}

const shortcuts = [
  { label: '流量概览', to: '/config/dashboard/traffic' },
  { label: '磁盘监控', to: '/config/system-ops/disk' },
  { label: '用户管理', to: '/config/users' },
  { label: '入站配置', to: '/config/inbound' },
  { label: '路由规则', to: '/config/routing' },
];

export const MobileStatusPanel: React.FC = () => {
  const [state, setState] = useState<PanelState>({
    traffic: [],
    wolConfigs: [],
    pcStatuses: [],
    hosts: [],
    disks: [],
    loading: true,
  });

  useEffect(() => {
    let alive = true;

    const load = async () => {
      try {
        const [traffic, wolConfigs, pcStatuses, hosts] = await Promise.all([
          apiService.getDailyUserTrafficStats().catch(() => []),
          apiService.getWolConfigs().catch(() => []),
          apiService.getAllPcStatus().catch(() => []),
          apiService.getDiskHosts().catch(() => []),
        ]);
        const firstHostId = hosts[0]?.hostId;
        const disks = firstHostId ? await apiService.getDisks(firstHostId).catch(() => []) : [];
        if (!alive) return;
        setState({ traffic, wolConfigs, pcStatuses, hosts, disks, loading: false });
      } catch {
        if (!alive) return;
        setState((prev) => ({ ...prev, loading: false }));
      }
    };

    load();
    return () => {
      alive = false;
    };
  }, []);

  const totalTraffic = useMemo(
    () => state.traffic.reduce((sum, item) => sum + item.totalBytes, 0),
    [state.traffic]
  );
  const onlineCount = state.pcStatuses.filter((item) => item.online).length;
  const enabledWolCount = state.wolConfigs.filter((item) => item.enabled).length;
  const diskOk = state.disks.length === 0
    ? '无数据'
    : state.disks.every((disk) => /PASS|OK/i.test(disk.status || '')) ? '磁盘 OK' : '需检查';

  return (
    <section className="mobile-status-panel">
      <div className="retro-screen mobile-status-hero">
        <div className="mobile-status-title">掌上状态面板</div>
        <div className="mobile-status-line">&gt; HOME-PROXY NOC ONLINE</div>
        <div className="mobile-status-line">&gt; {state.loading ? 'LOADING...' : 'READY'}</div>
      </div>

      <div className="mobile-status-grid">
        <div className="mobile-status-card">
          <span>今日流量</span>
          <strong>{formatBytes(totalTraffic)}</strong>
        </div>
        <div className="mobile-status-card">
          <span>磁盘健康</span>
          <strong>{diskOk}</strong>
        </div>
        <div className="mobile-status-card">
          <span>WOL 在线</span>
          <strong>{onlineCount} 在线</strong>
        </div>
        <div className="mobile-status-card">
          <span>WOL 设备</span>
          <strong>{enabledWolCount}</strong>
        </div>
      </div>

      <Link className="mobile-wol-action" to="/config/dashboard/wol">
        WOL 唤醒
      </Link>

      <div className="mobile-shortcuts">
        {shortcuts.map((item) => (
          <Link key={item.to} to={item.to} className="mobile-shortcut">
            {item.label}
          </Link>
        ))}
      </div>
    </section>
  );
};
