import React from 'react';
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { apiService } from '../services/api';
import { ObservabilityLogAudit, formatAuditBytes, formatAuditDuration, formatAuditTime } from './ObservabilityLogAudit';
import { AuditRecordV1, TelemetryQuery, TelemetryStatusV1, TelemetryType } from '../types/observability';

configure({ asyncUtilTimeout: 5000 });
jest.setTimeout(15000);

jest.mock('../services/api', () => ({ apiService: { getWorkers: jest.fn(), getTelemetryStatus: jest.fn(), queryTelemetryPage: jest.fn(), getTelemetryDetail: jest.fn() } }));
const api = apiService as jest.Mocked<typeof apiService>;
const record = {
  recordId: 'b55c0100-9239-4adc-b740-69bda02a60bd', connectionId: 'b55c0100-9239-4adc-b740-69bda02a60bd',
  workerId: 'home-01', workerInstanceId: 'instance', occurredAt: '2026-10-09T01:02:03.123456Z',
  receivedAt: '2026-10-09T01:02:04Z', username: 'alice', clientIp: '192.168.1.2', inboundProtocol: 'SOCKS5',
  logicalTargetHost: 'nas.test', effectiveTargetHost: '10.0.0.1', destinationOverrideApplied: true,
  uploadBytes: '9007199254740993', downloadBytes: '0', sessionOutcome: 'NORMAL_END', connectOutcome: 'CONNECTED',
  trafficCategory: 'INTERNAL_REWRITE', dnsDurationUs: null,
} as AuditRecordV1;
const status: TelemetryStatusV1 = { schemaVersion: 1, ingestEnabled: true, queryEnabled: true, delivery: 'BEST_EFFORT_WITH_ACK', scope: 'STAGE_A_INFRASTRUCTURE', authenticationAnalysis: 'GEOIP_CONTRACT_ONLY', worker: { capability: 'V1_NEGOTIATED', online: true, complete: false, dropped: 3 } };

