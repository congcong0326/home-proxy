import React, { useCallback, useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import * as echarts from 'echarts';
import { ReloadOutlined, ArrowUpOutlined, ArrowDownOutlined, GlobalOutlined, ApartmentOutlined, ApiOutlined, SafetyCertificateOutlined, CloudOutlined } from '@ant-design/icons';
import apiService from '../services/api';
import { CountOverview, OverviewData, OverviewPoint, OverviewQuery, ResultFilter } from '../types/overview';
import { ProxyWorkerDTO } from '../types/worker';
import { TrafficCategory } from '../types/observability';
import './TrafficOverview.css';

const categories: { value: TrafficCategory | 'ALL'; label: string }[] = [
  { value: 'EXTERNAL', label: '外网' }, { value: 'INTERNAL_REWRITE', label: '内网' },
  { value: 'ALL', label: '全部流量' },
];
const themeColor = (name: string, fallback: string) =>
  typeof document === 'undefined' ? fallback : getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
const chartColors = {
  success: themeColor('--retro-screen-text', '#39ff88'),
  info: themeColor('--retro-chart-info', '#7edce2'),
  failure: themeColor('--retro-chart-danger', '#ff7474'),
  warning: themeColor('--retro-chart-warning', '#ffc857'),
  secondary: themeColor('--retro-chart-secondary', '#b9a4f6'),
  neutral: themeColor('--retro-bg', '#bfc4bf'),
  muted: themeColor('--retro-panel-dark', '#8b908b'),
};
const results = [
  { value: 'SUCCESS', label: '成功', color: chartColors.success }, { value: 'FAILURE', label: '失败', color: chartColors.failure },
  { value: 'BLOCKED', label: '阻断', color: chartColors.warning }, { value: 'CHALLENGE', label: '挑战', color: chartColors.secondary },
  { value: 'CANCELLED', label: '取消', color: chartColors.neutral }, { value: 'UNKNOWN', label: '未知', color: chartColors.muted },
];
const number = (value?: string) => Number(value || 0);
const bytes = (value?: string | number) => {
  const n = Number(value || 0);
  if (!n) return '0 B';
  const i = Math.min(4, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / Math.pow(1024, i)).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} ${['B', 'KB', 'MB', 'GB', 'TB'][i]}`;
};
const count = (value?: string) => (value || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
const localInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
const clock = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
const GROUP = 'traffic-overview-v1';

function ResultSelect({ label, value, onChange, kinds = ['SUCCESS', 'FAILURE', 'BLOCKED', 'CANCELLED', 'UNKNOWN'] }: {
  label: string; value: ResultFilter; onChange: (value: ResultFilter) => void; kinds?: string[];
}) {
  return <select aria-label={label} value={value} onChange={e => onChange(e.target.value as ResultFilter)}>
    <option value="ALL">全部结果</option>
    {results.filter(r => kinds.includes(r.value)).map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
  </select>;
}

function chartOption(points: OverviewPoint[], series: { key: string; label: string; color: string; type: 'line' | 'bar'; stack?: string; step?: boolean; area?: boolean }[], isBytes = false) {
  const font = themeColor('--retro-font', '"Courier New", Consolas, monospace');
  const screenText = themeColor('--retro-screen-muted', '#d9fff4');
  const border = themeColor('--retro-border', '#111813');
  const muted = themeColor('--retro-muted', '#4b554d');
  return {
    animationDuration: 300, color: series.map(s => s.color), textStyle: { fontFamily: font },
    grid: { left: 10, right: 16, top: 24, bottom: 42, containLabel: true },
    legend: { bottom: 0, icon: 'rect', itemWidth: 10, itemHeight: 7, textStyle: { color: screenText, fontSize: 11, fontFamily: font } },
    tooltip: { trigger: 'axis', confine: true, backgroundColor: themeColor('--retro-panel', '#d6d8d3'), borderColor: border, borderWidth: 2, padding: 12,
      extraCssText: 'border-radius:2px;box-shadow:inset 1px 1px 0 #fff,inset -1px -1px 0 #8b908b;',
      textStyle: { color: themeColor('--retro-text', '#101812'), fontSize: 12, fontFamily: font },
      axisPointer: { type: 'line', lineStyle: { color: screenText, type: 'dashed' } },
      formatter: (items: any[]) => {
        if (!items?.length) return '';
        const row = points[items[0].dataIndex];
        return `${new Date(row.ts).toLocaleString('zh-CN')}<br/>` + items.map(item => {
          const original = row[series[item.seriesIndex].key] || '0';
          const marker = `<span style="display:inline-block;width:8px;height:8px;margin-right:6px;border:1px solid ${border};background:${series[item.seriesIndex].color}"></span>`;
          return `${marker}${item.seriesName} <b>${isBytes ? bytes(original) : count(original)}</b>${isBytes ? ` <span style="color:${muted}">(${original} B)</span>` : ''}`;
        }).join('<br/>');
      },
    },
    xAxis: { type: 'time', boundaryGap: false, axisLine: { show: true, lineStyle: { color: muted } }, axisTick: { show: false },
      axisLabel: { color: screenText, fontSize: 10, fontFamily: font, hideOverlap: true }, splitLine: { show: false } },
    yAxis: { type: 'value', min: 0, minInterval: isBytes ? undefined : 1, axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: screenText, fontSize: 10, fontFamily: font, formatter: isBytes ? (v: number) => bytes(v) : undefined },
      splitLine: { lineStyle: { color: muted, opacity: 0.6, type: 'dashed' } } },
    series: series.map(s => ({ name: s.label, type: s.type, stack: s.stack, step: s.step ? 'end' : undefined,
      smooth: false, showSymbol: false, symbol: 'rect', symbolSize: 5, barMaxWidth: 16,
      lineStyle: { width: 2 }, areaStyle: s.area ? { opacity: 0.14 } : undefined,
      itemStyle: { color: s.color, borderRadius: 0 },
      emphasis: { focus: 'series' }, data: points.map(p => [new Date(p.ts).getTime(), number(p[s.key])]),
    })),
  };
}
function Chart({ option, empty, loading }: { option: any; empty: boolean; loading: boolean }) {
  return <div className="ov-chart">
    <ReactECharts option={option} notMerge lazyUpdate opts={{ renderer: 'svg' }} style={{ height: 230 }}
      onChartReady={chart => { chart.group = GROUP; echarts.connect(GROUP); }} />
    {empty && !loading && <div className="ov-chart-empty">所选范围暂无记录</div>}
    {loading && <div className="ov-chart-loading">正在更新…</div>}
  </div>;
}

function countChart(data?: CountOverview) {
  return chartOption(data?.series || [], results.filter(r => data?.totals[r.value] !== undefined)
    .map(r => ({ key: r.value, label: r.label, color: r.color, type: 'bar', stack: 'count' })));
}

export function TrafficOverview() {
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [workerId, setWorkerId] = useState('default');
  const [workersReady, setWorkersReady] = useState(false);
  const [category, setCategory] = useState<TrafficCategory | 'ALL'>('EXTERNAL');
  const [preset, setPreset] = useState('24');
  const [from, setFrom] = useState(localInput(new Date(Date.now() - 86400000)));
  const [to, setTo] = useState(localInput(new Date()));
  const [username, setUsername] = useState('');
  const [clientIp, setClientIp] = useState('');
  const [source, setSource] = useState({ username: '', clientIp: '' });
  const [connectionResult, setConnectionResult] = useState<ResultFilter>('ALL');
  const [dnsResult, setDnsResult] = useState<ResultFilter>('ALL');
  const [authResult, setAuthResult] = useState<ResultFilter>('ALL');
  const [dnsOrigin, setDnsOrigin] = useState<'CLIENT' | 'PROXY_RESOLVE' | 'ALL'>('CLIENT');
  const [connectionView, setConnectionView] = useState('concurrency');
  const [data, setData] = useState<OverviewData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai';

  useEffect(() => {
    let active = true;
    apiService.getWorkers().then(items => {
      if (!active) return;
      setWorkers(items);
      setWorkerId(items.find(w => w.workerId === 'default' && w.enabled)?.workerId || items.find(w => w.enabled)?.workerId || 'default');
    }).catch(() => { if (active) setWorkers([]); }).finally(() => { if (active) setWorkersReady(true); });
    return () => { active = false; echarts.disconnect(GROUP); };
  }, []);

  const query = useMemo<OverviewQuery>(() => {
    const end = preset === 'custom' ? new Date(to).toISOString() : new Date().toISOString();
    const start = preset === 'custom' ? new Date(from).toISOString() : new Date(new Date(end).getTime() - Number(preset) * 3600000).toISOString();
    return { workerId, from: start, to: end, timeZone, ...source, trafficCategory: category,
      trafficResult: 'ALL', connectionResult, dnsResult, authResult, dnsOrigin };
    // Refresh advances relative ranges even if filters remain unchanged.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workerId, preset, from, to, source, category, connectionResult, dnsResult, authResult, dnsOrigin, timeZone, revision]);

  useEffect(() => {
    if (!workersReady) return;
    let active = true;
    setLoading(true); setError(''); setData(null);
    apiService.getObservabilityOverview(query).then(result => {
      if (active) setData(result);
    }).catch(e => { if (active) setError(e.message || '统计查询失败'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [query, workersReady]);

  useEffect(() => {
    if (!workersReady) return;
    let active = true;
    let sequence = 0;
    const timer = window.setInterval(() => {
      const id = ++sequence;
      apiService.getObservabilityLive(query).then(live => {
        if (active && id === sequence) setData(previous => previous ? { ...previous, live } : previous);
      }).catch(() => {
        if (active && id === sequence) setData(previous => previous ? { ...previous, live: { available: false, reason: 'STALE' } } : previous);
      });
    }, 5000);
    return () => { window.clearInterval(timer); active = false; };
  }, [query, workersReady]);

  const refresh = useCallback(() => { setSource({ username: username.trim(), clientIp: clientIp.trim() }); setRevision(v => v + 1); }, [username, clientIp]);
  const selectedLabel = categories.find(c => c.value === category)?.label || '外网';
  const empty = !data;
  const trafficOption = useMemo(() => chartOption(data?.traffic.series || [], [
    { key: 'download', label: '下载', color: chartColors.success, type: 'line', area: true },
    { key: 'upload', label: '上传', color: chartColors.info, type: 'line', area: true },
  ], true), [data?.traffic.series]);
  const concurrencyOption = useMemo(() => chartOption(data?.concurrency.series || [], [
    { key: 'peak', label: '桶内峰值', color: chartColors.info, type: 'line', step: true, area: true },
    { key: 'average', label: '桶内平均', color: chartColors.warning, type: 'line', step: true },
  ]), [data?.concurrency.series]);
  const connectionsOption = useMemo(() => countChart(data?.connections), [data?.connections]);
  const dnsOption = useMemo(() => countChart(data?.dns), [data?.dns]);
  const authOption = useMemo(() => countChart(data?.auth), [data?.auth]);
  const bucketLabel = data ? (data.bucketSeconds < 3600 ? `${data.bucketSeconds / 60} 分钟` : `${data.bucketSeconds / 3600} 小时`) : '自动';
  const totals = (kind: 'connections' | 'dns' | 'auth') => <div className="ov-result-totals">
    {results.filter(r => data?.[kind].totals[r.value] !== undefined).map(r => <span key={r.value}><i style={{ background: r.color }} />{r.label} <b>{count(data?.[kind].totals[r.value])}</b></span>)}
  </div>;

  return <div className="observability-overview">
    <header className="ov-heading"><div><div className="ov-eyebrow">GATEWAY OBSERVABILITY <span>V1</span></div><h2>流量概览</h2></div>
    </header>

    <div className="ov-filter-bar">
      <label>代理节点<select aria-label="代理节点" value={workerId} onChange={e => setWorkerId(e.target.value)}>
        {!workers.some(w => w.workerId === workerId) && <option value={workerId}>{workerId}</option>}
        {workers.map(w => <option key={w.workerId} value={w.workerId}>{w.displayName || w.workerId}</option>)}
      </select></label>
      <label>时间范围<select aria-label="时间范围" value={preset} onChange={e => setPreset(e.target.value)}><option value="1">最近 1 小时</option><option value="3">最近 3 小时</option><option value="24">最近 24 小时</option><option value="168">最近 7 天</option><option value="custom">自定义</option></select></label>
      <label>来源 IP<input aria-label="来源 IP" placeholder="全部来源" value={clientIp} onChange={e => setClientIp(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') refresh(); }} /></label>
      <label>用户／账号<input aria-label="用户／账号" placeholder="全部用户" value={username} onChange={e => setUsername(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') refresh(); }} /></label>
      <button className="ov-primary-button" onClick={refresh} disabled={loading}><ReloadOutlined spin={loading} /> {loading ? '查询中' : '查询 / 刷新'}</button>
      {preset === 'custom' && <div className="ov-custom-range"><label>开始时间<input aria-label="开始时间" type="datetime-local" value={from} onChange={e => { if (e.target.value) setFrom(e.target.value); }} /></label><span>至</span><label>结束时间<input aria-label="结束时间" type="datetime-local" value={to} onChange={e => { if (e.target.value) setTo(e.target.value); }} /></label></div>}
    </div>
    {error && <div role="alert" className="ov-error">{error}<button onClick={refresh}>重试</button></div>}

    <div className="ov-scope-row"><div className="ov-category-tabs" role="group" aria-label="流量类型">
      {categories.map(c => <button key={c.value} aria-label={c.label} aria-pressed={category === c.value} className={category === c.value ? 'active' : ''} onClick={() => setCategory(c.value)}>{c.value === 'EXTERNAL' ? <GlobalOutlined /> : c.value === 'INTERNAL_REWRITE' ? <ApartmentOutlined /> : null}{c.label}</button>)}
    </div><span className="ov-scope-hint">仅筛选流量与连接</span></div>

    <div className="ov-summary-grid">
      <div className="ov-summary ov-blue"><div className="ov-summary-label"><GlobalOutlined /> {selectedLabel}流量 <span>所选范围</span></div><strong>{empty ? '—' : bytes(data?.traffic.total)}</strong><div className="ov-summary-footer"><span><ArrowUpOutlined /> {empty ? '—' : bytes(data?.traffic.upload)}</span><span><ArrowDownOutlined /> {empty ? '—' : bytes(data?.traffic.download)}</span></div></div>
      <div className="ov-summary ov-purple"><div className="ov-summary-label"><ApiOutlined /> 当前并发 <span>每 5 秒更新</span></div><strong>{data?.live.available ? count(data.live.count) : '—'}<small> 条</small></strong><div className="ov-summary-footer">{data?.live.available ? `采样于 ${clock(data.live.sampledAt!)}` : data?.live.reason === 'STALE' ? '节点离线或采样已过期' : data?.live.reason === 'GROUPS_TRUNCATED' ? '来源分组过多，当前筛选不可用' : '等待新版节点上报已建立连接'}</div></div>
      <div className="ov-summary ov-teal"><div className="ov-summary-label"><CloudOutlined /> DNS 查询 <span>{dnsOrigin === 'CLIENT' ? '客户端' : dnsOrigin === 'PROXY_RESOLVE' ? '内部解析' : '全部查询'}</span></div><strong>{empty ? '—' : count(data?.dns.total)}<small> 次</small></strong><div className="ov-summary-footer">成功 {empty ? '—' : count(data?.dns.totals.SUCCESS)}<span>失败 {empty ? '—' : count(data?.dns.totals.FAILURE)}</span></div></div>
      <div className="ov-summary ov-amber"><div className="ov-summary-label"><SafetyCertificateOutlined /> 认证尝试 <span>凭据认证</span></div><strong>{empty ? '—' : count(data?.auth.total)}<small> 次</small></strong><div className="ov-summary-footer">成功 {empty ? '—' : count(data?.auth.totals.SUCCESS)}<span>失败 {empty ? '—' : count(data?.auth.totals.FAILURE)}</span></div></div>
    </div>

    <div className="ov-charts-grid">
      <section className="ov-panel"><div className="ov-panel-header"><div><h3><GlobalOutlined />流量趋势</h3><p>{selectedLabel} · 已结束连接载荷</p></div></div>
        <div className="ov-panel-metrics"><span>今日 <b>{empty ? '—' : bytes(data?.traffic.daily)}</b></span><span>本月 <b>{empty ? '—' : bytes(data?.traffic.monthly)}</b></span><span className="ov-right">正常／异常结束筛选</span></div>
        <Chart option={trafficOption} empty={!!data && data.traffic.total === '0'} loading={loading} />
      </section>
      <section className="ov-panel"><div className="ov-panel-header"><div><h3><ApiOutlined />连接趋势</h3><p>{selectedLabel} · {connectionView === 'concurrency' ? '已落库连接区间重建' : '连接结束时归属'}</p></div><div className="ov-panel-controls"><select aria-label="连接指标" value={connectionView} onChange={e => setConnectionView(e.target.value)}><option value="concurrency">历史并发</option><option value="attempts">建连次数</option></select>{connectionView === 'attempts' && <ResultSelect label="建连结果" value={connectionResult} onChange={setConnectionResult} />}</div></div>
        {connectionView === 'concurrency' ? <div className="ov-panel-metrics"><span>区间峰值 <b>{empty ? '—' : count(data?.concurrency.peak)}</b></span><span>区间平均 <b>{empty ? '—' : data?.concurrency.average}</b></span></div> : <div className="ov-panel-metrics"><span>建连记录 <b>{empty ? '—' : count(data?.connections.total)}</b></span>{totals('connections')}</div>}
        <Chart option={connectionView === 'concurrency' ? concurrencyOption : connectionsOption} empty={!!data && (connectionView === 'concurrency' ? data.concurrency.peak === '0' : data.connections.total === '0')} loading={loading} />
      </section>
      <section className="ov-panel"><div className="ov-panel-header"><div><h3><CloudOutlined />DNS 查询趋势</h3><p>每次逻辑查询计一次</p></div><div className="ov-panel-controls"><select aria-label="DNS 查询来源" value={dnsOrigin} onChange={e => setDnsOrigin(e.target.value as typeof dnsOrigin)}><option value="CLIENT">客户端查询</option><option value="PROXY_RESOLVE">内部解析</option><option value="ALL">全部查询</option></select><ResultSelect label="DNS 结果" value={dnsResult} onChange={setDnsResult} /></div></div>
        <div className="ov-panel-metrics">{totals('dns')}</div><Chart option={dnsOption} empty={!!data && data.dns.total === '0'} loading={loading} />
        <div className="ov-panel-note">成功含正常否定应答（NXDOMAIN）；阻断与取消单列。</div>
      </section>
      <section className="ov-panel"><div className="ov-panel-header"><div><h3><SafetyCertificateOutlined />认证尝试趋势</h3><p>成功与失败均保留实际来源</p></div><ResultSelect label="认证结果" value={authResult} onChange={setAuthResult} kinds={['SUCCESS', 'FAILURE', 'CHALLENGE', 'CANCELLED', 'UNKNOWN']} /></div>
        <div className="ov-panel-metrics">{totals('auth')}</div><Chart option={authOption} empty={!!data && data.auth.total === '0'} loading={loading} />
        <div className="ov-panel-note">失败包含凭据错误、拒绝与异常；挑战单列。</div>
      </section>
    </div>

    <section className="ov-panel ov-sources"><div className="ov-panel-header"><div><h3>流量来源排行</h3><p>{selectedLabel} · 所选时间范围 · 前 20 个用户与来源组合</p></div><span className="ov-table-badge">按总流量排序</span></div>
      <div className="ov-table-scroll"><table><thead><tr><th>用户 / 来源 IP</th><th>上传</th><th>下载</th><th>总流量</th><th>连接记录</th></tr></thead><tbody>
        {data?.traffic.sources.map((row, index) => <tr key={`${row.userId}-${row.clientIp}-${index}`}><td><span className="ov-rank">{String(index + 1).padStart(2, '0')}</span><span className="ov-source-name">{row.username || '未识别用户'}<small>{row.clientIp || '来源未知'}</small></span></td><td>{bytes(row.upload)}</td><td>{bytes(row.download)}</td><td className="ov-source-total">{bytes((BigInt(row.upload) + BigInt(row.download)).toString())}</td><td>{count(row.connections)}</td></tr>)}
        {!data?.traffic.sources.length && <tr><td colSpan={5} className="ov-table-empty">{loading ? '正在读取流量来源…' : error ? '数据暂不可用' : '所选范围暂无流量来源'}</td></tr>}
      </tbody></table></div>
      {data && <div className="ov-category-summary">{categories.filter(c => c.value !== 'ALL').map(c => <span key={c.value}>{c.label}<b>{bytes(data.traffic.categories.find(row => row.category === c.value)?.bytes)}</b></span>)}</div>}
    </section>
    <footer className="ov-footnote"><span>粒度 {bucketLabel} · 时区 {timeZone}</span><span>历史并发会在长连接结束后补齐；当前并发包含空闲连接，不跟随最终结束结果筛选。</span><span>新日志最早记录（UTC）：{data?.firstSessionAt || '暂无'}。本月仅统计已有新日志，未混入旧数据。</span></footer>
  </div>;
}
