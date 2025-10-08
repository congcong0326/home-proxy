import React, { useState } from 'react';
import { Layout, Menu, Breadcrumb, Typography, Button, Avatar, Dropdown, Space } from 'antd';
import { AreaChartOutlined, UserOutlined, LogoutOutlined, GlobalOutlined, MenuFoldOutlined, MenuUnfoldOutlined, BarChartOutlined, HeatMapOutlined, FileSearchOutlined } from '@ant-design/icons';
import { useNavigate, Outlet, useLocation } from 'react-router-dom';
import { useSelector } from 'react-redux';
import { RootState, useAppDispatch } from '../store';
import { logoutAsync } from '../store/authSlice';
import './ProxyConfig.css';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

const AccessOverview: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const dispatch = useAppDispatch();
  const { user } = useSelector((state: RootState) => state.auth);

  const menuItems = [
    {
      key: 'access-overview',
      icon: <AreaChartOutlined />,
      label: '访问概览',
      children: [
        { key: '/config/overview/log-audit', icon: <FileSearchOutlined />, label: '日志审计' },
        { key: '/config/overview/aggregate', icon: <AreaChartOutlined />, label: '聚合分析' },
        { key: '/config/overview/geo', icon: <GlobalOutlined />, label: '地理位置分布' },
      ],
    },
  ];

  const handleMenuClick = (key: string) => {
    navigate(key);
  };

  const handleUserMenuClick = async ({ key }: { key: string }) => {
    switch (key) {
      case 'changePassword':
        navigate('/change-password');
        break;
      case 'logout':
        try {
          await dispatch(logoutAsync()).unwrap();
        } catch (error) {
          console.error('Logout failed:', error);
        }
        navigate('/login');
        break;
      default:
        break;
    }
  };

  const userMenu = {
    items: [
      { key: 'changePassword', icon: <UserOutlined />, label: '修改密码' },
      { type: 'divider' as const },
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
    ],
    onClick: handleUserMenuClick,
  };

  const getBreadcrumbItems = () => {
    let currentItem: any = null;
    for (const item of menuItems) {
      if (item.children) {
        currentItem = item.children.find(child => child.key === location.pathname);
        if (currentItem) break;
      }
    }
    return [
      { title: '访问概览' },
      { title: currentItem ? currentItem.label : '未知页面' },
    ];
  };

  return (
    <Layout className="proxy-config-layout">
      <Sider trigger={null} collapsible collapsed={collapsed} className="proxy-config-sider" width={250}>
        <div className="proxy-config-logo">
          <div className="logo-icon">
            <AreaChartOutlined />
          </div>
          {!collapsed && (
            <div className="logo-text">
              <Title level={4} className="logo-title">NAS代理</Title>
              <Text className="logo-subtitle">访问概览</Text>
            </div>
          )}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          defaultOpenKeys={['access-overview']}
          className="proxy-config-menu"
          items={menuItems}
          onClick={({ key }) => handleMenuClick(key)}
        />
      </Sider>

      <Layout className="proxy-config-main">
        <Header className="proxy-config-header">
          <div className="header-left">
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              className="collapse-btn"
            />
            <Breadcrumb className="header-breadcrumb" items={getBreadcrumbItems()} />
          </div>
          <div className="header-right">
            <Space size="middle">
              <Text className="welcome-text">欢迎，{user?.username || '管理员'}</Text>
              <Dropdown menu={userMenu} placement="bottomRight">
                <Button type="text" className="user-btn">
                  <Space>
                    <Avatar size="small" icon={<UserOutlined />} />
                    <span>{user?.username || '管理员'}</span>
                  </Space>
                </Button>
              </Dropdown>
            </Space>
          </div>
        </Header>

        <Content className="proxy-config-content">
          <div className="content-wrapper">
            {location.pathname === '/config/overview' ? (
              <div className="config-overview">
                <Title level={2} className="overview-title">访问概览</Title>
                <Text className="overview-description">选择左侧菜单项查看访问统计与日志</Text>
              </div>
            ) : (
              <Outlet />
            )}
          </div>
        </Content>
      </Layout>
    </Layout>
  );
};

export default AccessOverview;