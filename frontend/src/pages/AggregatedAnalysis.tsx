import React, { useEffect, useMemo, useState } from 'react';
import { Card, Row, Col, Select, DatePicker, Space, Typography, Table, Tag, message } from 'antd';
import { apiService } from '../services/api';
import { TopItem } from '../types/log';
import { UserDTO } from '../types/user';
import ReactECharts from 'echarts-for-react';
import { formatBytes, formatNumber } from '../utils/format';

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

const AggregatedAnalysis: React.FC = () => {
  const [dimension, setDimension] = useState<Dimension>('users');
  const [metric, setMetric] = useState<Metric>('requests');
  const [range, setRange] = useState<any>();
  const [limit, setLimit] = useState<number>(10);
  const [loading, setLoading] = useState<boolean>(false);
  const [items, setItems] = useState<TopItem[]>([]);
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<number | undefined>(undefined);
  const [loadingUsers, setLoadingUsers] = useState<boolean>(false);

  // 是否显示用户过滤器
  const showUserFilter = dimension === 'user_apps';

  const dateParams = useMemo(() => {
    if (!range || !range[0] || !range[1]) return {};
    const [from, to] = range;
    return {
      from: from.startOf('month').format('YYYY-MM-DD'),
      to: to.endOf('month').format('YYYY-MM-DD'),
    };
  }, [range]);

  // 加载用户列表
  const loadUsers = async () => {
    setLoadingUsers(true);
    try {
      const response = await apiService.getUsers({
        page: 1,
        pageSize: 100,
        status: 1, // 只获取启用的用户
      });
      setUsers(response.items || []);
    } catch (err) {
      console.error('加载用户列表失败:', err);
      message.error('加载用户列表失败');
    } finally {
      setLoadingUsers(false);
    }
  };

  const loadTop = async () => {
    setLoading(true);
    try {
      const params: any = {
        ...(dateParams as any),
        dimension,
        metric,
        limit,
      };
      
      // 如果选择了用户且显示用户过滤器，添加userId参数
      if (showUserFilter && selectedUserId) {
        params.userId = selectedUserId;
      }
      
      const data = await apiService.getAccessDailyTop(params);
      setItems(data);
    } catch (err) {
      console.error(err);
      message.error('加载聚合数据失败');
    } finally {
      setLoading(false);
    }
  };

  // 当维度改变时，重置用户选择
  useEffect(() => {
    setSelectedUserId(undefined);
  }, [dimension]);

  // 当显示用户过滤器时，加载用户列表
  useEffect(() => {
    if (showUserFilter && users.length === 0) {
      loadUsers();
    }
  }, [showUserFilter, users.length]);

  useEffect(() => {
    loadTop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dimension, metric, range, limit, selectedUserId]);

  const columns = [
    { title: '排名', dataIndex: 'index', key: 'index', width: 80, render: (_: any, __: any, i: number) => i + 1 },
    { title: '维度键', dataIndex: 'key', key: 'key', render: (k: string) => <Tag>{k || '(空)'}</Tag> },
    { 
      title: metric === 'requests' ? '请求数' : '流量字节', 
      dataIndex: 'value', 
      key: 'value',
      render: (value: number) => {
        if (metric === 'bytes') {
          return formatBytes(value);
        }
        return formatNumber(value);
      }
    },
  ];

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Title level={3}>聚合分析</Title>
      <Text type="secondary">合并"用户访问"和"应用访问"，提供统一的 TopN 聚合分析视图。</Text>
      <Card bordered>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={8} lg={6}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>维度</Text>
              <Select
                options={dimensionOptions}
                value={dimension}
                onChange={setDimension}
                style={{ width: '100%' }}
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
                style={{ width: '100%' }}
              />
            </Space>
          </Col>
          {showUserFilter && (
            <Col xs={24} sm={12} md={8} lg={6}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Text strong>用户过滤</Text>
                <Select
                  placeholder="选择用户（可选）"
                  allowClear
                  showSearch
                  loading={loadingUsers}
                  value={selectedUserId}
                  onChange={setSelectedUserId}
                  style={{ width: '100%' }}
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={users.map(user => ({
                    label: user.username,
                    value: user.id,
                  }))}
                />
              </Space>
            </Col>
          )}
          <Col xs={24} sm={24} md={8} lg={showUserFilter ? 6 : 8}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text strong>月份范围</Text>
              <RangePicker
                picker="month"
                value={range}
                onChange={(v) => setRange(v)}
                style={{ width: '100%' }}
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
                style={{ width: '100%' }}
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

      <Card bordered title="TopN 饼图">
        <ReactECharts
          style={{ height: 360 }}
          option={{
            tooltip: { trigger: 'item' },
            legend: { type: 'scroll', orient: 'horizontal', bottom: 0 },
            series: [
              {
                type: 'pie',
                radius: ['40%', '70%'],
                avoidLabelOverlap: true,
                label: { show: true, formatter: '{b}: {d}%' },
                emphasis: { label: { show: true, fontSize: 16, fontWeight: 'bold' } },
                data: items.map(it => ({ name: it.key || '(空)', value: it.value })),
              },
            ],
          }}
        />
      </Card>
    </Space>
  );
};

export default AggregatedAnalysis;