# Retro NOC UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the React management console to a 90s-style Retro NOC interface while keeping desktop configuration complete and making mobile a status-first light operations console.

**Architecture:** Add one shared Retro NOC theme layer, then adapt layout and high-priority pages to use it. Desktop keeps the existing route structure and Ant Design interactions; mobile uses a drawer menu plus a new status-first dashboard panel.

**Tech Stack:** React 19, TypeScript, React Router, Redux Toolkit, Ant Design 5, Chart.js, Jest/React Testing Library, CSS media queries.

---

## File Structure

- Create `frontend/src/styles/retro-noc.css`: shared CSS variables, Retro NOC Ant Design overrides, panel/button/status/table/form styles, mobile responsive rules.
- Create `frontend/src/components/MobileStatusPanel.tsx`: mobile-first status summary and shortcut grid for WOL, traffic, disk, users, inbound, and routes.
- Create `frontend/src/components/MobileStatusPanel.test.tsx`: API-mocked smoke tests for mobile summary and shortcut links.
- Modify `frontend/src/index.tsx`: import shared Retro NOC CSS after `index.css`.
- Modify `frontend/src/App.tsx`: set Ant Design theme tokens to match the Retro NOC palette.
- Modify `frontend/src/pages/ProxyConfig.tsx`: add mobile drawer navigation, system status header text, and mobile-safe menu behavior.
- Modify `frontend/src/pages/ProxyConfig.css`: replace modern blue admin shell with Retro NOC layout and responsive drawer/content rules.
- Modify `frontend/src/pages/Login.css`: replace gradient login with Retro NOC login window.
- Modify `frontend/src/pages/Dashboard.tsx`: render `MobileStatusPanel` as the mobile dashboard surface while keeping desktop tabs.
- Modify `frontend/src/pages/Dashboard.css`: apply Retro NOC cards, charts, WOL device cards, modal form, and mobile table/card behavior.
- Modify `frontend/src/pages/UserManagement.css` and `frontend/src/pages/RouteManagement.css`: align stats/toolbars/tables/modals with the Retro NOC theme and add narrow-screen table overflow rules.
- Modify `frontend/src/pages/InboundManagement.tsx`: import a page stylesheet if needed.
- Create `frontend/src/pages/InboundManagement.css` if `retro-noc.css` is not enough for inbound-specific mobile form/table layout.
- Update existing tests only when markup or duplicate menu counts change.

## Task 1: Shared Retro NOC Theme Layer

**Files:**
- Create: `frontend/src/styles/retro-noc.css`
- Modify: `frontend/src/index.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add the shared CSS file**

Create `frontend/src/styles/retro-noc.css` with these sections and keep selectors global because the current codebase already uses page-level global CSS:

```css
:root {
  --retro-bg: #bfc4bf;
  --retro-panel: #d6d8d3;
  --retro-panel-light: #f1f3ee;
  --retro-panel-dark: #8b908b;
  --retro-border: #111813;
  --retro-primary: #003b46;
  --retro-primary-light: #2c6a72;
  --retro-screen: #101812;
  --retro-screen-text: #39ff88;
  --retro-screen-muted: #d9fff4;
  --retro-warning: #b97900;
  --retro-danger: #9b1c1c;
  --retro-ok: #126b3f;
  --retro-text: #101812;
  --retro-muted: #4b554d;
  --retro-radius: 2px;
  --retro-font: "Courier New", Consolas, "Liberation Mono", monospace;
  --retro-ui-font: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
}

body {
  background: var(--retro-bg);
  color: var(--retro-text);
  font-family: var(--retro-ui-font);
}

.retro-panel,
.ant-card,
.content-wrapper,
.config-overview {
  background: var(--retro-panel);
  border: 2px solid var(--retro-border);
  border-radius: var(--retro-radius);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark);
}

.retro-screen {
  background: var(--retro-screen);
  border: 2px solid #000;
  color: var(--retro-screen-text);
  font-family: var(--retro-font);
}

.retro-status-light {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--retro-font);
}

.retro-status-light::before {
  content: "";
  width: 9px;
  height: 9px;
  background: var(--retro-screen-text);
  border: 1px solid #022b13;
  box-shadow: 0 0 8px rgba(57, 255, 136, 0.75);
}

