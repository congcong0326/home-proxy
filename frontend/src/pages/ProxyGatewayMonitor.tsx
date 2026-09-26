import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Card, Col, Progress, Row, Select, Statistic, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ApiOutlined,
  ClockCircleOutlined,
  ClusterOutlined,
  HddOutlined,
  NodeIndexOutlined,
} from '@ant-design/icons';
import apiService from '../services/api';
import { ProxyWorkerDTO } from '../types/worker';
import './ProxyGatewayMonitor.css';

const { Text, Title } = Typography;

const formatBytes = (value?: number) => {
  if (value == null) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let current = value;
  let unitIndex = 0;
  while (current >= 1024 && unitIndex < units.length - 1) {
    current /= 1024;
    unitIndex += 1;
  }
  return `${current.toFixed(current >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
};

const formatUptime = (seconds?: number) => {
  if (seconds == null) return '-';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}天 ${hours}小时`;
  if (hours > 0) return `${hours}小时 ${minutes}分钟`;
  return `${minutes}分钟`;
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  // Worker status timestamps are LocalDateTime values written by the
  // controller JVM in UTC. Add the UTC designator before local formatting.
  const utcValue = value.endsWith('Z') ? value : `${value}Z`;
  return new Date(utcValue).toLocaleString('zh-CN', { hour12: false });
};

const shortHash = (value?: string, length = 8) => value ? value.slice(0, length) : '-';

