import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RouteManagement from './RouteManagement';
import { apiService } from '../services/api';
import {
  MatchOp,
  RouteConditionType,
  RoutePolicy,
  RouteStatus,
} from '../types/route';

jest.mock('../services/api', () => ({
  apiService: {
    getRoutes: jest.fn(),
    getPublishedRuleSets: jest.fn(),
    deleteRoute: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1024 });
  window.dispatchEvent(new Event('resize'));
});

test('does not show outbound tag in destination override route outbound info', async () => {
  (apiService.getRoutes as jest.Mock).mockResolvedValue({
    items: [
      {
        id: 201,
        name: 'rewrite route',
        rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: 'example.com' }],
        policy: RoutePolicy.DESTINATION_OVERRIDE,
        outboundTag: 'legacy-tag',
        outboundProxyHost: 'rewrite.example.com',
        outboundProxyPort: 443,
        status: RouteStatus.ENABLED,
        notes: '',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    pageSize: 10,
  });
  (apiService.getPublishedRuleSets as jest.Mock).mockResolvedValue([]);

  render(<RouteManagement />);

  expect(await screen.findByText('rewrite route')).toBeInTheDocument();
  expect(screen.queryByText('legacy-tag')).not.toBeInTheDocument();
  expect(screen.getByText('rewrite.example.com:443')).toBeInTheDocument();
});

test('uses a compact column set on mobile', async () => {
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 500 });
  window.dispatchEvent(new Event('resize'));
  (apiService.getRoutes as jest.Mock).mockResolvedValue({
    items: [
      {
        id: 201,
        name: 'mobile route',
        rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: 'example.com' }],
        policy: RoutePolicy.DIRECT,
        status: RouteStatus.ENABLED,
        notes: '',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    pageSize: 10,
  });
  (apiService.getPublishedRuleSets as jest.Mock).mockResolvedValue([]);

  render(<RouteManagement />);

  expect(await screen.findByText('mobile route')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByRole('columnheader', { name: 'ID' })).not.toBeInTheDocument());
  expect(screen.queryByRole('columnheader', { name: '规则数量' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '出站信息' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '更新时间' })).not.toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '路由名称' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '路由策略' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '操作' })).toBeInTheDocument();
});

test.each(['single', 'batch'])('%s delete shows referencing inbounds and where to remove the route', async (mode) => {
  const details = '路由“家庭直连”（ID：7）被以下入站配置引用，无法删除：\n'
    + '• 家庭代理（ID：12，节点：home-01，监听：0.0.0.0:1080）：兜底路由、绑定组 #2 → 路由顺序\n'
    + '请前往「入站配置」编辑以上配置，保存后重试。';
  (apiService.getRoutes as jest.Mock).mockResolvedValue({
    items: [{
      id: 7,
      name: '家庭直连',
      rules: [],
      policy: RoutePolicy.DIRECT,
      status: RouteStatus.ENABLED,
    }],
    total: 1,
    page: 1,
    pageSize: 10,
  });
  (apiService.getPublishedRuleSets as jest.Mock).mockResolvedValue([]);
  (apiService.deleteRoute as jest.Mock).mockRejectedValue(Object.assign(new Error(details), { status: 409 }));

  render(<RouteManagement />);
  await screen.findByText('家庭直连');
  if (mode === 'batch') {
    userEvent.click(screen.getAllByRole('checkbox')[1]);
    userEvent.click(screen.getByRole('button', { name: /批量删除/ }));
  } else {
    userEvent.click(screen.getByRole('button', { name: /删除/ }));
  }
  userEvent.click(await screen.findByRole('button', { name: '确 定' }));

  expect(await screen.findByText('路由被引用，无法删除')).toBeInTheDocument();
  expect(screen.getByText(/家庭代理（ID：12，节点：home-01/)).toHaveTextContent('兜底路由、绑定组 #2 → 路由顺序');
  expect(screen.getByText(/请前往「入站配置」/)).toHaveTextContent('保存后重试');
  expect(screen.getByRole('link', { name: '前往入站配置' })).toHaveAttribute('href', '/config/inbound');
  expect(screen.getByText('家庭直连')).toBeInTheDocument();
  expect(apiService.deleteRoute).toHaveBeenCalledWith(7);
}, 20000);
