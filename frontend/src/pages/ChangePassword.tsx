import React, { useEffect } from 'react';
import { Form, Input, Button, Card, Typography, Alert, Progress } from 'antd';
import { LockOutlined, CheckOutlined, KeyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import { changePasswordAsync, clearError } from '../store/authSlice';
import { ChangePasswordRequest } from '../types/auth';
import './ChangePassword.css';

const { Title, Text } = Typography;

interface PasswordStrength {
  score: number;
  label: string;
  color: string;
}

const ChangePassword: React.FC = () => {
  const [form] = Form.useForm();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const { loading, error, user, isAuthenticated } = useAppSelector((state) => state.auth);

  // 如果未登录，重定向到登录页
  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login', { replace: true });
    }
  }, [isAuthenticated, navigate]);

  // 清除错误信息
  useEffect(() => {
    return () => {
      dispatch(clearError());
    };
  }, [dispatch]);

  // 计算密码强度
  const calculatePasswordStrength = (password: string): PasswordStrength => {
    if (!password) return { score: 0, label: '', color: '#d9d9d9' };
    
    let score = 0;
    
    // 长度检查
    if (password.length >= 8) score += 25;
    if (password.length >= 12) score += 25;
    
    // 复杂度检查
    if (/[a-z]/.test(password)) score += 10;
    if (/[A-Z]/.test(password)) score += 10;
    if (/[0-9]/.test(password)) score += 10;
    if (/[^A-Za-z0-9]/.test(password)) score += 20;
    
    if (score < 30) return { score, label: '弱', color: '#ff4d4f' };
    if (score < 60) return { score, label: '中等', color: '#faad14' };
    if (score < 90) return { score, label: '强', color: '#52c41a' };
    return { score: 100, label: '很强', color: '#1890ff' };
  };

  // 处理密码修改提交
  const handleSubmit = async (values: ChangePasswordRequest & { confirmPassword: string }) => {
    try {
      const { confirmPassword, ...passwordData } = values;
      const result = await dispatch(changePasswordAsync(passwordData));
      if (changePasswordAsync.fulfilled.match(result)) {
        // 修改密码成功，跳转到仪表板
        navigate('/dashboard', { replace: true });
      }
    } catch (error) {
      console.error('Change password failed:', error);
    }
  };

  // 监听新密码变化，计算强度
  const [passwordStrength, setPasswordStrength] = React.useState<PasswordStrength>(
    { score: 0, label: '', color: '#d9d9d9' }
  );

  const handlePasswordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const password = e.target.value;
    setPasswordStrength(calculatePasswordStrength(password));
  };

  return (
    <div className="change-password-container">
      <div className="change-password-background">
        <div className="change-password-card-wrapper">
          <Card className="change-password-card" bordered={false}>
            <div className="change-password-header">
              <div className="change-password-logo">
                <KeyOutlined className="logo-icon" />
              </div>
              <Title level={2} className="change-password-title">
                {user?.mustChangePassword ? '首次登录' : '修改密码'}
              </Title>
              <Text type="secondary" className="change-password-subtitle">
                {user?.mustChangePassword 
                  ? '为了您的账户安全，请设置一个新密码' 
                  : '请输入当前密码和新密码'}
              </Text>
            </div>

            {error && (
              <Alert
                message="修改密码失败"
                description={error}
                type="error"
                showIcon
                closable
                onClose={() => dispatch(clearError())}
                className="change-password-error"
              />
            )}

            <Form
              form={form}
              name="changePassword"
              onFinish={handleSubmit}
              autoComplete="off"
              size="large"
              className="change-password-form"
            >
              {!user?.mustChangePassword && (
                <Form.Item
                  name="oldPassword"
                  rules={[
                    { required: true, message: '请输入当前密码' },
                    { min: 8, max: 64, message: '密码长度为8-64个字符' },
                  ]}
                >
                  <Input.Password
                    prefix={<LockOutlined />}
                    placeholder="当前密码"
                    autoComplete="current-password"
                  />
                </Form.Item>
              )}

              <Form.Item
                name="newPassword"
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 8, max: 64, message: '密码长度为8-64个字符' },
                  {
                    validator: (_, value) => {
                      if (!value) return Promise.resolve();
                      const strength = calculatePasswordStrength(value);
                      if (strength.score < 30) {
                        return Promise.reject(new Error('密码强度太弱，请使用更复杂的密码'));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
              >
                <Input.Password
                  prefix={<LockOutlined />}
                  placeholder="新密码"
                  autoComplete="new-password"
                  onChange={handlePasswordChange}
                />
              </Form.Item>

              {/* 密码强度指示器 */}
              {passwordStrength.score > 0 && (
                <div className="password-strength">
                  <div className="password-strength-label">
                    <Text type="secondary">密码强度: </Text>
                    <Text style={{ color: passwordStrength.color, fontWeight: 500 }}>
                      {passwordStrength.label}
                    </Text>
                  </div>
                  <Progress
                    percent={passwordStrength.score}
                    strokeColor={passwordStrength.color}
                    showInfo={false}
                    size="small"
                  />
                </div>
              )}

              <Form.Item
                name="confirmPassword"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '请确认新密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('newPassword') === value) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password
                  prefix={<CheckOutlined />}
                  placeholder="确认新密码"
                  autoComplete="new-password"
                />
              </Form.Item>

              <Form.Item>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={loading}
                  block
                  className="change-password-button"
                  icon={<KeyOutlined />}
                >
                  {loading ? '修改中...' : '修改密码'}
                </Button>
              </Form.Item>
            </Form>

            <div className="password-tips">
              <Title level={5}>密码要求：</Title>
              <ul>
                <li>长度至少8个字符</li>
                <li>建议包含大小写字母、数字和特殊字符</li>
                <li>避免使用常见密码或个人信息</li>
              </ul>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default ChangePassword;