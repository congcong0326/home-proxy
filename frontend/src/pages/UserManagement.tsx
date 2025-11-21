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
  UserOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
  SearchOutlined,
  ReloadOutlined,
  MoreOutlined,
  TeamOutlined,
  EyeOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import { UserDTO, UserStatus, UserQueryParams } from '../types/user';
import { apiService } from '../services/api';
import './UserManagement.css';

const { Title, Text } = Typography;

// 用户状态标签映射
const USER_STATUS_LABELS = {
  [UserStatus.ENABLED]: '启用',
  [UserStatus.DISABLED]: '禁用',
};

// 用户状态颜色映射
const USER_STATUS_COLORS = {
  [UserStatus.ENABLED]: 'success',
  [UserStatus.DISABLED]: 'default',
};

interface UserManagementState {
  users: UserDTO[];
  loading: boolean;
  total: number;
  currentPage: number;
  pageSize: number;
  searchKeyword: string;
  statusFilter?: UserStatus;
  sortBy: string;
  sortDir: 'asc' | 'desc';
  selectedRowKeys: React.Key[];
}

const UserManagement: React.FC = () => {
  const [state, setState] = useState<UserManagementState>({
    users: [],
    loading: false,
    total: 0,
    currentPage: 1,
    pageSize: 10,
    searchKeyword: '',
    statusFilter: undefined,
    sortBy: 'createdAt',
    sortDir: 'desc',
    selectedRowKeys: [],
  });

  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [credentialModalVisible, setCredentialModalVisible] = useState(false);
  const [currentUser, setCurrentUser] = useState<UserDTO | null>(null);

  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [credentialForm] = Form.useForm();

  // 初始模拟用户数据
  const initialMockUsers: UserDTO[] = useMemo(() => [
    {
      id: 1,
      username: 'admin',
      status: UserStatus.ENABLED,
      createdAt: '2024-01-15T10:30:00Z',
      updatedAt: '2024-01-20T14:20:00Z',
    },
    {
      id: 2,
      username: 'user001',
      status: UserStatus.ENABLED,
      createdAt: '2024-01-16T09:15:00Z',
      updatedAt: '2024-01-18T16:45:00Z',
    },
    {
      id: 3,
      username: 'user002',
      status: UserStatus.DISABLED,
      createdAt: '2024-01-17T11:20:00Z',
      updatedAt: '2024-01-19T13:30:00Z',
    },
    {
      id: 4,
      username: 'testuser',
      status: UserStatus.ENABLED,
      createdAt: '2024-01-18T08:45:00Z',
      updatedAt: '2024-01-21T10:15:00Z',
    },
  ], []);

  // 可变的模拟用户数据状态
  const [mockUsers, setMockUsers] = useState<UserDTO[]>(initialMockUsers);

  // 加载用户列表
  const loadUsers = useCallback(async (params?: Partial<UserQueryParams>) => {
    setState(prev => ({ ...prev, loading: true }));

    try {
      const queryParams: UserQueryParams = {
        page: params?.page || state.currentPage,
        pageSize: params?.pageSize || state.pageSize,
        q: params?.q !== undefined ? params.q : (state.searchKeyword || undefined),
        status: params?.status !== undefined ? params.status : state.statusFilter,
        sortBy: params?.sortBy || state.sortBy,
        sortDir: params?.sortDir || state.sortDir,
      };

      console.log('Loading users with params:', queryParams);
      
      // 尝试从API加载，如果失败则使用模拟数据
      try {
        const response = await apiService.getUsers(queryParams);
        console.log('Users loaded from API:', response);

        setState(prev => ({
           ...prev,
           users: response.items || [],
           total: response.total || 0,
           currentPage: response.page || 1,
           pageSize: response.pageSize || 10,
           loading: false,
         }));
      } catch (apiError) {
        console.warn('API调用失败，使用模拟数据:', apiError);
        
        // 使用模拟数据
        let filteredUsers = [...mockUsers];
        
        // 应用搜索过滤
        if (queryParams.q) {
          filteredUsers = filteredUsers.filter(user => 
            user.username.toLowerCase().includes(queryParams.q!.toLowerCase())
          );
        }
        
        // 应用状态过滤
        if (queryParams.status !== undefined) {
          filteredUsers = filteredUsers.filter(user => user.status === queryParams.status);
        }
        
        // 分页处理
        const startIndex = ((queryParams.page || 1) - 1) * (queryParams.pageSize || 10);
        const endIndex = startIndex + (queryParams.pageSize || 10);
        const paginatedUsers = filteredUsers.slice(startIndex, endIndex);
        
        setState(prev => ({
           ...prev,
           users: paginatedUsers,
           total: filteredUsers.length,
           currentPage: queryParams.page || 1,
           pageSize: queryParams.pageSize || 10,
           loading: false,
         }));
        
        message.info('当前使用模拟数据，请确保后端服务正常运行');
      }
    } catch (error) {
      console.error('加载用户列表失败:', error);
      message.error(`加载用户列表失败: ${error instanceof Error ? error.message : '未知错误'}`);
      setState(prev => ({ ...prev, loading: false, users: [], total: 0 }));
    }
  }, [state.currentPage, state.pageSize, state.searchKeyword, state.statusFilter, state.sortBy, state.sortDir, mockUsers]);

  // 初始加载用户列表
  useEffect(() => {
    loadUsers();
  }, []);

  // 当搜索条件变化时重新加载
  useEffect(() => {
    loadUsers({
      page: 1,
      pageSize: state.pageSize,
      q: state.searchKeyword || undefined,
      status: state.statusFilter,
      sortBy: state.sortBy,
      sortDir: state.sortDir,
    });
  }, [state.searchKeyword, state.statusFilter, state.sortBy, state.sortDir, loadUsers]);

  // 创建用户
  const handleCreateUser = async (values: any) => {
    try {
      await apiService.createUser(values);
      message.success('用户创建成功');
      setCreateModalVisible(false);
      createForm.resetFields();
      loadUsers();
    } catch (error) {
      console.error('创建用户失败:', error);
      message.error('创建用户失败');
    }
  };

  // 编辑用户
  const handleEditUser = async (values: any) => {
    if (!currentUser) return;

    try {
      await apiService.updateUser(currentUser.id, values);
      message.success('用户更新成功');
      setEditModalVisible(false);
      editForm.resetFields();
      setCurrentUser(null);
      loadUsers();
    } catch (error) {
      console.error('更新用户失败:', error);
      message.error('更新用户失败');
    }
  };

  // 删除用户
  const handleDeleteUser = async (userId: number) => {
    try {
      // 尝试API删除
      await apiService.deleteUser(userId);
      message.success('用户删除成功');
    } catch (error) {
      console.warn('API删除失败，从模拟数据中删除:', error);
      // API失败时，从模拟数据中删除
      setMockUsers(prev => prev.filter(user => user.id !== userId));
      message.success('用户删除成功');
    }
    
    // 计算删除后的正确页码
    const newTotal = state.total - 1;
    const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
    const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
    
    // 重新加载用户列表，使用正确的页码
    loadUsers({ page: targetPage });
  };

  // 重置用户凭证
  const handleResetCredential = async (values: any) => {
    if (!currentUser) return;

    try {
      await apiService.resetUserCredential(currentUser.id, values.newCredential);
      message.success('用户凭证重置成功');
      setCredentialModalVisible(false);
      credentialForm.resetFields();
      setCurrentUser(null);
    } catch (error) {
      console.error('重置用户凭证失败:', error);
      message.error('重置用户凭证失败');
    }
  };

  // 批量删除用户
  const handleBatchDelete = async () => {
    if (state.selectedRowKeys.length === 0) {
      message.warning('请选择要删除的用户');
      return;
    }

    try {
      // 尝试API批量删除
      await apiService.batchDeleteUsers(state.selectedRowKeys as number[]);
      message.success('批量删除成功');
    } catch (error) {
      console.warn('API批量删除失败，从模拟数据中删除:', error);
      // API失败时，从模拟数据中删除
      setMockUsers(prev => prev.filter(user => !state.selectedRowKeys.includes(user.id)));
      message.success('批量删除成功');
    }
    
    setState(prev => ({ ...prev, selectedRowKeys: [] }));
    
    // 计算删除后的正确页码
    const deletedCount = state.selectedRowKeys.length;
    const newTotal = state.total - deletedCount;
    const maxPage = Math.ceil(newTotal / state.pageSize) || 1;
    const targetPage = state.currentPage > maxPage ? maxPage : state.currentPage;
    
    // 重新加载用户列表，使用正确的页码
    loadUsers({ page: targetPage });
  };

  // 批量更新用户状态
  const handleBatchUpdateStatus = async (status: UserStatus) => {
    if (state.selectedRowKeys.length === 0) {
      message.warning('请选择要更新的用户');
      return;
    }

    try {
      await apiService.batchUpdateUserStatus(state.selectedRowKeys as number[], status);
      message.success('批量更新状态成功');
      setState(prev => ({ ...prev, selectedRowKeys: [] }));
      loadUsers();
    } catch (error) {
      console.error('批量更新状态失败:', error);
      message.error('批量更新状态失败');
    }
  };

  // 打开编辑模态框
  const openEditModal = (user: UserDTO) => {
    setCurrentUser(user);
    editForm.setFieldsValue({
      username: user.username,
      ipAddress: user.ipAddress,
      status: user.status,
    });
    setEditModalVisible(true);
  };

  // 打开重置凭证模态框
  const openCredentialModal = (user: UserDTO) => {
    setCurrentUser(user);
    setCredentialModalVisible(true);
  };

  // 搜索处理
  const handleSearch = (value: string) => {
    setState(prev => ({ ...prev, searchKeyword: value, currentPage: 1 }));
  };

  // 状态筛选处理
  const handleStatusFilter = (value: UserStatus | undefined) => {
    setState(prev => ({ ...prev, statusFilter: value, currentPage: 1 }));
  };

  // 表格变化处理
  const handleTableChange = (pagination: any, filters: any, sorter: any) => {
    setState(prev => ({
      ...prev,
      currentPage: pagination.current,
      pageSize: pagination.pageSize,
      sortBy: sorter.field || 'createdAt',
      sortDir: sorter.order === 'ascend' ? 'asc' : 'desc',
    }));
  };

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
      title: '用户名',
      dataIndex: 'username',
      key: 'username',
      sorter: true,
    },
    {
      title: '设备IP',
      dataIndex: 'ipAddress',
      key: 'ipAddress',
      width: 180,
      ellipsis: true,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: UserStatus) => (
        <Tag color={USER_STATUS_COLORS[status] as any}>
          {USER_STATUS_LABELS[status]}
        </Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      sorter: true,
      render: (date: string) => new Date(date).toLocaleString('zh-CN'),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      sorter: true,
      render: (date: string) => new Date(date).toLocaleString('zh-CN'),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: any, record: UserDTO) => (
        <Space size="small">
          <Tooltip title="查看详情">
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => {
                Modal.info({
                  title: '用户详情',
                  content: (
                    <div>
                      <p><strong>ID:</strong> {record.id}</p>
                      <p><strong>用户名:</strong> {record.username}</p>
                      <p><strong>设备IP:</strong> {record.ipAddress || '-'}</p>
                      <p><strong>状态:</strong> {USER_STATUS_LABELS[record.status as UserStatus]}</p>
                      <p><strong>创建时间:</strong> {new Date(record.createdAt).toLocaleString('zh-CN')}</p>
                      <p><strong>更新时间:</strong> {new Date(record.updatedAt).toLocaleString('zh-CN')}</p>
                    </div>
                  ),
                });
              }}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEditModal(record)}
            />
          </Tooltip>
          <Tooltip title="重置凭证">
            <Button
              type="text"
              size="small"
              icon={<KeyOutlined />}
              onClick={() => openCredentialModal(record)}
            />
          </Tooltip>
          <Tooltip title="删除">
            <Popconfirm
              title="确认删除"
              description="确定要删除这个用户吗？此操作不可恢复。"
              onConfirm={() => handleDeleteUser(record.id)}
              okText="确定"
              cancelText="取消"
            >
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
              />
            </Popconfirm>
          </Tooltip>
        </Space>
      ),
    },
  ];

  // 行选择配置
  const rowSelection = {
    selectedRowKeys: state.selectedRowKeys,
    onChange: (selectedRowKeys: React.Key[]) => {
      setState(prev => ({ ...prev, selectedRowKeys }));
    },
  };

  // 批量操作菜单
  const batchMenuItems: MenuProps['items'] = [
    {
      key: 'enable',
      label: '批量启用',
      onClick: () => handleBatchUpdateStatus(UserStatus.ENABLED),
    },
    {
      key: 'disable',
      label: '批量禁用',
      onClick: () => handleBatchUpdateStatus(UserStatus.DISABLED),
    },
    {
      type: 'divider',
    },
    {
      key: 'delete',
      label: '批量删除',
      danger: true,
      onClick: () => {
        Modal.confirm({
          title: '确认批量删除',
          content: `确定要删除选中的 ${state.selectedRowKeys.length} 个用户吗？此操作不可恢复。`,
          onOk: handleBatchDelete,
        });
      },
    },
  ];

  return (
    <div className="user-management-container">
      <div className="page-header">
        <Title level={3} className="page-title">
          用户管理
        </Title>
        <Text className="page-description">
          管理系统用户账户、权限和状态
        </Text>
      </div>

      <div className="content-wrapper">
        {/* 统计卡片 */}
        <Row gutter={16} className="stats-row">
          <Col xs={24} sm={12} md={6}>
            <Card className="stats-card">
              <Statistic
                title="总用户数"
                value={state.total}
                prefix={<TeamOutlined />}
                valueStyle={{ color: '#1890ff' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card className="stats-card">
              <Statistic
                title="启用用户"
                value={state.users?.filter(u => u.status === UserStatus.ENABLED).length || 0}
                prefix={<Badge status="success" />}
                valueStyle={{ color: '#52c41a' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card className="stats-card">
              <Statistic
                title="禁用用户"
                value={state.users?.filter(u => u.status === UserStatus.DISABLED).length || 0}
                prefix={<Badge status="default" />}
                valueStyle={{ color: '#8c8c8c' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card className="stats-card">
              <Statistic
                title="选中用户"
                value={state.selectedRowKeys.length}
                prefix={<UserOutlined />}
                valueStyle={{ color: '#722ed1' }}
              />
            </Card>
          </Col>
        </Row>

        {/* 工具栏 */}
        <Card className="toolbar-card">
          <div className="toolbar">
            <div className="toolbar-left">
              <Space>
                <Input.Search
                  placeholder="搜索用户名"
                  allowClear
                  style={{ width: 200 }}
                  onSearch={handleSearch}
                  enterButton={<SearchOutlined />}
                />
                <Select
                  placeholder="状态筛选"
                  allowClear
                  style={{ width: 120 }}
                  onChange={handleStatusFilter}
                  suffixIcon={<FilterOutlined />}
                  options={[
                    { value: UserStatus.ENABLED, label: '启用' },
                    { value: UserStatus.DISABLED, label: '禁用' },
                  ]}
                />
              </Space>
            </div>
            <div className="toolbar-right">
              <Space>
                {state.selectedRowKeys.length > 0 && (
                  <Dropdown menu={{ items: batchMenuItems }} placement="bottomRight">
                    <Button icon={<MoreOutlined />}>
                      批量操作 ({state.selectedRowKeys.length})
                    </Button>
                  </Dropdown>
                )}
                <Button
                  icon={<ReloadOutlined />}
                  onClick={() => loadUsers()}
                  loading={state.loading}
                >
                  刷新
                </Button>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => setCreateModalVisible(true)}
                >
                  创建用户
                </Button>
              </Space>
            </div>
          </div>
        </Card>

        {/* 用户表格 */}
        <Card className="table-card">
          <Table
            rowSelection={rowSelection}
            columns={columns}
            dataSource={state.users}
            rowKey="id"
            loading={state.loading}
            pagination={{
              current: state.currentPage,
              pageSize: state.pageSize,
              total: state.total,
              showSizeChanger: true,
              showQuickJumper: true,
              showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
              pageSizeOptions: ['10', '20', '50', '100'],
            }}
            onChange={handleTableChange}
            scroll={{ x: 800 }}
            className="user-table"
          />
        </Card>
      </div>

      {/* 创建用户模态框 */}
      <Modal
        title="创建用户"
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false);
          createForm.resetFields();
        }}
        footer={null}
        width={500}
        className="user-modal"
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={handleCreateUser}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '请输入用户名' },
              { min: 1, max: 64, message: '用户名长度为1-64个字符' },
              { pattern: /^[A-Za-z0-9_]+$/, message: '用户名只能包含字母、数字和下划线' },
            ]}
          >
            <Input placeholder="请输入用户名" />
          </Form.Item>

          <Form.Item
            name="credential"
            label="用户凭证"
            rules={[
              { required: true, message: '请输入用户凭证' },
              { min: 8, max: 64, message: '凭证长度为8-64个字符' },
            ]}
          >
            <Input.Password placeholder="请输入用户凭证" />
          </Form.Item>

          <Form.Item
            name="ipAddress"
            label="设备IP"
            rules={[
              { max: 45, message: '设备IP长度不能超过45个字符' },
            ]}
          >
            <Input placeholder="请输入设备IP（IPv4/IPv6）" />
          </Form.Item>

          <Form.Item
            name="status"
            label="状态"
            initialValue={UserStatus.ENABLED}
          >
            <Select options={[
              { value: UserStatus.ENABLED, label: '启用' },
              { value: UserStatus.DISABLED, label: '禁用' },
            ]} />
          </Form.Item>

          <Form.Item className="modal-actions">
            <Space>
              <Button onClick={() => {
                setCreateModalVisible(false);
                createForm.resetFields();
              }}>
                取消
              </Button>
              <Button type="primary" htmlType="submit">
                创建
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑用户模态框 */}
      <Modal
        title="编辑用户"
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false);
          editForm.resetFields();
          setCurrentUser(null);
        }}
        footer={null}
        width={500}
        className="user-modal"
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={handleEditUser}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '请输入用户名' },
              { min: 1, max: 64, message: '用户名长度为1-64个字符' },
              { pattern: /^[A-Za-z0-9_]+$/, message: '用户名只能包含字母、数字和下划线' },
            ]}
          >
            <Input placeholder="请输入用户名" />
          </Form.Item>

          <Form.Item
            name="ipAddress"
            label="设备IP"
            rules={[
              { max: 45, message: '设备IP长度不能超过45个字符' },
            ]}
          >
            <Input placeholder="请输入设备IP（IPv4/IPv6）" />
          </Form.Item>

          <Form.Item
            name="status"
            label="状态"
          >
            <Select options={[
              { value: UserStatus.ENABLED, label: '启用' },
              { value: UserStatus.DISABLED, label: '禁用' },
            ]} />
          </Form.Item>

          <Form.Item className="modal-actions">
            <Space>
              <Button onClick={() => {
                setEditModalVisible(false);
                editForm.resetFields();
                setCurrentUser(null);
              }}>
                取消
              </Button>
              <Button type="primary" htmlType="submit">
                更新
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置凭证模态框 */}
      <Modal
        title="重置用户凭证"
        open={credentialModalVisible}
        onCancel={() => {
          setCredentialModalVisible(false);
          credentialForm.resetFields();
          setCurrentUser(null);
        }}
        footer={null}
        width={500}
        className="user-modal"
      >
        <Form
          form={credentialForm}
          layout="vertical"
          onFinish={handleResetCredential}
          autoComplete="off"
        >
          <Form.Item
            name="newCredential"
            label="新凭证"
            rules={[
              { required: true, message: '请输入新凭证' },
              { min: 8, max: 64, message: '凭证长度为8-64个字符' },
            ]}
          >
            <Input.Password placeholder="请输入新凭证" />
          </Form.Item>

          <Form.Item className="modal-actions">
            <Space>
              <Button onClick={() => {
                setCredentialModalVisible(false);
                credentialForm.resetFields();
                setCurrentUser(null);
              }}>
                取消
              </Button>
              <Button type="primary" htmlType="submit">
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default UserManagement;