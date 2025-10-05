import React, { useState } from 'react';
import {
  Layout,
  Menu,
  Breadcrumb,
  Typography,
  Card,
  Row,
  Col,
  Button,
  Avatar,
  Dropdown,
  Space,
  Divider
} from 'antd';
import {
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
  TeamOutlined,
  GlobalOutlined,
  NodeIndexOutlined,
  BranchesOutlined,
  ThunderboltOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons';
import { useNavigate, Outlet, useLocation } from 'react-router-dom';
import { useSelector } from 'react-redux';
import { RootState, useAppDispatch } from '../store';
import { logoutAsync } from '../store/authSlice';
import './ProxyConfig.css';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

const ProxyConfig: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const dispatch = useAppDispatch();
  const { user } = useSelector((state: RootState) => state.auth);

  // 菜单项配置 - 手风琴式结构
  const menuItems = [
    {
      key: 'proxy-config',
      icon: <SettingOutlined />,
      label: '代理配置',
      children: [
        {
          key: '/config/users',
          icon: <TeamOutlined />,
          label: '用户管理',
        },
        {
          key: '/config/inbound',
          icon: <NodeIndexOutlined />,
          label: '入站配置',
        },
        {
          key: '/config/routing',
          icon: <BranchesOutlined />,
          label: '路由规则',
        },
        {
          key: '/config/ratelimit',
          icon: <ThunderboltOutlined />,
          label: '限流设置',
        },
      ],
    },
  ];

  // 处理菜单点击
  const handleMenuClick = async (key: string) => {
    navigate(key);
  };

  // 处理用户下拉菜单点击
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

  // 用户下拉菜单
  const userMenu = {
    items: [
      {
        key: 'changePassword',
        icon: <SettingOutlined />,
        label: '修改密码',
      },
      {
        type: 'divider' as const,
      },
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: '退出登录',
        danger: true,
      },
    ],
    onClick: handleUserMenuClick,
  };



  // 获取面包屑路径
  const getBreadcrumbItems = () => {
    let currentItem = null;
    // 在子菜单中查找当前路径
    for (const item of menuItems) {
      if (item.children) {
        currentItem = item.children.find(child => child.key === location.pathname);
        if (currentItem) break;
      }
    }
    return [
      {
        title: '代理配置',
      },
      {
        title: currentItem ? currentItem.label : '未知页面',
      },
    ];
  };

  return (
    <Layout className="proxy-config-layout">
      {/* 侧边栏 */}
      <Sider 
        trigger={null} 
        collapsible 
        collapsed={collapsed}
        className="proxy-config-sider"
        width={250}
      >
        <div className="proxy-config-logo">
          <div className="logo-icon">
            <GlobalOutlined />
          </div>
          {!collapsed && (
            <div className="logo-text">
              <Title level={4} className="logo-title">NAS代理</Title>
              <Text className="logo-subtitle">配置管理</Text>
            </div>
          )}
        </div>
        
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          defaultOpenKeys={['proxy-config']}
          className="proxy-config-menu"
          items={menuItems}
          onClick={({ key }) => handleMenuClick(key)}
        />
      </Sider>

      {/* 主内容区域 */}
      <Layout className="proxy-config-main">
        {/* 头部 */}
        <Header className="proxy-config-header">
          <div className="header-left">
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              className="collapse-btn"
            />
            
            <Breadcrumb 
              className="header-breadcrumb"
              items={getBreadcrumbItems()}
            />
          </div>
          
          <div className="header-right">
            <Space size="middle">
              <Text className="welcome-text">
                欢迎，{user?.username || '管理员'}
              </Text>
              
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

        {/* 内容区域 */}
        <Content className="proxy-config-content">
          <div className="content-wrapper">
            {/* 如果没有子路由，显示默认内容 */}
            {location.pathname === '/config' ? (
              <div className="config-overview">
                <Title level={2} className="overview-title">
                  代理配置管理
                </Title>
                <Text className="overview-description">
                  选择左侧菜单项开始配置您的代理服务
                </Text>
                
                <Divider />
                
                <Row gutter={[24, 24]} className="config-cards">
                  {menuItems[0].children?.map((item) => (
                    <Col xs={24} sm={12} lg={8} key={item.key}>
                      <Card 
                        hoverable
                        className="config-card"
                        onClick={() => handleMenuClick(item.key)}
                      >
                        <div className="card-content">
                          <div className="card-icon">
                            {item.icon}
                          </div>
                          <Title level={4} className="card-title">
                            {item.label}
                          </Title>
                          <Text className="card-description">
                            {getCardDescription(item.key)}
                          </Text>
                        </div>
                      </Card>
                    </Col>
                  ))}
                </Row>
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

// 获取卡片描述
const getCardDescription = (key: string): string => {
  switch (key) {
    case '/config/users':
      return '管理用户账号、状态与凭证';
    case '/config/inbound':
      return '配置入站监听与协议';
    case '/config/routing':
      return '设置路由规则与流量分发策略';
    case '/config/ratelimit':
      return '设置全局或指定用户的带宽与流量限制，支持时间/日期范围';
    default:
      return '配置项说明';
  }
};

export default ProxyConfig;