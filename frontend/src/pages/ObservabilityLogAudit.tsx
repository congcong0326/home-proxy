import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Select, Space, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { apiService } from '../services/api';
import { AuditRecordV1, TelemetryPageV1, TelemetryQuery, TelemetryStatusV1, TelemetryType, SessionLogV1 } from '../types/observability';
import { ProxyWorkerDTO } from '../types/worker';
import { PROTOCOL_TYPE_LABELS } from '../types/route';
import './ObservabilityLogAudit.css';

const PAGE_SIZE = 20;

const kinds: { key: TelemetryType; label: string }[] = [
  { key: 'sessions', label: '连接记录' }, { key: 'dns', label: 'DNS 查询' },
  { key: 'events', label: '诊断事件' }, { key: 'credential-auth', label: '认证审计' },
];
const categories = { INTERNAL_REWRITE: '内网访问（目标重写）', EXTERNAL: '外网', OTHER_INTERNAL: '其他内网', UNKNOWN: '未知' };
const sessionOutcomes: Record<string, string> = {
  NORMAL_END: '正常结束', ERROR_END: '异常结束', BLOCKED: '策略阻断', CANCELLED: '提前结束',
};
const connectOutcomes: Record<string, string> = {
  CONNECTED: '通道已建立', FAILED: '建连失败', BLOCKED: '策略阻断', CANCELLED: '建连未完成', NOT_ATTEMPTED: '未尝试建连',
};
const failureStages: Record<string, string> = {
  AUTH: '认证', OUTBOUND_CONNECT: '出站建连', RELAY: '数据转发', TARGET_RECOVERY: '目标恢复',
  INBOUND: '入站处理', CLIENT_HANDSHAKE: '客户端握手', CHANNEL_SETUP: '通道建立',
  ROUTING: '路由选择', ROUTE_SELECTION: '路由选择', DNS: 'DNS 解析',
  OUTBOUND_TCP: '出站 TCP 建连', OUTBOUND_TLS: '出站 TLS 握手', PROXY_HANDSHAKE: '上游代理握手',
  PROXY_AUTH: '上游代理认证', UPLOAD_BACKPRESSURE: '上传背压', DOWNLOAD_BACKPRESSURE: '下载背压', UNKNOWN: '未知',
};
const eventTypes: Record<string, string> = {
  CONNECT_RESULT: '建连结果', TARGET_RECOVERY_RESULT: '目标恢复结果',
  FIRST_ERROR: '首次错误', ERROR_SUMMARY: '错误汇总',
};
const eventOutcomes: Record<string, string> = {
  ...connectOutcomes, ...sessionOutcomes, ERROR: '发生错误', RECOVERED: '目标已恢复', UNKNOWN: '未知',
};
const errorCodes: Record<string, string> = {
  INVALID_CREDENTIALS: '用户名或密码错误', INVALID_BASIC_ENCODING: '认证信息格式错误',
  NO_ACCEPTABLE_METHOD: '没有可接受的认证方式', CONNECT_FAILED: '出站连接失败',
  POLICY_BLOCKED: '被策略阻断', WRITE_ERROR: '数据转发写入失败',
  RELAY_ERROR: '数据转发异常', UNHANDLED_EXCEPTION: '入站处理异常',
};
const authOutcomes: Record<string, string> = {
  SUCCESS: '认证通过', FAILURE: '认证失败', DENIED: '认证拒绝', CHALLENGE: '请求提供凭据',
  MALFORMED: '认证格式错误', CANCELLED: '认证取消', ERROR: '认证异常',
};
const dnsOutcomes: Record<string, string> = {
  ANSWERED: '已应答', BLOCKED: '策略阻断', ERROR: '查询异常', CANCELLED: '查询取消',
};
const outcomeLabels: Record<TelemetryType, Record<string, string>> = {
  sessions: sessionOutcomes, dns: dnsOutcomes, events: eventOutcomes, 'credential-auth': authOutcomes,
};
const protocolLabels: Record<string, string> = { ...PROTOCOL_TYPE_LABELS, DNS_SERVER: 'DNS 服务', DOT: 'DNS over TLS', TCP: 'TCP', UNKNOWN: '未知' };
const connectionProtocols = ['SOCKS5', 'HTTPS_CONNECT', 'SHADOW_SOCKS', 'TP_PROXY', 'TLS_SNI_TCP'];
const inboundProtocols: Record<TelemetryType, string[]> = {
  sessions: connectionProtocols, dns: ['DNS_SERVER'], events: connectionProtocols, 'credential-auth': ['SOCKS5', 'HTTPS_CONNECT'],
};
const authMethods: Record<string, string> = { HTTP_BASIC: 'HTTP Basic 认证', SOCKS5_PASSWORD: 'SOCKS5 用户名密码' };
const dnsQueryTypes: Record<string, string> = { A: 'IPv4 地址（A）', AAAA: 'IPv6 地址（AAAA）' };
const geoLookupStatuses: Record<string, string> = {
  RESOLVED: '已识别位置', PRIVATE: '内网地址', DB_UNAVAILABLE: '位置数据库不可用',
  NOT_FOUND: '未查到位置', ERROR: '位置查询异常', UNKNOWN: '未知',
};
const optionsFor = (labels: Record<string, string>) => Object.entries(labels).map(([value, label]) => ({ value, label }));
const routeActions: Record<string, string> = {
  DIRECT: '直连', DESTINATION_OVERRIDE: '目标重写', BLOCK: '阻断',
  DNS_REWRITING: '自定义 DNS 解析', OUTBOUND_PROXY: '出站代理', UNKNOWN: '未知',
};
const detailLabels: Record<string, Record<string, string>> = {
  inboundProtocol: protocolLabels,
  outboundProtocol: protocolLabels,
  authOutcome: authOutcomes,
  authMethod: authMethods,
  authEventKind: { CREDENTIAL_CHECK: '凭据校验', CHALLENGE: '请求提供凭据', PROTOCOL_REJECTION: '认证协议拒绝' },
  reasonCode: { ...errorCodes, VERIFIED: '凭据验证通过', ACCOUNT_LOCKED: '账号暂时锁定', MISSING_CREDENTIALS: '未提供凭据', UNSUPPORTED_AUTH_SCHEME: '不支持的认证方式', INVALID_BASIC_FORMAT: '认证信息格式错误' },
  identitySource: { ACCOUNT_AUTH: '账号认证', DEVICE_IP: '设备 IP 匹配', ANONYMOUS: '匿名用户', UNKNOWN: '未知' },
  qtype: dnsQueryTypes,
  queryOrigin: { CLIENT: '客户端查询', INTERNAL: '内部解析' },
  answerMode: { BLOCK: '策略阻断', REWRITE: 'DNS 重写', FAKE_IP: 'Fake-IP 应答', REAL: '真实解析' },
  emptyAnswerReason: { POLICY_BLOCK: '被策略阻断', ERROR: '查询异常', IPV6_SUPPRESSED: '已抑制 IPv6 应答', NODATA: '无对应记录' },
  cacheStatus: { HIT: '缓存命中', MISS: '缓存未命中', NOT_APPLICABLE: '不适用', UNKNOWN: '未知' },
  eventType: eventTypes,
  stage: failureStages,
  proxyHandshakeOutcome: { SUCCESS: '上游响应成功', LOCAL_REQUEST_SENT: '本地请求已发送', FAILED: '握手失败', CANCELLED: '握手未完成', UNKNOWN: '未知' },
  clientIpSource: { TRANSPORT_PEER: '连接对端地址', UNKNOWN: '未知' },
  targetSource: {
    INBOUND_REQUEST: '客户端请求提供', FAKE_IP_MAPPING: 'Fake-IP 映射恢复',
    HTTP_HOST: 'HTTP Host 识别', TLS_SNI: 'TLS SNI 识别', ORIGINAL_IP: '原始目标 IP', UNKNOWN: '未知',
  },
  outboundMode: routeActions,
  routeAction: routeActions,
  routeSelection: { DEFAULT: '默认路由', RULE_MATCH: '规则匹配', UNKNOWN: '未知' },
};
const fieldHelp: Record<string, string> = {
  clientHandshakeDurationUs: '从 Worker 接收连接至解析出完整代理请求；包含客户端发送等待、认证及入站 TLS（若启用）。认证失败或提前关闭计至失败／关闭。透明代理和 SNI 分流使用目标恢复耗时。',
  targetRecoveryDurationUs: '透明代理／SNI 分流从接收连接至完成目标识别，包含 Fake-IP 反查及首包嗅探等待；提前关闭计至关闭。普通代理直接提供目标时不适用。',
  routeSelectionDurationUs: '从进入路由选择到完成或退出选择，包含规则匹配和默认路由选择。',
  dnsDurationUs: '本机为本次出站解析目标或上游节点的耗时，包含缓存查询和 A／AAAA 回退；上游代理自行解析最终目标的耗时不可见。',
  outboundTcpDurationUs: '地址解析完成后，从实际发起 socket connect 至 TCP 成功或失败；不包含 DNS、TLS 和代理握手。',
  outboundTlsDurationUs: 'Worker 执行的 REALITY TLS 握手耗时，从 TCP 就绪至握手完成、失败或关闭；不测量 HTTPS 隧道内客户端与网站的 TLS。',
  proxyHandshakeDurationUs: 'HTTP／SOCKS5 从 TCP 就绪至上游成功响应或失败；Shadowsocks 至本地请求写入完成，VLESS 从 REALITY TLS 完成至本地请求发送完成。具体确认边界见代理握手结果。',
  proxyHandshakeOutcome: '上游响应成功仅表示代理协议确认通道；本地请求已发送不代表上游已确认目标连接或业务成功。',
  uploadBackpressureDurationUs: '实际中继时，因出站不可写而暂停客户端读取的累计区间；重复通知不重复计数，关闭时结束计时。已监测且没有发生时为 0。',
  downloadBackpressureDurationUs: '实际中继时，因客户端通道不可写而暂停出站读取的累计区间；重复通知不重复计数，关闭时结束计时。已监测且没有发生时为 0。',
  channelSetupDurationUs: '从开始出站连接到连接器报告通道就绪或失败；可能包含 DNS、TCP、TLS 和代理握手。各阶段可能有包含关系，请勿将所有耗时直接相加。',
  clientIpSource: '来源 IP 的取值依据。“连接对端地址”指直接连接 Worker 的对端；经过 NAT 或上游代理时，可能是 NAT 出口或上游代理的地址。',
  transportPeerIp: 'Worker 接收连接时看到的网络对端地址；当前来源 IP 取自此地址。',
  ruleSetVersion: '本次路由决策使用的规则集版本快照，用于追溯当时的规则。与配置版本不同，当前 Worker 尚未上报规则集版本，空值不表示没有使用规则。',
  originalDstIp: '透明代理截获连接时的原始目的 IP，可能是 Fake-IP。当前普通代理入站不单独记录此字段，客户端请求的目标见“逻辑目标”；也不是目标重写前的域名。',
  originalDstPort: '与原始目标 IP 配套记录的目的端口；当前没有原始目标 IP 时也不记录此端口。普通代理请求的端口见“逻辑端口”。',
  targetSource: '逻辑目标的取得方式。“客户端请求提供”表示目标由入站请求给出；透明代理也可从 Fake-IP 映射、HTTP Host、TLS SNI 或原始目的 IP 取得目标。',
  outboundPeerIp: 'Worker 实际建立出站连接的对端 IP；使用出站代理时，此处是上游代理地址，不一定是最终目标地址。',
  outboundMode: '本次路由采用的转发策略：直连、目标重写、阻断、自定义 DNS 解析或出站代理。',
};
const missing = '未测量／未采集';
const groupDigits = (value: string) => value.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
const rawAuditBytes = (value: string | null | undefined) => value == null ? missing : `${groupDigits(value)} B`;
export const formatAuditBytes = (value: string | null | undefined) => {
  if (value == null) return missing;
  const bytes = BigInt(value);
  const base = BigInt(1024);
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
  let unit = 0;
  let divisor = BigInt(1);
  while (unit < units.length - 1 && bytes >= divisor * base) {
    divisor *= base;
    unit++;
  }
  if (unit === 0) return rawAuditBytes(value);
  // Round with integer arithmetic so UInt64 byte counts retain their precision.
  const hundred = BigInt(100);
  const rounded = (bytes * hundred + divisor / BigInt(2)) / divisor;
  const fraction = (rounded % hundred).toString().padStart(2, '0').replace(/0+$/, '');
  return `${groupDigits((rounded / hundred).toString())}${fraction ? `.${fraction}` : ''} ${units[unit]}`;
};
export const formatAuditDuration = (value: string | null | undefined) => {
  if (value == null) return missing;
  const padded = value.padStart(4, '0');
  return `${groupDigits(padded.slice(0, -3))}.${padded.slice(-3)} ms（${groupDigits(value)} µs）`;
};
export const formatAuditTime = (value: string | null | undefined) => {
  if (!value) return missing;
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return value;
  const fraction = value.match(/\.(\d+)/)?.[1];
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(time)
    + (fraction ? `.${fraction}` : '');
};
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
const localInput = (time: Date) => new Date(time.getTime() - time.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
const getField = (record: AuditRecordV1, key: string): unknown => (record as unknown as Record<string, unknown>)[key];
const text = (value: unknown): string => value == null ? missing : typeof value === 'boolean' ? value ? '是' : '否' : Array.isArray(value) ? value.length ? value.join('，') : '无记录' : String(value);
const labelled = (labels: Record<string, string>, value: unknown) => value == null ? '未记录' : labels[String(value)] ?? String(value);
const sessionTarget = (row: AuditRecordV1) => {
  const host = getField(row, 'effectiveTargetHost') ?? getField(row, 'logicalTargetHost');
  if (host != null) return String(host);
  if (getField(row, 'selectedTargetIp') != null) return String(getField(row, 'selectedTargetIp'));
  if (getField(row, 'originalDstIp') != null) return `原始目标：${getField(row, 'originalDstIp')}`;
  return getField(row, 'failureStage') === 'AUTH' ? '认证未通过，目标未记录' : '目标未记录';
};
const sessionRoute = (row: AuditRecordV1) => {
  if (getField(row, 'routeName') != null) return String(getField(row, 'routeName'));
  if (getField(row, 'routeId') != null) return `路由 ID：${getField(row, 'routeId')}`;
  if (getField(row, 'routeAction') != null) return `路由动作：${getField(row, 'routeAction')}`;
  return getField(row, 'failureStage') === 'AUTH' ? '认证阶段未进入选路' : '路由未记录';
};
const credentialAttempts = (row: AuditRecordV1) =>
  (getField(row, 'credentialAuthAttempts') as NonNullable<SessionLogV1['credentialAuthAttempts']> | undefined) ?? [];
function SessionUser({ row }: { row: AuditRecordV1 }) {
  const attempts = credentialAttempts(row);
  const names = Array.from(new Set(attempts.filter(a => a.attemptedUsername != null && a.authOutcome !== 'SUCCESS').map(a => a.attemptedUsername!)));
  return <>
    {row.username != null && <div>{row.username}</div>}
    {names.slice(0, 3).map(name => <div key={name}>尝试账号：{name === '' ? '（空账号）' : name}（未认证）</div>)}
    {!row.username && names.length === 0 && <div>{attempts.length > 0 && attempts.every(a => a.attemptedUsername == null) ? '账号未提交或无法解析' : '用户未记录'}</div>}
    {(names.length > 3 || getField(row, 'credentialAuthAttemptsTruncated') === true) && <div>更多认证尝试请查看详情</div>}
  </>;
}
const resultOf = (type: TelemetryType, record: AuditRecordV1) => getField(record, type === 'sessions' ? 'sessionOutcome' : type === 'credential-auth' ? 'authOutcome' : 'outcome');
const errorText = (error: unknown) => error instanceof Error ? error.message : '请求失败';

export function AuditList({ type, query, onDetail }: { type: TelemetryType; query: TelemetryQuery; onDetail: (type: TelemetryType, record: AuditRecordV1) => void }) {
  const [page, setPage] = useState<TelemetryPageV1>();
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true); setError(''); setPage(undefined);
    apiService.queryTelemetryPage(type, { ...query, limit: PAGE_SIZE, offset }).then(value => {
      if (active) setPage(value);
    }).catch(reason => { if (active) setError(errorText(reason)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [type, query, offset, reload]);
  const columns: ColumnsType<AuditRecordV1> = [
    { title: `时间（${timezone}）`, key: 'time', width: 245, render: (_, row) => <span title={row.occurredAt}>{formatAuditTime(row.occurredAt)}</span> },
    { title: '用户／来源', key: 'source', width: 220, render: (_, row) => <>{type === 'sessions' ? <SessionUser row={row} /> : <div>{getField(row, 'attemptedUsername') != null ? text(getField(row, 'attemptedUsername')) : row.username ?? (type === 'credential-auth' ? '账号未提交或无法解析' : '用户未记录')}</div>}<div>{row.clientIp ?? '来源未记录'}</div></> },
    { title: '协议', dataIndex: 'inboundProtocol', width: 135, render: value => <span title={text(value)}>{labelled(protocolLabels, value)}</span> },
    { title: type === 'sessions' ? '目标／路由' : type === 'dns' ? '域名／类型' : type === 'events' ? '事件／阶段' : '认证方式／位置', key: 'subject', width: 230, render: (_, row) => {
      if (type === 'sessions') return <><div>{sessionTarget(row)}</div><div>{sessionRoute(row)}</div></>;
      if (type === 'dns') return <>{text(getField(row, 'qname'))} / <span title={text(getField(row, 'qtype'))}>{labelled(dnsQueryTypes, getField(row, 'qtype'))}</span></>;
      if (type === 'events') return <><span title={text(getField(row, 'eventType'))}>{labelled(eventTypes, getField(row, 'eventType'))}</span> / <span title={text(getField(row, 'stage'))}>{labelled(failureStages, getField(row, 'stage'))}</span></>;
      const analysis = getField(row, 'analysis') as Record<string, unknown> | null;
      return <><div title={text(getField(row, 'authMethod'))}>{labelled(authMethods, getField(row, 'authMethod'))}</div><div>{analysis?.sourceCountry ? `${text(analysis.sourceCountry)} ${text(analysis.sourceCity)}` : `位置未知（${labelled(geoLookupStatuses, analysis?.geoLookupStatus)}）`}</div></>;
    } },
    { title: '结果', key: 'outcome', width: 200, render: (_, row) => type === 'sessions' ? <>
      <Tag title={text(getField(row, 'sessionOutcome'))}>{labelled(sessionOutcomes, getField(row, 'sessionOutcome'))}</Tag>
      <div title={text(getField(row, 'connectOutcome'))}>建连：{labelled(connectOutcomes, getField(row, 'connectOutcome'))}</div>
      {getField(row, 'errorCode') != null && <div title={text(getField(row, 'errorCode'))}>{labelled(errorCodes, getField(row, 'errorCode'))}</div>}
    </> : <Tag title={text(resultOf(type, row))}>{labelled(outcomeLabels[type], resultOf(type, row))}</Tag> },
    ...(type === 'sessions' ? [{ title: '流量／分类', key: 'traffic', width: 235, render: (_: unknown, row: AuditRecordV1) => <><div>↑ <span title={rawAuditBytes(getField(row, 'uploadBytes') as string)}>{formatAuditBytes(getField(row, 'uploadBytes') as string)}</span></div><div>↓ <span title={rawAuditBytes(getField(row, 'downloadBytes') as string)}>{formatAuditBytes(getField(row, 'downloadBytes') as string)}</span></div><div>{categories[getField(row, 'trafficCategory') as keyof typeof categories] ?? '未知'}</div></> }] : []),
    { title: '详情', key: 'detail', width: 80, fixed: 'right', render: (_, row) => <Button size="small" onClick={() => onDetail(type, row)}>查看</Button> },
  ];
  return <>
    {error && <Alert type="error" showIcon message="查询失败" description={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {page && <Typography.Paragraph type="secondary">共 {groupDigits(page.total)} 条{Number(page.total) > page.maxOffset + PAGE_SIZE && '；已达浏览上限，请缩小时间或筛选范围。'}</Typography.Paragraph>}
    <Table rowKey="recordId" size="small" columns={columns} dataSource={page?.items ?? []} loading={loading} scroll={{ x: type === 'sessions' ? 1350 : 1100 }}
      locale={{ emptyText: error ? '查询失败' : '暂无日志' }}
      pagination={{ current: offset / PAGE_SIZE + 1, pageSize: PAGE_SIZE, total: Math.min(Number(page?.total ?? 0), (page?.maxOffset ?? 10000) + PAGE_SIZE), showSizeChanger: false, onChange: p => setOffset((p - 1) * PAGE_SIZE) }} />
  </>;
}

const commonFields = [
  ['recordId', '记录 ID'], ['connectionId', '连接 ID'], ['workerId', 'Worker'], ['workerInstanceId', 'Worker 实例'],
  ['occurredAt', '发生时间'], ['receivedAt', '管理端接收时间'], ['inboundName', '入站'], ['inboundId', '入站 ID'],
  ['inboundProtocol', '入站协议'], ['username', '已识别用户'], ['userId', '用户 ID'], ['identitySource', '身份来源'],
  ['clientIp', '来源 IP'], ['clientPort', '来源端口'], ['clientIpSource', '来源依据'], ['transportPeerIp', '连接对端 IP'],
  ['transportPeerPort', '连接对端端口'], ['configVersion', '配置版本'], ['ruleSetVersion', '规则集版本'],
];
const sessionGroups = [
  { title: '原始、逻辑与实际目标', fields: [ ['originalDstIp', '原始目标 IP'], ['originalDstPort', '原始端口'], ['logicalTargetHost', '逻辑目标'], ['logicalTargetPort', '逻辑端口'], ['targetSource', '目标识别来源'], ['destinationOverrideApplied', '目标重写实际生效'], ['effectiveTargetHost', '有效目标'], ['effectiveTargetPort', '有效端口'], ['selectedTargetIp', '实际选定目标 IP'], ['outboundPeerIp', '出站对端 IP'], ['outboundPeerPort', '出站对端端口'], ['outboundMode', '出站方式'], ['outboundProtocol', '出站协议'] ] },
  { title: '路由与流量分类', fields: [ ['routeId', '路由 ID'], ['routeName', '路由名称'], ['routeAction', '路由动作'], ['routeSelection', '选路依据'], ['matchedRuleId', '命中规则'], ['matchedRuleSet', '命中规则集'], ['uploadBytes', '上传'], ['downloadBytes', '下载'], ['trafficCategory', '业务流量分类'], ['trafficCategoryBasis', '分类依据'], ['trafficClassificationVersion', '分类算法版本'], ['targetAddressClass', '目标地址类别'], ['targetNetworkScope', '目标网络范围'], ['clientNetworkScope', '来源网络范围'], ['outboundPeerNetworkScope', '出站 peer 网络范围'] ] },
  { title: '结果与连接边界', fields: [ ['connectOutcome', '建连结果'], ['sessionOutcome', '结束结果'], ['failureStage', '失败阶段'], ['errorCode', '错误码'], ['closeInitiator', '关闭发起方'], ['closeReason', '关闭原因'], ['channelReadyBasis', '通道就绪依据'], ['targetConfirmationLevel', '目标确认级别'], ['proxyHandshakeOutcome', '代理握手结果'] ] },
  { title: '时间与耗时', fields: [ ['startedAt', '接收时间'], ['connectedAt', '通道就绪时间'], ['endedAt', '结束时间'], ['firstUploadAt', '首次上传'], ['firstDownloadAt', '首次下载'], ['lastActivityAt', '末次活动'], ['sessionDurationUs', '连接寿命'], ['channelSetupDurationUs', '通道建立耗时'], ['clientHandshakeDurationUs', '客户端握手'], ['targetRecoveryDurationUs', '目标恢复'], ['routeSelectionDurationUs', '路由选择'], ['dnsDurationUs', 'DNS'], ['outboundTcpDurationUs', '出站 TCP'], ['outboundTlsDurationUs', '出站 TLS'], ['proxyHandshakeDurationUs', '代理握手'], ['uploadBackpressureDurationUs', '上传背压'], ['downloadBackpressureDurationUs', '下载背压'] ] },
];
const otherFields: Record<string, string[][]> = {
  dns: [['queryId', '查询 ID'], ['parentConnectionId', '父连接 ID'], ['queryOrigin', '查询来源'], ['resolvePurpose', '解析用途'], ['qname', '域名'], ['qtype', '查询类型'], ['qclass', '查询类'], ['transport', '传输'], ['outcome', '结果'], ['rcode', '响应码'], ['answerMode', '应答模式'], ['answerIps', '回答 IP'], ['emptyAnswerReason', '空回答原因'], ['responseSent', '应答已发送'], ['ttlSeconds', 'TTL（秒）'], ['cacheStatus', '缓存'], ['resolverEndpoint', '解析器'], ['upstreamAttemptCount', '真实上游尝试数'], ['durationUs', '查询耗时'], ['upstreamDurationUs', '上游耗时'], ['requestBytes', '请求字节'], ['responseBytes', '响应字节'], ['errorCode', '错误码'], ['mappingId', '映射 ID'], ['mappingRegistryId', '映射注册表 ID']],
  events: [['eventId', '事件 ID'], ['eventSequence', '事件序号'], ['eventType', '事件类型'], ['stage', '阶段'], ['outcome', '结果'], ['errorCode', '错误码'], ['recoverySource', '恢复来源'], ['mappingOutcome', '映射结果'], ['fallbackOutcome', '回退结果'], ['durationUs', '阶段耗时'], ['repeatCount', '重复次数'], ['firstOccurredAt', '首次发生'], ['lastOccurredAt', '末次发生'], ['queryId', '查询 ID']],
  'credential-auth': [['authAttemptId', '认证尝试 ID'], ['authMethod', '认证方式'], ['authEventKind', '认证事件'], ['attemptedUsername', '尝试用户名'], ['verifiedUsername', '已验证用户名'], ['authOutcome', '认证结果'], ['reasonCode', '原因码'], ['authDurationUs', '认证耗时']],
};
const unavailableSessionDuration = (record: AuditRecordV1, key: string): string | undefined => {
  const inbound = record.inboundProtocol;
  if (key === 'clientHandshakeDurationUs' && ['TP_PROXY', 'TLS_SNI_TCP'].includes(inbound ?? '')) return '不适用（见目标恢复耗时）';
  if (key === 'targetRecoveryDurationUs' && ['SOCKS5', 'HTTPS_CONNECT', 'SHADOW_SOCKS'].includes(inbound ?? '')) return '不适用（客户端直接提供目标）';
  if (key === 'outboundTlsDurationUs' && getField(record, 'outboundProtocol') && getField(record, 'outboundProtocol') !== 'VLESS_REALITY') return '不适用（此出站不执行 TLS）';
  if (key === 'proxyHandshakeDurationUs' && getField(record, 'outboundMode') && getField(record, 'outboundMode') !== 'OUTBOUND_PROXY') return '不适用（未使用上游代理）';
  if (['uploadBackpressureDurationUs', 'downloadBackpressureDurationUs'].includes(key) && getField(record, 'connectOutcome') !== 'CONNECTED') return '未进入中继';
  return undefined;
};
function RecordFields({ record, type }: { record: AuditRecordV1; type: TelemetryType }) {
  const groups = [{ title: '身份与上下文', fields: commonFields }, ...(type === 'sessions' ? sessionGroups : [{ title: kinds.find(k => k.key === type)?.label, fields: otherFields[type] }])];
  const missingOriginalTarget = getField(record, 'failureStage') === 'AUTH' ? '认证阶段未记录目标'
    : record.inboundProtocol === 'TP_PROXY' ? '未记录原始目标'
    : record.inboundProtocol ? '此入站不单独记录原始目标' : '未记录原始目标';
  return <>
    {type === 'sessions' && credentialAttempts(record).length > 0 && <Card size="small" title="尝试登录账号" style={{ marginTop: 12 }}>
      <Table size="small" rowKey="authAttemptId" pagination={false} dataSource={credentialAttempts(record)} scroll={{ x: 650 }} columns={[
        { title: '尝试账号', dataIndex: 'attemptedUsername', render: (value: string | null) => value == null ? '账号未提交或无法解析' : value === '' ? '（空账号）' : value },
        { title: '认证结果', dataIndex: 'authOutcome', render: (value: unknown) => labelled(authOutcomes, value) },
        { title: '认证方式', dataIndex: 'authMethod', render: (value: unknown) => <span title={text(value)}>{labelled(authMethods, value)}</span> },
        { title: '认证时间', dataIndex: 'occurredAt', render: formatAuditTime },
      ]} />
      {getField(record, 'credentialAuthAttemptsTruncated') === true && <Typography.Paragraph>仅显示最近 20 次尝试，更多记录见关联日志。</Typography.Paragraph>}
    </Card>}
    {groups.map(group => <Card size="small" title={group.title} key={group.title} style={{ marginTop: 12 }}>
      <Descriptions size="small" bordered column={1} items={group.fields.map(([key, label]) => {
        const value = key === 'username' && type === 'credential-auth' ? getField(record, 'verifiedUsername') ?? record.username : getField(record, key);
        const rendered = key.endsWith('Bytes') ? formatAuditBytes(value as string | null) : key.endsWith('Us') ? (type === 'sessions' && value == null ? unavailableSessionDuration(record, key) ?? missing : formatAuditDuration(value as string | null))
          : key.endsWith('At') ? formatAuditTime(value as string | null) : key === 'trafficCategory' ? categories[value as keyof typeof categories] ?? '未知'
          : key === 'connectOutcome' ? labelled(connectOutcomes, value) : key === 'sessionOutcome' ? labelled(sessionOutcomes, value)
          : key === 'failureStage' ? labelled(failureStages, value) : key === 'errorCode' ? labelled(errorCodes, value)
          : key === 'outcome' ? labelled(outcomeLabels[type], value)
          : detailLabels[key] ? labelled(detailLabels[key], value)
          : key === 'ruleSetVersion' && value == null ? '未上报规则集版本'
          : type === 'sessions' && (key === 'originalDstIp' || key === 'originalDstPort') && value == null ? missingOriginalTarget
          : key === 'username' && value == null ? '未确认用户身份' : key === 'userId' && value == null ? '未关联用户 ID' : text(value);
        const fieldLabel = fieldHelp[key] ? <Space size={4}>{label}<Tooltip title={fieldHelp[key]} trigger={['hover', 'focus', 'click']}>
          <span tabIndex={0} aria-label={`${label}说明`}><QuestionCircleOutlined /></span>
        </Tooltip></Space> : label;
        return { key, label: fieldLabel, children: <span title={key.endsWith('Bytes') ? rawAuditBytes(value as string | null) : text(value)} style={{ overflowWrap: 'anywhere' }}>{rendered}</span> };
      })} />
    </Card>)}
    {type === 'credential-auth' && <Card size="small" title="来源分析" style={{ marginTop: 12 }}>
      <Descriptions bordered column={1} items={[
        ['sourceCountry', '国家'], ['sourceRegion', '地区'], ['sourceCity', '城市'], ['geoLookupStatus', '位置查询状态'], ['sourceNetworkScope', '来源网络范围'], ['geoDatabaseVersion', 'GeoIP 版本'], ['baselineStatus', '基线状态'], ['riskRuleVersion', '风险规则版本'], ['riskTags', '风险标签'],
      ].map(([key, label]) => {
        const value = (getField(record, 'analysis') as Record<string, unknown> | null)?.[key];
        return { key, label, children: <span title={text(value)}>{key === 'geoLookupStatus' ? labelled(geoLookupStatuses, value) : text(value)}</span> };
      })} />
    </Card>}
  </>;
}

export function AuditDetail({ selected, scope, onClose }: { selected: { type: TelemetryType; record: AuditRecordV1 }; scope: TelemetryQuery; onClose: () => void }) {
  const [record, setRecord] = useState<AuditRecordV1>();
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [child, setChild] = useState<{ type: TelemetryType; record: AuditRecordV1 }>();
  const [relatedType, setRelatedType] = useState<TelemetryType>('events');
  useEffect(() => {
    let active = true;
    setRecord(undefined); setError('');
    apiService.getTelemetryDetail(selected.type, selected.record.recordId, { workerId: scope.workerId, from: scope.from, to: scope.to })
      .then(row => { if (active) setRecord(row); }).catch(reason => { if (active) setError(errorText(reason)); });
    return () => { active = false; };
  }, [selected, scope, reload]);
  const relatedScope = React.useMemo(() => ({ workerId: scope.workerId, from: scope.from, to: scope.to, connectionId: selected.record.connectionId ?? undefined }), [scope, selected]);
  return <Drawer open title={`${kinds.find(k => k.key === selected.type)?.label}详情`} width={Math.min(1050, window.innerWidth)} onClose={onClose}>
    {error && <Alert type="error" message="详情查询失败" description={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {!record && !error && <Typography.Paragraph>正在加载…</Typography.Paragraph>}
    {record && <RecordFields record={record} type={selected.type} />}
    {record && selected.type === 'sessions' && record.connectionId && <Card title="关联日志" size="small" style={{ marginTop: 12 }}>
      <Tabs activeKey={relatedType} onChange={key => setRelatedType(key as TelemetryType)} items={kinds.filter(k => k.key !== 'sessions')} />
      <AuditList key={relatedType} type={relatedType} query={relatedScope} onDetail={(type, row) => setChild({ type, record: row })} />
    </Card>}
    {child && <AuditDetail selected={child} scope={relatedScope} onClose={() => setChild(undefined)} />}
  </Drawer>;
}

export function ObservabilityLogAudit() {
  const [form] = Form.useForm();
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [workerId, setWorkerId] = useState('');
  const [type, setType] = useState<TelemetryType>('sessions');
  const [query, setQuery] = useState<TelemetryQuery>();
  const [status, setStatus] = useState<TelemetryStatusV1>();
  const [error, setError] = useState('');
  const [statusError, setStatusError] = useState('');
  const [selected, setSelected] = useState<{ type: TelemetryType; record: AuditRecordV1 }>();
  const [reload, setReload] = useState(0);
  const dates = React.useMemo(() => ({ from: localInput(new Date(Date.now() - 86400000)), to: localInput(new Date()) }), []);
  useEffect(() => {
    let active = true;
    apiService.getWorkers().then(rows => {
      if (!active) return;
      setWorkers(rows); setWorkerId(previous => previous || (rows.find(w => w.enabled && w.status?.online) ?? rows.find(w => w.enabled) ?? rows[0])?.workerId || '');
    }).catch(reason => { if (active) setError(errorText(reason)); });
    return () => { active = false; };
  }, [reload]);
  useEffect(() => {
    let active = true;
    setStatus(undefined); setStatusError(''); setSelected(undefined);
    if (workerId) apiService.getTelemetryStatus(workerId).then(value => { if (active) setStatus(value); }).catch(reason => { if (active) setStatusError(errorText(reason)); });
    return () => { active = false; };
  }, [workerId, reload]);
  useEffect(() => {
    setSelected(undefined); setError('');
    form.resetFields();
    setQuery(workerId ? { workerId, from: new Date(dates.from).toISOString(), to: new Date(dates.to).toISOString() } : undefined);
  }, [workerId, type, form, dates]);
  const apply = (values: Record<string, string>) => {
    const from = new Date(values.from); const to = new Date(values.to);
    if (!workerId || Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || to <= from || to.getTime() - from.getTime() > 31 * 86400000) {
      setError('请选择 Worker 与有效时间，结束必须晚于开始，窗口最多 31 天。'); return;
    }
    setError(''); setSelected(undefined);
    const { from: ignoredFrom, to: ignoredTo, ...filters } = values;
    setQuery({ workerId, from: from.toISOString(), to: to.toISOString(), ...filters });
  };
  return <div className="observability-audit">
    <Typography.Title level={2}>日志审计</Typography.Title>
    <Card size="small" title="筛选条件">
      <Space wrap style={{ marginBottom: 12 }}><span>Worker</span><Select aria-label="Worker" style={{ minWidth: 260 }} value={workerId || undefined} placeholder="选择 Worker" onChange={setWorkerId} options={workers.map(w => ({ value: w.workerId, label: `${w.displayName}（${w.workerId}）` }))} /><Button onClick={() => setReload(v => v + 1)}>刷新</Button></Space>
      <Tabs activeKey={type} onChange={key => setType(key as TelemetryType)} items={kinds} />
      <Form form={form} preserve={false} layout="inline" initialValues={dates} onFinish={apply} className="audit-filters">
        <Form.Item name="from" label={`开始（${timezone}）`}><Input aria-label="开始时间" type="datetime-local" step="1" /></Form.Item>
        <Form.Item name="to" label="结束（不含）"><Input aria-label="结束时间" type="datetime-local" step="1" /></Form.Item>
        <Form.Item name="username" label="用户"><Input aria-label="用户" placeholder="用户名包含；连接／认证含尝试账号" maxLength={128} /></Form.Item>
        <Form.Item name="clientIp" label="来源 IP"><Input aria-label="来源 IP" placeholder="精确匹配" /></Form.Item>
        <Form.Item name="protocol" label="协议"><Select aria-label="协议" allowClear showSearch optionFilterProp="label" placeholder="选择入站协议" style={{ width: 200 }} options={inboundProtocols[type].map(value => ({ value, label: protocolLabels[value] }))} /></Form.Item>
        <Form.Item name="outcome" label="结果"><Select aria-label="结果" allowClear showSearch optionFilterProp="label" placeholder={type === 'sessions' ? '结束结果' : '选择结果'} style={{ width: 170 }} options={optionsFor(outcomeLabels[type])} /></Form.Item>
        {(type === 'sessions' || type === 'dns') && <Form.Item name="target" label={type === 'dns' ? '域名' : '目标'}><Input aria-label="目标／域名" placeholder="包含（原始／逻辑／有效目标）" /></Form.Item>}
        {type === 'sessions' && <><Form.Item name="connectOutcome" label="建连结果"><Select aria-label="建连结果" allowClear showSearch optionFilterProp="label" placeholder="建连结果" style={{ width: 160 }} options={optionsFor(connectOutcomes)} /></Form.Item><Form.Item name="route" label="路由"><Input placeholder="名称包含或完整 ID" /></Form.Item><Form.Item name="trafficCategory" label="流量分类"><Select aria-label="流量分类" allowClear showSearch optionFilterProp="label" style={{ width: 220 }} options={optionsFor(categories)} /></Form.Item></>}
        {type === 'dns' && <Form.Item name="qtype" label="查询类型"><Select aria-label="查询类型" allowClear showSearch optionFilterProp="label" placeholder="选择查询类型" style={{ width: 180 }} options={optionsFor(dnsQueryTypes)} /></Form.Item>}
        {type === 'events' && <Form.Item name="eventType" label="事件类型"><Select aria-label="事件类型" allowClear showSearch optionFilterProp="label" placeholder="选择事件类型" style={{ width: 170 }} options={optionsFor(eventTypes)} /></Form.Item>}
        {type === 'credential-auth' && <Form.Item name="authMethod" label="认证方式"><Select aria-label="认证方式" allowClear showSearch optionFilterProp="label" placeholder="选择认证方式" style={{ width: 210 }} options={optionsFor(authMethods)} /></Form.Item>}
        <Form.Item><Button htmlType="submit" type="primary">查询</Button></Form.Item>
        <Form.Item><Button onClick={() => { form.resetFields(); apply(form.getFieldsValue()); }}>重置</Button></Form.Item>
      </Form>
    </Card>
    {error && <Alert type="error" showIcon message={error} />}
    {statusError && <Alert type="error" showIcon message="查询服务检查失败" description={statusError} />}
    {status && !status.queryEnabled && <Alert type="warning" message="管理端新版查询未开启" />}
    {query && status?.queryEnabled && <Card size="small" title={kinds.find(k => k.key === type)?.label}>
      <AuditList key={`${type}:${JSON.stringify(query)}`} type={type} query={query} onDetail={(detailType, record) => setSelected({ type: detailType, record })} />
    </Card>}
    {!workerId && !error && <Alert type="info" message={workers.length ? '请选择 Worker' : '正在加载 Worker；没有可用节点时请先到代理节点配置'} />}
    {selected && query && <AuditDetail key={`${selected.type}:${selected.record.recordId}`} selected={selected} scope={query} onClose={() => setSelected(undefined)} />}
  </div>;
}
