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
  ProtocolType,
  PROTOCOL_TYPE_LABELS,
  OutboundProxyEncAlgo,
} from '../types/route';
import {
  PROXY_ENC_ALGO_OPTIONS,
  SHADOWSOCKS_2022_PSK_LENGTH,
  isShadowsocks2022Algo,
} from '../types/proxyEncAlgo';
import { RuleSetSummaryDTO } from '../types/ruleset';

// 使用 AntD v5 的 options 属性，不再使用 Select.Option
const { TextArea } = Input;
const { Text } = Typography;

interface RouteFormProps {
  initialValues?: RouteDTO;
  onSubmit: (values: CreateRouteRequest | UpdateRouteRequest) => Promise<void>;
  onCancel: () => void;
  loading?: boolean;
  mode: 'create' | 'edit';
  ruleSetOptions?: RuleSetSummaryDTO[];
}

interface FormValues {
  name: string;
  policy: RoutePolicy;
  rules: RouteRule[];
  outboundTag?: string;
  outboundProxyType?: ProtocolType;
  outboundProxyHost?: string;
  outboundProxyPort?: number;
  outboundProxyUsername?: string;
  outboundProxyPassword?: string;
  outboundProxyEncAlgo?: OutboundProxyEncAlgo;
  realityServerName?: string;
  realityPublicKey?: string;
  realityShortId?: string;
  realityUuid?: string;
  realityFlow?: string;
  realityConnectTimeoutMillis?: number;
  status: RouteStatus;
  notes?: string;
}

