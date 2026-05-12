import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { store } from '../store';
import ProxyConfig from './ProxyConfig';

const renderProxyConfig = (initialPath = '/config') => {
  render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/config" element={<ProxyConfig />}>
            <Route path="system-ops/disk" element={<div>Disk Page</div>} />
            <Route path="system-ops/backup" element={<div>Backup Page</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </Provider>
  );
};

test('does not show unfinished rate limit and geo distribution entries', async () => {
  renderProxyConfig();

  await waitFor(() => expect(screen.getByText('用户管理')).toBeInTheDocument());

  expect(screen.queryByText('限流设置')).not.toBeInTheDocument();
  expect(screen.queryByText('地理位置分布')).not.toBeInTheDocument();
  expect(screen.queryByText('规则源说明')).not.toBeInTheDocument();
  expect(screen.getByText('规则集')).toBeInTheDocument();
  expect(screen.getByText('日志审计')).toBeInTheDocument();
});

test('shows disk monitor under the system operations menu', async () => {
  renderProxyConfig('/config/system-ops/disk');

  await waitFor(() => expect(screen.getByText('系统运维')).toBeInTheDocument());

  expect(screen.getAllByText('磁盘监控')).toHaveLength(2);
  expect(screen.getByText('Disk Page')).toBeInTheDocument();
});

test('shows data backup under the system operations menu', async () => {
  renderProxyConfig('/config/system-ops/backup');

  await waitFor(() => expect(screen.getByText('系统运维')).toBeInTheDocument());

  expect(screen.getAllByText('数据备份')).toHaveLength(2);
  expect(screen.getByText('Backup Page')).toBeInTheDocument();
});
