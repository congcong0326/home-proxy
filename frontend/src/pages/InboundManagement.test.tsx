import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import InboundManagement from './InboundManagement';
import { apiService } from '../services/api';
import { ProtocolType } from '../types/route';

jest.mock('../services/api', () => ({
  apiService: {
    getInbounds: jest.fn(),
    getInboundMonthlyTraffic: jest.fn(),
    getInboundMonthlyTrafficV1: jest.fn(),
    getWorkers: jest.fn(),
    getUsers: jest.fn(),
    getRoutes: jest.fn(),
    getUserById: jest.fn(),
    getRouteById: jest.fn(),
    createInbound: jest.fn(),
    updateInbound: jest.fn(),
    deleteInbound: jest.fn(),
  },
}));

const mockInboundResponse = {
  items: [
    {
      id: 1,
      workerId: 'home-01',
      name: 'socks inbound',
      protocol: ProtocolType.SOCKS5,
      listenIp: '0.0.0.0',
      port: 1080,
      tlsEnabled: true,
      sniffEnabled: true,
      inboundRouteBindings: [{ userIds: [1], routeIds: [1] }],
      status: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    {
      id: 2,
      workerId: 'home-02',
      name: 'http inbound',
      protocol: ProtocolType.HTTPS_CONNECT,
      listenIp: '0.0.0.0',
      port: 8080,
      tlsEnabled: true,
      sniffEnabled: true,
      inboundRouteBindings: [{ userIds: [1], routeIds: [1] }],
      status: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
  total: 2,
  page: 1,
  pageSize: 10,
};

beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1024 });
  window.dispatchEvent(new Event('resize'));
  (apiService.getInbounds as jest.Mock).mockResolvedValue(mockInboundResponse);
  (apiService.getInboundMonthlyTraffic as jest.Mock).mockResolvedValue({
    inboundId: 1,
    bytesIn: 0,
    bytesOut: 0,
    totalBytes: 0,
  });
  (apiService.getInboundMonthlyTrafficV1 as jest.Mock).mockResolvedValue({
    inboundId: 1, workerId: 'home-01', period: '2026-10', timeZone: 'Asia/Shanghai',
    traffic: { upload: '1024', download: '2048', total: '3072' },
    categories: {
      EXTERNAL: { upload: '1024', download: '2048', total: '3072' },
      INTERNAL_REWRITE: { upload: '0', download: '0', total: '0' },
      OTHER_INTERNAL: { upload: '0', download: '0', total: '0' },
      UNKNOWN: { upload: '0', download: '0', total: '0' },
    },
    firstSessionAt: '2026-10-02 00:00:00',
  });
  (apiService.getWorkers as jest.Mock).mockResolvedValue([]);
  (apiService.getUsers as jest.Mock).mockResolvedValue({ items: [] });
  (apiService.getRoutes as jest.Mock).mockResolvedValue({ items: [] });
});

test('queries new monthly facts for each inbound and Worker and exposes category totals', async () => {
  render(<InboundManagement />);
  const traffic = await screen.findAllByText('1 KB / 2 KB');
  expect(apiService.getInboundMonthlyTrafficV1).toHaveBeenCalledWith(1, 'home-01');
  expect(apiService.getInboundMonthlyTrafficV1).toHaveBeenCalledWith(2, 'home-02');
  expect(apiService.getInboundMonthlyTraffic).not.toHaveBeenCalled();
  fireEvent.mouseEnter(traffic[0]);
  expect(await screen.findByText(/内网访问（目标重写）：/)).toBeInTheDocument();
  expect(screen.getByText(/其他内网：/)).toBeInTheDocument();
  expect(screen.getByText(/未知分类：/)).toBeInTheDocument();
  expect(screen.getByText('总量：3 KB')).toBeInTheDocument();
});

test('new query failures are visible and never automatically use legacy traffic', async () => {
  (apiService.getInboundMonthlyTrafficV1 as jest.Mock).mockRejectedValue(new Error('QUERY_DISABLED'));
  const warning = jest.spyOn(console, 'warn').mockImplementation(() => {});
  render(<InboundManagement />);
  expect(await screen.findAllByText('查询失败')).toHaveLength(2);
  expect(screen.queryByText('0 B / 0 B')).not.toBeInTheDocument();
  expect(apiService.getInboundMonthlyTraffic).not.toHaveBeenCalled();
  warning.mockRestore();
});

