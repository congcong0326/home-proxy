import React, { useEffect } from 'react';
import { Form, Input, Button, Alert, Typography, Card } from 'antd';
import { UserOutlined, LockOutlined, LoginOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import { loginAsync, clearError } from '../store/authSlice';
import { LoginRequest } from '../types/auth';
import './Login.css';

const { Title, Text } = Typography;

const Login: React.FC = () => {
  const [form] = Form.useForm();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const location = useLocation();
  const { loading, error, isAuthenticated } = useAppSelector((state) => state.auth);

  // 获取重定向路径
  const from = (location.state as any)?.from?.pathname || '/dashboard';

  // 如果已经登录，重定向到目标页面
  useEffect(() => {
    if (isAuthenticated) {
      navigate(from, { replace: true });
    }
  }, [isAuthenticated, navigate, from]);

  // 清除错误信息
  useEffect(() => {
    return () => {
      dispatch(clearError());
    };
  }, [dispatch]);

  // 处理登录提交
  const handleSubmit = async (values: LoginRequest) => {
    try {
      const result = await dispatch(loginAsync(values));
      if (loginAsync.fulfilled.match(result)) {
        // 登录成功，检查是否需要修改密码
        if (result.payload.mustChangePassword) {
          navigate('/change-password', { replace: true });
        } else {
          navigate(from, { replace: true });
        }
      }
    } catch (error) {
      console.error('Login failed:', error);
    }
  };

  return (
    <div className="login-container">
      <div className="login-background">
        <div className="login-card-wrapper">
          <Card className="login-card" variant="borderless">
            <div className="login-header">
              <div className="login-logo">
                <LoginOutlined className="logo-icon" />
              </div>
              <Title level={2} className="login-title">
                NAS代理管理后台
              </Title>
              <Text type="secondary" className="login-subtitle">
                请输入您的管理员账号和密码
              </Text>
            </div>

            {error && (
              <Alert
                message="登录失败"
                description={error}
                type="error"
                showIcon
                closable
                onClose={() => dispatch(clearError())}
                className="login-error"
              />
            )}

            <Form
              form={form}
              name="login"
              onFinish={handleSubmit}
              autoComplete="off"
              size="large"
              className="login-form"
            >
              <Form.Item
                name="username"
                rules={[
                  { required: true, message: '请输入用户名' },
                  { min: 1, max: 64, message: '用户名长度为1-64个字符' },
                  { pattern: /^[A-Za-z0-9_]+$/, message: '用户名只能包含字母、数字和下划线' },
                ]}
              >
                <Input
                  prefix={<UserOutlined />}
                  placeholder="用户名"
                  autoComplete="username"
                />
              </Form.Item>

              <Form.Item
                name="password"
                rules={[
                  { required: true, message: '请输入密码' },
                  { min: 8, max: 64, message: '密码长度为8-64个字符' },
                ]}
              >
                <Input.Password
                  prefix={<LockOutlined />}
                  placeholder="密码"
                  autoComplete="current-password"
                />
              </Form.Item>

              <Form.Item>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={loading}
                  block
                  className="login-button"
                  icon={<LoginOutlined />}
                >
                  {loading ? '登录中...' : '登录'}
                </Button>
              </Form.Item>
            </Form>

            <div className="login-footer">
              <Text type="secondary" className="login-footer-text">
                © 2024 NAS代理管理系统. 保留所有权利.
              </Text>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default Login;