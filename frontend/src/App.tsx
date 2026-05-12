import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { Provider } from 'react-redux';
import { ConfigProvider, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { store } from './store';
import { useAppDispatch, useAppSelector } from './hooks/redux';
import { getCurrentUserAsync, getSetupStatusAsync } from './store/authSlice';
import ProtectedRoute from './components/ProtectedRoute';
import Login from './pages/Login';
import SetupAdmin from './pages/SetupAdmin';
import ChangePassword from './pages/ChangePassword';
import ProxyConfig from './pages/ProxyConfig';
import UserManagement from './pages/UserManagement';
import RouteManagement from './pages/RouteManagement';
import RuleSetManagement from './pages/RuleSetManagement';
import './App.css';
import InboundManagement from './pages/InboundManagement';
import LogAudit from './pages/LogAudit';
import AggregatedAnalysis from './pages/AggregatedAnalysis';
import Dashboard from './pages/Dashboard';
import TrafficOverview from './pages/TrafficOverview';
import WolManagement from './pages/WolManagement';
import DiskMonitor from './pages/DiskMonitor';
import MailGateway from './pages/MailGateway';
import DatabaseBackup from './pages/DatabaseBackup';

// 应用主组件
const AppContent: React.FC = () => {
  const dispatch = useAppDispatch();
  const { isAuthenticated, user, token, setupChecked, setupLoading, setupRequired } = useAppSelector((state) => state.auth);

  // 应用启动时检查是否需要初始化管理员
  useEffect(() => {
    dispatch(getSetupStatusAsync());
  }, [dispatch]);

  // 初始化状态明确后再检查认证状态
  useEffect(() => {
    if (setupChecked && !setupRequired && token && !user) {
      dispatch(getCurrentUserAsync());
    }
  }, [dispatch, setupChecked, setupRequired, token, user]);

  if (!setupChecked || setupLoading) {
    return (
      <div style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
        background: '#f0f2f5'
      }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <Router>
      <Routes>
        {/* 首次初始化页面 */}
        <Route
          path="/setup"
          element={
            setupRequired ? (
              <SetupAdmin />
            ) : isAuthenticated ? (
              <Navigate to="/config/dashboard" replace />
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        {/* 登录页面 */}
        <Route 
          path="/login" 
          element={
            setupRequired ? (
              <Navigate to="/setup" replace />
            ) : isAuthenticated ? (
              user?.mustChangePassword ? (
                <Navigate to="/change-password" replace />
              ) : (
                <Navigate to="/config/dashboard" replace />
              )
            ) : (
              <Login />
            )
          } 
        />
        
        {/* 修改密码页面 */}
        <Route 
          path="/change-password" 
          element={
            setupRequired ? (
              <Navigate to="/setup" replace />
            ) : isAuthenticated ? (
              <ChangePassword />
            ) : (
              <Navigate to="/login" replace />
            )
          } 
        />
        

        {/* 代理配置与管理台子页面 */}
        <Route 
          path="/config"
          element={
            <ProtectedRoute>
              <ProxyConfig />
            </ProtectedRoute>
          }
        >
          {/* 已有配置子页面 */}
          <Route path="users" element={<UserManagement />} />
          <Route path="routing" element={<RouteManagement />} />
          <Route path="rule-sets" element={<RuleSetManagement />} />
          <Route path="inbound" element={<InboundManagement />} />
          {/* 访问概览嵌套路由 */}
          <Route path="overview/log-audit" element={<LogAudit />} />
          <Route path="overview/aggregate" element={<AggregatedAnalysis />} />
          {/* Dashboard路由 */}
          <Route path="dashboard" element={<Dashboard />} />
          {/* Dashboard子页面路由 */}
          <Route path="dashboard/traffic" element={<TrafficOverview />} />
          <Route path="dashboard/wol" element={<WolManagement />} />
          <Route path="dashboard/disk" element={<Navigate to="/config/system-ops/disk" replace />} />
          <Route path="dashboard/mail-gateway" element={<MailGateway />} />
          {/* 系统运维路由 */}
          <Route path="system-ops/disk" element={<DiskMonitor />} />
          <Route path="system-ops/backup" element={<DatabaseBackup />} />
        </Route>
        
        {/* 默认路由重定向 */}
        <Route 
          path="/" 
          element={
            <Navigate to={setupRequired ? "/setup" : isAuthenticated ? "/config/dashboard" : "/login"} replace />
          } 
        />
        
        {/* 404页面 - 重定向到仪表盘或登录页 */}
        <Route 
          path="*" 
          element={
            <Navigate to={setupRequired ? "/setup" : isAuthenticated ? "/config/dashboard" : "/login"} replace />
          } 
        />
      </Routes>
    </Router>
  );
};

// 根应用组件
function App() {
  return (
    <Provider store={store}>
      <ConfigProvider
        locale={zhCN}
        theme={{
          token: {
            colorPrimary: '#003b46',
            colorSuccess: '#126b3f',
            colorWarning: '#b97900',
            colorError: '#9b1c1c',
            colorText: '#101812',
            colorBgLayout: '#bfc4bf',
            colorBgContainer: '#d6d8d3',
            borderRadius: 2,
            fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
          },
          components: {
            Layout: {
              bodyBg: '#bfc4bf',
              headerBg: '#d6d8d3',
              siderBg: '#d6d8d3',
            },
            Menu: {
              darkItemBg: '#d6d8d3',
              darkSubMenuItemBg: '#c8cbc6',
              darkItemColor: '#101812',
              darkItemSelectedBg: '#003b46',
              darkItemSelectedColor: '#d9fff4',
            },
          },
        }}
      >
        <AppContent />
      </ConfigProvider>
    </Provider>
  );
}

export default App;
