import React, { useEffect, useState } from 'react';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { Card, Form, Row, Col, Input, DatePicker, Button, Table, Tag, Drawer, Space, Typography } from 'antd';
import apiService from '../services/api';
import { AccessLogListItem, AccessLogDetail, AccessLogQueryParams, PageResponse } from '../types/log';
import { formatBytes } from '../utils/format';

const { RangePicker } = DatePicker;
const { Text } = Typography;

type AccessLogFormValues = AccessLogQueryParams & { range?: [Dayjs, Dayjs] };

const getTodayRange = (): [Dayjs, Dayjs] => [dayjs().startOf('day'), dayjs().endOf('day')];

const LogAudit: React.FC = () => {
  const [form] = Form.useForm<AccessLogFormValues>();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<AccessLogListItem[]>([]);
  const [pageInfo, setPageInfo] = useState<{ total: number; page: number; pageSize: number }>({ total: 0, page: 0, pageSize: 10 });
  const [detail, setDetail] = useState<AccessLogDetail | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  // 人性化格式化耗时（ms/s/m/h）
  const formatDuration = (value?: number) => {
    if (value === undefined || value === null || isNaN(Number(value))) return '-';
    if (value < 1000) return `${value} ms`;
    const seconds = value / 1000;
    if (seconds < 60) return `${seconds.toFixed(2)} s`;
    const minutes = Math.floor(seconds / 60);
    const remSeconds = Math.floor(seconds % 60);
    if (minutes < 60) return `${minutes}m ${remSeconds}s`;
    const hours = minutes / 60;
    return `${hours.toFixed(2)} h`;
  };

  const buildQueryParams = (params?: Partial<AccessLogQueryParams>): AccessLogQueryParams => {
    const values = form.getFieldsValue() as AccessLogFormValues;
    const { range, ...restValues } = values;
    const [start, end] = range || [];
    const merged: AccessLogQueryParams = {
      ...restValues,
      page: pageInfo.page,
      size: pageInfo.pageSize,
      ...params,
    };
    merged.from = params?.from ?? restValues.from ?? (start ? start.toISOString() : undefined);
    merged.to = params?.to ?? restValues.to ?? (end ? end.toISOString() : undefined);
    return merged;
  };

  const fetchData = async (params?: Partial<AccessLogQueryParams>) => {
    setLoading(true);
    try {
      const query = buildQueryParams(params);
      const res: PageResponse<AccessLogListItem> = await apiService.getAccessLogs(query);
      setData(res.content || []);
      setPageInfo({
        total: res.totalElements || 0,
        page: res.number || 0,
        pageSize: res.size || query.size || 10,
      });
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  // 分页变化时重新加载
  useEffect(() => {
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageInfo.page, pageInfo.pageSize]);

  const onSearch = () => {
    setPageInfo(prev => ({ ...prev, page: 0 }));
    fetchData({ page: 0 });
  };

  const onReset = () => {
    form.resetFields();
    const [start, end] = getTodayRange();
    form.setFieldsValue({ range: [start, end] });
    setPageInfo({ total: 0, page: 0, pageSize: 10 });
    fetchData({ page: 0, size: 10, from: start.toISOString(), to: end.toISOString() });
  };

  const openDetail = async (id: string) => {
    try {
      const d = await apiService.getAccessLogById(id);
      setDetail(d);
      setDetailOpen(true);
    } catch (e) {
      console.error(e);
    }
  };

  const columns = [
    { title: '时间', dataIndex: 'ts', key: 'ts', render: (v: string) => new Date(v).toLocaleString() },
    { title: '代理名称', dataIndex: 'proxyName', key: 'proxyName' },
    { title: '路由策略', dataIndex: 'routePolicyName', key: 'routePolicyName' },
    { title: '用户', dataIndex: 'username', key: 'username' },
    { title: '客户端IP', dataIndex: 'clientIp', key: 'clientIp' },
    { title: '原目标', dataIndex: 'originalTargetHost', key: 'originalTargetHost' },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: number) => <Tag color={v === 200 ? 'green' : 'red'}>{v}</Tag> },
    { title: '源地理', key: 'srcGeo', render: (_: any, r: AccessLogListItem) => `${r.srcGeoCountry || ''} ${r.srcGeoCity || ''}`.trim() || '-' },
    { title: '目标地理', key: 'dstGeo', render: (_: any, r: AccessLogListItem) => `${r.dstGeoCountry || ''} ${r.dstGeoCity || ''}`.trim() || '-' },
    { title: '上行字节', dataIndex: 'bytesIn', key: 'bytesIn', render: (v?: number) => v !== undefined && v !== null ? formatBytes(v) : '-' },
    { title: '下行字节', dataIndex: 'bytesOut', key: 'bytesOut', render: (v?: number) => v !== undefined && v !== null ? formatBytes(v) : '-' },
    { title: '隧道时长', dataIndex: 'requestDurationMs', key: 'requestDurationMs', render: (v?: number) => formatDuration(v) },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: AccessLogListItem) => (
        <Space>
          <Button type="link" onClick={() => openDetail(record.requestId)}>详情</Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card title="日志审计" bordered={false} style={{ marginBottom: 16 }}>
        <Form form={form} layout="vertical" initialValues={{ range: getTodayRange() }}>
          <Row gutter={16}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="时间范围" name="range">
                <RangePicker
                  showTime
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="用户名" name="username"><Input allowClear /></Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="客户端IP" name="clientIp"><Input allowClear /></Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="状态码" name="status"><Input placeholder="例如 200" allowClear /></Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="代理名称" name="proxyName"><Input allowClear /></Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="路由策略" name="routePolicyName"><Input allowClear /></Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item label="目标地址" name="originalTargetHost"><Input allowClear /></Form.Item>
            </Col>
          </Row>
          <Space>
            <Button type="primary" onClick={onSearch}>查询</Button>
            <Button onClick={onReset}>重置</Button>
          </Space>
        </Form>
      </Card>

      <Card bordered={false}>
        <Table
          rowKey="requestId"
          loading={loading}
          columns={columns}
          dataSource={data}
          pagination={{
            total: pageInfo.total,
            current: pageInfo.page + 1,
            pageSize: pageInfo.pageSize,
            showSizeChanger: true,
            onChange: (p, ps) => setPageInfo({ ...pageInfo, page: p - 1, pageSize: ps }),
          }}
        />
      </Card>

      <Drawer title="访问详情" width={600} open={detailOpen} onClose={() => setDetailOpen(false)}>
        {detail ? (
          <div>
            <Card size="small" title="基本信息" style={{ marginBottom: 12 }}>
              <p><Text type="secondary">时间</Text>: {new Date(detail.ts).toLocaleString()}</p>
              <p><Text type="secondary">请求ID</Text>: {detail.requestId}</p>
              <p><Text type="secondary">用户</Text>: {detail.username} (ID: {detail.userId})</p>
              <p><Text type="secondary">状态</Text>: {detail.status}</p>
            </Card>
            <Card size="small" title="代理信息" style={{ marginBottom: 12 }}>
              <p><Text type="secondary">代理名称</Text>: {detail.proxyName || '-'}</p>
            </Card>
            <Card size="small" title="源信息" style={{ marginBottom: 12 }}>
              <p><Text type="secondary">客户端IP</Text>: {detail.clientIp}</p>
              <p><Text type="secondary">源地理</Text>: {detail.srcGeoCountry} {detail.srcGeoCity}</p>
            </Card>
            <Card size="small" title="目标信息" style={{ marginBottom: 12 }}>
              <p><Text type="secondary">原目标</Text>: {detail.originalTargetHost} {detail.originalTargetPort ? `:${detail.originalTargetPort}` : ''}</p>
              <p><Text type="secondary">改写目标</Text>: {detail.rewriteTargetHost} {detail.rewriteTargetPort ? `:${detail.rewriteTargetPort}` : ''}</p>
              <p><Text type="secondary">目标地理</Text>: {detail.dstGeoCountry} {detail.dstGeoCity}</p>
            </Card>
            <Card size="small" title="路由与协议" style={{ marginBottom: 12 }}>
              <p><Text type="secondary">入站协议</Text>: {detail.inboundProtocolType}</p>
              <p><Text type="secondary">出站协议</Text>: {detail.outboundProtocolType}</p>
              <p><Text type="secondary">路由策略</Text>: {detail.routePolicyName} (ID: {detail.routePolicyId})</p>
            </Card>
            {(detail.errorCode || detail.errorMsg) && (
              <Card size="small" title="错误信息" style={{ marginBottom: 12 }}>
                {detail.errorCode && <p><Text type="secondary">错误码</Text>: <Text type="danger">{detail.errorCode}</Text></p>}
                {detail.errorMsg && <p><Text type="secondary">错误信息</Text>: <Text type="danger">{detail.errorMsg}</Text></p>}
              </Card>
            )}
            <Card size="small" title="流量与时延">
              <p><Text type="secondary">上行/下行字节</Text>: {formatBytes(detail.bytesIn || 0)} / {formatBytes(detail.bytesOut || 0)}</p>
              <p><Text type="secondary">隧道时长</Text>: {formatDuration(detail.requestDurationMs)}</p>
              <p><Text type="secondary">DNS耗时(ms)</Text>: {detail.dnsDurationMs}</p>
              <p><Text type="secondary">连接耗时(ms)</Text>: {detail.connectDurationMs}</p>
              <p><Text type="secondary">连接目标耗时(ms)</Text>: {detail.connectTargetDurationMs}</p>
            </Card>
          </div>
        ) : (
          <Text type="secondary">无数据</Text>
        )}
      </Drawer>
    </div>
  );
};

export default LogAudit;