test('empty new history displays zero without filling it from old logs', async () => {
  const sample = await (apiService.getInboundMonthlyTrafficV1 as jest.Mock)();
  (apiService.getInboundMonthlyTrafficV1 as jest.Mock).mockResolvedValue({
    ...sample,
    traffic: { upload: '0', download: '0', total: '0' },
    categories: Object.fromEntries(Object.keys(sample.categories).map(category =>
      [category, { upload: '0', download: '0', total: '0' }])),
    firstSessionAt: null,
  });
  render(<InboundManagement />);
  expect(await screen.findAllByText('0 B / 0 B')).toHaveLength(2);
  expect(apiService.getInboundMonthlyTraffic).not.toHaveBeenCalled();
});

test('explicit legacy selection switches only traffic requests', async () => {
  render(<InboundManagement />);
  await screen.findAllByText('1 KB / 2 KB');
  const inboundCalls = (apiService.getInbounds as jest.Mock).mock.calls.length;
  const source = screen.getByRole('combobox', { name: '流量统计来源' });
  fireEvent.mouseDown(source.closest('.ant-select-selector') as Element);
  fireEvent.click(await screen.findByTitle('旧日志'));
  await screen.findAllByText('0 B / 0 B');
  expect(apiService.getInboundMonthlyTraffic).toHaveBeenCalledWith(1);
  expect(apiService.getInboundMonthlyTraffic).toHaveBeenCalledWith(2);
  expect(apiService.getInbounds).toHaveBeenCalledTimes(inboundCalls);
});

const openCreateModal = async () => {
  fireEvent.click(await screen.findByRole('button', { name: /新建配置/ }));
  const title = await screen.findByText('新建入站配置');
  const dialog = title.closest('.ant-modal-content');

  expect(dialog).not.toBeNull();
  return dialog as HTMLElement;
};

const openProtocolSelect = (dialog: HTMLElement) => {
  const protocolInput = dialog.querySelector('#protocol');
  const protocolSelector = protocolInput?.closest('.ant-select-selector');

  expect(protocolSelector).not.toBeNull();
  fireEvent.mouseDown(protocolSelector as Element);
};

test('shows TLS status only for SOCKS5 inbound rows', async () => {
  render(<InboundManagement />);

  const socksRow = (await screen.findByText('socks inbound')).closest('tr');
  const httpRow = (await screen.findByText('http inbound')).closest('tr');

  expect(socksRow).not.toBeNull();
  expect(httpRow).not.toBeNull();
  expect(within(socksRow as HTMLTableRowElement).getByText('启用')).toBeInTheDocument();
  expect(within(httpRow as HTMLTableRowElement).queryByText('启用')).not.toBeInTheDocument();
});

test('counts TLS enabled statistic only for SOCKS5 inbound rows', async () => {
  render(<InboundManagement />);

  await screen.findByText('socks inbound');
  const tlsTitle = await screen.findByText('TLS启用');
  const tlsCard = tlsTitle.closest('.ant-card');

  expect(tlsCard).not.toBeNull();
  expect(within(tlsCard as HTMLElement).getByText('1')).toBeInTheDocument();
});

test('does not offer deprecated SOCKS5 HTTPS mixed protocol', async () => {
  render(<InboundManagement />);

  const dialog = await openCreateModal();

  openProtocolSelect(dialog);

  expect(screen.queryByText('SOCKS5+HTTPS混合协议')).not.toBeInTheDocument();
});

test('shows TLS switch only when creating SOCKS5 inbound config', async () => {
  render(<InboundManagement />);

  const dialog = await openCreateModal();

  expect(within(dialog).getByText('启用TLS')).toBeInTheDocument();

  openProtocolSelect(dialog);
  fireEvent.click(await screen.findByTitle('HTTPS CONNECT协议'));

  await waitFor(() => expect(within(dialog).queryByText('启用TLS')).not.toBeInTheDocument());
});

test('uses a compact column set on mobile', async () => {
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 500 });
  window.dispatchEvent(new Event('resize'));

  render(<InboundManagement />);

  expect(await screen.findByText('socks inbound')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByRole('columnheader', { name: '监听IP' })).not.toBeInTheDocument());
  expect(screen.queryByRole('columnheader', { name: 'TLS' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '本月流量(上/下行)' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '更新时间' })).not.toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '名称' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '协议' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '操作' })).toBeInTheDocument();
});
