import React, { useEffect } from 'react';
import { Layout, Card, Typography, Button, Space, Avatar, Dropdown, MenuProps } from 'antd';
import { UserOutlined, LogoutOutlined, KeyOutlined, DashboardOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import { logoutAsync } from '../store/authSlice';
import './Dashboard.css';

const { Header, Content } = Layout;
const { Title, Text } = Typography;

const Dashboard: React.FC = () => {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const { user, isAuthenticated } = useAppSelector((state) => state.auth);

  // 如果未登录，重定向到登录页
  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login', { replace: true });
    }
  }, [isAuthenticated, navigate]);

  // 处理退出登录
  const handleLogout = async () => {
    await dispatch(logoutAsync());
    navigate('/login', { replace: true });
  };

  // 用户菜单项
  const userMenuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人信息',
      onClick: () => navigate('/profile'),
    },
    {
      key: 'change-password',
      icon: <KeyOutlined />,
      label: '修改密码',
      onClick: () => navigate('/change-password'),
    },
    {
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout,
    },
  ];

  if (!user) {
    return null;
  }

  return (
    <Layout className="dashboard-layout">
      <Header className="dashboard-header">
        <div className="dashboard-header-left">
          <DashboardOutlined className="dashboard-logo" />
          <Title level={4} className="dashboard-title">
            NAS代理管理后台
          </Title>
        </div>
        <div className="dashboard-header-right">
          <Space>
            <Text className="welcome-text">
              欢迎, {user.username}
            </Text>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Button type="text" className="user-button">
                <Avatar size="small" icon={<UserOutlined />} />
                <span className="username">{user.username}</span>
              </Button>
            </Dropdown>
          </Space>
        </div>
      </Header>
      
      <Content className="dashboard-content">
        <div className="dashboard-container">
          <div className="welcome-section">
            <Card className="welcome-card" variant="outlined">
              <div className="welcome-content">
                <Title level={2}>欢迎使用 NAS代理管理后台</Title>
                <Text type="secondary" className="welcome-description">
                  这是一个轻量级的多协议智能代理管理系统，支持 SOCKS5、HTTP CONNECT 和 Shadowsocks 协议。
                </Text>
                
                <div className="user-info">
                  <Title level={4}>当前用户信息</Title>
                  <div className="user-details">
                    <div className="user-detail-item">
                      <Text strong>用户名: </Text>
                      <Text>{user.username}</Text>
                    </div>
                    <div className="user-detail-item">
                      <Text strong>角色: </Text>
                      <Text>{user.roles.join(', ') || '无'}</Text>
                    </div>
                    <div className="user-detail-item">
                      <Text strong>用户ID: </Text>
                      <Text>{user.id}</Text>
                    </div>
                  </div>
                </div>

                <div className="quick-actions">
                  <Title level={4}>快速操作</Title>
                  <Space wrap>
                    <Button type="primary" onClick={() => navigate('/config/inbounds')}>
                      入站配置
                    </Button>
                    <Button onClick={() => navigate('/config/routes')}>
                      路由规则
                    </Button>
                    <Button onClick={() => navigate('/config/users')}>
                      用户管理
                    </Button>
                    <Button onClick={() => navigate('/config/rate-limits')}>
                      限流配置
                    </Button>
                  </Space>
                </div>
              </div>
            </Card>
          </div>

          <div className="status-section">
            <Card title="系统状态" className="status-card" variant="outlined">
              <div className="status-content">
                <Text type="secondary">
                  系统监控功能正在开发中，敬请期待...
                </Text>
              </div>
            </Card>
          </div>
        </div>
      </Content>
    </Layout>
  );
};

export default Dashboard;