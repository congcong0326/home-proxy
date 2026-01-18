import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  Statistic
} from 'antd';
import {
  PlusOutlined,
  ReloadOutlined,
  DeleteOutlined,
  EditOutlined,
  NodeIndexOutlined,
  FilterOutlined,
  SettingOutlined
} from '@ant-design/icons';
import { apiService } from '../services/api';
import { InboundConfigDTO, InboundConfigCreateRequest, InboundConfigUpdateRequest, InboundRouteBinding, InboundTrafficStats } from '../types/inbound';
import { ProtocolType, PROTOCOL_TYPE_LABELS } from '../types/route';
import { UserDTO, UserStatus } from '../types/user';
import { RouteDTO } from '../types/route';
import { ProxyEncAlgo, PROXY_ENC_ALGO_LABELS } from '../types/proxyEncAlgo';
import { formatBytes } from '../utils/format';

const { Title } = Typography;

interface PageState {
  loading: boolean;
  inbounds: InboundConfigDTO[];
  total: number;
  currentPage: number;
  pageSize: number;
  sortBy: string;
  sortDir: 'asc' | 'desc';
  protocolFilter?: ProtocolType;
  statusFilter?: number;
  tlsFilter?: boolean;
  searchPort?: number;
  selectedRowKeys: React.Key[];
}

