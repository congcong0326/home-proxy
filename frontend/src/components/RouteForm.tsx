import React, { useState, useEffect } from 'react';
import {
  Form,
  Input,
  Select,
  Button,
  Space,
  Card,
  Row,
  Col,
  InputNumber,
  Switch,
  Divider,
  Alert,
  Typography,
  Tag,
  Tooltip,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  InfoCircleOutlined,
  SaveOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import {
  RouteDTO,
  RoutePolicy,
  RouteStatus,
  CreateRouteRequest,
  UpdateRouteRequest,
  ROUTE_POLICY_LABELS,
  ROUTE_STATUS_LABELS,
  RouteRule,
  RouteConditionType,
  MatchOp,
} from '../types/route';

// 使用 AntD v5 的 options 属性，不再使用 Select.Option
const { TextArea } = Input;
const { Text } = Typography;

interface RouteFormProps {
  initialValues?: RouteDTO;
  onSubmit: (values: CreateRouteRequest | UpdateRouteRequest) => Promise<void>;
  onCancel: () => void;
  loading?: boolean;
  mode: 'create' | 'edit';
}

interface FormValues {
  name: string;
  policy: RoutePolicy;
  rules: RouteRule[];
  outboundTag?: string;
  outboundProxyHost?: string;
  outboundProxyPort?: number;
  outboundProxyUsername?: string;
  outboundProxyPassword?: string;
  status: RouteStatus;
  notes?: string;
}

const RouteForm: React.FC<RouteFormProps> = ({
  initialValues,
  onSubmit,
  onCancel,
  loading = false,
  mode,
}) => {
  const [form] = Form.useForm<FormValues>();
  const [policy, setPolicy] = useState<RoutePolicy>(initialValues?.policy || RoutePolicy.DIRECT);
  const [rules, setRules] = useState<RouteRule[]>(
    initialValues?.rules?.length
      ? initialValues.rules
      : [{ domain: RouteConditionType.DOMAIN, geo: MatchOp.IN, value: '' }]
  );

  useEffect(() => {
    if (initialValues) {
      form.setFieldsValue({
        name: initialValues.name,
        policy: initialValues.policy,
        rules: initialValues.rules,
        outboundTag: initialValues.outboundTag,
        outboundProxyHost: initialValues.outboundProxyHost,
        outboundProxyPort: initialValues.outboundProxyPort,
        outboundProxyUsername: initialValues.outboundProxyUsername,
        outboundProxyPassword: initialValues.outboundProxyPassword,
        status: initialValues.status,
        notes: initialValues.notes,
      });
      setPolicy(initialValues.policy);
      setRules(initialValues.rules);
    }
  }, [initialValues, form]);

  const handlePolicyChange = (value: RoutePolicy) => {
    setPolicy(value);
    // 切换不同策略时清理相关字段
    if (value === RoutePolicy.DESTINATION_OVERRIDE) {
      // 目标重写不需要认证信息
      form.setFieldsValue({
        outboundProxyUsername: undefined,
        outboundProxyPassword: undefined,
      });
    } else if (value !== RoutePolicy.OUTBOUND_PROXY) {
      // 直连或阻断清空所有出站配置
      form.setFieldsValue({
        outboundTag: undefined,
        outboundProxyHost: undefined,
        outboundProxyPort: undefined,
        outboundProxyUsername: undefined,
        outboundProxyPassword: undefined,
      });
    }
  };

  const handleAddRule = () => {
    const newRules = [...rules, { domain: RouteConditionType.DOMAIN, geo: MatchOp.IN, value: '' }];
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleRemoveRule = (index: number) => {
    if (rules.length <= 1) return; // 至少保留一个规则
    const newRules = rules.filter((_, i) => i !== index);
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleRuleValueChange = (index: number, value: string) => {
    const newRules = [...rules];
    newRules[index] = { ...newRules[index], value };
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleRuleConditionChange = (index: number, value: RouteConditionType) => {
    const newRules = [...rules];
    newRules[index] = { ...newRules[index], domain: value };
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleRuleOpChange = (index: number, value: MatchOp) => {
    const newRules = [...rules];
    newRules[index] = { ...newRules[index], geo: value };
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleSubmit = async (values: FormValues) => {
    try {
      const submitData = {
        name: values.name,
        policy: values.policy,
        rules: rules
          .filter(r => r.value && r.value.trim())
          .map(r => ({ domain: r.domain, geo: r.geo, value: r.value.trim() })),
        status: values.status,
        notes: values.notes,
        ...(values.policy === RoutePolicy.OUTBOUND_PROXY && {
          outboundTag: values.outboundTag,
          outboundProxyHost: values.outboundProxyHost,
          outboundProxyPort: values.outboundProxyPort,
          outboundProxyUsername: values.outboundProxyUsername,
          outboundProxyPassword: values.outboundProxyPassword,
        }),
        ...(values.policy === RoutePolicy.DESTINATION_OVERRIDE && {
          outboundTag: values.outboundTag,
          outboundProxyHost: values.outboundProxyHost,
          outboundProxyPort: values.outboundProxyPort,
        }),
      };

      await onSubmit(submitData);
    } catch (error) {
      console.error('Form submission error:', error);
    }
  };

  const validateRules = () => {
    const validRules = rules.filter(r => r.value && r.value.trim());
    if (validRules.length === 0) {
      return Promise.reject(new Error('至少需要一个有效的路由规则'));
    }
    return Promise.resolve();
  };

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
      initialValues={{
        policy: RoutePolicy.DIRECT,
        status: RouteStatus.ENABLED,
        rules: [{ domain: RouteConditionType.DOMAIN, geo: MatchOp.IN, value: '' }],
      }}
    >
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="name"
            label="路由名称"
            rules={[
              { required: true, message: '请输入路由名称' },
              { min: 2, max: 50, message: '路由名称长度应在2-50个字符之间' },
            ]}
          >
            <Input placeholder="请输入路由名称" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="policy"
            label="路由策略"
            rules={[{ required: true, message: '请选择路由策略' }]}
          >
            <Select
              placeholder="请选择路由策略"
              onChange={handlePolicyChange}
              options={[
                { value: RoutePolicy.DIRECT, label: (
                  <Space>
                    <Tag color="green">{ROUTE_POLICY_LABELS[RoutePolicy.DIRECT]}</Tag>
                    <Text type="secondary">直接连接</Text>
                  </Space>
                ) },
                { value: RoutePolicy.BLOCK, label: (
                  <Space>
                    <Tag color="red">{ROUTE_POLICY_LABELS[RoutePolicy.BLOCK]}</Tag>
                    <Text type="secondary">阻断连接</Text>
                  </Space>
                ) },
                { value: RoutePolicy.OUTBOUND_PROXY, label: (
                  <Space>
                    <Tag color="blue">{ROUTE_POLICY_LABELS[RoutePolicy.OUTBOUND_PROXY]}</Tag>
                    <Text type="secondary">代理转发</Text>
                  </Space>
                ) },
                { value: RoutePolicy.DESTINATION_OVERRIDE, label: (
                  <Space>
                    <Tag color="purple">{ROUTE_POLICY_LABELS[RoutePolicy.DESTINATION_OVERRIDE]}</Tag>
                    <Text type="secondary">目标地址重写</Text>
                  </Space>
                ) },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>

      <Divider orientation="left">路由规则</Divider>
      
      <Alert
        message="路由规则说明"
        description={
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            <li>支持域名匹配，如：example.com</li>
            <li>支持通配符匹配，如：*.example.com</li>
            <li>支持子域名匹配，如：sub.example.com</li>
            <li>至少需要配置一个有效规则</li>
          </ul>
        }
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />

      <Form.Item
        name="rules"
        rules={[{ validator: validateRules }]}
      >
        <Card size="small" title="路由规则">
          {rules.map((rule, index) => (
            <Row key={index} gutter={8} style={{ marginBottom: 8 }}>
              <Col>
                <Select
                  value={rule.domain}
                  onChange={(val) => handleRuleConditionChange(index, val as RouteConditionType)}
                  style={{ width: 120 }}
                  options={[
                    { value: RouteConditionType.DOMAIN, label: '域名' },
                    { value: RouteConditionType.GEO, label: '地理位置' },
                  ]}
                />
              </Col>
              <Col>
                <Select
                  value={rule.geo}
                  onChange={(val) => handleRuleOpChange(index, val as MatchOp)}
                  style={{ width: 120 }}
                  options={[
                    { value: MatchOp.IN, label: '属于' },
                    { value: MatchOp.NOT_IN, label: '不属于' },
                  ]}
                />
              </Col>
              <Col flex="auto">
                <Input
                  placeholder={rule.domain === RouteConditionType.DOMAIN ? '请输入域名，如：*.example.com' : '请输入地理位置，如：CN/US'}
                  value={rule.value}
                  onChange={(e) => handleRuleValueChange(index, e.target.value)}
                  addonBefore={`规则 ${index + 1}`}
                />
              </Col>
              <Col>
                <Space>
                  {index === rules.length - 1 && (
                    <Button
                      type="dashed"
                      icon={<PlusOutlined />}
                      onClick={handleAddRule}
                      size="small"
                    >
                      添加
                    </Button>
                  )}
                  {rules.length > 1 && (
                    <Button
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => handleRemoveRule(index)}
                      size="small"
                    >
                      删除
                    </Button>
                  )}
                </Space>
              </Col>
            </Row>
          ))}
        </Card>
      </Form.Item>

      {policy === RoutePolicy.OUTBOUND_PROXY && (
        <>
          <Divider orientation="left">代理配置</Divider>
          
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="outboundTag"
                label={
                  <Space>
                    出站标签
                    <Tooltip title="用于标识此代理配置的唯一标签">
                      <InfoCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                rules={[
                  { required: true, message: '请输入出站标签' },
                  { pattern: /^[a-zA-Z0-9_-]+$/, message: '标签只能包含字母、数字、下划线和连字符' },
                ]}
              >
                <Input placeholder="请输入出站标签" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="outboundProxyHost"
                label="代理地址"
                rules={[
                  { required: true, message: '请输入代理地址' },
                  { pattern: /^[a-zA-Z0-9.-]+$/, message: '请输入有效的主机地址' },
                ]}
              >
                <Input placeholder="请输入代理服务器地址" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="outboundProxyPort"
                label="代理端口"
                rules={[
                  { required: true, message: '请输入代理端口' },
                  { type: 'number', min: 1, max: 65535, message: '端口范围应在1-65535之间' },
                ]}
              >
                <InputNumber
                  placeholder="端口"
                  style={{ width: '100%' }}
                  min={1}
                  max={65535}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="outboundProxyUsername"
                label="用户名（可选）"
              >
                <Input placeholder="代理认证用户名" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="outboundProxyPassword"
                label="密码（可选）"
              >
                <Input.Password placeholder="代理认证密码" />
              </Form.Item>
            </Col>
          </Row>
        </>
      )}

      {policy === RoutePolicy.DESTINATION_OVERRIDE && (
        <>
          <Divider orientation="left">目标重写配置</Divider>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="outboundTag"
                label={
                  <Space>
                    出站标签
                    <Tooltip title="用于标识此配置的唯一标签">
                      <InfoCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                rules={[
                  { required: true, message: '请输入出站标签' },
                  { pattern: /^[a-zA-Z0-9_-]+$/, message: '标签只能包含字母、数字、下划线和连字符' },
                ]}
              >
                <Input placeholder="请输入出站标签" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="outboundProxyHost"
                label="目标地址"
                rules={[
                  { required: true, message: '请输入目标地址' },
                  { pattern: /^[a-zA-Z0-9.-]+$/, message: '请输入有效的主机地址' },
                ]}
              >
                <Input placeholder="请输入目标服务器地址" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="outboundProxyPort"
                label="目标端口（可选）"
                rules={[
                  { type: 'number', min: 1, max: 65535, message: '端口范围应在1-65535之间' },
                ]}
              >
                <InputNumber
                  placeholder="端口"
                  style={{ width: '100%' }}
                  min={1}
                  max={65535}
                />
              </Form.Item>
            </Col>
          </Row>
        </>
      )}

      <Divider orientation="left">其他设置</Divider>
      
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="status"
            label="路由状态"
            rules={[{ required: true, message: '请选择路由状态' }]}
          >
            <Select
              placeholder="请选择路由状态"
              options={[
                { value: RouteStatus.ENABLED, label: (
                  <Space>
                    <Tag color="green">{ROUTE_STATUS_LABELS[RouteStatus.ENABLED]}</Tag>
                    <Text type="secondary">路由生效</Text>
                  </Space>
                ) },
                { value: RouteStatus.DISABLED, label: (
                  <Space>
                    <Tag color="red">{ROUTE_STATUS_LABELS[RouteStatus.DISABLED]}</Tag>
                    <Text type="secondary">路由禁用</Text>
                  </Space>
                ) },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>

      <Form.Item
        name="notes"
        label="备注说明"
      >
        <TextArea
          placeholder="请输入路由的备注说明（可选）"
          rows={3}
          maxLength={200}
          showCount
        />
      </Form.Item>

      <Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            icon={<SaveOutlined />}
          >
            {mode === 'create' ? '创建路由' : '保存修改'}
          </Button>
          <Button
            onClick={onCancel}
            icon={<CloseOutlined />}
          >
            取消
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
};

export default RouteForm;