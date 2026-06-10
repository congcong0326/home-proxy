import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Col, Progress, Row, Statistic, Tag, Typography } from 'antd';
import { ApiOutlined, ClockCircleOutlined, HddOutlined, NodeIndexOutlined } from '@ant-design/icons';
import apiService from '../services/api';
import { ProxyGatewayStatus } from '../types/gateway';
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
  return value.replace('T', ' ').slice(0, 19);
};

const ProxyGatewayMonitor: React.FC = () => {
  const [status, setStatus] = useState<ProxyGatewayStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadStatus = async () => {
    try {
      const data = await apiService.getProxyGatewayStatus();
      setStatus(data);
      setError(null);
    } catch (err) {
      setError('暂无代理网关心跳数据');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStatus();
    const timer = window.setInterval(loadStatus, 5000);
    return () => window.clearInterval(timer);
  }, []);

  const memoryPercent = useMemo(() => {
    if (!status?.heapUsedBytes || !status.heapMaxBytes) return 0;
    return Math.min(100, Math.round((status.heapUsedBytes / status.heapMaxBytes) * 100));
  }, [status]);

  return (
    <div className="proxy-gateway-monitor">
      <div className="page-header">
        <h2>代理网关监控</h2>
        <p>查看代理网关的在线状态、运行指标和当前配置同步情况</p>
      </div>

      {error && (
        <Alert
          className="gateway-alert"
          type="warning"
          showIcon
          message={error}
          description="代理网关启动并完成首次上报后，这里会显示实时状态。"
        />
      )}

      <Card className="gateway-status-card" loading={loading}>
        <div className="gateway-status-header">
          <div>
            <Title level={4}>网关状态</Title>
            <Text type="secondary">{status?.hostname || '未上报主机名'}</Text>
          </div>
          <Tag color={status?.online ? 'success' : 'warning'}>{status?.online ? '在线' : '离线'}</Tag>
        </div>

        <Row gutter={[16, 16]}>
          <Col xs={24} md={12} xl={6}>
            <Statistic title="网关标识" value={status?.workerId || '-'} prefix={<ApiOutlined />} />
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
            <Text type="secondary">当前配置 Hash</Text>
            <strong>{status?.lastConfigHash ? status.lastConfigHash.slice(0, 8) : '-'}</strong>
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
    </div>
  );
};

export default ProxyGatewayMonitor;
