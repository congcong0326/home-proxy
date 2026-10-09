import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Collapse, DatePicker, Empty, Input, Row, Select, Space, Table, Tag, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import dayjs from 'dayjs';
import apiService from '../services/api';
import { DistributionData, DistributionDimension, DistributionItem, DistributionMetric, DistributionQuery } from '../types/distribution';
import { ConnectOutcome, TrafficCategory } from '../types/observability';
import { ProxyWorkerDTO } from '../types/worker';
import './ObservabilityDistribution.css';

const dimensions: { value: DistributionDimension; label: string }[] = [
  { value: 'users', label: '用户' }, { value: 'targets', label: '目标主机' },
  { value: 'user_targets', label: '用户－目标' }, { value: 'routes', label: '路由规则' },
  { value: 'client_ips', label: '来源 IP' }, { value: 'categories', label: '流量分类' },
];
const metrics: { value: DistributionMetric; label: string }[] = [
  { value: 'connections', label: '连接数' }, { value: 'bytes', label: '总流量' },
  { value: 'upload', label: '上行流量' }, { value: 'download', label: '下行流量' },
];
const categories: Record<TrafficCategory, string> = {
  EXTERNAL: '外网', INTERNAL_REWRITE: '内网 · 目标重写', OTHER_INTERNAL: '其他内网', UNKNOWN: '未分类',
};
const outcomes: Record<ConnectOutcome, string> = {
  CONNECTED: '已建立', FAILED: '失败', BLOCKED: '阻断', CANCELLED: '取消', NOT_ATTEMPTED: '未尝试',
};
const digits = (value: string) => value.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
const rawBytes = (value: string) => `${digits(value)} B`;
const bytes = (value: string) => {
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
  const amount = BigInt(value);
  let divisor = BigInt(1), unit = 0;
  while (unit < units.length - 1 && amount >= divisor * BigInt(1024)) { divisor *= BigInt(1024); unit++; }
  if (!unit) return rawBytes(value);
  const rounded = (amount * BigInt(100) + divisor / BigInt(2)) / divisor;
  const fraction = (rounded % BigInt(100)).toString().padStart(2, '0').replace(/0+$/, '');
  return `${digits((rounded / BigInt(100)).toString())}${fraction ? `.${fraction}` : ''} ${units[unit]}`;
};
const byteCell = (value: string) => <span title={rawBytes(value)}>{bytes(value)}</span>;
// Integer arithmetic keeps counts and percentages accurate beyond Number.MAX_SAFE_INTEGER.
export function distributionPercent(value: string, total: string) {
  if (total === '0') return '0.00';
  const rounded = (BigInt(value) * BigInt(10000) + BigInt(total) / BigInt(2)) / BigInt(total);
  return `${rounded / BigInt(100)}.${(rounded % BigInt(100)).toString().padStart(2, '0')}`;
}
function label(item: DistributionItem, dimension: DistributionDimension) {
  const [id, name, target] = item.keys;
  const user = `${name || (id ? `用户 #${id}` : '未识别用户')}${name && id ? ` (#${id})` : ''}`;
  switch (dimension) {
    case 'users': return user;
    case 'user_targets': return `${user} → ${target || '未知目标'}`;
    case 'routes': return `${name || (id ? `规则 #${id}` : '未选定路由')}${name && id ? ` (#${id})` : ''}`;
    case 'categories': return categories[id as TrafficCategory] || '未分类';
    case 'targets': return id || '未知目标';
    default: return id || '未知来源';
  }
}
const dateInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10);

function FilterField({ title, children, span = 6 }: { title: string; children: React.ReactNode; span?: number }) {
  return <Col xs={24} sm={12} md={8} lg={span}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text strong>{title}</Typography.Text>
      {children}
    </Space>
  </Col>;
}

