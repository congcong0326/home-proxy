import React from 'react';
import { render, screen } from '@testing-library/react';
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