.ant-btn {
  border-radius: var(--retro-radius);
  box-shadow: inset 1px 1px 0 #ffffff, inset -1px -1px 0 var(--retro-panel-dark);
  font-weight: 700;
}

.ant-btn-primary {
  background: var(--retro-primary);
  border-color: var(--retro-border);
  color: var(--retro-screen-muted);
}

.ant-btn-primary:hover {
  background: var(--retro-primary-light) !important;
  border-color: var(--retro-border) !important;
}

.ant-table-wrapper,
.ant-table {
  border-radius: var(--retro-radius);
}

.ant-table-thead > tr > th {
  background: var(--retro-primary) !important;
  color: var(--retro-screen-muted) !important;
  font-family: var(--retro-font);
}

.ant-tag {
  border-radius: var(--retro-radius);
  font-family: var(--retro-font);
  font-weight: 700;
}

@media (max-width: 768px) {
  .ant-table-wrapper {
    overflow-x: auto;
  }

  .ant-modal {
    max-width: calc(100vw - 24px);
  }

  .ant-card,
  .content-wrapper,
  .config-overview {
    box-shadow: inset 1px 1px 0 #ffffff, inset -1px -1px 0 var(--retro-panel-dark);
  }
}
```

- [ ] **Step 2: Import the theme globally**

Modify `frontend/src/index.tsx` so the theme loads after base reset styles:

```ts
import './index.css';
import './styles/retro-noc.css';
```

- [ ] **Step 3: Set Ant Design theme tokens**

Modify the `ConfigProvider` in `frontend/src/App.tsx`:

```tsx
<ConfigProvider
  locale={zhCN}
  theme={{
    token: {
      colorPrimary: '#003b46',
      colorSuccess: '#126b3f',
      colorWarning: '#b97900',
      colorError: '#9b1c1c',
      colorText: '#101812',
      colorBgLayout: '#bfc4bf',
      colorBgContainer: '#d6d8d3',
      borderRadius: 2,
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
    },
    components: {
      Layout: {
        bodyBg: '#bfc4bf',
        headerBg: '#d6d8d3',
        siderBg: '#d6d8d3',
      },
      Menu: {
        darkItemBg: '#d6d8d3',
        darkSubMenuItemBg: '#c8cbc6',
        darkItemColor: '#101812',
        darkItemSelectedBg: '#003b46',
        darkItemSelectedColor: '#d9fff4',
      },
    },
  }}
>
```

- [ ] **Step 4: Verify the app still boots**

Run: `cd frontend && npm test -- --watch=false App.test.tsx`

Expected: PASS with the two existing App tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/styles/retro-noc.css frontend/src/index.tsx frontend/src/App.tsx
git commit -m "feat: add retro noc theme layer"
```

## Task 2: Main Shell And Mobile Drawer

**Files:**
- Modify: `frontend/src/pages/ProxyConfig.tsx`
- Modify: `frontend/src/pages/ProxyConfig.css`
- Modify: `frontend/src/pages/ProxyConfig.test.tsx`

- [ ] **Step 1: Add a mobile drawer test**

Append this test to `frontend/src/pages/ProxyConfig.test.tsx`. It locks in the requirement that mobile users can open navigation without a desktop sidebar:

```tsx
test('opens mobile navigation drawer from the header menu button', async () => {
  renderProxyConfig('/config');

  const menuButton = await screen.findByRole('button', { name: /打开导航|收起导航|展开导航/ });
  fireEvent.click(menuButton);

  await waitFor(() => expect(screen.getByText('NETWORK ADMIN MENU')).toBeInTheDocument());
  expect(screen.getAllByText('WOL唤醒页面').length).toBeGreaterThan(0);
  expect(screen.getAllByText('路由规则').length).toBeGreaterThan(0);
});
```

Also update the import line:

```ts
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
```

- [ ] **Step 2: Verify the new test fails**

Run: `cd frontend && npm test -- --watch=false ProxyConfig.test.tsx`

Expected: FAIL because the drawer title `NETWORK ADMIN MENU` does not exist yet.

- [ ] **Step 3: Add drawer state and shared menu renderer**

In `frontend/src/pages/ProxyConfig.tsx`, add `Drawer` to the Ant Design import:

```ts
Drawer,
Grid
```

Add these values inside `ProxyConfig`:

