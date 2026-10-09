import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
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
  Tooltip,
  Typography,
  Statistic,
  Grid
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
import { InboundConfigDTO, InboundConfigCreateRequest, InboundConfigUpdateRequest, InboundRouteBinding, InboundTrafficStats, InboundMonthlyTrafficV1 } from '../types/inbound';
import { TrafficCategory } from '../types/observability';
import { ProtocolType, PROTOCOL_TYPE_LABELS } from '../types/route';
import { UserDTO } from '../types/user';
import { RouteDTO } from '../types/route';
import { ProxyWorkerDTO } from '../types/worker';
import { PROXY_ENC_ALGO_OPTIONS } from '../types/proxyEncAlgo';
import { formatBytes } from '../utils/format';
import RouteOrderEditor from '../components/RouteOrderEditor';
import './InboundManagement.css';

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
  workerFilter?: string;
  selectedRowKeys: React.Key[];
}

const normalizeInboundConfig = (inbound: InboundConfigDTO): InboundConfigDTO => {
  const normalizedBindings = (inbound.inboundRouteBindings ?? [])
    .filter((binding): binding is InboundRouteBinding => Boolean(binding))
    .map((binding) => ({
      userIds: (binding.userIds ?? []).filter((id): id is number => typeof id === 'number'),
      routeIds: (binding.routeIds ?? []).filter((id): id is number => typeof id === 'number'),
    }))
    .filter((binding) => binding.userIds.length > 0 || binding.routeIds.length > 0);

  return {
    ...inbound,
    inboundRouteBindings: normalizedBindings.length > 0
      ? normalizedBindings
      : [{ userIds: [], routeIds: [] }],
  };
};

const routeOrderRules = [
  {
    validator: async (_: unknown, value?: number[]) => {
      const routeIdList = (value ?? []).filter((id): id is number => typeof id === 'number');

      if (!routeIdList.length) {
        throw new Error('请至少添加一个路由');
      }

      if (new Set(routeIdList).size !== routeIdList.length) {
        throw new Error('同一绑定组内不能重复选择路由');
      }
    },
  },
];

const supportsTls = (protocol?: ProtocolType) => protocol === ProtocolType.SOCKS5;

