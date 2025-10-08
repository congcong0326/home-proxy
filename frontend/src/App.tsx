import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { Provider } from 'react-redux';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { store } from './store';
import { useAppDispatch, useAppSelector } from './hooks/redux';
import { getCurrentUserAsync } from './store/authSlice';
import ProtectedRoute from './components/ProtectedRoute';
import Login from './pages/Login';
import ChangePassword from './pages/ChangePassword';
import ProxyConfig from './pages/ProxyConfig';
import UserManagement from './pages/UserManagement';
import RouteManagement from './pages/RouteManagement';
import './App.css';
import RateLimitManagement from './pages/RateLimitManagement';
import InboundManagement from './pages/InboundManagement';
import LogAudit from './pages/LogAudit';
import AggregatedAnalysis from './pages/AggregatedAnalysis';

// 应用主组件
const AppContent: React.FC = () => {
  const dispatch = useAppDispatch();
  const { isAuthenticated, user } = useAppSelector((state) => state.auth);

  // 应用启动时检查认证状态
  useEffect(() => {
    const token = localStorage.getItem('token');
    if (token && !user) {
      dispatch(getCurrentUserAsync());
    }
  }, [dispatch, user]);

  return (
    <Router>
      <Routes>
        {/* 登录页面 */}
        <Route 
          path="/login" 
          element={
            isAuthenticated ? (
              user?.mustChangePassword ? (
                <Navigate to="/change-password" replace />
              ) : (
                <Navigate to="/config/users" replace />
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
            isAuthenticated ? (
              <ChangePassword />
            ) : (
              <Navigate to="/login" replace />
            )
          } 
        />
        

        
        {/* 受保护的代理配置页面 */}
        <Route 
          path="/config" 
          element={
            <ProtectedRoute>
              <ProxyConfig />
            </ProtectedRoute>
          }
        >
          {/* 用户管理子页面 */}
          <Route path="users" element={<UserManagement />} />
          {/* 路由管理子页面 */}
          <Route path="routing" element={<RouteManagement />} />
          {/* 其他配置子页面占位符 */}
          <Route path="inbound" element={<InboundManagement />} />
          <Route path="ratelimit" element={<RateLimitManagement />} />
        </Route>

        {/* 访问概览子页面嵌入到代理配置下，统一左侧栏 */}
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
          <Route path="inbound" element={<InboundManagement />} />
          <Route path="ratelimit" element={<RateLimitManagement />} />
          {/* 访问概览嵌套路由 */}
          <Route path="overview/log-audit" element={<LogAudit />} />
          <Route path="overview/aggregate" element={<AggregatedAnalysis />} />
          <Route path="overview/geo" element={<div style={{ padding: 24 }}>地理位置分布 - 待实现</div>} />
        </Route>
        
        {/* 默认路由重定向 */}
        <Route 
          path="/" 
          element={
            <Navigate to={isAuthenticated ? "/config/users" : "/login"} replace />
          } 
        />
        
        {/* 404页面 - 重定向到用户管理或登录页 */}
        <Route 
          path="*" 
          element={
            <Navigate to={isAuthenticated ? "/config/users" : "/login"} replace />
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
      <ConfigProvider locale={zhCN}>
        <AppContent />
      </ConfigProvider>
    </Provider>
  );
}

export default App;