beforeEach(() => {
  jest.clearAllMocks();
  api.getWorkers.mockResolvedValue([{ workerId: 'home-01', displayName: 'Home', enabled: true }]);
  api.getTelemetryStatus.mockResolvedValue(status);
  api.queryTelemetryPage.mockResolvedValue({ items: [record], total: '101', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' });
  api.getTelemetryDetail.mockResolvedValue(record);
});

test.each([
  ['0', '0 B'], ['1', '1 B'], ['1023', '1,023 B'], ['1024', '1 KB'],
  ['1536', '1.5 KB'], ['1264', '1.23 KB'], ['1265', '1.24 KB'],
  ['1048576', '1 MB'], ['1073741824', '1 GB'], ['1099511627776', '1 TB'],
  ['9007199254740993', '8 PB'], ['18446744073709551615', '16 EB'],
  [null, '未测量／未采集'], [undefined, '未测量／未采集'],
])('formats %s bytes as %s', (value, expected) => {
  expect(formatAuditBytes(value)).toBe(expected);
});

test('formats null durations and submillisecond values without rounding integers', () => {
  expect(formatAuditDuration('9007199254740993')).toBe('9,007,199,254,740.993 ms（9,007,199,254,740,993 µs）');
  expect(formatAuditDuration('1')).toBe('0.001 ms（1 µs）');
  expect(formatAuditDuration('0')).toBe('0.000 ms（0 µs）');
  expect(formatAuditDuration(null)).toBe('未测量／未采集');
  expect(formatAuditTime('2026-10-09T01:02:03.123456Z')).toContain('.123456');
});

test('submits backend filters and paginates; applying filters resets the offset', async () => {
  render(<ObservabilityLogAudit />);
  expect(screen.getByRole('heading', { name: '日志审计' })).toBeInTheDocument();
  expect(await screen.findByText('8 PB')).toHaveAttribute('title', '9,007,199,254,740,993 B');
  expect(screen.getByText('0 B')).toHaveAttribute('title', '0 B');
  fireEvent.click(screen.getByTitle('2'));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('sessions', expect.objectContaining({ offset: 20, limit: 20 })));
  fireEvent.change(screen.getByRole('textbox', { name: '用户' }), { target: { value: 'alice' } });
  fireEvent.change(screen.getByRole('textbox', { name: '来源 IP' }), { target: { value: '192.168.1.2' } });
  fireEvent.click(screen.getByRole('button', { name: '查 询' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('sessions', expect.objectContaining({ username: 'alice', clientIp: '192.168.1.2', offset: 0 })));
  expect(screen.queryByText('采集与覆盖状态')).not.toBeInTheDocument();
  expect(screen.queryByText(/存在丢弃或覆盖不足/)).not.toBeInTheDocument();
});

test('fetches stable ID details and related facts without inheriting list filters', async () => {
  api.queryTelemetryPage.mockImplementation(async (type, query) => ({ items: query.connectionId ? [] : [record], total: query.connectionId ? '0' : '1', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' }));
  render(<ObservabilityLogAudit />);
  await screen.findByRole('button', { name: /查\s*看/ });
  fireEvent.change(screen.getByRole('textbox', { name: '用户' }), { target: { value: 'alice' } });
  fireEvent.click(screen.getByRole('button', { name: '查 询' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('sessions', expect.objectContaining({ username: 'alice' })));
  fireEvent.click(await screen.findByRole('button', { name: /查\s*看/ }));
  await screen.findByText('关联日志');
  expect(api.getTelemetryDetail).toHaveBeenCalledWith('sessions', record.recordId, expect.objectContaining({ workerId: 'home-01' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenCalledWith('events', expect.objectContaining({ connectionId: record.connectionId })));
  const associated = api.queryTelemetryPage.mock.calls.find(([type, query]) => type === 'events' && query.connectionId)?.[1] as TelemetryQuery;
  expect(associated.username).toBeUndefined();
  const drawer = screen.getByRole('dialog');
  expect(within(drawer).getByText('8 PB')).toHaveAttribute('title', '9,007,199,254,740,993 B');
  expect(within(drawer).getByText('0 B')).toHaveAttribute('title', '0 B');
  expect(within(drawer).getAllByText('未测量／未采集').length).toBeGreaterThan(0);
});

test('keeps four units independent and lets credential failures open without a destination', async () => {
  const auth = { ...record, recordId: 'auth-record', attemptedUsername: 'unknown-account', authOutcome: 'FAILURE', authMethod: 'HTTP_BASIC', analysis: null } as AuditRecordV1;
  api.queryTelemetryPage.mockImplementation(async type => ({ items: type === 'credential-auth' ? [auth] : [], total: type === 'credential-auth' ? '1' : '0', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' }));
  api.getTelemetryDetail.mockResolvedValue(auth);
  render(<ObservabilityLogAudit />);
  await screen.findByText('暂无日志');
  for (const [label, type] of [['DNS 查询', 'dns'], ['诊断事件', 'events'], ['认证审计', 'credential-auth']]) {
    fireEvent.click(screen.getByRole('tab', { name: label }));
    await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith(type, expect.objectContaining({ workerId: 'home-01', offset: 0 })));
  }
  fireEvent.click(await screen.findByRole('button', { name: /查\s*看/ }));
  await screen.findByText('来源分析');
  expect(screen.getAllByText('unknown-account').length).toBeGreaterThan(0);
});

test('shows query disabled without displaying collection diagnostics', async () => {
  api.getTelemetryStatus.mockResolvedValue({ ...status, queryEnabled: false, ingestEnabled: false, worker: { capability: 'UNSUPPORTED_OR_DISABLED', online: false, complete: false } });
  render(<ObservabilityLogAudit />);
  await screen.findByText('管理端新版查询未开启');
  expect(screen.queryByText(/Worker 未协商 V1/)).not.toBeInTheDocument();
  expect(api.queryTelemetryPage).not.toHaveBeenCalled();
});

test('translates diagnostic events in lists and details, keeps unknown codes, and sends original filter values', async () => {
  const event = { ...record, sessionOutcome: undefined, recordId: 'connect-event', eventType: 'CONNECT_RESULT', stage: 'CHANNEL_SETUP', outcome: 'CONNECTED' } as AuditRecordV1;
  const unknown = { ...record, sessionOutcome: undefined, recordId: 'future-event', eventType: 'FUTURE_EVENT', stage: 'FUTURE_STAGE', outcome: 'FUTURE_OUTCOME' } as AuditRecordV1;
  api.queryTelemetryPage.mockImplementation(async type => ({ items: type === 'events' ? [event, unknown] : [], total: type === 'events' ? '2' : '0', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' }));
  api.getTelemetryDetail.mockResolvedValue(event);
  render(<ObservabilityLogAudit />);
  fireEvent.click(screen.getByRole('tab', { name: '诊断事件' }));
  const setup = await screen.findByText('通道建立');
  const row = setup.closest('tr')!;
  expect(within(row).getByText('alice')).toBeInTheDocument();
  expect(within(row).getByText('192.168.1.2')).toBeInTheDocument();
  expect(within(row).getByText('SOCKS5协议')).toHaveAttribute('title', 'SOCKS5');
  expect(within(row).getByText('建连结果')).toHaveAttribute('title', 'CONNECT_RESULT');
  expect(within(row).getByText('通道已建立')).toHaveAttribute('title', 'CONNECTED');
  expect(screen.getByText('FUTURE_EVENT')).toBeInTheDocument();
  expect(screen.getByText('FUTURE_STAGE')).toBeInTheDocument();
  expect(screen.getByText('FUTURE_OUTCOME')).toBeInTheDocument();

  fireEvent.mouseDown(screen.getByRole('combobox', { name: '事件类型' }));
  fireEvent.click(await screen.findByText('首次错误'));
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '结果' }));
  fireEvent.click(await screen.findByText('发生错误'));
  fireEvent.click(screen.getByRole('button', { name: '查 询' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('events', expect.objectContaining({ eventType: 'FIRST_ERROR', outcome: 'ERROR' })));

  const detailRow = (await screen.findByText('通道建立')).closest('tr')!;
  fireEvent.click(within(detailRow).getByRole('button', { name: /查\s*看/ }));
  const drawer = await screen.findByRole('dialog');
  await within(drawer).findByText('通道建立');
  expect(within(drawer).getByText('建连结果')).toHaveAttribute('title', 'CONNECT_RESULT');
  expect(within(drawer).getByText('通道已建立')).toHaveAttribute('title', 'CONNECTED');
});

const filterCases = [
  { type: 'sessions', tab: '连接记录', resultField: 'sessionOutcome', result: 'ERROR_END', resultLabel: '异常结束', protocol: 'TP_PROXY', protocolLabel: '透明代理',
    extra: [{ name: '建连结果', field: 'connectOutcome', code: 'FAILED', label: '建连失败' }, { name: '流量分类', field: 'trafficCategory', code: 'INTERNAL_REWRITE', label: '内网访问（目标重写）' }] },
  { type: 'dns', tab: 'DNS 查询', resultField: 'outcome', result: 'ANSWERED', resultLabel: '已应答', protocol: 'DNS_SERVER', protocolLabel: 'DNS 服务',
    extra: [{ name: '查询类型', field: 'qtype', code: 'AAAA', label: 'IPv6 地址（AAAA）' }] },
  { type: 'events', tab: '诊断事件', resultField: 'outcome', result: 'ERROR', resultLabel: '发生错误', protocol: 'SOCKS5', protocolLabel: 'SOCKS5协议',
    extra: [{ name: '事件类型', field: 'eventType', code: 'FIRST_ERROR', label: '首次错误' }] },
  { type: 'credential-auth', tab: '认证审计', resultField: 'authOutcome', result: 'FAILURE', resultLabel: '认证失败', protocol: 'HTTPS_CONNECT', protocolLabel: 'HTTPS CONNECT协议',
    extra: [{ name: '认证方式', field: 'authMethod', code: 'HTTP_BASIC', label: 'HTTP Basic 认证' }] },
];

async function chooseAuditOption(name: string, label: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name }));
  const option = await waitFor(() => {
    const dropdown = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    expect(dropdown).not.toBeNull();
    return within(dropdown as HTMLElement).getByText(label);
  });
  fireEvent.click(option);
}

test.each(filterCases)('$tab uses translated dropdowns and submits raw enum values; reset clears them', async item => {
  const sample = { ...record, recordId: `${item.type}-record`, inboundProtocol: item.protocol, [item.resultField]: item.result,
    qname: 'example.test', stage: 'AUTH', ...Object.fromEntries(item.extra.map(field => [field.field, field.code])) } as AuditRecordV1;
  api.queryTelemetryPage.mockImplementation(async type => ({ items: type === item.type ? [sample] : [], total: type === item.type ? '1' : '0', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' }));
  api.getTelemetryDetail.mockResolvedValue(sample);
  render(<ObservabilityLogAudit />);
  fireEvent.click(screen.getByRole('tab', { name: item.tab }));
  const result = await screen.findByText(item.resultLabel);
  expect(result).toHaveAttribute('title', item.result);
  const row = result.closest('tr')!;
  expect(within(row).getByText(item.protocolLabel)).toHaveAttribute('title', item.protocol);
  await chooseAuditOption('协议', item.protocolLabel);
  await chooseAuditOption('结果', item.resultLabel);
  for (const extra of item.extra) await chooseAuditOption(extra.name, extra.label);
  fireEvent.click(screen.getByRole('button', { name: '查 询' }));
  const filters = { protocol: item.protocol, outcome: item.result, ...Object.fromEntries(item.extra.map(field => [field.field, field.code])) };
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith(item.type, expect.objectContaining(filters)));

  fireEvent.click(screen.getByRole('button', { name: '重 置' }));
  await waitFor(() => {
    expect(api.queryTelemetryPage).toHaveBeenLastCalledWith(item.type, expect.objectContaining({ protocol: undefined, outcome: undefined }));
    const query = api.queryTelemetryPage.mock.calls.at(-1)![1];
    for (const extra of item.extra) expect(query[extra.field as keyof TelemetryQuery]).toBeUndefined();
  });
  fireEvent.click(await screen.findByRole('button', { name: /查\s*看/ }));
  const drawer = await screen.findByRole('dialog');
  expect(await within(drawer).findByText(item.resultLabel, { exact: true })).toHaveAttribute('title', item.result);
  expect(within(drawer).getByText(item.protocolLabel)).toHaveAttribute('title', item.protocol);
});

test('all authentication outcomes are translated and selecting a result keeps its original value', async () => {
  const outcomes = { SUCCESS: '认证通过', FAILURE: '认证失败', DENIED: '认证拒绝', CHALLENGE: '请求提供凭据', MALFORMED: '认证格式错误', CANCELLED: '认证取消', ERROR: '认证异常' };
  const rows = Object.keys(outcomes).map(authOutcome => ({ ...record, recordId: authOutcome, authOutcome, authMethod: 'SOCKS5_PASSWORD' } as AuditRecordV1));
  api.queryTelemetryPage.mockImplementation(async type => ({ items: type === 'credential-auth' ? rows : [], total: type === 'credential-auth' ? '7' : '0', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' }));
  render(<ObservabilityLogAudit />);
  fireEvent.click(screen.getByRole('tab', { name: '认证审计' }));
  await screen.findByText('认证通过');
  for (const [code, label] of Object.entries(outcomes)) {
    expect(screen.getByText(label)).toHaveAttribute('title', code);
  }
  await chooseAuditOption('结果', '认证拒绝');
  await chooseAuditOption('认证方式', 'SOCKS5 用户名密码');
  fireEvent.click(screen.getByRole('button', { name: '查 询' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('credential-auth', expect.objectContaining({ outcome: 'DENIED', authMethod: 'SOCKS5_PASSWORD' })));
  fireEvent.click(screen.getByRole('tab', { name: 'DNS 查询' }));
  await waitFor(() => expect(api.queryTelemetryPage).toHaveBeenLastCalledWith('dns', expect.objectContaining({ offset: 0 })));
  const query = api.queryTelemetryPage.mock.calls.at(-1)![1];
  expect(query.authMethod).toBeUndefined();
  expect(query.outcome).toBeUndefined();
});

test('explains authentication failure before routing without implying a missing measurement', async () => {
  const earlyFailure = { ...record, effectiveTargetHost: null, logicalTargetHost: null, routeName: null,
    username: null, userId: null,
    credentialAuthAttempts: [{ authAttemptId: 'auth-failure', attemptedUsername: 'unknown-account', authOutcome: 'FAILURE', authMethod: 'SOCKS5_PASSWORD', occurredAt: record.occurredAt }],
    sessionOutcome: 'ERROR_END', connectOutcome: 'NOT_ATTEMPTED', failureStage: 'AUTH', errorCode: 'INVALID_CREDENTIALS' } as AuditRecordV1;
  api.queryTelemetryPage.mockResolvedValue({ items: [earlyFailure], total: '1', limit: 20, offset: 0, maxOffset: 10000, timeBoundary: '[from,to)' });
  render(<ObservabilityLogAudit />);
  await screen.findByText('认证未通过，目标未记录');
  expect(screen.getByText('认证阶段未进入选路')).toBeInTheDocument();
  expect(screen.getByText('异常结束')).toBeInTheDocument();
  expect(screen.getByText('建连：未尝试建连')).toBeInTheDocument();
  expect(screen.getByText('用户名或密码错误')).toBeInTheDocument();
  expect(screen.getByText('尝试账号：unknown-account（未认证）')).toBeInTheDocument();
  api.getTelemetryDetail.mockResolvedValue(earlyFailure);
  fireEvent.click(screen.getByRole('button', { name: /查\s*看/ }));
  const drawer = await screen.findByRole('dialog');
  await within(drawer).findByText('尝试登录账号');
  expect(within(drawer).getByText('unknown-account', { exact: true })).toBeInTheDocument();
  expect(within(drawer).getByText('认证失败', { exact: true })).toBeInTheDocument();
});

test('query errors are distinct from an empty result and can be retried', async () => {
  api.queryTelemetryPage.mockRejectedValueOnce(new Error('storage unavailable'));
  render(<ObservabilityLogAudit />);
  await screen.findByText('storage unavailable');
  expect(screen.queryByText('暂无日志')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
  await screen.findByText('8 PB');
});

test('shows measured stage timings, protocol applicability and the actual proxy confirmation boundary', async () => {
  const measured = { ...record, clientHandshakeDurationUs: '1200', routeSelectionDurationUs: '25',
    outboundTcpDurationUs: '840', proxyHandshakeDurationUs: '600', proxyHandshakeOutcome: 'LOCAL_REQUEST_SENT',
    outboundMode: 'OUTBOUND_PROXY', outboundProtocol: 'SHADOW_SOCKS',
    uploadBackpressureDurationUs: '0', downloadBackpressureDurationUs: '0' } as AuditRecordV1;
  api.getTelemetryDetail.mockResolvedValue(measured);
  render(<ObservabilityLogAudit />);
  fireEvent.click(await screen.findByRole('button', { name: /查\s*看/ }));
  const drawer = await screen.findByRole('dialog');
  await within(drawer).findByText('1.200 ms（1,200 µs）');
  expect(within(drawer).getByText('0.840 ms（840 µs）')).toBeInTheDocument();
  expect(within(drawer).getByText('本地请求已发送')).toBeInTheDocument();
  expect(within(drawer).getByText('不适用（客户端直接提供目标）')).toBeInTheDocument();
  expect(within(drawer).getByText('不适用（此出站不执行 TLS）')).toBeInTheDocument();
  expect(within(drawer).queryByText('限流等待')).not.toBeInTheDocument();
});
