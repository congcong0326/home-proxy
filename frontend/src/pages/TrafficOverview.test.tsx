import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import apiService from '../services/api';
import { TrafficOverview } from './TrafficOverview';
import { OverviewData } from '../types/overview';

jest.mock('echarts-for-react', () => ({ __esModule: true, default: () => <div data-testid="overview-chart" /> }));
jest.mock('echarts', () => ({ connect: jest.fn(), disconnect: jest.fn() }));
jest.mock('chartjs-adapter-date-fns', () => ({}));
jest.mock('../services/api', () => ({ __esModule: true, default: {
  getWorkers: jest.fn(), getObservabilityOverview: jest.fn(), getObservabilityLive: jest.fn(),
  getDailyUserTrafficStats: jest.fn(), getMonthlyUserTrafficStats: jest.fn(), getGlobalTrafficTrend: jest.fn(), getUsers: jest.fn(),
} }));
const data = (total = '1024'): OverviewData => ({
  from: '2026-10-09T00:00:00Z', to: '2026-10-09T01:00:00Z', timeZone: 'Asia/Shanghai', bucketSeconds: 60,
  firstSessionAt: '2026-10-08 01:00:00', historyBasis: 'ENDED_SESSIONS_ONLY',
  traffic: { upload: '512', download: '512', total, daily: '2048', monthly: '8192', series: [], sources: [], categories: [] },
  concurrency: { series: [], average: '1.30', peak: '3' }, live: { available: false, reason: 'UNSUPPORTED' },
  connections: { series: [], totals: { SUCCESS: '4', FAILURE: '2' }, total: '6' },
  dns: { series: [], totals: { SUCCESS: '10', FAILURE: '2', BLOCKED: '1' }, total: '13' },
  auth: { series: [], totals: { SUCCESS: '3', FAILURE: '1', CHALLENGE: '1' }, total: '5' },
});
beforeEach(() => {
  jest.clearAllMocks();
  (apiService.getWorkers as jest.Mock).mockResolvedValue([{ workerId: 'home-01', displayName: '家庭节点', enabled: true }, { workerId: 'edge-a', displayName: '边缘节点', enabled: true }]);
  (apiService.getObservabilityOverview as jest.Mock).mockResolvedValue(data());
  (apiService.getObservabilityLive as jest.Mock).mockResolvedValue({ available: true, count: '2', sampledAt: '2026-10-09T01:00:00Z' });
});
it('defaults to external traffic and shows four distinct charts without substituting legacy data', async () => {
  render(<TrafficOverview />);
  await waitFor(() => expect(apiService.getObservabilityOverview).toHaveBeenCalledWith(expect.objectContaining({ workerId: 'home-01', trafficCategory: 'EXTERNAL', dnsOrigin: 'CLIENT' })));
  expect(screen.getByRole('button', { name: '外网' })).toHaveAttribute('aria-pressed', 'true');
  await screen.findByText('1 KB');
  expect(screen.getAllByTestId('overview-chart')).toHaveLength(4);
  expect(screen.getByText('等待新版节点上报已建立连接')).toBeInTheDocument();
  expect(apiService.getDailyUserTrafficStats).not.toHaveBeenCalled();
});
it('applies independent DNS/auth and traffic filters and submitted source constraints', async () => {
  render(<TrafficOverview />);
  await screen.findByText('1 KB');
  fireEvent.click(screen.getByRole('button', { name: /内网 · 目标重写/ }));
  fireEvent.change(screen.getByLabelText('DNS 结果'), { target: { value: 'FAILURE' } });
  fireEvent.change(screen.getByLabelText('认证结果'), { target: { value: 'SUCCESS' } });
  fireEvent.change(screen.getByLabelText('用户／账号'), { target: { value: 'failed-account' } });
  fireEvent.change(screen.getByLabelText('来源 IP'), { target: { value: '192.168.1.2' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /查询 \/ 刷新/ })).not.toBeDisabled());
  fireEvent.click(screen.getByRole('button', { name: /查询 \/ 刷新/ }));
  await waitFor(() => expect(apiService.getObservabilityOverview).toHaveBeenLastCalledWith(expect.objectContaining({
    trafficCategory: 'INTERNAL_REWRITE', dnsResult: 'FAILURE', authResult: 'SUCCESS', username: 'failed-account', clientIp: '192.168.1.2',
  })));
});
it('ignores late responses after changing node', async () => {
  let resolveFirst: (result: OverviewData) => void = () => {};
  (apiService.getObservabilityOverview as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }));
  render(<TrafficOverview />);
  await waitFor(() => expect(apiService.getObservabilityOverview).toHaveBeenCalledTimes(1));
  fireEvent.change(screen.getByLabelText('代理节点'), { target: { value: 'edge-a' } });
  await screen.findByText('1 KB');
  resolveFirst(data('999999999'));
  await waitFor(() => expect(screen.getByText('1 KB')).toBeInTheDocument());
  expect(screen.queryByText('953.67 MB')).not.toBeInTheDocument();
});
it('reports unavailable queries without showing fabricated zero totals', async () => {
  (apiService.getObservabilityOverview as jest.Mock).mockRejectedValue(new Error('新版查询未启用'));
  render(<TrafficOverview />);
  expect(await screen.findByRole('alert')).toHaveTextContent('新版查询未启用');
  expect(screen.queryByText('0 B')).not.toBeInTheDocument();
  expect(apiService.getDailyUserTrafficStats).not.toHaveBeenCalled();
});