const RouteForm: React.FC<RouteFormProps> = ({
  initialValues,
  onSubmit,
  onCancel,
  loading = false,
  mode,
  ruleSetOptions = [],
}) => {
  const [form] = Form.useForm<FormValues>();
  const [policy, setPolicy] = useState<RoutePolicy>(initialValues?.policy || RoutePolicy.DIRECT);
  const [rules, setRules] = useState<RouteRule[]>(
    initialValues?.rules?.length
      ? initialValues.rules
      : [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '' }]
  );
  // 监听代理类型以控制加密算法下拉框的显示
  const outboundProxyTypeWatch = Form.useWatch('outboundProxyType', form);
  const outboundProxyEncAlgoWatch = Form.useWatch('outboundProxyEncAlgo', form);
  const allowMultiDnsHost = outboundProxyTypeWatch === ProtocolType.DOT || outboundProxyTypeWatch === ProtocolType.DNS_SERVER;
  const shadowsocks2022PskLength = outboundProxyEncAlgoWatch
    ? SHADOWSOCKS_2022_PSK_LENGTH[outboundProxyEncAlgoWatch]
    : undefined;

  useEffect(() => {
    if (initialValues) {
      const outboundProxyConfig = (initialValues.outboundProxyConfig || {}) as Record<string, unknown>;
      form.setFieldsValue({
        name: initialValues.name,
        policy: initialValues.policy,
        rules: initialValues.rules,
        outboundTag: initialValues.outboundTag,
        outboundProxyType: initialValues.outboundProxyType,
        outboundProxyHost: initialValues.outboundProxyHost,
        outboundProxyPort: initialValues.outboundProxyPort,
        outboundProxyUsername: initialValues.outboundProxyUsername,
        outboundProxyPassword: initialValues.outboundProxyPassword,
        outboundProxyEncAlgo: initialValues.outboundProxyEncAlgo,
        realityServerName: outboundProxyConfig.serverName as string | undefined,
        realityPublicKey: outboundProxyConfig.publicKey as string | undefined,
        realityShortId: outboundProxyConfig.shortId as string | undefined,
        realityUuid: outboundProxyConfig.uuid as string | undefined,
        realityFlow: (outboundProxyConfig.flow as string | undefined) || 'xtls-rprx-vision',
        realityConnectTimeoutMillis: (outboundProxyConfig.connectTimeoutMillis as number | undefined) || 10000,
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
      // 目标重写只需要目标地址和端口，不需要出站标签或代理认证信息
      form.setFieldsValue({
        outboundTag: undefined,
        outboundProxyType: undefined,
        outboundProxyUsername: undefined,
        outboundProxyPassword: undefined,
        outboundProxyEncAlgo: undefined,
        realityServerName: undefined,
        realityPublicKey: undefined,
        realityShortId: undefined,
        realityUuid: undefined,
        realityFlow: undefined,
        realityConnectTimeoutMillis: undefined,
      });
    } else if (value === RoutePolicy.DNS_REWRITING) {
      // DNS 重写不需要认证信息
      form.setFieldsValue({
        outboundProxyUsername: undefined,
        outboundProxyPassword: undefined,
      });
    } else if (value !== RoutePolicy.OUTBOUND_PROXY) {
      // 直连或阻断清空所有出站配置
      form.setFieldsValue({
        outboundTag: undefined,
        outboundProxyType: undefined,
        outboundProxyHost: undefined,
        outboundProxyPort: undefined,
        outboundProxyUsername: undefined,
        outboundProxyPassword: undefined,
        outboundProxyEncAlgo: undefined,
        realityServerName: undefined,
        realityPublicKey: undefined,
        realityShortId: undefined,
        realityUuid: undefined,
        realityFlow: undefined,
        realityConnectTimeoutMillis: undefined,
      });
    }
  };

  const handleAddRule = () => {
    const newRules = [...rules, { conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '' }];
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
    const defaults =
      value === RouteConditionType.GEO ? 'CN' :
      value === RouteConditionType.RULE_SET ? (ruleSetOptions[0]?.ruleKey || '') : '';
    newRules[index] = { ...newRules[index], conditionType: value, value: defaults };
    setRules(newRules);
    form.setFieldsValue({ rules: newRules });
  };

  const handleRuleOpChange = (index: number, value: MatchOp) => {
    const newRules = [...rules];
    newRules[index] = { ...newRules[index], op: value };
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
          .map(r => ({ conditionType: r.conditionType, op: r.op, value: r.value.trim() })),
        status: values.status,
        notes: values.notes,
        ...(values.policy === RoutePolicy.OUTBOUND_PROXY && {
          outboundTag: values.outboundTag,
          outboundProxyType: values.outboundProxyType,
          outboundProxyHost: values.outboundProxyHost,
          outboundProxyPort: values.outboundProxyPort,
          outboundProxyUsername: values.outboundProxyType === ProtocolType.VLESS_REALITY ? undefined : values.outboundProxyUsername,
          outboundProxyPassword: values.outboundProxyType === ProtocolType.VLESS_REALITY ? undefined : values.outboundProxyPassword,
          outboundProxyEncAlgo: values.outboundProxyType === ProtocolType.SHADOW_SOCKS ? values.outboundProxyEncAlgo : undefined,
          outboundProxyConfig: values.outboundProxyType === ProtocolType.VLESS_REALITY
            ? {
                serverName: values.realityServerName?.trim(),
                publicKey: values.realityPublicKey?.trim(),
                shortId: values.realityShortId?.trim(),
                uuid: values.realityUuid?.trim(),
                flow: values.realityFlow || 'xtls-rprx-vision',
                connectTimeoutMillis: values.realityConnectTimeoutMillis || 10000,
              }
            : {},
        }),
        ...(values.policy === RoutePolicy.DESTINATION_OVERRIDE && {
          outboundProxyHost: values.outboundProxyHost,
          outboundProxyPort: values.outboundProxyPort,
        }),
        ...(values.policy === RoutePolicy.DNS_REWRITING && {
          outboundTag: values.outboundTag,
          outboundProxyHost: values.outboundProxyHost,
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
        realityFlow: 'xtls-rprx-vision',
        realityConnectTimeoutMillis: 10000,
        rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '' }],
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
                { value: RoutePolicy.DNS_REWRITING, label: (
                  <Space>
                    <Tag color="cyan">{ROUTE_POLICY_LABELS[RoutePolicy.DNS_REWRITING]}</Tag>
                    <Text type="secondary">自定义DNS解析</Text>
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
            <li>支持引用已发布规则集，如：ai-openai、ai-common</li>
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
                value={rule.conditionType}
                onChange={(val) => handleRuleConditionChange(index, val as RouteConditionType)}
                style={{ width: 120 }}
                options={[
                  { value: RouteConditionType.DOMAIN, label: '域名' },
                  { value: RouteConditionType.GEO, label: '地理位置' },
                  { value: RouteConditionType.RULE_SET, label: '规则集' },
                ]}
              />
              </Col>
              <Col>
                <Select
                  value={rule.op}
                  onChange={(val) => handleRuleOpChange(index, val as MatchOp)}
                  style={{ width: 120 }}
                  options={[
                    { value: MatchOp.IN, label: '属于' },
                    { value: MatchOp.NOT_IN, label: '不属于' },
                  ]}
                />
              </Col>
              <Col flex="auto">
                {rule.conditionType === RouteConditionType.GEO ? (
                  <Select
                    value={rule.value || 'CN'}
                    onChange={(val) => handleRuleValueChange(index, val as string)}
                    style={{ width: '100%' }}
                    options={[{ value: 'CN', label: '中国' }]}
                  />
                ) : rule.conditionType === RouteConditionType.RULE_SET ? (
                  <Select
                    value={rule.value || undefined}
                    onChange={(val) => handleRuleValueChange(index, val as string)}
                    style={{ width: '100%' }}
                    placeholder="请选择已发布规则集"
                    options={ruleSetOptions.map((item) => ({
                      value: item.ruleKey,
                      label: `${item.name} (${item.ruleKey})`,
                    }))}
                  />
                ) : (
                  <Input
                    placeholder={'请输入域名，如：*.example.com'}
                    value={rule.value}
                    onChange={(e) => handleRuleValueChange(index, e.target.value)}
                    addonBefore={`规则 ${index + 1}`}
                  />
                )}
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
                name="outboundProxyType"
                label="代理类型"
                rules={[{ required: true, message: '请选择代理类型' }]}
              >
                <Select
                  placeholder="请选择代理类型"
                  options={[
                    { value: ProtocolType.SOCKS5, label: PROTOCOL_TYPE_LABELS[ProtocolType.SOCKS5] },
                    { value: ProtocolType.HTTPS_CONNECT, label: PROTOCOL_TYPE_LABELS[ProtocolType.HTTPS_CONNECT] },
                    { value: ProtocolType.DOT, label: PROTOCOL_TYPE_LABELS[ProtocolType.DOT] },
                    { value: ProtocolType.DNS_SERVER, label: PROTOCOL_TYPE_LABELS[ProtocolType.DNS_SERVER] },
                    { value: ProtocolType.SHADOW_SOCKS, label: PROTOCOL_TYPE_LABELS[ProtocolType.SHADOW_SOCKS] },
                    { value: ProtocolType.VLESS_REALITY, label: PROTOCOL_TYPE_LABELS[ProtocolType.VLESS_REALITY] },
                  ]}
                />
              </Form.Item>
            </Col>
            {outboundProxyTypeWatch === ProtocolType.SHADOW_SOCKS && (
              <Col span={12}>
                <Form.Item
                  name="outboundProxyEncAlgo"
                  label="加密算法（Shadowsocks）"
                  rules={[{ required: true, message: '请选择加密算法' }]}
                >
                  <Select
                    placeholder="请选择加密算法"
                    options={PROXY_ENC_ALGO_OPTIONS}
                  />
                </Form.Item>
              </Col>
            )}
          </Row>

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
                  {
                    validator: (_, value: string) => {
                      if (!value) return Promise.resolve();
                      const hostPattern = /^[a-zA-Z0-9.-]+$/;
                      if (allowMultiDnsHost) {
                        const parts = value.split(',').map((p: string) => p.trim()).filter(Boolean);
                        if (!parts.length) {
                          return Promise.reject(new Error('请输入至少一个地址'));
                        }
                        const invalid = parts.find(p => !hostPattern.test(p));
                        return invalid
                          ? Promise.reject(new Error('请输入有效的主机地址，多个地址请用逗号分隔'))
                          : Promise.resolve();
                      }
                      return hostPattern.test(value)
                        ? Promise.resolve()
                        : Promise.reject(new Error('请输入有效的主机地址'));
                    },
                  },
                ]}
                extra={allowMultiDnsHost ? '支持多个DNS上游，使用英文逗号分隔，例如：1.1.1.1,8.8.8.8' : undefined}
              >
                <Input placeholder={allowMultiDnsHost ? '多个DNS上游用逗号分隔，如 1.1.1.1,8.8.8.8' : '请输入代理服务器地址'} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={outboundProxyTypeWatch === ProtocolType.VLESS_REALITY ? 12 : 8}>
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
            {outboundProxyTypeWatch !== ProtocolType.VLESS_REALITY && (
              <>
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
                    extra={
                      outboundProxyTypeWatch === ProtocolType.SHADOW_SOCKS &&
                      isShadowsocks2022Algo(outboundProxyEncAlgoWatch)
                        ? `支持单个 Base64 uPSK，或 iPSK:uPSK 形式；每段都必须是 ${shadowsocks2022PskLength} 字节 Base64`
                        : undefined
                    }
                  >
                    <Input.Password placeholder="代理认证密码" />
                  </Form.Item>
                </Col>
              </>
            )}
          </Row>

          {outboundProxyTypeWatch === ProtocolType.VLESS_REALITY && (
            <>
              <Divider orientation="left">REALITY 配置</Divider>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item
                    name="realityServerName"
                    label="Server Name"
                    rules={[{ required: true, message: '请输入 REALITY serverName' }]}
                  >
                    <Input placeholder="例如：www.example.com" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="realityPublicKey"
                    label="Public Key"
                    rules={[{ required: true, message: '请输入 REALITY publicKey' }]}
                  >
                    <Input placeholder="请输入 Xray REALITY publicKey" />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item
                    name="realityShortId"
                    label="Short ID"
                    rules={[
                      { required: true, message: '请输入 REALITY shortId' },
                      { pattern: /^[0-9a-fA-F]{0,16}$/, message: 'shortId 必须是 0-16 位十六进制字符' },
                    ]}
                  >
                    <Input placeholder="例如：6ba85179e30d4fc2" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="realityUuid"
                    label="UUID"
                    rules={[{ required: true, message: '请输入 VLESS UUID' }]}
                  >
                    <Input.Password placeholder="请输入 VLESS UUID" />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item
                    name="realityFlow"
                    label="Flow"
                    rules={[{ required: true, message: '请选择 VLESS flow' }]}
                  >
                    <Select
                      options={[
                        { value: 'xtls-rprx-vision', label: 'xtls-rprx-vision' },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="realityConnectTimeoutMillis"
                    label="连接超时（毫秒）"
                    rules={[{ type: 'number', min: 1, message: '连接超时必须大于0' }]}
                  >
                    <InputNumber
                      placeholder="10000"
                      style={{ width: '100%' }}
                      min={1}
                    />
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
        </>
      )}

      {(policy === RoutePolicy.DESTINATION_OVERRIDE || policy === RoutePolicy.DNS_REWRITING) && (
        <>
          <Divider orientation="left">{policy === RoutePolicy.DNS_REWRITING ? 'DNS解析配置' : '目标重写配置'}</Divider>
          <Row gutter={16}>
            {policy === RoutePolicy.DNS_REWRITING && (
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
            )}
            <Col span={policy === RoutePolicy.DNS_REWRITING ? 12 : 24}>
              <Form.Item
                name="outboundProxyHost"
                label={policy === RoutePolicy.DNS_REWRITING ? '应答IP' : '目标地址'}
                rules={[
                  { required: true, message: '请输入目标地址' },
                  { pattern: /^[a-zA-Z0-9.-]+$/, message: '请输入有效的主机地址' },
                ]}
              >
                <Input placeholder={policy === RoutePolicy.DNS_REWRITING ? '请输入应答IP' : '请输入目标服务器地址'} />
              </Form.Item>
            </Col>
          </Row>
          {policy === RoutePolicy.DESTINATION_OVERRIDE && (
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
          )}
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