```tsx
const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
const screens = Grid.useBreakpoint();
const isMobile = screens.md === false;
```

Replace the direct `<Menu ... />` with a `renderMenu` helper:

```tsx
const renderMenu = () => (
  <Menu
    theme="dark"
    mode="inline"
    selectedKeys={[location.pathname]}
    defaultOpenKeys={['dashboard', 'system-ops', 'proxy-config', 'access-overview']}
    className="proxy-config-menu"
    items={menuItems}
    onClick={({ key }) => {
      handleMenuClick(key);
      setMobileMenuOpen(false);
    }}
  />
);
```

Use `{renderMenu()}` inside the desktop `Sider`.

- [ ] **Step 4: Make the header button mobile-aware**

Replace the collapse button with:

```tsx
<Button
  type="text"
  aria-label={isMobile ? '打开导航' : collapsed ? '展开导航' : '收起导航'}
  icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
  onClick={() => {
    if (isMobile) {
      setMobileMenuOpen(true);
      return;
    }
    setCollapsed(!collapsed);
  }}
  className="collapse-btn"
/>
```

- [ ] **Step 5: Add the mobile drawer after the desktop Sider**

Add this immediately after `</Sider>`:

```tsx
<Drawer
  title="NETWORK ADMIN MENU"
  placement="left"
  open={mobileMenuOpen}
  onClose={() => setMobileMenuOpen(false)}
  className="retro-nav-drawer"
  width={300}
>
  <div className="proxy-config-logo mobile">
    <div className="logo-icon">
      <GlobalOutlined />
    </div>
    <div className="logo-text">
      <Title level={4} className="logo-title">NAS代理</Title>
      <Text className="logo-subtitle">Retro NOC Console</Text>
    </div>
  </div>
  {renderMenu()}
</Drawer>
```

- [ ] **Step 6: Rewrite shell CSS for Retro NOC and mobile**

Update `frontend/src/pages/ProxyConfig.css` to make these behavior changes:

```css
.proxy-config-layout {
  min-height: 100vh;
  background: var(--retro-bg);
}

.proxy-config-sider {
  background: var(--retro-panel) !important;
  border-right: 2px solid var(--retro-border);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark);
}

.proxy-config-logo {
  background: var(--retro-primary);
  border: 2px solid var(--retro-border);
  box-shadow: inset 1px 1px 0 var(--retro-primary-light);
  margin: 10px;
  padding: 14px 12px;
}

.logo-title,
.logo-subtitle,
.logo-icon {
  color: var(--retro-screen-muted) !important;
  font-family: var(--retro-font);
}

.proxy-config-menu .ant-menu-item,
.proxy-config-menu .ant-menu-submenu-title {
  border-radius: var(--retro-radius);
  font-family: var(--retro-font);
  font-weight: 700;
}

.proxy-config-menu .ant-menu-item-selected {
  background: var(--retro-primary) !important;
  color: var(--retro-screen-muted) !important;
  transform: none;
  box-shadow: inset 1px 1px 0 var(--retro-primary-light);
}

.proxy-config-header {
  height: 56px;
  background: var(--retro-panel);
  border-bottom: 2px solid var(--retro-border);
  box-shadow: inset 0 1px 0 #ffffff;
}

.proxy-config-header::before {
  content: "HOME-PROXY NETWORK ADMIN";
  color: var(--retro-primary);
  font-family: var(--retro-font);
  font-size: 12px;
  font-weight: 700;
  margin-right: 12px;
}

.proxy-config-content {
  background: var(--retro-bg);
  padding: 18px;
}

.retro-nav-drawer .ant-drawer-header,
.retro-nav-drawer .ant-drawer-body {
  background: var(--retro-panel);
  border-color: var(--retro-border);
}

.retro-nav-drawer .ant-drawer-title {
  font-family: var(--retro-font);
  color: var(--retro-primary);
}

@media (max-width: 768px) {
  .proxy-config-sider {
    display: none;
  }

  .proxy-config-header {
    padding: 0 10px;
  }

  .proxy-config-header::before,
  .welcome-text,
  .header-breadcrumb {
    display: none;
  }

  .proxy-config-content {
    padding: 10px;
  }

  .content-wrapper {
    min-height: calc(100vh - 76px);
  }
}
```

Keep any existing loading/error utility selectors that are still referenced.

