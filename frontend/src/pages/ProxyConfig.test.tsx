import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { store } from '../store';
import ProxyConfig from './ProxyConfig';

const renderProxyConfig = () => {
  render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/config']}>
        <Routes>
          <Route path="/config" element={<ProxyConfig />} />
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
  expect(screen.getByText('日志审计')).toBeInTheDocument();
});