export function ObservabilityDistribution() {
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [workerId, setWorkerId] = useState('');
  const [workerError, setWorkerError] = useState('');
  const [dimension, setDimension] = useState<DistributionDimension>('users');
  const [metric, setMetric] = useState<DistributionMetric>('connections');
  const [category, setCategory] = useState<TrafficCategory | 'ALL'>('EXTERNAL');
  const [outcome, setOutcome] = useState<ConnectOutcome | 'ALL'>('ALL');
  const [limit, setLimit] = useState(10);
  const [from, setFrom] = useState(() => dateInput(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
  const [to, setTo] = useState(() => dateInput(new Date()));
  const [username, setUsername] = useState('');
  const [clientIp, setClientIp] = useState('');
  const [source, setSource] = useState({ username: '', clientIp: '' });
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<DistributionData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    apiService.getWorkers().then(items => {
      if (!active) return;
      setWorkers(items);
      setWorkerId(items.find(w => w.enabled)?.workerId || items[0]?.workerId || '');
      if (!items.length) setWorkerError('尚未配置代理节点');
    }).catch(e => { if (active) setWorkerError(e.message || '加载代理节点失败'); });
    return () => { active = false; };
  }, []);

  const query = useMemo<DistributionQuery | null>(() => {
    if (!from || !to) return null;
    const start = new Date(`${from}T00:00:00`);
    const end = new Date(`${to}T00:00:00`);
    end.setDate(end.getDate() + 1);
    if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || end <= start || end.getTime() - start.getTime() > 93 * 86400000) return null;
    return { workerId, from: start.toISOString(), to: end.toISOString(), dimension, metric, limit,
      trafficCategory: category, connectOutcome: outcome, ...source };
  }, [workerId, from, to, dimension, metric, limit, category, outcome, source]);

  useEffect(() => {
    let active = true;
    setData(null);
    if (!workerId || !query) { setLoading(false); return; }
    setLoading(true); setError('');
    apiService.getObservabilityDistribution(query).then(result => { if (active) setData(result); })
      .catch(e => { if (active) setError(e.message || '访问分布查询失败'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [workerId, query, revision]);

  const chart = useMemo(() => {
    if (!data) return {};
    const entries = data.items.map((item, index) => ({ name: label(item, data.dimension), value: Number(item.value), id: index }));
    if (data.otherValue !== '0') entries.push({ name: '其余项目', value: Number(data.otherValue), id: entries.length });
    return {
      tooltip: { trigger: 'item', renderMode: 'richText', formatter: (point: { dataIndex: number; name: string }) => {
        const value = data.items[point.dataIndex]?.value || data.otherValue;
        return `${point.name}\n${data.metric === 'connections' ? digits(value) : rawBytes(value)}\n占全部 ${distributionPercent(value, data.totalValue)}%`;
      } },
      legend: { type: 'scroll', bottom: 0 },
      series: [{ type: 'pie', radius: ['40%', '70%'], avoidLabelOverlap: true,
        label: { show: true, formatter: (point: { dataIndex: number; name: string }) =>
          `${point.name}: ${distributionPercent(data.items[point.dataIndex]?.value || data.otherValue, data.totalValue)}%` },
        emphasis: { label: { show: true, fontSize: 16, fontWeight: 'bold' } }, data: entries }],
    };
  }, [data]);

  return <Space direction="vertical" size="large" style={{ width: '100%' }} className="observability-distribution">
    <Typography.Title level={3}>访问分布</Typography.Title>
    <Card variant="outlined">
      <form onSubmit={e => { e.preventDefault(); setSource({ username: username.trim(), clientIp: clientIp.trim() }); setRevision(v => v + 1); }}>
        <Row gutter={[16, 16]}>
          <FilterField title="维度"><Select aria-label="维度" options={dimensions} value={dimension} onChange={setDimension} style={{ width: '100%' }} /></FilterField>
          <FilterField title="指标"><Select aria-label="指标" options={metrics} value={metric} onChange={setMetric} style={{ width: '100%' }} /></FilterField>
          <Col xs={24} sm={24} md={8} lg={8}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Typography.Text strong>日期范围</Typography.Text>
              <DatePicker.RangePicker value={from && to ? [dayjs(from), dayjs(to)] : null}
                onChange={dates => { setFrom(dates?.[0]?.format('YYYY-MM-DD') || ''); setTo(dates?.[1]?.format('YYYY-MM-DD') || ''); }}
                placeholder={['开始日期', '结束日期']} format="YYYY-MM-DD" style={{ width: '100%' }} />
            </Space>
          </Col>
          <FilterField title="Top 数量" span={4}><Select aria-label="Top 数量" options={[5, 10, 20, 50, 100].map(n => ({ value: n, label: `Top ${n}` }))} value={limit} onChange={setLimit} style={{ width: '100%' }} /></FilterField>
          <FilterField title="代理节点"><Select aria-label="代理节点" value={workerId || undefined} placeholder="等待节点"
            options={workers.map(w => ({ value: w.workerId, label: `${w.displayName || w.workerId}${w.enabled ? '' : '（已停用）'}` }))}
            onChange={setWorkerId} style={{ width: '100%' }} /></FilterField>
          <FilterField title="流量分类"><Select aria-label="流量分类" value={category} onChange={setCategory} style={{ width: '100%' }}
            options={[{ value: 'ALL', label: '全部分类' }, ...Object.entries(categories).map(([value, label]) => ({ value, label }))]} /></FilterField>
          <FilterField title="建连结果"><Select aria-label="建连结果" value={outcome} onChange={setOutcome} style={{ width: '100%' }}
            options={[{ value: 'ALL', label: '全部结果' }, ...Object.entries(outcomes).map(([value, label]) => ({ value, label }))]} /></FilterField>
          <Col xs={24} sm={12} md={8} lg={6} className="distribution-query">
            <Button type="primary" htmlType="submit" loading={loading} disabled={!query || !workerId}>查询 / 刷新</Button>
          </Col>
        </Row>
        <Collapse ghost className="distribution-more-filters" items={[{ key: 'source', label: '更多筛选', children: <Row gutter={[16, 16]}>
          <FilterField title="用户" span={12}><Input aria-label="用户" value={username} onChange={e => setUsername(e.target.value)} placeholder="已识别用户名（精确匹配）" maxLength={256} /></FilterField>
          <FilterField title="来源 IP" span={12}><Input aria-label="来源 IP" value={clientIp} onChange={e => setClientIp(e.target.value)} placeholder="精确匹配" maxLength={256} /></FilterField>
        </Row> }]} />
      </form>
    </Card>
    {workerError && <Alert type="error" showIcon message={workerError} />}
    {!query && <Alert type="warning" showIcon message="请选择有效日期，范围最多 93 天" />}
    {error && <Alert type="error" showIcon message={error} />}
    <Card variant="outlined" title="TopN 结果">
      {data && <Typography.Paragraph type="secondary" className="distribution-summary">
        <Space wrap size="middle">
          <span>全部连接：{digits(data.totals.connections)}</span><span>上行：{byteCell(data.totals.upload)}</span>
          <span>下行：{byteCell(data.totals.download)}</span><span>总流量：{byteCell(data.totals.bytes)}</span>
        </Space>
      </Typography.Paragraph>}
      <Table<DistributionItem> rowKey={row => JSON.stringify(row.keys)} loading={loading} dataSource={data?.items || []} pagination={false} scroll={{ x: 540 }}
        locale={{ emptyText: error || workerError ? '查询不可用' : '所选范围暂无记录' }}
        columns={[
          { title: '排名', width: 80, render: (_, __, index) => index + 1 },
          { title: dimensions.find(d => d.value === dimension)?.label, render: (_, item) => <Tag className="distribution-key">{label(item, dimension)}</Tag> },
          { title: metrics.find(m => m.value === metric)?.label, render: (_, item) => metric === 'connections' ? digits(item.value) : byteCell(item.value) },
          { title: '占全部', render: (_, item) => `${distributionPercent(item.value, data?.totalValue || '0')}%` },
        ]} />
    </Card>
    <Card variant="outlined" title="TopN 饼图">{data && data.totalValue !== '0' ? <ReactECharts option={chart} notMerge opts={{ renderer: 'svg' }} style={{ height: 360 }} /> : <Empty description={loading ? '正在查询…' : error || workerError ? '查询不可用' : '暂无可展示的占比'} />}</Card>
  </Space>;
}