- [ ] **Step 7: Verify shell tests pass**

Run: `cd frontend && npm test -- --watch=false ProxyConfig.test.tsx`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/ProxyConfig.tsx frontend/src/pages/ProxyConfig.css frontend/src/pages/ProxyConfig.test.tsx
git commit -m "feat: add retro noc responsive shell"
```

## Task 3: Mobile Status Dashboard

**Files:**
- Create: `frontend/src/components/MobileStatusPanel.tsx`
- Create: `frontend/src/components/MobileStatusPanel.test.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/Dashboard.css`

- [ ] **Step 1: Write tests for the mobile status panel**

Create `frontend/src/components/MobileStatusPanel.test.tsx`:

```tsx
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MobileStatusPanel } from './MobileStatusPanel';
import { apiService } from '../services/api';

jest.mock('../services/api', () => ({
  apiService: {
    getDailyUserTrafficStats: jest.fn(),
    getWolConfigs: jest.fn(),
    getAllPcStatus: jest.fn(),
    getDiskHosts: jest.fn(),
    getDisks: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
  (apiService.getDailyUserTrafficStats as jest.Mock).mockResolvedValue([
    { userId: 1, username: 'admin', byteIn: 1024, byteOut: 2048, totalBytes: 3072 },
  ]);
  (apiService.getWolConfigs as jest.Mock).mockResolvedValue([
    { id: 1, name: 'NAS', ipAddress: '10.0.0.2', subnetMask: '255.255.255.255', macAddress: 'AA:BB:CC:DD:EE:FF', wolPort: 9, status: 1, enabled: true, createdAt: '', updatedAt: '', online: false },
  ]);
  (apiService.getAllPcStatus as jest.Mock).mockResolvedValue([{ name: 'NAS', ip: '10.0.0.2', online: true, enabled: true, macAddress: 'AA:BB:CC:DD:EE:FF', wolPort: 9 }]);
  (apiService.getDiskHosts as jest.Mock).mockResolvedValue([{ hostId: 'nas-main', hostName: 'NAS Main', lastSeenAt: new Date().toISOString() }]);
  (apiService.getDisks as jest.Mock).mockResolvedValue([{ device: 'sda', model: 'Disk One', serial: 'SN001', size: '1 TB', status: 'PASSED', temperature: 35 }]);
});

test('renders traffic, disk, wol, and management shortcuts', async () => {
  render(
    <MemoryRouter>
      <MobileStatusPanel />
    </MemoryRouter>
  );

  expect(await screen.findByText('掌上状态面板')).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('3 KB')).toBeInTheDocument());
  expect(screen.getByText('磁盘 OK')).toBeInTheDocument();
  expect(screen.getByText('1 在线')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /WOL 唤醒/ })).toHaveAttribute('href', '/config/dashboard/wol');
  expect(screen.getByRole('link', { name: /用户管理/ })).toHaveAttribute('href', '/config/users');
  expect(screen.getByRole('link', { name: /路由规则/ })).toHaveAttribute('href', '/config/routing');
});
```

- [ ] **Step 2: Verify the test fails**

Run: `cd frontend && npm test -- --watch=false MobileStatusPanel.test.tsx`

Expected: FAIL because `MobileStatusPanel` does not exist.

- [ ] **Step 3: Implement `MobileStatusPanel`**

Create `frontend/src/components/MobileStatusPanel.tsx`:

```tsx
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
          <span>监控主机</span>
          <strong>{state.hosts.length}</strong>
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
```

- [ ] **Step 4: Render the panel from Dashboard**

Modify `frontend/src/pages/Dashboard.tsx`:

```tsx
import { MobileStatusPanel } from '../components/MobileStatusPanel';
```

Render it before the existing desktop dashboard:

```tsx
return (
  <>
    <MobileStatusPanel />
    <div className="dashboard dashboard-desktop">
      ...
    </div>
  </>
);
```

- [ ] **Step 5: Add mobile dashboard CSS**

Append to `frontend/src/pages/Dashboard.css`:

```css
.mobile-status-panel {
  display: none;
}

