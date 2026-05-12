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
  (apiService.getAllPcStatus as jest.Mock).mockResolvedValue([
    { name: 'NAS', ip: '10.0.0.2', online: true, enabled: true, macAddress: 'AA:BB:CC:DD:EE:FF', wolPort: 9 },
  ]);
  (apiService.getDiskHosts as jest.Mock).mockResolvedValue([
    { hostId: 'nas-main', hostName: 'NAS Main', lastSeenAt: new Date().toISOString() },
  ]);
  (apiService.getDisks as jest.Mock).mockResolvedValue([
    { device: 'sda', model: 'Disk One', serial: 'SN001', size: '1 TB', status: 'PASSED', temperature: 35 },
  ]);
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
