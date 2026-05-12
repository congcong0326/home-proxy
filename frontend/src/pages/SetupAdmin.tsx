import React, { useEffect } from 'react';
import { Form, Input, Button, Alert, Typography, Card } from 'antd';
import { UserOutlined, LockOutlined, UserAddOutlined, CheckOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import { setupAdminAsync, clearError } from '../store/authSlice';
import './Login.css';

const { Title, Text } = Typography;

const SetupAdmin: React.FC = () => {
  const [form] = Form.useForm();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const { loading, error } = useAppSelector((state) => state.auth);

  useEffect(() => {
    return () => {
      dispatch(clearError());
    };
  }, [dispatch]);

  const handleSubmit = async (values: { username: string; password: string; confirmPassword: string }) => {
    const result = await dispatch(setupAdminAsync({
      username: values.username,
      password: values.password,
    }));
    if (setupAdminAsync.fulfilled.match(result)) {
      navigate('/config/dashboard', { replace: true });
    }
  };

  return (
    <div className="login-container">
      <div className="login-background">
        <div className="login-card-wrapper">
          <Card className="login-card" variant="borderless">
            <div className="login-header">
              <div className="login-logo">
                <UserAddOutlined className="logo-icon" />
              </div>
              <Title level={2} className="login-title">
                创建管理员账号
              </Title>
              <Text type="secondary" className="login-subtitle">
                设置管理后台的首个超级管理员
              </Text>
            </div>

            {error && (
              <Alert
                message="初始化失败"
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
              name="setupAdmin"
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
                  placeholder="管理员用户名"
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
                  placeholder="管理员密码"
                  autoComplete="new-password"
                />
              </Form.Item>

              <Form.Item
                name="confirmPassword"
                dependencies={['password']}
                rules={[
                  { required: true, message: '请确认密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('password') === value) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password
                  prefix={<CheckOutlined />}
                  placeholder="确认管理员密码"
                  autoComplete="new-password"
                />
              </Form.Item>

              <Form.Item>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={loading}
                  block
                  className="login-button"
                  icon={<UserAddOutlined />}
                >
                  {loading ? '创建中...' : '创建并进入'}
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default SetupAdmin;
