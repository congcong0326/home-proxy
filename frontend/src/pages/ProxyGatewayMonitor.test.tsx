import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import ProxyGatewayMonitor from './ProxyGatewayMonitor';
import apiService from '../services/api';

jest.mock('../services/api', () => ({
  __esModule: true,
  default: {
    getProxyGatewayStatus: jest.fn(),
  },
}));

const mockedApi = apiService as jest.Mocked<typeof apiService>;

beforeEach(() => {
  jest.clearAllMocks();
  mockedApi.getProxyGatewayStatus.mockResolvedValue({
    workerId: 'gateway-main',
    hostname: 'nas-proxy',
    startedAt: '2026-05-16T10:00:00',
    lastSeenAt: '2026-05-16T10:01:00',
    lastConfigHash: 'abcdef123456',
    uptimeSeconds: 3660,
    heapUsedBytes: 128 * 1024 * 1024,
    heapMaxBytes: 512 * 1024 * 1024,
    runningInboundCount: 3,
    activeConnectionCount: 0,
    online: true,
  });
});

test('renders proxy gateway metrics without exposing internal task records', async () => {
  const { container } = render(<ProxyGatewayMonitor />);

  expect(await screen.findByText('gateway-main')).toBeInTheDocument();
  expect(screen.getByText('代理网关监控')).toBeInTheDocument();
  expect(screen.getByText('网关标识')).toBeInTheDocument();
  expect(screen.getByText('当前配置 Hash')).toBeInTheDocument();
  expect(screen.getByText('abcdef12')).toBeInTheDocument();

  await waitFor(() => expect(mockedApi.getProxyGatewayStatus).toHaveBeenCalled());
  expect(container).not.toHaveTextContent('最近任务');
  expect(container).not.toHaveTextContent('任务记录');
  expect(container).not.toHaveTextContent('Worker ID');
  expect(container).not.toHaveTextContent('轮询');
});
