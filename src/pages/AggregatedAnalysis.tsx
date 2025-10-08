import React, { useEffect, useMemo, useState } from 'react';
import { Card, Row, Col, Select, DatePicker, Space, Typography, Table, Tag, message } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { apiService } from '../services/api';
import { TopItem } from '../types/log';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

type Dimension = 'users' | 'apps' | 'user_apps' | 'src_geo' | 'dst_geo';
type Metric = 'requests' | 'bytes';

const dimensionOptions: { label: string; value: Dimension }[] = [
  { label: '用户', value: 'users' },
  { label: '应用（目标主机）', value: 'apps' },
  { label: '用户-应用', value: 'user_apps' },
  { label: '来源国家', value: 'src_geo' },
  { label: '目标国家', value: 'dst_geo' },
];

const metricOptions: { label: string; value: Metric }[] = [
  { label: '请求数', value: 'requests' },
  { label: '流量字节', value: 'bytes' },
];

const defaultRange = () => {
  const start = dayjs().startOf('month');
  const end = dayjs();
  return [start, end] as [Dayjs, Dayjs];
};

const AggregatedAnalysis: React.FC = () => {
  const [dimension, setDimension] = useState<Dimension>('users');
  const [metric, setMetric] = useState<Metric>('requests');
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange());
  const [limit, setLimit] = useState<number>(10);
  const [loading, setLoading] = useState<boolean>(false);
  const [items, setItems] = useState<TopItem[]>([]);

  const dateParams = useMemo(() => {
    const [from, to] = range;
    return {
      from: from.format('YYYY-MM-DD'),
      to: to.format('YYYY-MM-DD'),
    };
  }, [range]);

  const loadTop = async () => {
    setLoading(true);
    try {
      const data = await apiService.getAccessTop({
        ...dateParams,
        dimension,
        metric,
        limit,
      } as any);
      setItems(data);
    } catch (err) {
      console.error(err);
      message.error('加载聚合数据失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dimension, metric, range, limit]);

  const columns = [
    { title: '排名', dataIndex: 'index', key: 'index', width: 80, render: (_: any, __: any, i: number) => i + 1 },
    { title: '维度键', dataIndex: 'key', key: 'key', render: (k: string) => <Tag>{k || '(空)'}</Tag> },
    { title: metric === 'requests' ? '请求数' : '流量字节', dataIndex: 'value', key: 'value' },
  ];

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Title level={3}>聚合分析</Title>
      <Text type="secondary">合并“用户访问”和“应用访问”，提供统一的 TopN 聚合分析视图。</Text>
      <Card bordered>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={8} lg={6}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>维度</Text>
              <Select
                options={dimensionOptions}
                value={dimension}
                onChange={setDimension}
              />
            </Space>
          </Col>
          <Col xs={24} sm={12} md={8} lg={6}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>指标</Text>
              <Select
                options={metricOptions}
                value={metric}
                onChange={setMetric}
              />
            </Space>
          </Col>
          <Col xs={24} sm={24} md={8} lg={8}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>时间范围</Text>
              <RangePicker
                allowClear={false}
                value={range}
                onChange={(v) => {
                  if (v && v[0] && v[1]) setRange([v[0], v[1]] as [Dayjs, Dayjs]);
                }}
              />
            </Space>
          </Col>
          <Col xs={24} sm={12} md={8} lg={4}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>Top 数量</Text>
              <Select
                options={[5, 10, 20, 50, 100].map(n => ({ label: `Top ${n}`, value: n }))}
                value={limit}
                onChange={setLimit}
              />
            </Space>
          </Col>
        </Row>
      </Card>
      <Card bordered title="TopN 结果">
        <Table
          rowKey={(r) => `${r.key}-${r.value}`}
          loading={loading}
          columns={columns}
          dataSource={items}
          pagination={false}
        />
      </Card>
    </Space>
  );
};

export default AggregatedAnalysis;