@media (max-width: 768px) {
  .dashboard-desktop {
    display: none;
  }

  .mobile-status-panel {
    display: block;
    padding: 10px;
  }

  .mobile-status-hero {
    padding: 12px;
    margin-bottom: 10px;
    line-height: 1.6;
  }

  .mobile-status-title {
    color: var(--retro-screen-muted);
    font-family: var(--retro-font);
    font-weight: 700;
    margin-bottom: 6px;
  }

  .mobile-status-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .mobile-status-card,
  .mobile-shortcut,
  .mobile-wol-action {
    background: var(--retro-panel);
    border: 2px solid var(--retro-border);
    box-shadow: inset 1px 1px 0 #ffffff, inset -1px -1px 0 var(--retro-panel-dark);
    color: var(--retro-text);
    text-decoration: none;
  }

  .mobile-status-card {
    min-height: 70px;
    padding: 9px;
  }

  .mobile-status-card span {
    display: block;
    color: var(--retro-muted);
    font-family: var(--retro-font);
    font-size: 11px;
  }

  .mobile-status-card strong {
    display: block;
    margin-top: 10px;
    font-size: 18px;
  }

  .mobile-wol-action {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 46px;
    margin: 10px 0;
    background: var(--retro-primary);
    color: var(--retro-screen-muted);
    font-family: var(--retro-font);
    font-weight: 700;
  }

  .mobile-shortcuts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .mobile-shortcut {
    min-height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: var(--retro-font);
    font-size: 12px;
    font-weight: 700;
  }
}
```

- [ ] **Step 6: Verify tests pass**

Run: `cd frontend && npm test -- --watch=false MobileStatusPanel.test.tsx App.test.tsx`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/MobileStatusPanel.tsx frontend/src/components/MobileStatusPanel.test.tsx frontend/src/pages/Dashboard.tsx frontend/src/pages/Dashboard.css
git commit -m "feat: add mobile retro status dashboard"
```

## Task 4: Status Pages Retro Styling

**Files:**
- Modify: `frontend/src/pages/Dashboard.css`
- Modify: `frontend/src/pages/DiskMonitor.tsx` only if mobile column props must change
- Modify: `frontend/src/pages/DiskMonitor.test.tsx` only if accessible text changes

- [ ] **Step 1: Extend dashboard/status CSS**

Update existing `.dashboard`, `.traffic-overview`, `.dashboard-card`, `.traffic-stat-item`, `.trend-controls`, `.wol-*`, `.device-*`, `.modal-*`, and `.form-*` selectors in `Dashboard.css` to use:

```css
.dashboard,
.traffic-overview,
.wol-management {
  padding: 18px;
  background: var(--retro-bg);
}

.page-header,
.dashboard-header {
  border: 2px solid var(--retro-border);
  background: var(--retro-panel);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark);
  padding: 14px;
  margin-bottom: 12px;
}

.page-header h2,
.dashboard-title,
.card-title {
  color: var(--retro-primary);
  font-family: var(--retro-font);
  letter-spacing: 0;
}

.dashboard-card,
.traffic-stat-item,
.wol-device-card,
.modal-content {
  background: var(--retro-panel);
  border: 2px solid var(--retro-border);
  border-radius: var(--retro-radius);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark);
}

.traffic-trend-card .trend-chart-container {
  background: var(--retro-screen);
  border: 2px solid #000;
  padding: 10px;
}

.wol-button.primary,
.device-action-btn.wake,
.btn-primary {
  background: var(--retro-primary);
  color: var(--retro-screen-muted);
  border: 2px solid var(--retro-border);
}

.device-status.online,
.stat-value.download {
  color: var(--retro-ok);
}

.device-status.offline,
.stat-value.upload {
  color: var(--retro-warning);
}
```

- [ ] **Step 2: Add mobile status page rules**

Append:

```css
@media (max-width: 768px) {
  .dashboard,
  .traffic-overview,
  .wol-management {
    padding: 10px;
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .traffic-stats-grid,
  .wol-devices-grid {
    grid-template-columns: 1fr;
  }

  .trend-controls,
  .wol-controls,
  .device-actions,
  .form-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .trend-select,
  .trend-date-input,
  .wol-button,
  .device-action-btn,
  .btn-primary,
  .btn-secondary {
    width: 100%;
    min-height: 40px;
  }

  .modal-content {
    width: calc(100vw - 20px);
    max-height: calc(100vh - 20px);
    overflow: auto;
  }

  .user-stats-table,
  .dashboard-card .ant-table-wrapper {
    overflow-x: auto;
  }
}
```

