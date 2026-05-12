import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import InboundManagement from './InboundManagement';
import { apiService } from '../services/api';
import { ProtocolType } from '../types/route';

jest.mock('../services/api', () => ({
  apiService: {
    getInbounds: jest.fn(),
    getInboundMonthlyTraffic: jest.fn(),
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
  (apiService.getUsers as jest.Mock).mockResolvedValue({ items: [] });
  (apiService.getRoutes as jest.Mock).mockResolvedValue({ items: [] });
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
