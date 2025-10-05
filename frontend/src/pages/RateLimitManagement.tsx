import React, { useEffect, useMemo, useState } from 'react';
import { Table, Button, Space, Tag, Modal, Form, InputNumber, Select, DatePicker, TimePicker, message, Card, Row, Col, Typography, Switch } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, FilterOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { apiService } from '../services/api';
import { UserDTO } from '../types/user';
import { RateLimitDTO, RateLimitCreateRequest, RateLimitUpdateRequest, RateLimitScopeType, RateLimitQueryParams, PageResponse as RateLimitPageResponse } from '../types/ratelimit';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface RateLimitState {
  items: RateLimitDTO[];
  loading: boolean;
  total: number;
  page: number;
  pageSize: number;
  sortBy: string;
  sortDir: 'asc' | 'desc';
  scopeType?: RateLimitScopeType;
  enabled?: boolean;
}

const RateLimitManagement: React.FC = () => {
  const [state, setState] = useState<RateLimitState>({
    items: [],
    loading: false,
    total: 0,
    page: 1,
    pageSize: 10,
    sortBy: 'id',
    sortDir: 'desc',
    scopeType: undefined,
    enabled: undefined,
  });

  const [createVisible, setCreateVisible] = useState(false);
  const [editVisible, setEditVisible] = useState(false);
  const [currentItem, setCurrentItem] = useState<RateLimitDTO | null>(null);
  const [form] = Form.useForm();

  const loadData = async (overrides: Partial<RateLimitQueryParams> = {}) => {
    setState(prev => ({ ...prev, loading: true }));
    try {
      const params: RateLimitQueryParams = {
        page: overrides.page ?? state.page,
        size: overrides.size ?? state.pageSize,
        sortBy: overrides.sortBy ?? state.sortBy,
        sortDir: overrides.sortDir ?? state.sortDir,
        scopeType: overrides.scopeType ?? state.scopeType,
        enabled: overrides.enabled ?? state.enabled,
      };
      const res = await apiService.getRateLimits(params);
      setState(prev => ({
        ...prev,
        items: res.items,
        total: res.total,
        page: res.page,
        pageSize: res.pageSize,
      }));
    } catch (err) {
      console.warn('加载限流策略失败，使用本地模拟数据:', err);
      // 简单模拟数据
      const mock: RateLimitDTO[] = [
        {
          id: 1,
          scopeType: RateLimitScopeType.GLOBAL,
          enabled: true,
          uplinkLimitBps: 10_000_000,
          downlinkLimitBps: 20_000_000,
          burstBytes: 5_000_000,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ];
      setState(prev => ({ ...prev, items: mock, total: mock.length }));
    } finally {
      setState(prev => ({ ...prev, loading: false }));
    }
  };

  useEffect(() => {
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openCreate = () => {
    setCreateVisible(true);
    form.resetFields();
    form.setFieldsValue({
      scopeType: RateLimitScopeType.GLOBAL,
      enabled: true,
    });
  };

  const openEdit = (item: RateLimitDTO) => {
    setCurrentItem(item);
    setEditVisible(true);
    form.setFieldsValue({
      scopeType: item.scopeType,
      enabled: item.enabled,
      userIds: item.userIds,
      uplinkLimitBps: item.uplinkLimitBps,
      downlinkLimitBps: item.downlinkLimitBps,
      burstBytes: item.burstBytes,
      effectiveTime: item.effectiveTimeStart && item.effectiveTimeEnd ? [dayjs(item.effectiveTimeStart, 'HH:mm:ss'), dayjs(item.effectiveTimeEnd, 'HH:mm:ss')] : undefined,
      effectiveDate: item.effectiveFrom && item.effectiveTo ? [dayjs(item.effectiveFrom, 'YYYY-MM-DD'), dayjs(item.effectiveTo, 'YYYY-MM-DD')] : undefined,
    });
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      const payload: RateLimitCreateRequest = {
        scopeType: values.scopeType,
        userIds: values.scopeType === RateLimitScopeType.USERS ? (values.userIds || []) : undefined,
        uplinkLimitBps: values.uplinkLimitBps,
        downlinkLimitBps: values.downlinkLimitBps,
        burstBytes: values.burstBytes,
        enabled: values.enabled,
        effectiveTimeStart: values.effectiveTime ? values.effectiveTime[0].format('HH:mm:ss') : undefined,
        effectiveTimeEnd: values.effectiveTime ? values.effectiveTime[1].format('HH:mm:ss') : undefined,
        effectiveFrom: values.effectiveDate ? values.effectiveDate[0].format('YYYY-MM-DD') : undefined,
        effectiveTo: values.effectiveDate ? values.effectiveDate[1].format('YYYY-MM-DD') : undefined,
      };
      const created = await apiService.createRateLimit(payload);
      message.success('创建成功');
      setCreateVisible(false);
      form.resetFields();
      setState(prev => ({ ...prev, items: [created, ...prev.items], total: prev.total + 1 }));
    } catch (err) {
      console.error(err);
      message.error((err as Error).message || '创建失败');
    }
  };

  const handleUpdate = async () => {
    if (!currentItem) return;
    try {
      const values = await form.validateFields();
      const payload: RateLimitUpdateRequest = {
        scopeType: values.scopeType,
        userIds: values.scopeType === RateLimitScopeType.USERS ? (values.userIds || []) : undefined,
        uplinkLimitBps: values.uplinkLimitBps,
        downlinkLimitBps: values.downlinkLimitBps,
        burstBytes: values.burstBytes,
        enabled: values.enabled,
        effectiveTimeStart: values.effectiveTime ? values.effectiveTime[0].format('HH:mm:ss') : undefined,
        effectiveTimeEnd: values.effectiveTime ? values.effectiveTime[1].format('HH:mm:ss') : undefined,
        effectiveFrom: values.effectiveDate ? values.effectiveDate[0].format('YYYY-MM-DD') : undefined,
        effectiveTo: values.effectiveDate ? values.effectiveDate[1].format('YYYY-MM-DD') : undefined,
      };
      const updated = await apiService.updateRateLimit(currentItem.id!, payload);
      message.success('更新成功');
      setEditVisible(false);
      setCurrentItem(null);
      setState(prev => ({ ...prev, items: prev.items.map(it => it.id === updated.id ? updated : it) }));
    } catch (err) {
      console.error(err);
      message.error((err as Error).message || '更新失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await apiService.deleteRateLimit(id);
      message.success('删除成功');
      setState(prev => ({ ...prev, items: prev.items.filter(it => it.id !== id), total: prev.total - 1 }));
    } catch (err) {
      console.error(err);
      message.error((err as Error).message || '删除失败');
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '范围', dataIndex: 'scopeType', key: 'scopeType', render: (v: RateLimitScopeType) => v === RateLimitScopeType.GLOBAL ? <Tag color="blue">全局</Tag> : <Tag color="purple">指定用户</Tag> },
    { title: '用户ID', dataIndex: 'userIds', key: 'userIds', render: (ids?: number[]) => ids?.join(', ') || '-' },
    { title: '上行(bps)', dataIndex: 'uplinkLimitBps', key: 'uplinkLimitBps' },
    { title: '下行(bps)', dataIndex: 'downlinkLimitBps', key: 'downlinkLimitBps' },
    { title: '突发(bytes)', dataIndex: 'burstBytes', key: 'burstBytes' },
    { title: '每日时间', key: 'effectiveTime', render: (_: any, r: RateLimitDTO) => (r.effectiveTimeStart && r.effectiveTimeEnd) ? `${r.effectiveTimeStart} ~ ${r.effectiveTimeEnd}` : '-' },
    { title: '日期范围', key: 'effectiveDate', render: (_: any, r: RateLimitDTO) => (r.effectiveFrom && r.effectiveTo) ? `${r.effectiveFrom} ~ ${r.effectiveTo}` : '-' },
    { title: '启用', dataIndex: 'enabled', key: 'enabled', render: (v: boolean) => v ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag> },
    { title: '操作', key: 'actions', render: (_: any, r: RateLimitDTO) => (
      <Space>
        <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(r)}>编辑</Button>
        <Button icon={<DeleteOutlined />} size="small" danger onClick={() => handleDelete(r.id!)}>删除</Button>
      </Space>
    )},
  ];

  const [userOptions, setUserOptions] = useState<UserDTO[]>([]);
  const loadUserOptions = async () => {
    try {
      const res = await apiService.getUsers({ page: 1, pageSize: 100, sortBy: 'id', sortDir: 'desc' });
      setUserOptions(res.items);
    } catch (err) {
      console.warn('加载用户列表失败，使用空列表');
      setUserOptions([]);
    }
  };

  useEffect(() => {
    loadUserOptions();
  }, []);

  return (
    <div style={{ padding: 24 }}>
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Title level={3}>限流设置</Title>
          <Text type="secondary">配置访问频率限制和流量控制</Text>
        </Col>
        <Col>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadData()}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建策略</Button>
          </Space>
        </Col>
      </Row>

      <Card style={{ marginBottom: 16 }}>
        <Space>
          <Select
            style={{ width: 160 }}
            value={state.scopeType}
            onChange={(v) => { setState(prev => ({ ...prev, scopeType: v })); loadData({ scopeType: v }); }}
            allowClear
            placeholder="范围类型"
            options={[
              { label: '全局', value: RateLimitScopeType.GLOBAL },
              { label: '指定用户', value: RateLimitScopeType.USERS },
            ]}
          />
          <Select
            style={{ width: 120 }}
            value={state.enabled}
            onChange={(v) => { setState(prev => ({ ...prev, enabled: v })); loadData({ enabled: v }); }}
            allowClear
            placeholder="启用状态"
            options={[
              { label: '启用', value: true },
              { label: '禁用', value: false },
            ]}
          />
        </Space>
      </Card>

      <Table
        rowKey="id"
        loading={state.loading}
        dataSource={state.items}
        columns={columns as any}
        pagination={{
          current: state.page,
          pageSize: state.pageSize,
          total: state.total,
          onChange: (page, pageSize) => loadData({ page, size: pageSize }),
        }}
      />

      <Modal
        title="新建限流策略"
        open={createVisible}
        onCancel={() => setCreateVisible(false)}
        onOk={handleCreate}
        okText="创建"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="scopeType" label="范围类型" rules={[{ required: true }]}> 
            <Select options={[
              { label: '全局', value: RateLimitScopeType.GLOBAL },
              { label: '指定用户', value: RateLimitScopeType.USERS },
            ]} />
          </Form.Item>
          <Form.Item shouldUpdate={(prev, curr) => prev.scopeType !== curr.scopeType} noStyle>
            {({ getFieldValue }) => getFieldValue('scopeType') === RateLimitScopeType.USERS ? (
              <Form.Item name="userIds" label="用户ID" rules={[{ required: true, message: '请选择用户' }]}> 
                <Select mode="multiple" placeholder="选择用户" optionFilterProp="label" options={userOptions.map(u => ({ label: `${u.username} (#${u.id})`, value: u.id }))} />
              </Form.Item>
            ) : null}
          </Form.Item>
          <Row gutter={16}>
            <Col span={8}><Form.Item name="uplinkLimitBps" label="上行带宽(bps)" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="downlinkLimitBps" label="下行带宽(bps)" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="burstBytes" label="突发字节数" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="effectiveTime" label="每日生效时间"><TimePicker.RangePicker style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item name="effectiveDate" label="生效日期范围"><RangePicker style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑限流策略"
        open={editVisible}
        onCancel={() => setEditVisible(false)}
        onOk={handleUpdate}
        okText="保存"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="scopeType" label="范围类型" rules={[{ required: true }]}> 
            <Select options={[
              { label: '全局', value: RateLimitScopeType.GLOBAL },
              { label: '指定用户', value: RateLimitScopeType.USERS },
            ]} />
          </Form.Item>
          <Form.Item shouldUpdate={(prev, curr) => prev.scopeType !== curr.scopeType} noStyle>
            {({ getFieldValue }) => getFieldValue('scopeType') === RateLimitScopeType.USERS ? (
              <Form.Item name="userIds" label="用户ID" rules={[{ required: true, message: '请选择用户' }]}> 
                <Select mode="multiple" placeholder="选择用户" optionFilterProp="label" options={userOptions.map(u => ({ label: `${u.username} (#${u.id})`, value: u.id }))} />
              </Form.Item>
            ) : null}
          </Form.Item>
          <Row gutter={16}>
            <Col span={8}><Form.Item name="uplinkLimitBps" label="上行带宽(bps)" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="downlinkLimitBps" label="下行带宽(bps)" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="burstBytes" label="突发字节数" rules={[{ type: 'number', min: 1 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="effectiveTime" label="每日生效时间"><TimePicker.RangePicker style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={12}><Form.Item name="effectiveDate" label="生效日期范围"><RangePicker style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default RateLimitManagement;