- [ ] **Step 3: Fix DiskMonitor grid columns if they overflow**

If manual mobile inspection shows the top DiskMonitor summary row is cramped, change the second summary `<Row>` columns in `frontend/src/pages/DiskMonitor.tsx` from fixed spans to responsive spans:

```tsx
<Col xs={24} md={7}>
<Col xs={12} md={5}>
<Col xs={24} md={8}>
<Col xs={24} md={4} style={{ textAlign: 'right' }}>
```

- [ ] **Step 4: Verify status tests**

Run: `cd frontend && npm test -- --watch=false DiskMonitor.test.tsx`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Dashboard.css frontend/src/pages/DiskMonitor.tsx frontend/src/pages/DiskMonitor.test.tsx
git commit -m "feat: restyle status pages for retro noc"
```

## Task 5: Management Pages Responsive Retro Styling

**Files:**
- Modify: `frontend/src/pages/UserManagement.css`
- Modify: `frontend/src/pages/RouteManagement.css`
- Modify: `frontend/src/pages/InboundManagement.tsx`
- Create: `frontend/src/pages/InboundManagement.css`

- [ ] **Step 1: Add inbound stylesheet import**

Add this import to `frontend/src/pages/InboundManagement.tsx`:

```ts
import './InboundManagement.css';
```

- [ ] **Step 2: Create inbound styles**

Create `frontend/src/pages/InboundManagement.css`:

```css
.inbound-management {
  padding: 18px;
  background: var(--retro-bg);
  min-height: 100%;
}

.inbound-management .page-header,
.inbound-management .operation-bar,
.inbound-management > .ant-table-wrapper,
.inbound-management > .ant-card {
  background: var(--retro-panel);
  border: 2px solid var(--retro-border);
  border-radius: var(--retro-radius);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark);
}

.inbound-management .page-header h2,
.inbound-management .page-header .ant-typography {
  color: var(--retro-primary);
  font-family: var(--retro-font);
}

@media (max-width: 768px) {
  .inbound-management {
    padding: 10px;
  }

  .inbound-management .stats-row .ant-col {
    flex: 0 0 100%;
    max-width: 100%;
    margin-bottom: 8px;
  }

  .inbound-management .operation-bar .ant-row,
  .inbound-management .operation-bar .ant-space {
    width: 100%;
  }

  .inbound-management .operation-bar .ant-col {
    flex: 0 0 100%;
    max-width: 100%;
    margin-bottom: 8px;
  }

  .inbound-management .ant-table-wrapper {
    overflow-x: auto;
  }

  .inbound-management .ant-modal .ant-row .ant-col {
    flex: 0 0 100%;
    max-width: 100%;
  }
}
```

- [ ] **Step 3: Align UserManagement CSS**

Update `frontend/src/pages/UserManagement.css` so `.content-wrapper`, `.stats-card`, `.toolbar-card`, `.table-card`, and `.user-modal` use Retro NOC variables. Ensure mobile toolbar stacks:

```css
@media (max-width: 768px) {
  .user-management-container .page-header,
  .user-management-container .content-wrapper {
    padding: 12px;
  }

  .toolbar {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .toolbar-left,
  .toolbar-right,
  .toolbar-right .ant-space {
    width: 100%;
  }

  .toolbar-right .ant-space {
    display: grid;
    grid-template-columns: 1fr;
  }

  .user-table {
    min-width: 760px;
  }
}
```

- [ ] **Step 4: Align RouteManagement CSS**

Update `frontend/src/pages/RouteManagement.css` to use Retro NOC variables and preserve the existing route-specific selectors. Replace blue gradients with `var(--retro-primary)` and add:

```css
@media (max-width: 768px) {
  .route-management {
    padding: 10px;
  }

  .route-management .stats-row .ant-col {
    flex: 0 0 100%;
    max-width: 100%;
  }

  .route-management .operation-bar .ant-row,
  .route-management .operation-bar .ant-space {
    width: 100%;
  }

  .route-management .operation-bar .ant-space {
    display: grid;
    grid-template-columns: 1fr;
  }

  .route-management .ant-table-wrapper {
    overflow-x: auto;
  }
}
```

- [ ] **Step 5: Verify management page tests**

Run:

```bash
cd frontend
npm test -- --watch=false InboundManagement.test.tsx RouteManagement.test.tsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/UserManagement.css frontend/src/pages/RouteManagement.css frontend/src/pages/InboundManagement.tsx frontend/src/pages/InboundManagement.css
git commit -m "feat: restyle management pages for retro noc"
```

## Task 6: Login Retro Window

**Files:**
- Modify: `frontend/src/pages/Login.css`
- Test: `frontend/src/App.test.tsx`

- [ ] **Step 1: Replace login visual CSS**

Update `frontend/src/pages/Login.css` so the page is a Retro NOC login window:

```css
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--retro-bg);
  padding: 20px;
}

