import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ProxyGatewayMonitor from './ProxyGatewayMonitor';
import apiService from '../services/api';

jest.mock('../services/api', () => ({
  __esModule: true,
  default: {
    getWorkers: jest.fn(),
  },
}));

const mockedApi = apiService as jest.Mocked<typeof apiService>;

beforeEach(() => {
  jest.clearAllMocks();
  mockedApi.getWorkers.mockResolvedValue([
    {
      workerId: 'gateway-main',
      displayName: '主网关',
      enabled: true,
      status: {
        workerId: 'gateway-main',
        hostname: 'nas-proxy',
        startedAt: '2026-05-16T10:00:00',
        lastSeenAt: '2026-05-16T10:01:00',
        fetchedConfigHash: 'abcdef123456',
        appliedConfigHash: 'abcdef123456',
        applySuccess: true,
        uptimeSeconds: 3660,
        heapUsedBytes: 128 * 1024 * 1024,
        heapMaxBytes: 512 * 1024 * 1024,
        runningInboundCount: 3,
        activeConnectionCount: 2,
        online: true,
      },
    },
    {
      workerId: 'gateway-backup',
      displayName: '备用网关',
      enabled: true,
      status: {
        workerId: 'gateway-backup',
        hostname: 'backup-proxy',
        startedAt: '2026-05-16T09:00:00',
        lastSeenAt: '2026-05-16T09:30:00',
        fetchedConfigHash: 'fedcba987654',
        appliedConfigHash: 'fedcba987654',
        applySuccess: true,
        uptimeSeconds: 7200,
        heapUsedBytes: 64 * 1024 * 1024,
        heapMaxBytes: 512 * 1024 * 1024,
        runningInboundCount: 1,
        activeConnectionCount: 0,
        online: false,
      },
    },
  ]);
});

test('renders multi-worker summary and selected worker metrics', async () => {
  const { container } = render(<ProxyGatewayMonitor />);

  expect(await screen.findByText('代理网关监控')).toBeInTheDocument();
  expect(await screen.findByText('节点总数')).toBeInTheDocument();
  expect(screen.getByText('在线节点')).toBeInTheDocument();
  expect((await screen.findAllByText('gateway-main')).length).toBeGreaterThan(0);
  expect(screen.getByText('gateway-backup')).toBeInTheDocument();
  expect(screen.getByText('已应用配置 Hash')).toBeInTheDocument();
  expect(screen.getAllByText('abcdef12').length).toBe(2);

  await waitFor(() => expect(mockedApi.getWorkers).toHaveBeenCalled());
  expect(container).not.toHaveTextContent('最近任务');
  expect(container).not.toHaveTextContent('任务记录');
  expect(container).not.toHaveTextContent('轮询');
});

test('switches detail metrics when a worker row is selected', async () => {
  render(<ProxyGatewayMonitor />);

  const backupWorkerId = await screen.findByText('gateway-backup');
  fireEvent.click(backupWorkerId.closest('tr') as HTMLTableRowElement);

  expect((await screen.findAllByText('fedcba98')).length).toBe(2);
  expect(screen.getAllByText('备用网关').length).toBeGreaterThan(0);
});