const ProxyGatewayMonitor: React.FC = () => {
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [selectedWorkerId, setSelectedWorkerId] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadWorkers = useCallback(async () => {
    try {
      const data = await apiService.getWorkers();
      setWorkers(data);
      setSelectedWorkerId(current => {
        if (current && data.some(worker => worker.workerId === current)) return current;
        return data.find(worker => worker.status?.online)?.workerId ?? data[0]?.workerId;
      });
      setError(null);
    } catch (err) {
      setError('代理节点状态加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadWorkers();
    const timer = window.setInterval(() => void loadWorkers(), 5000);
    return () => window.clearInterval(timer);
  }, [loadWorkers]);

  const selectedWorker = useMemo(
    () => workers.find(worker => worker.workerId === selectedWorkerId),
    [selectedWorkerId, workers]
  );
  const status = selectedWorker?.status;

  const summary = useMemo(() => workers.reduce((result, worker) => ({
    online: result.online + (worker.status?.online ? 1 : 0),
    runningInbounds: result.runningInbounds + (worker.status?.runningInboundCount ?? 0),
    activeConnections: result.activeConnections + (worker.status?.activeConnectionCount ?? 0),
  }), { online: 0, runningInbounds: 0, activeConnections: 0 }), [workers]);

  const memoryPercent = useMemo(() => {
    if (!status?.heapUsedBytes || !status.heapMaxBytes) return 0;
    return Math.min(100, Math.round((status.heapUsedBytes / status.heapMaxBytes) * 100));
  }, [status]);

  const columns: TableColumnsType<ProxyWorkerDTO> = [
    {
      title: '节点',
      key: 'worker',
      render: (_, worker) => (
        <div className="gateway-worker-name">
          <strong>{worker.displayName}</strong>
          <Text type="secondary">{worker.workerId}</Text>
        </div>
      ),
    },
    {
      title: '状态',
      key: 'status',
      render: (_, worker) => (
        <div className="gateway-worker-tags">
          <Tag color={worker.enabled ? 'green' : 'red'}>{worker.enabled ? '启用' : '禁用'}</Tag>
          <Tag color={worker.status?.online ? 'blue' : 'default'}>{worker.status?.online ? '在线' : '离线'}</Tag>
        </div>
      ),
    },
    {
      title: '主机名',
      dataIndex: ['status', 'hostname'],
      key: 'hostname',
      render: (value?: string) => value || '-',
    },
    {
      title: '入站',
      dataIndex: ['status', 'runningInboundCount'],
      key: 'inbounds',
      align: 'right',
      render: (value?: number) => value ?? 0,
    },
    {
      title: '连接',
      dataIndex: ['status', 'activeConnectionCount'],
      key: 'connections',
      align: 'right',
      render: (value?: number) => value ?? 0,
    },
    {
      title: '配置状态',
      key: 'config',
      render: (_, worker) => worker.status?.applySuccess === false
        ? <Tag color="error">应用失败</Tag>
        : shortHash(worker.status?.appliedConfigHash, 12),
    },
    {
      title: '最近心跳',
      key: 'lastSeenAt',
      render: (_, worker) => formatDateTime(worker.status?.lastSeenAt),
    },
  ];

  return (
    <div className="proxy-gateway-monitor">
      <div className="page-header gateway-page-header">
        <h2>代理网关监控</h2>
        <Select
          className="gateway-worker-select"
          aria-label="选择代理节点"
          placeholder="选择代理节点"
          value={selectedWorkerId}
          onChange={setSelectedWorkerId}
          options={workers.map(worker => ({
            value: worker.workerId,
            label: `${worker.displayName} (${worker.workerId})`,
          }))}
          showSearch
          optionFilterProp="label"
          disabled={workers.length === 0}
        />
      </div>

      {error && <Alert className="gateway-alert" type="error" showIcon message={error} />}

      <Row gutter={[16, 16]} className="gateway-summary">
        <Col xs={12} xl={6}>
          <Card className="gateway-summary-card" loading={loading}>
            <Statistic title="节点总数" value={workers.length} prefix={<ClusterOutlined />} />
          </Card>
        </Col>
        <Col xs={12} xl={6}>
          <Card className="gateway-summary-card" loading={loading}>
            <Statistic title="在线节点" value={summary.online} suffix={`/ ${workers.length}`} />
          </Card>
        </Col>
        <Col xs={12} xl={6}>
          <Card className="gateway-summary-card" loading={loading}>
            <Statistic title="运行入站数" value={summary.runningInbounds} prefix={<NodeIndexOutlined />} />
          </Card>
        </Col>
        <Col xs={12} xl={6}>
          <Card className="gateway-summary-card" loading={loading}>
            <Statistic title="当前连接数" value={summary.activeConnections} />
          </Card>
        </Col>
      </Row>

      <Card className="gateway-list-card" title="节点概览">
        <Table
          rowKey="workerId"
          loading={loading}
          columns={columns}
          dataSource={workers}
          pagination={false}
          scroll={{ x: 960 }}
          locale={{ emptyText: '暂无代理节点' }}
          rowClassName={worker => worker.workerId === selectedWorkerId ? 'gateway-worker-row-selected' : ''}
          onRow={worker => ({ onClick: () => setSelectedWorkerId(worker.workerId) })}
        />
      </Card>

      {selectedWorker && (
        <>
          {status?.applySuccess === false && (
            <Alert
              className="gateway-alert"
              type="error"
              showIcon
              message={`${selectedWorker.displayName} 配置应用失败`}
              description={status.applyError || '节点未上报错误详情'}
            />
          )}

          <Card className="gateway-status-card">
            <div className="gateway-status-header">
              <div>
                <Title level={4}>{selectedWorker.displayName}</Title>
                <Text type="secondary">{status?.hostname || '未上报主机名'}</Text>
              </div>
              <div className="gateway-worker-tags">
                <Tag color={selectedWorker.enabled ? 'green' : 'red'}>{selectedWorker.enabled ? '启用' : '禁用'}</Tag>
                <Tag color={status?.online ? 'success' : 'warning'}>{status?.online ? '在线' : '离线'}</Tag>
              </div>
            </div>

            <Row gutter={[16, 16]}>
              <Col xs={24} md={12} xl={6}>
                <Statistic title="网关标识" value={selectedWorker.workerId} prefix={<ApiOutlined />} />
              </Col>
              <Col xs={24} md={12} xl={6}>
                <Statistic title="运行时长" value={formatUptime(status?.uptimeSeconds)} prefix={<ClockCircleOutlined />} />
              </Col>
              <Col xs={24} md={12} xl={6}>
                <Statistic title="运行入站数" value={status?.runningInboundCount ?? 0} prefix={<NodeIndexOutlined />} />
              </Col>
              <Col xs={24} md={12} xl={6}>
                <Statistic title="当前连接数" value={status?.activeConnectionCount ?? 0} />
              </Col>
            </Row>

            <div className="gateway-details">
              <div>
                <Text type="secondary">最近心跳</Text>
                <strong>{formatDateTime(status?.lastSeenAt)}</strong>
              </div>
              <div>
                <Text type="secondary">启动时间</Text>
                <strong>{formatDateTime(status?.startedAt)}</strong>
              </div>
              <div>
                <Text type="secondary">已拉取配置 Hash</Text>
                <strong>{shortHash(status?.fetchedConfigHash)}</strong>
              </div>
              <div>
                <Text type="secondary">已应用配置 Hash</Text>
                <strong>{shortHash(status?.appliedConfigHash)}</strong>
              </div>
            </div>
          </Card>

          <Row gutter={[16, 16]} className="gateway-metrics">
            <Col xs={24} lg={12}>
              <Card title="JVM 内存" className="gateway-metric-card">
                <div className="memory-header">
                  <span><HddOutlined /> 使用率</span>
                  <strong>{memoryPercent}%</strong>
                </div>
                <Progress percent={memoryPercent} status={memoryPercent >= 85 ? 'exception' : 'active'} />
                <div className="memory-values">
                  <span>已用 {formatBytes(status?.heapUsedBytes)}</span>
                  <span>最大 {formatBytes(status?.heapMaxBytes)}</span>
                </div>
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title="代理运行指标" className="gateway-metric-card">
                <Row gutter={16}>
                  <Col span={12}>
                    <Statistic title="运行入站数" value={status?.runningInboundCount ?? 0} />
                  </Col>
                  <Col span={12}>
                    <Statistic title="当前连接数" value={status?.activeConnectionCount ?? 0} />
                  </Col>
                </Row>
              </Card>
            </Col>
          </Row>
        </>
      )}
    </div>
  );
};

export default ProxyGatewayMonitor;