const InboundManagement: React.FC = () => {
  const [state, setState] = useState<PageState>({
    loading: false,
    inbounds: [],
    total: 0,
    currentPage: 1,
    pageSize: 10,
    sortBy: 'updatedAt',
    sortDir: 'desc',
    selectedRowKeys: [],
  });

  // 创建/编辑模态框
  const [createVisible, setCreateVisible] = useState(false);
  const [editVisible, setEditVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<InboundConfigDTO | null>(null);
  const [createForm] = Form.useForm<InboundConfigCreateRequest>();
  const [editForm] = Form.useForm<InboundConfigUpdateRequest>();
  const [trafficMap, setTrafficMap] = useState<Record<number, InboundTrafficStats>>({});

  // 监听协议选择，便于基于协议调整表单项可见性与默认值
  const createProtocolWatch = Form.useWatch('protocol', createForm);
  const editProtocolWatch = Form.useWatch('protocol', editForm);

  // 根据协议选择调整创建表单的 TLS 默认值
  useEffect(() => {
    if (createProtocolWatch === ProtocolType.TP_PROXY) {
      createForm.setFieldsValue({ tlsEnabled: false });
    }
  }, [createProtocolWatch]);

  // 根据协议调整编辑表单的 TLS 默认值
  useEffect(() => {
    if (editProtocolWatch === ProtocolType.TP_PROXY) {
      editForm.setFieldsValue({ tlsEnabled: false });
    }
  }, [editProtocolWatch]);

  // 下拉数据
  const [userOptions, setUserOptions] = useState<UserDTO[]>([]);
  const [routeOptions, setRouteOptions] = useState<RouteDTO[]>([]);

  // 选择器选项映射（AntD v5 推荐使用 options 属性）
  const protocolSelectOptions = useMemo(() => ([
    { value: ProtocolType.SOCKS5, label: PROTOCOL_TYPE_LABELS[ProtocolType.SOCKS5] },
    { value: ProtocolType.HTTPS_CONNECT, label: PROTOCOL_TYPE_LABELS[ProtocolType.HTTPS_CONNECT] },
    { value: ProtocolType.SOCKS5_HTTPS, label: PROTOCOL_TYPE_LABELS[ProtocolType.SOCKS5_HTTPS] },
    { value: ProtocolType.SS, label: PROTOCOL_TYPE_LABELS[ProtocolType.SS] },
    { value: ProtocolType.TP_PROXY, label: PROTOCOL_TYPE_LABELS[ProtocolType.TP_PROXY] },
    { value: ProtocolType.DNS_SERVER, label: PROTOCOL_TYPE_LABELS[ProtocolType.DNS_SERVER] },
  ]), []);

  const statusSelectOptions = useMemo(() => ([
    { value: 1, label: '启用' },
    { value: 0, label: '禁用' },
  ]), []);

  const tlsSelectOptions = useMemo(() => ([
    { value: true, label: '启用TLS' },
    { value: false, label: '关闭TLS' },
  ]), []);

  const userSelectOptions = useMemo(() => (
    userOptions.map(u => ({ value: u.id, label: u.username }))
  ), [userOptions]);

  const routeSelectOptions = useMemo(() => (
    routeOptions.map(r => ({ value: r.id, label: r.name }))
  ), [routeOptions]);

  // Shadowsocks 加密算法选项
  const ssMethodSelectOptions = useMemo(() => ([
    { value: ProxyEncAlgo.AES_256_GCM, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.AES_256_GCM] },
    { value: ProxyEncAlgo.AES_128_GCM, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.AES_128_GCM] },
    { value: ProxyEncAlgo.CHACHA20_IETF_POLY1305, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.CHACHA20_IETF_POLY1305] },
  ]), []);

  const fetchInboundTraffic = useCallback(async (list: InboundConfigDTO[]) => {
    const ids = (list || []).map(i => i.id).filter((id): id is number => typeof id === 'number');
    if (!ids.length) return;
    const results = await Promise.all(ids.map(async (id) => {
      try {
        const data = await apiService.getInboundMonthlyTraffic(id);
        return { id, data };
      } catch (e) {
        console.warn('加载入站流量失败', id, e);
        return null;
      }
    }));
    setTrafficMap(prev => {
      const next = { ...prev };
      results.forEach(item => {
        if (item?.data) next[item.id] = item.data;
      });
      return next;
    });
  }, []);

  const loadUsersAndRoutes = useCallback(async () => {
    try {
      const usersPage = await apiService.getUsers({ page: 1, pageSize: 100, status: UserStatus.ENABLED });
      setUserOptions(usersPage.items || []);
    } catch (e) {
      console.warn('加载用户失败，可能后端未启动，使用空列表');
      setUserOptions([]);
    }
    try {
      const enabledRoutes = await apiService.getEnabledRoutes();
      setRouteOptions(enabledRoutes);
    } catch (e) {
      console.warn('加载启用路由失败，使用空列表');
      setRouteOptions([]);
    }
  }, []);

  const loadInbounds = useCallback(async () => {
    setState(prev => ({ ...prev, loading: true }));
    try {
      const res = await apiService.getInbounds({
        page: state.currentPage,
        size: state.pageSize,
        sortBy: state.sortBy,
        sortDir: state.sortDir,
        protocol: state.protocolFilter,
        status: state.statusFilter,
        tlsEnabled: state.tlsFilter,
        port: state.searchPort,
      });
      setState(prev => ({
        ...prev,
        inbounds: res.content,
        total: res.totalElements,
        loading: false,
      }));
      await fetchInboundTraffic(res.content || []);
    } catch (error) {
      console.error('加载入站配置失败:', error);
      setState(prev => ({ ...prev, loading: false }));
    }
  }, [fetchInboundTraffic, state.currentPage, state.pageSize, state.sortBy, state.sortDir, state.protocolFilter, state.statusFilter, state.tlsFilter, state.searchPort]);

  useEffect(() => {
    loadUsersAndRoutes();
  }, [loadUsersAndRoutes]);

  useEffect(() => {
    loadInbounds();
  }, [loadInbounds]);

  const handleCreate = async () => {
    try {
      const values = await createForm.validateFields();
      const payload: InboundConfigCreateRequest = {
        ...values,
        sniffEnabled: true, // 默认开启嗅探以兼容后端字段
      };
      await apiService.createInbound(payload);
      setCreateVisible(false);
      createForm.resetFields();
      loadInbounds();
    } catch (e) {
      console.error('创建入站失败:', e);
    }
  };

  const handleUpdate = async () => {
    if (!editingItem) return;
    try {
      const values = await editForm.validateFields();
      const payload: InboundConfigUpdateRequest = {
        ...values,
        sniffEnabled: editingItem.sniffEnabled ?? true, // 编辑时保留原值（默认开启）
      };
      await apiService.updateInbound(editingItem.id, payload);
      setEditVisible(false);
      setEditingItem(null);
      loadInbounds();
    } catch (e) {
      console.error('更新入站失败:', e);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await apiService.deleteInbound(id);
      loadInbounds();
    } catch (e) {
      console.error('删除入站失败:', e);
    }
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { 
      title: '协议', 
      dataIndex: 'protocol', 
      key: 'protocol',
      render: (v: ProtocolType) => <Tag color="blue">{PROTOCOL_TYPE_LABELS[v]}</Tag>
    },
    { title: '监听IP', dataIndex: 'listenIp', key: 'listenIp' },
    { title: '端口', dataIndex: 'port', key: 'port' },
    { title: 'TLS', dataIndex: 'tlsEnabled', key: 'tlsEnabled', render: (v: boolean) => v ? <Tag color="green">启用</Tag> : <Tag color="default">关闭</Tag> },
    { title: '本月流量(上/下行)', key: 'traffic', render: (_: any, record: InboundConfigDTO) => {
      const t = record.id ? trafficMap[record.id] : undefined;
      if (!t) return '-';
      return `${formatBytes(t.bytesIn || 0)} / ${formatBytes(t.bytesOut || 0)}`;
    }},
    { title: '状态', dataIndex: 'status', key: 'status', render: (s: number) => s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag> },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: InboundConfigDTO) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditingItem(record);
            setEditVisible(true);
            editForm.setFieldsValue({
              name: record.name,
              protocol: record.protocol,
              listenIp: record.listenIp,
              port: record.port,
              tlsEnabled: record.tlsEnabled,
              ssMethod: record.ssMethod,
              inboundRouteBindings: (record.inboundRouteBindings && record.inboundRouteBindings.length > 0)
                ? record.inboundRouteBindings
                : [{ userIds: record.allowedUserIds || [], routeIds: record.routeIds || [] }],
              status: record.status,
              notes: record.notes,
            });
          }}>编辑</Button>
          <Popconfirm title={`确定删除入站配置「${record.name}」？`} onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  const stats = useMemo(() => {
    const total = state.inbounds.length;
    const enabled = state.inbounds.filter(i => i.status === 1).length;
    const tlsOn = state.inbounds.filter(i => i.tlsEnabled).length;
    return { total, enabled, tlsOn };
  }, [state.inbounds]);

  return (
    <div className="inbound-management">
      <div className="page-header">
        <Title level={2}><NodeIndexOutlined /> 入站配置</Title>
        <Row gutter={16} className="stats-row">
          <Col span={8}><Card><Statistic title="总配置数" value={stats.total} prefix={<SettingOutlined />} /></Card></Col>
          <Col span={8}><Card><Statistic title="启用中" value={stats.enabled} valueStyle={{ color: '#3f8600' }} /></Card></Col>
          <Col span={8}><Card><Statistic title="TLS启用" value={stats.tlsOn} valueStyle={{ color: '#1890ff' }} /></Card></Col>
        </Row>
      </div>

      <Card className="operation-bar">
        <Row gutter={16} align="middle">
          <Col flex="auto">
            <Space>
              <Select
                placeholder="协议过滤"
                allowClear
                style={{ width: 180 }}
                suffixIcon={<FilterOutlined />}
                options={protocolSelectOptions}
                onChange={(v) => setState(prev => ({ ...prev, protocolFilter: v as ProtocolType }))}
              />
              <Select
                placeholder="状态过滤"
                allowClear
                style={{ width: 140 }}
                suffixIcon={<FilterOutlined />}
                options={statusSelectOptions}
                onChange={(v) => setState(prev => ({ ...prev, statusFilter: v as number }))}
              />
              <Select
                placeholder="TLS过滤"
                allowClear
                style={{ width: 140 }}
                suffixIcon={<FilterOutlined />}
                options={tlsSelectOptions}
                onChange={(v) => setState(prev => ({ ...prev, tlsFilter: v as boolean }))}
              />
              <InputNumber
                placeholder="端口搜索"
                style={{ width: 140 }}
                onChange={(v) => setState(prev => ({
                  ...prev,
                  // 仅当值为 number 时更新，避免 ValueType 包含 string 导致类型不匹配
                  searchPort: typeof v === 'number' ? v : undefined,
                }))}
              />
            </Space>
          </Col>
          <Col>
            <Space>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateVisible(true)}>新建配置</Button>
              <Button icon={<ReloadOutlined />} onClick={loadInbounds} loading={state.loading}>刷新</Button>
            </Space>
          </Col>
        </Row>
      </Card>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={state.inbounds}
        loading={state.loading}
        pagination={{
          current: state.currentPage,
          pageSize: state.pageSize,
          total: state.total,
          onChange: (page, pageSize) => setState(prev => ({ ...prev, currentPage: page, pageSize }))
        }}
      />

      {/* 创建模态框 */}
          <Modal
            title="新建入站配置"
            open={createVisible}
            onCancel={() => setCreateVisible(false)}
            onOk={handleCreate}
            okText="创建"
          >
        <Form form={createForm} layout="vertical" initialValues={{ protocol: ProtocolType.SOCKS5, tlsEnabled: false, status: 1, inboundRouteBindings: [{ userIds: [], routeIds: [] }] }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="配置名称" /></Form.Item></Col>
            <Col span={12}><Form.Item name="protocol" label="协议" rules={[{ required: true }]}> 
              <Select placeholder="选择协议" options={protocolSelectOptions} />
            </Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="listenIp" label="监听IP" rules={[{ required: true }]}><Input placeholder="0.0.0.0" /></Form.Item></Col>
            <Col span={12}><Form.Item name="port" label="端口" rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="tlsEnabled" label="启用TLS" valuePropName="checked"><Switch /></Form.Item></Col>
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}> 
              <Select options={statusSelectOptions} />
            </Form.Item></Col>
          </Row>
          <Form.Item shouldUpdate noStyle>
            {() => (
              createForm.getFieldValue('protocol') === ProtocolType.SS ? (
                <>
                  <Row gutter={16}>
                    <Col span={12}><Form.Item name="ssMethod" label="SS加密方法" rules={[{ required: true }]}> 
                      <Select placeholder="选择加密算法" options={ssMethodSelectOptions} />
                    </Form.Item></Col>
                  </Row>
                  <Card size="small" title="绑定关系">
                    <Row gutter={16}>
                      <Col span={12}><Form.Item name={['inboundRouteBindings', 0, 'userIds']} label="绑定用户(仅1个)" rules={[{ required: true }]}> 
                        <Select mode="multiple" maxTagCount={1} maxCount={1} placeholder="选择用户" options={userSelectOptions} />
                      </Form.Item></Col>
                      <Col span={12}><Form.Item name={['inboundRouteBindings', 0, 'routeIds']} label="路由顺序" rules={[{ required: true }]}> 
                        <Select mode="multiple" placeholder="按顺序选择路由" options={routeSelectOptions} />
                      </Form.Item></Col>
                    </Row>
                  </Card>
                </>
              ) : (
                <Form.List name="inboundRouteBindings">
                  {(fields, { add, remove }) => (
                    <>
                      {fields.map(({ key, name }) => (
                        <Card key={key} size="small" title={`绑定组 #${name + 1}`} extra={<Button size="small" danger onClick={() => remove(name)}>移除</Button>} style={{ marginBottom: 12 }}>
                          <Row gutter={16}>
                            <Col span={12}><Form.Item name={[name, 'userIds']} label="用户" rules={[{ required: true }]}> 
                              <Select mode="multiple" placeholder="选择用户" options={userSelectOptions} />
                            </Form.Item></Col>
                            <Col span={12}><Form.Item name={[name, 'routeIds']} label="路由顺序" rules={[{ required: true }]}> 
                              <Select mode="multiple" placeholder="按顺序选择路由" options={routeSelectOptions} />
                            </Form.Item></Col>
                          </Row>
                        </Card>
                      ))}
                      <Button type="dashed" onClick={() => add({ userIds: [], routeIds: [] } as InboundRouteBinding)} block icon={<PlusOutlined />}>添加绑定组</Button>
                    </>
                  )}
                </Form.List>
              )
            )}
          </Form.Item>
          <Form.Item name="notes" label="备注"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>

      {/* 编辑模态框 */}
      <Modal
        title={`编辑入站配置：${editingItem?.name || ''}`}
        open={editVisible}
        onCancel={() => { setEditVisible(false); setEditingItem(null); }}
        onOk={handleUpdate}
        okText="保存"
      >
        <Form form={editForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="protocol" label="协议" rules={[{ required: true }]}> 
              <Select disabled options={protocolSelectOptions} />
            </Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="listenIp" label="监听IP" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="port" label="端口" rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="tlsEnabled" label="启用TLS" valuePropName="checked"><Switch /></Form.Item></Col>
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}> 
              <Select options={statusSelectOptions} />
            </Form.Item></Col>
          </Row>

          <Form.Item shouldUpdate noStyle>
            {() => (
              editForm.getFieldValue('protocol') === ProtocolType.SS ? (
                <>
                  <Row gutter={16}>
                    <Col span={12}><Form.Item name="ssMethod" label="SS加密方法" rules={[{ required: true }]}> 
                      <Select placeholder="选择加密算法" options={ssMethodSelectOptions} />
                    </Form.Item></Col>
                  </Row>
                  <Card size="small" title="绑定关系">
                    <Row gutter={16}>
                      <Col span={12}><Form.Item name={['inboundRouteBindings', 0, 'userIds']} label="绑定用户(仅1个)" rules={[{ required: true }]}> 
                        <Select mode="multiple" maxTagCount={1} maxCount={1} placeholder="选择用户" options={userSelectOptions} />
                      </Form.Item></Col>
                      <Col span={12}><Form.Item name={['inboundRouteBindings', 0, 'routeIds']} label="路由顺序" rules={[{ required: true }]}> 
                        <Select mode="multiple" placeholder="按顺序选择路由" options={routeSelectOptions} />
                      </Form.Item></Col>
                    </Row>
                  </Card>
                </>
              ) : (
                <Form.List name="inboundRouteBindings">
                  {(fields, { add, remove }) => (
                    <>
                      {fields.map(({ key, name }) => (
                        <Card key={key} size="small" title={`绑定组 #${name + 1}`} extra={<Button size="small" danger onClick={() => remove(name)}>移除</Button>} style={{ marginBottom: 12 }}>
                          <Row gutter={16}>
                            <Col span={12}><Form.Item name={[name, 'userIds']} label="用户" rules={[{ required: true }]}> 
                              <Select mode="multiple" placeholder="选择用户" options={userSelectOptions} />
                            </Form.Item></Col>
                            <Col span={12}><Form.Item name={[name, 'routeIds']} label="路由顺序" rules={[{ required: true }]}> 
                              <Select mode="multiple" placeholder="按顺序选择路由" options={routeSelectOptions} />
                            </Form.Item></Col>
                          </Row>
                        </Card>
                      ))}
                      <Button type="dashed" onClick={() => add({ userIds: [], routeIds: [] } as InboundRouteBinding)} block icon={<PlusOutlined />}>添加绑定组</Button>
                    </>
                  )}
                </Form.List>
              )
            )}
          </Form.Item>
          <Form.Item name="notes" label="备注"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default InboundManagement;
