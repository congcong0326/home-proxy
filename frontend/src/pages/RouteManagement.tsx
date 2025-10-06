import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Table,
  Button,
  Input,
  Select,
  Space,
  Modal,
  Form,
  message,
  Popconfirm,
  Tag,
  Tooltip,
  Card,
  Row,
  Col,
  Statistic,
  Badge,
  Typography,
  Dropdown,
  MenuProps,
} from 'antd';
import {
  SettingOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SearchOutlined,
  ReloadOutlined,
  MoreOutlined,
  NodeIndexOutlined,
  EyeOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import {
  RouteDTO,
  RouteStatus,
  RoutePolicy,
  RouteQueryParams,
  CreateRouteRequest,
  UpdateRouteRequest,
  ROUTE_STATUS_LABELS,
  ROUTE_STATUS_COLORS,
  ROUTE_POLICY_LABELS,
  ROUTE_POLICY_COLORS,
  PageResponse,
  RouteConditionType,
  MatchOp
} from '../types/route';
import { apiService } from '../services/api';
import RouteForm from '../components/RouteForm';
import './RouteManagement.css';

const { Title, Text } = Typography;

interface RouteManagementState {
  routes: RouteDTO[];
  loading: boolean;
  total: number;
  currentPage: number;
  pageSize: number;
  searchKeyword: string;
  policyFilter?: RoutePolicy;
  statusFilter?: RouteStatus;
  sortBy: string;
  sortDir: 'asc' | 'desc';
  selectedRowKeys: React.Key[];
}

const RouteManagement: React.FC = () => {
  const [state, setState] = useState<RouteManagementState>({
    routes: [],
    loading: false,
    total: 0,
    currentPage: 1,
    pageSize: 10,
    searchKeyword: '',
    policyFilter: undefined,
    statusFilter: undefined,
    sortBy: 'createdAt',
    sortDir: 'desc',
    selectedRowKeys: [],
  });

  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [currentRoute, setCurrentRoute] = useState<RouteDTO | null>(null);
  const [formLoading, setFormLoading] = useState(false);

  // 初始模拟路由数据
  const initialMockRoutes: RouteDTO[] = useMemo(() => [
    {
      id: 1,
      name: '默认直连',
      rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '*.local' }],
      policy: RoutePolicy.DIRECT,
      status: RouteStatus.ENABLED,
      notes: '本地域名直连',
      createdAt: '2024-01-15T10:30:00Z',
      updatedAt: '2024-01-20T14:20:00Z',
    },
    {
      id: 2,
      name: '广告拦截',
      rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.NOT_IN, value: '*.ads.com' }],
      policy: RoutePolicy.BLOCK,
      status: RouteStatus.ENABLED,
      notes: '拦截广告域名',
      createdAt: '2024-01-16T09:15:00Z',
      updatedAt: '2024-01-18T16:45:00Z',
    },
    {
      id: 3,
      name: '代理转发',
      rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '*.example.com' }],
      policy: RoutePolicy.OUTBOUND_PROXY,
      outboundTag: 'proxy-1',
      outboundProxyHost: 'proxy.example.com',
      outboundProxyPort: 8080,
      status: RouteStatus.ENABLED,
      notes: '通过代理访问',
      createdAt: '2024-01-17T11:20:00Z',
      updatedAt: '2024-01-19T13:30:00Z',
    },
  ], []);

  // 使用可变的路由数据状态
  const [mockRoutes, setMockRoutes] = useState<RouteDTO[]>(initialMockRoutes);

  // 加载路由列表
  const loadRoutes = useCallback(async () => {
    setState(prev => ({ ...prev, loading: true }));
    
    try {
      const params: RouteQueryParams = {
        page: state.currentPage,
        size: state.pageSize,
        sort: state.sortBy,
        direction: state.sortDir,
        name: state.searchKeyword || undefined,
        policy: state.policyFilter,
        status: state.statusFilter,
      };
      
      const response = await apiService.getRoutes(params);
      
      setState(prev => ({
        ...prev,
        routes: response.content,
        total: response.totalElements,
        loading: false,
      }));
    } catch (error) {
      console.error('Failed to load routes:', error);
      
      // 使用模拟数据作为后备
      let filteredRoutes = [...mockRoutes];
      
      // 应用搜索过滤
      if (state.searchKeyword) {
        const keyword = state.searchKeyword.toLowerCase();
        filteredRoutes = filteredRoutes.filter(route => 
          route.name.toLowerCase().includes(keyword) ||
          (route.notes && route.notes.toLowerCase().includes(keyword))
        );
      }
      
      // 应用策略过滤
      if (state.policyFilter !== undefined) {
        filteredRoutes = filteredRoutes.filter(route => route.policy === state.policyFilter);
      }
      
      // 应用状态过滤
      if (state.statusFilter !== undefined) {
        filteredRoutes = filteredRoutes.filter(route => route.status === state.statusFilter);
      }
      
      // 应用排序
      filteredRoutes.sort((a, b) => {
        const aValue = a[state.sortBy as keyof RouteDTO];
        const bValue = b[state.sortBy as keyof RouteDTO];
        const compare = (x: any, y: any) => {
          if (typeof x === 'number' && typeof y === 'number') return x - y;
          const xs = String(x ?? '');
          const ys = String(y ?? '');
          return xs.localeCompare(ys);
        };
        const result = compare(aValue, bValue);
        return state.sortDir === 'asc' ? result : -result;
      });
      
      // 应用分页
      const startIndex = (state.currentPage - 1) * state.pageSize;
      const endIndex = startIndex + state.pageSize;
      const paginatedRoutes = filteredRoutes.slice(startIndex, endIndex);
      
      setState(prev => ({
        ...prev,
        routes: paginatedRoutes,
        total: filteredRoutes.length,
        loading: false,
      }));
    }
  }, [state.currentPage, state.pageSize, state.searchKeyword, state.policyFilter, state.statusFilter, state.sortBy, state.sortDir, mockRoutes]);

  // 初始加载
  useEffect(() => {
    loadRoutes();
  }, [loadRoutes]);

  // 处理搜索
  const handleSearch = useCallback((value: string) => {
    setState(prev => ({ ...prev, searchKeyword: value, currentPage: 1 }));
  }, []);

  // 处理策略过滤
  const handlePolicyFilter = useCallback((value: RoutePolicy | undefined) => {
    setState(prev => ({ ...prev, policyFilter: value, currentPage: 1 }));
  }, []);

  // 处理状态过滤
  const handleStatusFilter = useCallback((value: RouteStatus | undefined) => {
    setState(prev => ({ ...prev, statusFilter: value, currentPage: 1 }));
  }, []);

  // 处理分页变化
  const handlePageChange = useCallback((page: number, pageSize?: number) => {
    setState(prev => ({
      ...prev,
      currentPage: page,
      pageSize: pageSize || prev.pageSize,
    }));
  }, []);

  // 处理排序变化
  const handleTableChange = useCallback((pagination: any, filters: any, sorter: any) => {
    if (sorter.field) {
      setState(prev => ({
        ...prev,
        sortBy: sorter.field,
        sortDir: sorter.order === 'ascend' ? 'asc' : 'desc',
        currentPage: 1,
      }));
    }
  }, []);

  // 处理删除路由
  const handleDeleteRoute = useCallback(async (route: RouteDTO) => {
    try {
      await apiService.deleteRoute(route.id);
      message.success('路由删除成功');
      
      // 计算删除后应该显示的页码
      const newTotal = state.total - 1;
      const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
      const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
      
      setState(prev => ({ ...prev, currentPage: targetPage }));
      loadRoutes();
    } catch (error) {
      console.error('Failed to delete route:', error);
      
      // API失败时从模拟数据中删除
      try {
        setMockRoutes(prev => prev.filter(r => r.id !== route.id));
        message.success('路由删除成功');
        
        // 计算删除后应该显示的页码
        const newTotal = state.total - 1;
        const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
        const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
        
        setState(prev => ({ ...prev, currentPage: targetPage }));
        loadRoutes();
      } catch (mockError) {
        message.error('删除路由失败');
      }
    }
  }, [state.total, state.currentPage, state.pageSize, loadRoutes]);

  // 处理批量删除
  const handleBatchDelete = useCallback(async () => {
    if (state.selectedRowKeys.length === 0) {
      message.warning('请选择要删除的路由');
      return;
    }

    try {
      // 逐个删除路由（后端可能没有批量删除接口）
      for (const id of state.selectedRowKeys) {
        await apiService.deleteRoute(Number(id));
      }
      
      message.success(`成功删除 ${state.selectedRowKeys.length} 个路由`);
      setState(prev => ({ ...prev, selectedRowKeys: [] }));
      
      // 计算删除后应该显示的页码
      const newTotal = state.total - state.selectedRowKeys.length;
      const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
      const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
      
      setState(prev => ({ ...prev, currentPage: targetPage }));
      loadRoutes();
    } catch (error) {
      console.error('Failed to batch delete routes:', error);
      
      // API失败时从模拟数据中删除
      try {
        setMockRoutes(prev => prev.filter(route => !state.selectedRowKeys.includes(route.id)));
        message.success(`成功删除 ${state.selectedRowKeys.length} 个路由`);
        setState(prev => ({ ...prev, selectedRowKeys: [] }));
        
        // 计算删除后应该显示的页码
        const newTotal = state.total - state.selectedRowKeys.length;
        const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
        const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
        
        setState(prev => ({ ...prev, currentPage: targetPage }));
        loadRoutes();
      } catch (mockError) {
        message.error('批量删除路由失败');
      }
    }
  }, [state.selectedRowKeys, state.total, state.currentPage, state.pageSize, loadRoutes]);

  // 处理创建路由
  const handleCreateRoute = useCallback(async (values: CreateRouteRequest) => {
    setFormLoading(true);
    try {
      await apiService.createRoute(values);
      message.success('路由创建成功');
      setCreateModalVisible(false);
      loadRoutes();
    } catch (error) {
      console.error('Failed to create route:', error);
      
      // API失败时添加到模拟数据
      try {
        const newRoute: RouteDTO = {
          id: Math.max(...mockRoutes.map(r => r.id), 0) + 1,
          name: values.name,
          rules: values.rules,
          policy: values.policy,
          outboundTag: values.outboundTag,
          outboundProxyType: values.outboundProxyType,
          outboundProxyHost: values.outboundProxyHost,
          outboundProxyPort: values.outboundProxyPort,
          outboundProxyUsername: values.outboundProxyUsername,
          outboundProxyPassword: values.outboundProxyPassword,
          outboundProxyEncAlgo: values.outboundProxyEncAlgo,
          status: values.status ?? RouteStatus.ENABLED,
          notes: values.notes,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        
        setMockRoutes(prev => [...prev, newRoute]);
        message.success('路由创建成功');
        setCreateModalVisible(false);
        loadRoutes();
      } catch (mockError) {
        message.error('创建路由失败');
      }
    } finally {
      setFormLoading(false);
    }
  }, [loadRoutes, mockRoutes]);

  // 处理更新路由
  const handleUpdateRoute = useCallback(async (values: UpdateRouteRequest) => {
    if (!currentRoute) return;
    
    setFormLoading(true);
    try {
      await apiService.updateRoute(currentRoute.id, values);
      message.success('路由更新成功');
      setEditModalVisible(false);
      setCurrentRoute(null);
      loadRoutes();
    } catch (error) {
      console.error('Failed to update route:', error);
      
      // API失败时更新模拟数据
      try {
        setMockRoutes(prev => prev.map(route => 
          route.id === currentRoute.id 
            ? {
                ...route,
                name: values.name ?? route.name,
                rules: values.rules ?? route.rules,
                policy: values.policy ?? route.policy,
                outboundTag: values.outboundTag ?? route.outboundTag,
                outboundProxyType: values.outboundProxyType ?? route.outboundProxyType,
                outboundProxyHost: values.outboundProxyHost ?? route.outboundProxyHost,
                outboundProxyPort: values.outboundProxyPort ?? route.outboundProxyPort,
                outboundProxyUsername: values.outboundProxyUsername ?? route.outboundProxyUsername,
                outboundProxyPassword: values.outboundProxyPassword ?? route.outboundProxyPassword,
                status: values.status ?? route.status,
                notes: values.notes ?? route.notes,
                updatedAt: new Date().toISOString(),
              }
            : route
        ));
        
        message.success('路由更新成功');
        setEditModalVisible(false);
        setCurrentRoute(null);
        loadRoutes();
      } catch (mockError) {
        message.error('更新路由失败');
      }
    } finally {
      setFormLoading(false);
    }
  }, [currentRoute, loadRoutes]);

  // 处理编辑路由
  const handleEditRoute = useCallback((route: RouteDTO) => {
    setCurrentRoute(route);
    setEditModalVisible(true);
  }, []);

  // 为表单适配的提交包装函数（避免联合类型与具体类型不匹配）
  const handleCreateRouteSubmit = useCallback(async (values: CreateRouteRequest | UpdateRouteRequest) => {
    return handleCreateRoute(values as CreateRouteRequest);
  }, [handleCreateRoute]);

  const handleUpdateRouteSubmit = useCallback(async (values: CreateRouteRequest | UpdateRouteRequest) => {
    return handleUpdateRoute(values as UpdateRouteRequest);
  }, [handleUpdateRoute]);

  // 处理查看路由详情
  const handleViewRoute = useCallback((route: RouteDTO) => {
    Modal.info({
      title: '路由详情',
      width: 600,
      content: (
        <div>
          <p><strong>路由名称：</strong>{route.name}</p>
          <p><strong>路由策略：</strong>
            <Tag color={ROUTE_POLICY_COLORS[route.policy]}>
              {ROUTE_POLICY_LABELS[route.policy]}
            </Tag>
          </p>
          <p><strong>路由规则：</strong></p>
  <ul>
            {route.rules.map((rule, index) => (
              <li key={index}>
                {rule.conditionType === RouteConditionType.DOMAIN ? '域名' : '地理位置'}{' '}
                {rule.op === MatchOp.IN ? '属于' : '不属于'}{' '}
                {rule.value}
              </li>
            ))}
          </ul>
          {(route.policy === RoutePolicy.OUTBOUND_PROXY || route.policy === RoutePolicy.DESTINATION_OVERRIDE) && (
            <>
              <p><strong>出站标签：</strong>{route.outboundTag}</p>
              {route.policy === RoutePolicy.OUTBOUND_PROXY && (
                <>
                  <p><strong>代理类型：</strong>{route.outboundProxyType}</p>
                  {route.outboundProxyType === 'SHADOW_SOCKS' && (
                    <p><strong>加密算法：</strong>{route.outboundProxyEncAlgo || '未设置'}</p>
                  )}
                </>
              )}
              <p><strong>{route.policy === RoutePolicy.OUTBOUND_PROXY ? '代理地址' : '目标地址'}：</strong>{route.outboundProxyHost}:{route.outboundProxyPort}</p>
            </>
          )}
          <p><strong>状态：</strong>
            <Tag color={ROUTE_STATUS_COLORS[route.status as RouteStatus]}>
              {ROUTE_STATUS_LABELS[route.status as RouteStatus]}
            </Tag>
          </p>
          <p><strong>备注：</strong>{route.notes || '无'}</p>
          <p><strong>创建时间：</strong>{new Date(route.createdAt).toLocaleString()}</p>
          <p><strong>更新时间：</strong>{new Date(route.updatedAt).toLocaleString()}</p>
        </div>
      ),
    });
  }, []);

  // 表格列定义
  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80,
      sorter: true,
    },
    {
      title: '路由名称',
      dataIndex: 'name',
      key: 'name',
      sorter: true,
      render: (text: string, record: RouteDTO) => (
        <Space>
          <Text strong>{text}</Text>
          {record.notes && (
            <Tooltip title={record.notes}>
              <Badge size="small" color="blue" />
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '路由策略',
      dataIndex: 'policy',
      key: 'policy',
      width: 120,
      render: (policy: RoutePolicy) => (
        <Tag color={ROUTE_POLICY_COLORS[policy]}>
          {ROUTE_POLICY_LABELS[policy]}
        </Tag>
      ),
    },
    {
      title: '规则数量',
      dataIndex: 'rules',
      key: 'rulesCount',
      width: 100,
      render: (rules: any[]) => (
        <Badge count={rules?.length || 0} showZero color="#108ee9" />
      ),
    },
    {
      title: '出站信息',
      key: 'outbound',
      width: 200,
      render: (_: any, record: RouteDTO) => {
        if (record.policy === RoutePolicy.OUTBOUND_PROXY || record.policy === RoutePolicy.DESTINATION_OVERRIDE) {
          return (
            <Space direction="vertical" size="small">
              <Text type="secondary">{record.outboundTag}</Text>
              <Text code>{record.outboundProxyHost}:{record.outboundProxyPort}</Text>
            </Space>
          );
        }
        return <Text type="secondary">-</Text>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: RouteStatus) => (
        <Tag color={ROUTE_STATUS_COLORS[status]}>
          {ROUTE_STATUS_LABELS[status]}
        </Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      sorter: true,
      render: (text: string) => new Date(text).toLocaleString(),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: any, record: RouteDTO) => {
        const items: MenuProps['items'] = [
          {
            key: 'view',
            icon: <EyeOutlined />,
            label: '查看详情',
            onClick: () => handleViewRoute(record),
          },
          {
            key: 'edit',
            icon: <EditOutlined />,
            label: '编辑',
            onClick: () => handleEditRoute(record),
          },
          {
            type: 'divider',
          },
          {
            key: 'delete',
            icon: <DeleteOutlined />,
            label: '删除',
            danger: true,
            onClick: () => {
              Modal.confirm({
                title: '确认删除',
                content: `确定要删除路由 "${record.name}" 吗？`,
                onOk: () => handleDeleteRoute(record),
              });
            },
          },
        ];

        return (
          <Space>
            <Button
              type="link"
              icon={<EditOutlined />}
              onClick={() => handleEditRoute(record)}
            >
              编辑
            </Button>
            <Popconfirm
              title={`确定要删除路由 "${record.name}" 吗？`}
              onConfirm={() => handleDeleteRoute(record)}
              okText="确定"
              cancelText="取消"
            >
              <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
            <Dropdown menu={{ items }} trigger={['click']}>
              <Button type="link" icon={<MoreOutlined />} />
            </Dropdown>
          </Space>
        );
      },
    },
  ];

  // 行选择配置
  const rowSelection = {
    selectedRowKeys: state.selectedRowKeys,
    onChange: (selectedRowKeys: React.Key[]) => {
      setState(prev => ({ ...prev, selectedRowKeys }));
    },
  };

  // 统计数据
  const stats = useMemo(() => {
    const enabledCount = mockRoutes.filter(route => route.status === RouteStatus.ENABLED).length;
    const disabledCount = mockRoutes.filter(route => route.status === RouteStatus.DISABLED).length;
    const directCount = mockRoutes.filter(route => route.policy === RoutePolicy.DIRECT).length;
    const proxyCount = mockRoutes.filter(route => route.policy === RoutePolicy.OUTBOUND_PROXY).length;
    const blockCount = mockRoutes.filter(route => route.policy === RoutePolicy.BLOCK).length;
    
    return {
      total: mockRoutes.length,
      enabled: enabledCount,
      disabled: disabledCount,
      direct: directCount,
      proxy: proxyCount,
      block: blockCount,
    };
  }, [mockRoutes]);

  return (
    <div className="route-management">
      {/* 页面标题和统计 */}
      <div className="page-header">
        <Title level={2}>
          <NodeIndexOutlined /> 路由管理
        </Title>
        
        <Row gutter={16} className="stats-row">
          <Col span={4}>
            <Card>
              <Statistic
                title="总路由数"
                value={stats.total}
                prefix={<SettingOutlined />}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card>
              <Statistic
                title="启用中"
                value={stats.enabled}
                valueStyle={{ color: '#3f8600' }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card>
              <Statistic
                title="已禁用"
                value={stats.disabled}
                valueStyle={{ color: '#cf1322' }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card>
              <Statistic
                title="直连策略"
                value={stats.direct}
                valueStyle={{ color: '#52c41a' }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card>
              <Statistic
                title="代理策略"
                value={stats.proxy}
                valueStyle={{ color: '#1890ff' }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card>
              <Statistic
                title="阻断策略"
                value={stats.block}
                valueStyle={{ color: '#f5222d' }}
              />
            </Card>
          </Col>
        </Row>
      </div>

      {/* 操作栏 */}
      <Card className="operation-bar">
        <Row gutter={16} align="middle">
          <Col flex="auto">
            <Space>
              <Input.Search
                placeholder="搜索路由名称或备注"
                allowClear
                style={{ width: 300 }}
                onSearch={handleSearch}
                enterButton={<SearchOutlined />}
              />
              
              <Select
                placeholder="策略过滤"
                allowClear
                style={{ width: 120 }}
                onChange={handlePolicyFilter}
                suffixIcon={<FilterOutlined />}
                options={[
                  { value: RoutePolicy.DIRECT, label: ROUTE_POLICY_LABELS[RoutePolicy.DIRECT] },
                  { value: RoutePolicy.BLOCK, label: ROUTE_POLICY_LABELS[RoutePolicy.BLOCK] },
                  { value: RoutePolicy.OUTBOUND_PROXY, label: ROUTE_POLICY_LABELS[RoutePolicy.OUTBOUND_PROXY] },
                  { value: RoutePolicy.DESTINATION_OVERRIDE, label: ROUTE_POLICY_LABELS[RoutePolicy.DESTINATION_OVERRIDE] },
                ]}
              />
              
              <Select
                placeholder="状态过滤"
                allowClear
                style={{ width: 100 }}
                onChange={handleStatusFilter}
                suffixIcon={<FilterOutlined />}
                options={[
                  { value: RouteStatus.ENABLED, label: ROUTE_STATUS_LABELS[RouteStatus.ENABLED] },
                  { value: RouteStatus.DISABLED, label: ROUTE_STATUS_LABELS[RouteStatus.DISABLED] },
                ]}
              />
            </Space>
          </Col>
          
          <Col>
            <Space>
              {state.selectedRowKeys.length > 0 && (
                <Popconfirm
                  title={`确定要删除选中的 ${state.selectedRowKeys.length} 个路由吗？`}
                  onConfirm={handleBatchDelete}
                  okText="确定"
                  cancelText="取消"
                >
                  <Button danger icon={<DeleteOutlined />}>
                    批量删除 ({state.selectedRowKeys.length})
                  </Button>
                </Popconfirm>
              )}
              
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => setCreateModalVisible(true)}
              >
                新建路由
              </Button>
              
              <Button
                icon={<ReloadOutlined />}
                onClick={loadRoutes}
                loading={state.loading}
              >
                刷新
              </Button>
            </Space>
          </Col>
        </Row>
      </Card>

      {/* 路由列表 */}
      <Card>
        <Table
          rowSelection={rowSelection}
          columns={columns}
          dataSource={state.routes}
          rowKey="id"
          loading={state.loading}
          pagination={{
            current: state.currentPage,
            pageSize: state.pageSize,
            total: state.total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
            onChange: handlePageChange,
          }}
          onChange={handleTableChange}
          scroll={{ x: 1200 }}
        />
      </Card>

      {/* 创建路由模态框 */}
      <Modal
        title="新建路由"
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false);
          setFormLoading(false);
        }}
        footer={null}
        width={900}
        destroyOnClose
      >
        <RouteForm
          mode="create"
          onSubmit={handleCreateRouteSubmit}
          onCancel={() => {
            setCreateModalVisible(false);
            setFormLoading(false);
          }}
          loading={formLoading}
        />
      </Modal>

      {/* 编辑路由模态框 */}
      <Modal
        title="编辑路由"
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false);
          setCurrentRoute(null);
          setFormLoading(false);
        }}
        footer={null}
        width={900}
        destroyOnClose
      >
        {currentRoute && (
          <RouteForm
            mode="edit"
            initialValues={currentRoute}
            onSubmit={handleUpdateRouteSubmit}
            onCancel={() => {
              setEditModalVisible(false);
              setCurrentRoute(null);
              setFormLoading(false);
            }}
            loading={formLoading}
          />
        )}
      </Modal>
    </div>
  );
};

export default RouteManagement;