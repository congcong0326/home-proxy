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
  Radio,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  SaveOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import {
  RouteDTO,
  RouteAction,
  RouteStatus,
  CreateRouteRequest,
  UpdateRouteRequest,
  ROUTE_ACTION_LABELS,
  ROUTE_STATUS_LABELS,
  RouteRule,
  RouteConditionType,
  MatchOp,
} from '../types/route';
import { OUTBOUND_KIND_COLORS, OUTBOUND_KIND_LABELS, OutboundDTO, OutboundKind } from '../types/outbound';
import { RewriteTableDTO } from '../types/rewrite';
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
  outbounds?: OutboundDTO[];
  rewriteTables?: RewriteTableDTO[];
}

type RewriteSource = 'table' | 'fixed';

interface FormValues {
  name: string;
  action: RouteAction;
  rules: RouteRule[];
  outboundId?: number;
  rewriteSource: RewriteSource;
  rewriteTableId?: number;
  rewriteTargetHost?: string;
  rewriteTargetPort?: number;
  status: RouteStatus;
  notes?: string;
}

const OUTBOUND_KIND_ORDER = [OutboundKind.DIRECT, OutboundKind.BLOCK, OutboundKind.PROXY, OutboundKind.DNS_UPSTREAM];
const IP_PATTERN = /^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]+:[0-9a-fA-F:.]*)$/;

