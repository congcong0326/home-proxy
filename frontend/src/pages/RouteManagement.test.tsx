import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
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