.login-background::before {
  display: none;
}

.login-card {
  background: var(--retro-panel);
  border: 2px solid var(--retro-border);
  border-radius: var(--retro-radius);
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 var(--retro-panel-dark), 10px 10px 0 rgba(0, 0, 0, 0.18);
  padding: 0;
  overflow: hidden;
}

.login-header {
  background: var(--retro-primary);
  color: var(--retro-screen-muted);
  border-bottom: 2px solid var(--retro-border);
  padding: 18px;
  margin-bottom: 0;
}

.login-title,
.login-subtitle,
.login-logo .logo-icon {
  color: var(--retro-screen-muted) !important;
  -webkit-text-fill-color: currentColor;
  font-family: var(--retro-font);
}

.login-form {
  padding: 22px 22px 0;
}

.login-footer {
  margin: 0 22px;
  padding: 14px 0 18px;
  border-top: 1px solid var(--retro-panel-dark);
}

.login-button {
  background: var(--retro-primary);
  border: 2px solid var(--retro-border);
  border-radius: var(--retro-radius);
  color: var(--retro-screen-muted);
}

@media (max-width: 480px) {
  .login-card-wrapper {
    padding: 0;
  }

  .login-card {
    width: 100%;
  }
}
```

Remove or override the old purple gradient and hover transform rules.

- [ ] **Step 2: Verify login still renders**

Run: `cd frontend && npm test -- --watch=false App.test.tsx`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/Login.css
git commit -m "feat: restyle login as retro noc window"
```

## Task 7: Final Verification

**Files:**
- No planned source edits unless verification reveals a defect.

- [ ] **Step 1: Run focused frontend tests**

Run:

```bash
cd frontend
npm test -- --watch=false App.test.tsx ProxyConfig.test.tsx MobileStatusPanel.test.tsx DiskMonitor.test.tsx InboundManagement.test.tsx RouteManagement.test.tsx
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run: `make frontend-build`

Expected: build completes successfully.

- [ ] **Step 3: Start the frontend dev server for visual inspection**

Run: `make frontend-dev`

Expected: development server starts and prints a local URL, usually `http://localhost:3000`.

- [ ] **Step 4: Inspect desktop and mobile viewports**

Use browser screenshots or manual browser checks for:

- Desktop `/config/dashboard`: Retro NOC side navigation, header status bar, no text overlap.
- Desktop `/login`: Retro NOC login window, no purple gradient.
- Mobile `/config/dashboard`: status panel appears, desktop tabs hidden.
- Mobile `/config/dashboard/wol`: WOL cards stack, wake buttons are full-width and tappable.
- Mobile `/config/system-ops/disk`: top controls stack and disk table does not overflow the viewport without horizontal scroll.
- Mobile `/config/users`, `/config/inbound`, `/config/routing`: pages reachable from mobile drawer and content remains usable.

- [ ] **Step 5: Stop any dev server started for verification**

If a foreground command is still running, stop it with Ctrl-C. Do not leave required sessions running.

- [ ] **Step 6: Commit verification fixes if any**

Only if Step 4 required source edits:

```bash
git add frontend/src
git commit -m "fix: polish retro noc responsive layout"
```

## Self-Review

- Spec coverage: The plan covers Retro NOC visual language, desktop complete management, mobile status-first operations, WOL/traffic/disk/users/inbound/routes, no backend API changes, no new UI framework, and desktop/mobile verification.
- Placeholder scan: No unresolved placeholders or undefined implementation decisions remain.
- Type consistency: New component imports existing `apiService`, `formatBytes`, `UserTrafficStats`, `WolConfig`, `PcStatus`, `DiskHost`, and `DiskInfo` types already present in the codebase.
