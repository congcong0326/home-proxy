import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DiskMonitor from './DiskMonitor';
import { apiService } from '../services/api';

jest.mock('chart.js', () => ({
  Chart: { register: jest.fn() },
  CategoryScale: jest.fn(),
  LinearScale: jest.fn(),
  PointElement: jest.fn(),
  LineElement: jest.fn(),
  Title: jest.fn(),
  Tooltip: jest.fn(),
  Legend: jest.fn(),
}));

jest.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="line-chart" />,
}));

jest.mock('../services/api', () => ({
  apiService: {
    getDiskHosts: jest.fn(),
    getDisks: jest.fn(),
    getDiskDetail: jest.fn(),
    getDiskPushToken: jest.fn(),
    regenerateDiskPushToken: jest.fn(),
  },
}));

const detail = {
  device: 'sda',
  model: 'Disk One',
  serial: 'SN001',
  size: '1 TB',
  temperature: 35,
  health: 'PASSED',
  smartSupported: true,
  smartEnabled: true,
  powerOnHours: 100,
  powerCycleCount: 4,
  dataUnitsRead: 1000,
  dataUnitsWritten: 2000,
  reallocatedSectorCount: 0,
  seekErrorRate: 0,
  spinRetryCount: 0,
  udmaCrcErrorCount: 0,
  percentageUsed: 0,
  unsafeShutdowns: 0,
  mediaErrors: 0,
  ssdLifeLeft: 0,
  flashWritesGiB: 0,
  lifetimeWritesGiB: 0,
  lifetimeReadsGiB: 0,
  averageEraseCount: 0,
  maxEraseCount: 0,
  totalEraseCount: 0,
  diskType: 'HDD',
  historyTemperature: [35],
  historyReadBytes: [0],
  historyWriteBytes: [0],
};

beforeEach(() => {
  jest.clearAllMocks();
  (apiService.getDiskPushToken as jest.Mock).mockResolvedValue({ token: 'plain-visible-token' });
});

test('loads hosts first and queries disks and details with selected hostId', async () => {
  (apiService.getDiskHosts as jest.Mock).mockResolvedValue([
    { hostId: 'nas-main', hostName: 'NAS Main', lastSeenAt: '2026-05-12T02:20:00Z', lastSourceIp: '10.0.0.2' },
  ]);
  (apiService.getDisks as jest.Mock).mockResolvedValue([
    { device: 'sda', model: 'Disk One', serial: 'SN001', size: '1 TB', status: 'PASSED', temperature: 35 },
  ]);
  (apiService.getDiskDetail as jest.Mock).mockResolvedValue(detail);

  render(<DiskMonitor />);

  expect(await screen.findByText('NAS Main')).toBeInTheDocument();
  await waitFor(() => expect(apiService.getDisks).toHaveBeenCalledWith('nas-main'));
  await waitFor(() => expect(apiService.getDiskDetail).toHaveBeenCalledWith('sda', 'nas-main'));
  await waitFor(() => expect(screen.getAllByText('Disk One').length).toBeGreaterThan(0));
});

test('shows push token in plaintext and updates it after regeneration', async () => {
  (apiService.getDiskHosts as jest.Mock).mockResolvedValue([]);
  (apiService.regenerateDiskPushToken as jest.Mock).mockResolvedValue({ token: 'new-visible-token' });

  render(<DiskMonitor />);

  expect(await screen.findByText('plain-visible-token')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: /重新生成/ }));

  await waitFor(() => expect(apiService.regenerateDiskPushToken).toHaveBeenCalled());
  expect(await screen.findByText('new-visible-token')).toBeInTheDocument();
});

test('shows setup guidance when no host has pushed disk samples', async () => {
  (apiService.getDiskHosts as jest.Mock).mockResolvedValue([]);

  render(<DiskMonitor />);

  expect(await screen.findByText('暂无主机上报数据')).toBeInTheDocument();
  expect(screen.getByText(/请在宿主机配置 scripts\/disk-monitor\/push-smartctl.sh/)).toBeInTheDocument();
});
