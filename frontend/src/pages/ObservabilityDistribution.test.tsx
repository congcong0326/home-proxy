import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import apiService from '../services/api';
import { distributionLabel, distributionPercent, ObservabilityDistribution } from './ObservabilityDistribution';
import { DistributionData } from '../types/distribution';

jest.setTimeout(15000);

jest.mock('echarts-for-react', () => ({ __esModule: true, default: ({ option }: { option: { series: { data: { name: string }[] }[] } }) => <div data-testid="distribution-chart">{option.series[0].data.map(item => item.name).join(', ')}</div> }));
jest.mock('../services/api', () => {
  const service = { queryTelemetryPage: jest.fn(), getWorkers: jest.fn(), getObservabilityDistribution: jest.fn(), getAccessDailyTop: jest.fn() };
  return { __esModule: true, default: service, apiService: service };
});
const data = (): DistributionData => ({
  from: '2026-10-01T00:00:00Z', to: '2026-11-01T00:00:00Z', dimension: 'users', metric: 'connections',
  items: [{ keys: ['42', 'alice'], counters: { connections: '3', upload: '18446744073709551620', download: '20', bytes: '18446744073709551640' }, value: '3' }],
  totals: { connections: '10', upload: '18446744073709551630', download: '30', bytes: '18446744073709551660' },
  totalValue: '10', otherValue: '7', groups: '6', missingConnections: '1', firstSessionAt: '2026-10-01 01:00:00',
  historyBasis: 'ENDED_SESSIONS_ONLY', timeBoundary: '[from,to)',
});
beforeEach(() => {
  jest.resetAllMocks();
  (apiService.getWorkers as jest.Mock).mockResolvedValue([{ workerId: 'home-01', displayName: '家庭节点', enabled: true }, { workerId: 'edge', displayName: '边缘节点', enabled: true }]);
  (apiService.getObservabilityDistribution as jest.Mock).mockResolvedValue(data());
});
async function choose(name: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name }));
  const item = await screen.findByText(option, { selector: '.ant-select-item-option-content' });
  await act(async () => { fireEvent.click(item); });
}
it('uses full denominators, exact counters and the remainder in the chart', async () => {
  render(<ObservabilityDistribution />);
  await screen.findByText('30.00%');
  expect(apiService.getObservabilityDistribution).toHaveBeenCalledWith(expect.objectContaining({ workerId: 'home-01', trafficCategory: 'EXTERNAL', dimension: 'users', metric: 'connections' }));
  expect(screen.getByTitle('18,446,744,073,709,551,660 B')).toHaveTextContent('16 EB');
  expect(screen.getByTestId('distribution-chart')).toHaveTextContent('其余项目');
  expect(apiService.getAccessDailyTop).not.toHaveBeenCalled();
  expect(distributionPercent('18446744073709551620', '36893488147419103240')).toBe('50.00');
});
it('submits source filters, supports classification and rejects invalid ranges', async () => {
  render(<ObservabilityDistribution />);
  await screen.findByText('30.00%');
  await choose('维度', '用户－目标');
  await choose('流量分类', '内网 · 目标重写');
  await choose('建连结果', '失败');
  fireEvent.click(screen.getByRole('button', { name: /更多筛选/ }));
  fireEvent.change(screen.getByRole('textbox', { name: '用户' }), { target: { value: ' alice ' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '查询 / 刷新' })).not.toBeDisabled());
  fireEvent.click(screen.getByRole('button', { name: '查询 / 刷新' }));
  await waitFor(() => expect(apiService.getObservabilityDistribution).toHaveBeenLastCalledWith(expect.objectContaining({ dimension: 'user_targets', trafficCategory: 'INTERNAL_REWRITE', connectOutcome: 'FAILED', username: 'alice' })));
  const calls = (apiService.getObservabilityDistribution as jest.Mock).mock.calls.length;
  const clear = screen.getByPlaceholderText('开始日期').closest('.ant-picker')!.querySelector('.ant-picker-clear')!;
  fireEvent.click(clear);
  expect(await screen.findByRole('alert')).toHaveTextContent('请选择有效日期');
  expect(apiService.getObservabilityDistribution).toHaveBeenCalledTimes(calls);
});
it('ignores responses from the previously selected node', async () => {
  let resolveOld: (result: DistributionData) => void = () => {};
  (apiService.getObservabilityDistribution as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
  render(<ObservabilityDistribution />);
  await waitFor(() => expect(apiService.getObservabilityDistribution).toHaveBeenCalledTimes(1));
  await choose('代理节点', '边缘节点');
  await screen.findByText('30.00%');
  await act(async () => { resolveOld({ ...data(), totalValue: '100' }); });
  await waitFor(() => expect(screen.getByText('30.00%')).toBeInTheDocument());
  expect(screen.queryByText('3.00%')).not.toBeInTheDocument();
});
it('shows query failure without fabricated zero totals or legacy fallback', async () => {
  (apiService.getObservabilityDistribution as jest.Mock).mockRejectedValue(new Error('新版查询未启用'));
  render(<ObservabilityDistribution />);
  expect(await screen.findByRole('alert')).toHaveTextContent('新版查询未启用');
  expect(screen.queryByText('0 B')).not.toBeInTheDocument();
  expect(apiService.getAccessDailyTop).not.toHaveBeenCalled();
});

it('groups location labels without discarding partial geography', () => {
  const item = { value: '1', keys: ['中国', '', ''] };
  expect(distributionLabel(item, 'auth_geo')).toBe('中国 / 地区未知 / 城市未知');
  expect(distributionLabel({ ...item, keys: ['中国', '广东', '深圳'] }, 'auth_geo')).toBe('中国 / 广东 / 深圳');
  expect(distributionLabel({ ...item, keys: ['__PRIVATE__', '', ''] }, 'auth_geo')).toBe('内网来源');
  expect(distributionLabel({ ...item, keys: ['__UNKNOWN__', '', ''] }, 'auth_geo')).toBe('位置未知');
});
it('switches to authentication units and drills into the same filtered audit scope', async () => {
  const auth = { attempts: '10', success: '2', failure: '7', other: '1' };
  (apiService.getObservabilityDistribution as jest.Mock).mockImplementation(query => Promise.resolve(query.dimension === 'auth_geo' ? {
    from: query.from, to: query.to, dimension: 'auth_geo', metric: 'authentications',
    items: [{ keys: ['中国', '', ''], value: '10', auth, sources: [{ clientIp: '1.1.1.1', counts: auth }] }],
    authTotals: auth, totalValue: '20', otherValue: '10', groups: '2', historyBasis: 'AUTH_ATTEMPTS_ONLY', timeBoundary: '[from,to)',
  } : data()));
  (apiService.queryTelemetryPage as jest.Mock).mockResolvedValue({ items: [], total: '0', limit: 50, offset: 0 });
  render(<ObservabilityDistribution />);
  await screen.findByText('30.00%');
  await choose('维度', '认证地理位置');
  await screen.findByText('全部认证：10');
  expect(screen.queryByRole('combobox', { name: '流量分类' })).not.toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: '建连结果' })).not.toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: '指标' })).toBeDisabled();
  expect(screen.getByTestId('distribution-chart')).toHaveTextContent('中国 / 地区未知 / 城市未知, 其他位置');
  await choose('认证结果', '失败');
  expect(apiService.getObservabilityDistribution).toHaveBeenLastCalledWith(expect.objectContaining({ dimension: 'auth_geo', metric: 'authentications', trafficCategory: 'ALL', connectOutcome: 'ALL', authOutcome: 'FAILURE' }));
  fireEvent.click(screen.getByRole('button', { name: '中国 / 地区未知 / 城市未知' }));
  await waitFor(() => expect(apiService.queryTelemetryPage).toHaveBeenCalledWith('credential-auth', expect.objectContaining({ workerId: 'home-01', clientIp: '1.1.1.1', authOutcomeGroup: 'FAILURE' })));
});