const trafficCategoryLabels: Record<TrafficCategory, string> = {
  INTERNAL_REWRITE: '内网访问（目标重写）',
  EXTERNAL: '外网访问',
  OTHER_INTERNAL: '其他内网',
  UNKNOWN: '未知分类',
};

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
  const [trafficSource, setTrafficSource] = useState<'v1' | 'legacy'>('v1');
  const [trafficMap, setTrafficMap] = useState<Record<number, InboundTrafficStats | InboundMonthlyTrafficV1>>({});
  const [trafficErrors, setTrafficErrors] = useState<number[]>([]);
  const screens = Grid.useBreakpoint();
  const isMobile = typeof window !== 'undefined' ? window.innerWidth <= 768 : screens.md === false;

  // 监听协议选择，便于基于协议调整表单项可见性与默认值
  const createProtocolWatch = Form.useWatch('protocol', createForm);
  const editProtocolWatch = Form.useWatch('protocol', editForm);
  const createWorkerWatch = Form.useWatch('workerId', createForm);
  const editWorkerWatch = Form.useWatch('workerId', editForm);
  const [transparentByWorker, setTransparentByWorker] = useState<Record<string, boolean | 'error'>>({});

  useEffect(() => {
    const workerId = createVisible && createProtocolWatch === ProtocolType.DNS_SERVER
      ? createWorkerWatch
      : editVisible && editProtocolWatch === ProtocolType.DNS_SERVER ? editWorkerWatch : undefined;
    if (!workerId) return;
    let current = true;
    apiService.getInbounds({ workerId, protocol: ProtocolType.TP_PROXY, status: 1, page: 1, pageSize: 1 })
      .then(result => {
        if (current) setTransparentByWorker(previous => ({ ...previous, [workerId]: result.total > 0 }));
      })
      .catch(() => {
        if (current) setTransparentByWorker(previous => ({ ...previous, [workerId]: 'error' }));
      });
    return () => { current = false; };
  }, [createVisible, editVisible, createProtocolWatch, editProtocolWatch, createWorkerWatch, editWorkerWatch]);

  const fakeIpHint = (workerId?: string) => {
    const status = !workerId ? '请选择代理节点以检查透明代理状态。'
      : transparentByWorker[workerId] === undefined ? '正在检查同节点透明代理状态。'
      : transparentByWorker[workerId] === 'error' ? '暂时无法检查同节点透明代理状态。'
      : transparentByWorker[workerId] ? '同节点已有启用的透明代理。'
      : '同节点尚无启用的透明代理，打开 Fake IP 前需先创建。';
    return `${status}客户端 DNS 查询及 Fake IP 的 TCP 流量都必须进入该节点。`;
  };

  // 非 SOCKS5 协议不展示 TLS，提交时也保持关闭
  useEffect(() => {
    if (!supportsTls(createProtocolWatch)) {
      createForm.setFieldsValue({ tlsEnabled: false });
    }
  }, [createForm, createProtocolWatch]);

  // 非 SOCKS5 协议不展示 TLS，提交时也保持关闭
  useEffect(() => {
    if (!supportsTls(editProtocolWatch)) {
      editForm.setFieldsValue({ tlsEnabled: false });
    }
  }, [editForm, editProtocolWatch]);

  // 下拉数据
  const [userOptions, setUserOptions] = useState<UserDTO[]>([]);
  const [routeOptions, setRouteOptions] = useState<RouteDTO[]>([]);
  const [workerOptions, setWorkerOptions] = useState<ProxyWorkerDTO[]>([]);

  // 选择器选项映射（AntD v5 推荐使用 options 属性）
  const protocolSelectOptions = useMemo(() => ([
    { value: ProtocolType.SOCKS5, label: PROTOCOL_TYPE_LABELS[ProtocolType.SOCKS5] },
    { value: ProtocolType.HTTPS_CONNECT, label: PROTOCOL_TYPE_LABELS[ProtocolType.HTTPS_CONNECT] },
    { value: ProtocolType.SS, label: PROTOCOL_TYPE_LABELS[ProtocolType.SS] },
    { value: ProtocolType.TP_PROXY, label: PROTOCOL_TYPE_LABELS[ProtocolType.TP_PROXY] },
    { value: ProtocolType.DNS_SERVER, label: PROTOCOL_TYPE_LABELS[ProtocolType.DNS_SERVER] },
    { value: ProtocolType.TLS_SNI_TCP, label: PROTOCOL_TYPE_LABELS[ProtocolType.TLS_SNI_TCP] },
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
    userOptions.map(u => ({
      value: u.id,
      label: u.status === 1 ? u.username : `${u.username}（已禁用）`,
      disabled: u.status !== 1,
    }))
  ), [userOptions]);

  const routeSelectOptions = useMemo(() => (
    routeOptions.map(r => ({
      value: r.id,
      label: r.status === 1 ? r.name : `${r.name}（已禁用）`,
      disabled: r.status !== 1,
    }))
  ), [routeOptions]);

  const workerSelectOptions = useMemo(() => workerOptions.map((worker) => ({
    value: worker.workerId,
    label: worker.enabled ? worker.displayName : `${worker.displayName}（已禁用）`,
    disabled: !worker.enabled,
  })), [workerOptions]);

  // Shadowsocks 加密算法选项
  const ssMethodSelectOptions = useMemo(() => PROXY_ENC_ALGO_OPTIONS, []);

  useEffect(() => {
    let current = true;
    setTrafficMap({});
    setTrafficErrors([]);
    const fetchTraffic = async () => {
      const results = await Promise.all(state.inbounds.map(async (inbound) => {
        try {
          const data = trafficSource === 'v1'
            ? await apiService.getInboundMonthlyTrafficV1(inbound.id, inbound.workerId)
            : await apiService.getInboundMonthlyTraffic(inbound.id);
          return { id: inbound.id, data };
        } catch (e) {
          console.warn('加载入站流量失败', inbound.id, e);
          return { id: inbound.id, data: undefined };
        }
      }));
      if (!current) return;
      const next: Record<number, InboundTrafficStats | InboundMonthlyTrafficV1> = {};
      results.forEach(item => { if (item.data) next[item.id] = item.data; });
      setTrafficMap(next);
      setTrafficErrors(results.filter(item => !item.data).map(item => item.id));
    };
    void fetchTraffic();
    return () => { current = false; };
  }, [state.inbounds, trafficSource]);

  const loadUsersAndRoutes = useCallback(async () => {
    try {
      const usersPage = await apiService.getUsers({ page: 1, pageSize: 200, sortBy: 'updatedAt', sortDir: 'desc' });
      setUserOptions(usersPage.items || []);
    } catch (e) {
      console.warn('加载用户失败，可能后端未启动，使用空列表');
      setUserOptions([]);
    }
    try {
      setWorkerOptions(await apiService.getWorkers());
    } catch (e) {
      console.warn('加载代理节点失败', e);
      setWorkerOptions([]);
    }
    try {
      const routesPage = await apiService.getRoutes({ page: 1, pageSize: 1000, sortBy: 'updatedAt', sortDir: 'desc' });
      setRouteOptions(routesPage.items || []);
    } catch (e) {
      console.warn('加载路由失败，使用空列表');
      setRouteOptions([]);
    }
  }, []);

  const ensureBindingOptionsLoaded = useCallback(async (bindings: InboundRouteBinding[]) => {
    const normalizedBindings = bindings.map((binding) => ({
      userIds: (binding.userIds ?? []).filter((id): id is number => typeof id === 'number'),
      routeIds: (binding.routeIds ?? []).filter((id): id is number => typeof id === 'number'),
    }));

    const requiredUserIds = Array.from(new Set(normalizedBindings.flatMap((binding) => binding.userIds)));
    const requiredRouteIds = Array.from(new Set(normalizedBindings.flatMap((binding) => binding.routeIds)));

    const existingUserIds = new Set(userOptions.map((user) => user.id));
    const existingRouteIds = new Set(routeOptions.map((route) => route.id));

    const missingUserIds = requiredUserIds.filter((id) => !existingUserIds.has(id));
    const missingRouteIds = requiredRouteIds.filter((id) => !existingRouteIds.has(id));

    if (!missingUserIds.length && !missingRouteIds.length) {
      return;
    }

    const [missingUsers, missingRoutes] = await Promise.all([
      Promise.all(
        missingUserIds.map(async (id) => {
          try {
            return await apiService.getUserById(id);
          } catch (error) {
            console.warn('加载绑定用户失败', id, error);
            return null;
          }
        })
      ),
      Promise.all(
        missingRouteIds.map(async (id) => {
          try {
            return await apiService.getRouteById(id);
          } catch (error) {
            console.warn('加载绑定路由失败', id, error);
            return null;
          }
        })
      ),
    ]);

    if (missingUsers.some(Boolean)) {
      setUserOptions((prev) => {
        const merged = new Map(prev.map((user) => [user.id, user] as const));
        missingUsers.forEach((user) => {
          if (user) {
            merged.set(user.id, user);
          }
        });
        return Array.from(merged.values());
      });
    }

    if (missingRoutes.some(Boolean)) {
      setRouteOptions((prev) => {
        const merged = new Map(prev.map((route) => [route.id, route] as const));
        missingRoutes.forEach((route) => {
          if (route) {
            merged.set(route.id, route);
          }
        });
        return Array.from(merged.values());
      });
    }
  }, [routeOptions, userOptions]);

  const openEditModal = useCallback(async (record: InboundConfigDTO) => {
    const normalizedRecord = normalizeInboundConfig(record);
    await ensureBindingOptionsLoaded(normalizedRecord.inboundRouteBindings ?? []);
    editForm.setFieldsValue({
      workerId: normalizedRecord.workerId,
      fallbackRouteId: normalizedRecord.fallbackRouteId,
      anonymousUserId: normalizedRecord.anonymousUserId,
      name: normalizedRecord.name,
      protocol: normalizedRecord.protocol,
      listenIp: normalizedRecord.listenIp,
      port: normalizedRecord.port,
      tlsEnabled: normalizedRecord.tlsEnabled,
      fakeIpEnabled: normalizedRecord.fakeIpEnabled ?? false,
      ssMethod: normalizedRecord.ssMethod,
      inboundRouteBindings: normalizedRecord.inboundRouteBindings,
      status: normalizedRecord.status,
      notes: normalizedRecord.notes,
    });
    setEditingItem(normalizedRecord);
    setEditVisible(true);
  }, [editForm, ensureBindingOptionsLoaded]);

  const loadInbounds = useCallback(async () => {
    setState(prev => ({ ...prev, loading: true }));
    try {
      const res = await apiService.getInbounds({
        page: state.currentPage,
        pageSize: state.pageSize,
        sortBy: state.sortBy,
        sortDir: state.sortDir,
        protocol: state.protocolFilter,
        status: state.statusFilter,
        tlsEnabled: state.tlsFilter,
        port: state.searchPort,
        workerId: state.workerFilter,
      });
      const normalizedInbounds = (res.items || []).map(normalizeInboundConfig);
      setState(prev => ({
        ...prev,
        inbounds: normalizedInbounds,
        total: res.total,
        loading: false,
      }));
    } catch (error) {
      console.error('加载入站配置失败:', error);
      setState(prev => ({ ...prev, loading: false }));
    }
  }, [state.currentPage, state.pageSize, state.sortBy, state.sortDir, state.protocolFilter, state.statusFilter, state.tlsFilter, state.searchPort, state.workerFilter]);

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
        tlsEnabled: supportsTls(values.protocol) ? values.tlsEnabled : false,
        sniffEnabled: true, // 默认开启嗅探以兼容后端字段
        fakeIpEnabled: values.protocol === ProtocolType.DNS_SERVER && Boolean(values.fakeIpEnabled),
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
        tlsEnabled: supportsTls(values.protocol) ? values.tlsEnabled : false,
        sniffEnabled: editingItem.sniffEnabled ?? true, // 编辑时保留原值（默认开启）
        fakeIpEnabled: values.protocol === ProtocolType.DNS_SERVER && Boolean(values.fakeIpEnabled),
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

  const desktopColumns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '节点', dataIndex: 'workerId', key: 'workerId' },
    { 
      title: '协议', 
      dataIndex: 'protocol', 
      key: 'protocol',
      render: (v: ProtocolType) => <Tag color="blue">{PROTOCOL_TYPE_LABELS[v]}</Tag>
    },
    { title: '监听IP', dataIndex: 'listenIp', key: 'listenIp' },
    { title: '端口', dataIndex: 'port', key: 'port' },
    {
      title: 'TLS',
      dataIndex: 'tlsEnabled',
      key: 'tlsEnabled',
      render: (v: boolean, record: InboundConfigDTO) => (
        supportsTls(record.protocol)
          ? (v ? <Tag color="green">启用</Tag> : <Tag color="default">关闭</Tag>)
          : '-'
      ),
    },
    { title: '本月流量(上/下行)', key: 'traffic', render: (_: any, record: InboundConfigDTO) => {
      if (trafficErrors.includes(record.id)) return <Typography.Text type="warning">查询失败</Typography.Text>;
      const t = trafficMap[record.id];
      if (!t) return '加载中';
      if (!('traffic' in t)) return `${formatBytes(t.bytesIn || 0)} / ${formatBytes(t.bytesOut || 0)}`;
      const detail = <div>
        <div>{t.period} · {t.timeZone} · 上行 / 下行</div>
        {(Object.keys(trafficCategoryLabels) as TrafficCategory[]).map(category => <div key={category}>
          {trafficCategoryLabels[category]}：{formatBytes(Number(t.categories[category].upload))} / {formatBytes(Number(t.categories[category].download))}
        </div>)}
        <div>总量：{formatBytes(Number(t.traffic.total))}</div>
        <div>上行 {t.traffic.upload} 字节 / 下行 {t.traffic.download} 字节</div>
      </div>;
      return <Tooltip title={detail}>
        <span>{formatBytes(Number(t.traffic.upload))} / {formatBytes(Number(t.traffic.download))}</span>
      </Tooltip>;
    }},
    { title: '状态', dataIndex: 'status', key: 'status', render: (s: number) => s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag> },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: InboundConfigDTO) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => { void openEditModal(record); }}>编辑</Button>
          <Popconfirm title={`确定删除入站配置「${record.name}」？`} onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  const mobileColumns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: InboundConfigDTO) => (
        <Space direction="vertical" size={0}>
          <span>{name}</span>
          <Typography.Text type="secondary" className="mobile-row-meta">
            {record.listenIp}:{record.port}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '协议',
      dataIndex: 'protocol',
      key: 'protocol',
      width: 92,
      render: (v: ProtocolType) => <Tag color="blue">{PROTOCOL_TYPE_LABELS[v]}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 76,
      render: (s: number) => s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 116,
      render: (_: any, record: InboundConfigDTO) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => { void openEditModal(record); }}>编辑</Button>
          <Popconfirm title={`确定删除入站配置「${record.name}」？`} onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const columns = isMobile ? mobileColumns : desktopColumns;

  const stats = useMemo(() => {
    const total = state.inbounds.length;
    const enabled = state.inbounds.filter(i => i.status === 1).length;
    const tlsOn = state.inbounds.filter(i => supportsTls(i.protocol) && i.tlsEnabled).length;
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
                placeholder="节点过滤"
                allowClear
                style={{ width: 180 }}
                suffixIcon={<FilterOutlined />}
                options={workerSelectOptions}
                onChange={(v) => setState(prev => ({ ...prev, workerFilter: v as string, currentPage: 1 }))}
              />
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

      <Space style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">流量统计</Typography.Text>
        <Select aria-label="流量统计来源" value={trafficSource} onChange={setTrafficSource} style={{ width: 150 }}
          options={[{ value: 'v1', label: '新日志' }, { value: 'legacy', label: '旧日志' }]} />
      </Space>

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
        <Form form={createForm} layout="vertical" initialValues={{ protocol: ProtocolType.SOCKS5, tlsEnabled: false, fakeIpEnabled: false, status: 1, inboundRouteBindings: [{ userIds: [], routeIds: [] }] }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="workerId" label="代理节点" rules={[{ required: true }]}><Select options={workerSelectOptions} /></Form.Item></Col>
            <Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="配置名称" /></Form.Item></Col>
            <Col span={12}><Form.Item name="protocol" label="协议" rules={[{ required: true }]}> 
              <Select placeholder="选择协议" options={protocolSelectOptions} />
            </Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="anonymousUserId" label="匿名用户"><Select allowClear options={userSelectOptions} /></Form.Item></Col>
            <Col span={12}><Form.Item name="fallbackRouteId" label="兜底路由"><Select allowClear options={routeSelectOptions} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="listenIp" label="监听IP" rules={[{ required: true }]}><Input placeholder="0.0.0.0" /></Form.Item></Col>
            <Col span={12}><Form.Item name="port" label="端口" rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            {supportsTls(createProtocolWatch) && (
              <Col span={12}><Form.Item name="tlsEnabled" label="启用TLS" valuePropName="checked"><Switch /></Form.Item></Col>
            )}
            {createProtocolWatch === ProtocolType.DNS_SERVER && (
              <Col span={12}><Form.Item name="fakeIpEnabled" label="启用 Fake IP" valuePropName="checked"><Switch /></Form.Item></Col>
            )}
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}> 
              <Select options={statusSelectOptions} />
            </Form.Item></Col>
          </Row>
          {createProtocolWatch === ProtocolType.DNS_SERVER && (
            <Typography.Paragraph type="secondary">{fakeIpHint(createWorkerWatch)}</Typography.Paragraph>
          )}
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
                      <Col span={12}>
                        <Form.Item name={['inboundRouteBindings', 0, 'routeIds']} label="路由顺序" rules={routeOrderRules}>
                          <RouteOrderEditor routeOptions={routeSelectOptions} />
                        </Form.Item>
                      </Col>
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
                            <Col span={12}>
                              <Form.Item name={[name, 'routeIds']} label="路由顺序" rules={routeOrderRules}>
                                <RouteOrderEditor routeOptions={routeSelectOptions} />
                              </Form.Item>
                            </Col>
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
        forceRender
        onCancel={() => { setEditVisible(false); setEditingItem(null); }}
        onOk={handleUpdate}
        okText="保存"
      >
        <Form form={editForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}><Form.Item name="workerId" label="代理节点" rules={[{ required: true }]}><Select options={workerSelectOptions} /></Form.Item></Col>
            <Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="protocol" label="协议" rules={[{ required: true }]}> 
              <Select disabled options={protocolSelectOptions} />
            </Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="anonymousUserId" label="匿名用户"><Select allowClear options={userSelectOptions} /></Form.Item></Col>
            <Col span={12}><Form.Item name="fallbackRouteId" label="兜底路由"><Select allowClear options={routeSelectOptions} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="listenIp" label="监听IP" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="port" label="端口" rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={16}>
            {supportsTls(editProtocolWatch) && (
              <Col span={12}><Form.Item name="tlsEnabled" label="启用TLS" valuePropName="checked"><Switch /></Form.Item></Col>
            )}
            {editProtocolWatch === ProtocolType.DNS_SERVER && (
              <Col span={12}><Form.Item name="fakeIpEnabled" label="启用 Fake IP" valuePropName="checked"><Switch /></Form.Item></Col>
            )}
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}> 
              <Select options={statusSelectOptions} />
            </Form.Item></Col>
          </Row>
          {editProtocolWatch === ProtocolType.DNS_SERVER && (
            <Typography.Paragraph type="secondary">{fakeIpHint(editWorkerWatch)}</Typography.Paragraph>
          )}

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
                      <Col span={12}>
                        <Form.Item name={['inboundRouteBindings', 0, 'routeIds']} label="路由顺序" rules={routeOrderRules}>
                          <RouteOrderEditor routeOptions={routeSelectOptions} />
                        </Form.Item>
                      </Col>
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
                            <Col span={12}>
                              <Form.Item name={[name, 'routeIds']} label="路由顺序" rules={routeOrderRules}>
                                <RouteOrderEditor routeOptions={routeSelectOptions} />
                              </Form.Item>
                            </Col>
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