const RouteForm: React.FC<RouteFormProps> = ({
  initialValues,
  onSubmit,
  onCancel,
  loading = false,
  mode,
  ruleSetOptions = [],
  outbounds = [],
  rewriteTables = [],
}) => {
  const [form] = Form.useForm<FormValues>();
  const [rules, setRules] = useState<RouteRule[]>(
    initialValues?.rules?.length
      ? initialValues.rules
      : [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: '' }]
  );
  const action: RouteAction = Form.useWatch('action', form) ?? initialValues?.action ?? RouteAction.FORWARD;
  const rewriteSource: RewriteSource = Form.useWatch('rewriteSource', form)
    ?? (initialValues?.rewriteTableId ? 'table' : 'fixed');
  const rewriteTableId = Form.useWatch('rewriteTableId', form);
  const usesTable = action === RouteAction.REWRITE_DESTINATION && rewriteSource === 'table';
  const showRules = !usesTable;
  const selectedTable = rewriteTables.find((table) => table.id === rewriteTableId);

  useEffect(() => {
    if (initialValues) {
      form.setFieldsValue({
        name: initialValues.name,
        action: initialValues.action ?? RouteAction.FORWARD,
        rules: initialValues.rules,
        outboundId: initialValues.action === RouteAction.FORWARD ? initialValues.outboundId : undefined,
        rewriteSource: initialValues.rewriteTableId ? 'table' : 'fixed',
        rewriteTableId: initialValues.rewriteTableId,
        rewriteTargetHost: initialValues.rewriteTargetHost,
        rewriteTargetPort: initialValues.rewriteTargetPort,
        status: initialValues.status,
        notes: initialValues.notes,
      });
      if (initialValues.rules?.length) setRules(initialValues.rules);
    }
  }, [initialValues, form]);

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
      const tableRoute = values.action === RouteAction.REWRITE_DESTINATION && values.rewriteSource === 'table';
      const submitData: CreateRouteRequest = {
        name: values.name,
        action: values.action,
        rules: tableRoute ? [] : rules
          .filter(r => r.value && r.value.trim())
          .map(r => ({ conditionType: r.conditionType, op: r.op, value: r.value.trim() })),
        status: values.status,
        notes: values.notes,
        outboundId: values.action === RouteAction.FORWARD ? values.outboundId : undefined,
        rewriteTableId: tableRoute ? values.rewriteTableId : undefined,
        rewriteTargetHost: !tableRoute && values.action !== RouteAction.FORWARD ? values.rewriteTargetHost?.trim() : undefined,
        rewriteTargetPort: !tableRoute && values.action === RouteAction.REWRITE_DESTINATION ? values.rewriteTargetPort : undefined,
      };

      await onSubmit(submitData);
    } catch (error) {
      console.error('Form submission error:', error);
    }
  };

  const validateRules = () => {
    if (!showRules) return Promise.resolve();
    const validRules = rules.filter(r => r.value && r.value.trim());
    if (validRules.length === 0) {
      return Promise.reject(new Error('至少需要一个有效的路由规则'));
    }
    return Promise.resolve();
  };

  const outboundOptions = OUTBOUND_KIND_ORDER
    .map((kind) => ({
      label: OUTBOUND_KIND_LABELS[kind],
      options: outbounds.filter((outbound) => outbound.kind === kind).map((outbound) => ({
        value: outbound.id,
        label: (
          <Space>
            <Tag color={OUTBOUND_KIND_COLORS[outbound.kind]}>{OUTBOUND_KIND_LABELS[outbound.kind]}</Tag>
            {outbound.name}
            {outbound.host && <Text type="secondary">{outbound.host}:{outbound.port}</Text>}
          </Space>
        ),
      })),
    }))
    .filter((group) => group.options.length > 0);

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
      initialValues={{
        action: RouteAction.FORWARD,
        rewriteSource: 'fixed',
        status: RouteStatus.ENABLED,
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
            name="action"
            label="路由动作"
            rules={[{ required: true, message: '请选择路由动作' }]}
          >
            <Select
              placeholder="请选择路由动作"
              options={[
                { value: RouteAction.FORWARD, label: (
                  <Space>
                    <Tag color="blue">{ROUTE_ACTION_LABELS[RouteAction.FORWARD]}</Tag>
                    <Text type="secondary">直连、阻断或经上游转发</Text>
                  </Space>
                ) },
                { value: RouteAction.REWRITE_DESTINATION, label: (
                  <Space>
                    <Tag color="purple">{ROUTE_ACTION_LABELS[RouteAction.REWRITE_DESTINATION]}</Tag>
                    <Text type="secondary">改写连接目标后直连</Text>
                  </Space>
                ) },
                { value: RouteAction.REWRITE_DNS, label: (
                  <Space>
                    <Tag color="cyan">{ROUTE_ACTION_LABELS[RouteAction.REWRITE_DNS]}</Tag>
                    <Text type="secondary">DNS 入站返回固定 IP</Text>
                  </Space>
                ) },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>

      {action === RouteAction.FORWARD && (
        <Form.Item
          name="outboundId"
          label="出站"
          rules={[{ required: true, message: '请选择出站' }]}
          extra="上游代理和 DNS 上游在「出站管理」中维护"
        >
          <Select placeholder="请选择出站" options={outboundOptions} showSearch optionFilterProp="label" />
        </Form.Item>
      )}

      {action === RouteAction.REWRITE_DESTINATION && (
        <>
          <Form.Item name="rewriteSource" label="改写目标来源">
            <Radio.Group>
              <Radio value="table">引用改写表</Radio>
              <Radio value="fixed">固定目标</Radio>
            </Radio.Group>
          </Form.Item>
          {rewriteSource === 'table' ? (
            <Form.Item
              name="rewriteTableId"
              label="改写表"
              rules={[{ required: true, message: '请选择改写表' }]}
              extra={selectedTable
                ? `表中 ${selectedTable.entries.length} 个域名即匹配条件，命中后改写到对应目标并直连`
                : '表中的域名即匹配条件，无需再配置路由规则'}
            >
              <Select
                placeholder="请选择改写表"
                options={rewriteTables.map((table) => ({ value: table.id, label: `${table.name}（${table.entries.length} 条）` }))}
              />
            </Form.Item>
          ) : (
            <Row gutter={16}>
              <Col span={16}>
                <Form.Item
                  name="rewriteTargetHost"
                  label="目标地址"
                  rules={[
                    { required: true, message: '请输入目标地址' },
                    { pattern: /^[a-zA-Z0-9.:-]+$/, message: '请输入有效的主机地址' },
                  ]}
                >
                  <Input placeholder="例如：192.168.10.85" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="rewriteTargetPort"
                  label="目标端口"
                  rules={[
                    { required: true, message: '请输入目标端口' },
                    { type: 'number', min: 1, max: 65535, message: '端口范围应在1-65535之间' },
                  ]}
                >
                  <InputNumber placeholder="端口" style={{ width: '100%' }} min={1} max={65535} />
                </Form.Item>
              </Col>
            </Row>
          )}
        </>
      )}

      {action === RouteAction.REWRITE_DNS && (
        <Form.Item
          name="rewriteTargetHost"
          label="应答IP"
          rules={[
            { required: true, message: '请输入应答IP' },
            { pattern: IP_PATTERN, message: '请输入 IPv4 或 IPv6 地址' },
          ]}
        >
          <Input placeholder="例如：192.168.10.1" />
        </Form.Item>
      )}

      {showRules && (
      <>
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
