import React, { useEffect, useMemo, useState } from 'react';
import {
  Card,
  Row,
  Col,
  Table,
  Button,
  Tag,
  Space,
  Modal,
  Form,
  Input,
  Switch,
  InputNumber,
  Select,
  Typography,
  message,
  Tooltip
} from 'antd';
import {
  MailOutlined,
  PlusOutlined,
  EditOutlined,
  SendOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  SafetyOutlined
} from '@ant-design/icons';
import { apiService } from '../services/api';
import { MailGateway, MailTarget, MailSendLog, MailSendRequest } from '../types/mail';
import './MailGateway.css';

const { Title, Paragraph, Text } = Typography;
const { TextArea } = Input;

type TemplateKey = 'ops.alert' | 'ops.traffic_report';

const templates: Record<TemplateKey, { title: string; desc: string; toList: string; ccList?: string; defaultSubject: string; defaultContent: string; contentType: 'text/plain' | 'text/html'; }> = {
  'ops.alert': {
    title: '系统告警',
    desc: '线上异常/报警触发即时邮件',
    toList: 'ops-alerts@example.com',
    ccList: '',
    defaultSubject: '[告警] ${service} 异常',
    defaultContent: '<p><strong>服务：</strong>${service}</p><p><strong>严重级别：</strong>${severity}</p><p><strong>描述：</strong>${message}</p>',
    contentType: 'text/html'
  },
  'ops.traffic_report': {
    title: '流量报表',
    desc: '日/周/月的流量归档推送',
    toList: 'ops-report@example.com',
    ccList: '',
    defaultSubject: '[流量报表] ${period}',
    defaultContent: '<p>您好，</p><p>请查收 ${period} 流量报表（自动发送）。</p>',
    contentType: 'text/html'
  }
};

const MailGatewayPage: React.FC = () => {
  const [gateways, setGateways] = useState<MailGateway[]>([]);
  const [targets, setTargets] = useState<MailTarget[]>([]);
  const [logs, setLogs] = useState<MailSendLog[]>([]);
  const [loadingGateways, setLoadingGateways] = useState(false);
  const [loadingTargets, setLoadingTargets] = useState(false);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [sending, setSending] = useState(false);

  const [gatewayModalOpen, setGatewayModalOpen] = useState(false);
  const [editingGateway, setEditingGateway] = useState<MailGateway | null>(null);
  const [targetModalOpen, setTargetModalOpen] = useState(false);
  const [editingTarget, setEditingTarget] = useState<MailTarget | null>(null);

  const [logBizKeyFilter, setLogBizKeyFilter] = useState<string | undefined>(undefined);

  const [gatewayForm] = Form.useForm<MailGateway>();
  const [targetForm] = Form.useForm<MailTarget>();
  const [sendForm] = Form.useForm<MailSendRequest>();

  const gatewayMap = useMemo(() => {
    const map = new Map<number, MailGateway>();
    gateways.forEach((g) => {
      if (g.id) map.set(g.id, g);
    });
    return map;
  }, [gateways]);

  useEffect(() => {
    loadGateways();
    loadTargets();
    loadLogs();
  }, []);

  const loadGateways = async () => {
    try {
      setLoadingGateways(true);
      const data = await apiService.listMailGateways();
      setGateways(data);
    } catch (e: any) {
      message.error(e?.message || '加载网关失败');
    } finally {
      setLoadingGateways(false);
    }
  };

  const loadTargets = async () => {
    try {
      setLoadingTargets(true);
      const data = await apiService.listMailTargets();
      setTargets(data);
    } catch (e: any) {
      message.error(e?.message || '加载投递目标失败');
    } finally {
      setLoadingTargets(false);
    }
  };

  const loadLogs = async (bizKey?: string) => {
    try {
      setLoadingLogs(true);
      const res = await apiService.listMailSendLogs({
        page: 1,
        size: 6,
        bizKey
      });
      setLogs(res.items || []);
    } catch (e: any) {
      message.error(e?.message || '加载发送日志失败');
    } finally {
      setLoadingLogs(false);
    }
  };

  const openGatewayModal = (gateway?: MailGateway) => {
    setEditingGateway(gateway || null);
    setGatewayModalOpen(true);
    gatewayForm.resetFields();
    if (gateway) {
      gatewayForm.setFieldsValue({
        ...gateway
      });
    } else {
      gatewayForm.setFieldsValue({
        protocol: 'smtp',
        sslEnabled: false,
        starttlsEnabled: true,
        enabled: true,
        port: 587
      });
    }
  };

  const openTargetModal = (target?: MailTarget) => {
    setEditingTarget(target || null);
    setTargetModalOpen(true);
    targetForm.resetFields();
    if (target) {
      targetForm.setFieldsValue(target);
    } else {
      targetForm.setFieldsValue({
        enabled: true
      });
    }
  };

  const handleGatewaySubmit = async () => {
    try {
      const values = await gatewayForm.validateFields();
      const payload: Omit<MailGateway, 'id' | 'createdAt' | 'updatedAt'> = {
        ...values,
        protocol: values.protocol || 'smtp',
        sslEnabled: Boolean(values.sslEnabled),
        starttlsEnabled: Boolean(values.starttlsEnabled),
        enabled: Boolean(values.enabled)
      };
      if (editingGateway?.id) {
        await apiService.updateMailGateway(editingGateway.id, payload);
        message.success('网关已更新');
      } else {
        await apiService.createMailGateway(payload);
        message.success('网关已创建');
      }
      setGatewayModalOpen(false);
      loadGateways();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || '保存失败');
    }
  };

  const handleTargetSubmit = async () => {
    try {
      const values = await targetForm.validateFields();
      const payload: Omit<MailTarget, 'id' | 'createdAt' | 'updatedAt'> = {
        ...values,
        enabled: Boolean(values.enabled)
      };
      if (editingTarget?.id) {
        await apiService.updateMailTarget(editingTarget.id, payload);
        message.success('投递目标已更新');
      } else {
        await apiService.createMailTarget(payload);
        message.success('投递目标已创建');
      }
      setTargetModalOpen(false);
      loadTargets();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || '保存失败');
    }
  };

  const handleToggleGateway = async (gateway: MailGateway, enabled: boolean) => {
    if (!gateway.id) return;
    try {
      await apiService.toggleMailGateway(gateway.id, enabled);
      loadGateways();
      message.success(enabled ? '已启用网关' : '已停用网关');
    } catch (e: any) {
      message.error(e?.message || '切换失败');
    }
  };

  const handleToggleTarget = async (target: MailTarget, enabled: boolean) => {
    if (!target.id) return;
    try {
      await apiService.updateMailTarget(target.id, { ...target, enabled });
      loadTargets();
      message.success(enabled ? '已启用' : '已停用');
    } catch (e: any) {
      message.error(e?.message || '切换失败');
    }
  };

  const handleSend = async () => {
    try {
      const values = await sendForm.validateFields();
      const payload: MailSendRequest = {
        ...values,
        contentType: values.contentType || 'text/plain'
      };
      setSending(true);
      await apiService.sendMail(payload);
      message.success('发送成功，已记录日志');
      loadLogs(logBizKeyFilter);
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || '发送失败');
    } finally {
      setSending(false);
    }
  };

  const applyTemplateToForms = (bizKey: TemplateKey) => {
    const tpl = templates[bizKey];
    targetForm.setFieldsValue({
      bizKey,
      toList: tpl.toList,
      ccList: tpl.ccList,
      enabled: true
    });
    sendForm.setFieldsValue({
      bizKey,
      subject: tpl.defaultSubject,
      content: tpl.defaultContent,
      contentType: tpl.contentType
    });
    setTargetModalOpen(true);
    setEditingTarget(null);
  };

  const gatewayColumns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '连接',
      key: 'connection',
      render: (_: any, record: MailGateway) => (
        <div>
          <div>{record.host}:{record.port}</div>
          <Text type="secondary" style={{ fontSize: 12 }}>{record.protocol?.toUpperCase()}</Text>
        </div>
      )
    },
    {
      title: '发件人',
      dataIndex: 'fromAddress',
      key: 'fromAddress',
      render: (text: string, record: MailGateway) => text || record.username
    },
    {
      title: '安全',
      key: 'secure',
      render: (_: any, record: MailGateway) => (
        <Space size="small">
          {record.sslEnabled && <Tag color="cyan">SSL</Tag>}
          {record.starttlsEnabled && <Tag color="geekblue">STARTTLS</Tag>}
        </Space>
      )
    },
    {
      title: '状态',
      key: 'enabled',
      render: (_: any, record: MailGateway) => (
        <Switch checked={record.enabled} onChange={(val) => handleToggleGateway(record, val)} />
      )
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: MailGateway) => (
        <Button type="link" icon={<EditOutlined />} onClick={() => openGatewayModal(record)}>
          编辑
        </Button>
      )
    }
  ];

  const targetColumns = [
    { title: '业务Key', dataIndex: 'bizKey', key: 'bizKey' },
    { title: 'To', dataIndex: 'toList', key: 'toList', ellipsis: true },
    {
      title: '网关',
      key: 'gateway',
      render: (_: any, record: MailTarget) => {
        if (record.gatewayId && gatewayMap.get(record.gatewayId)) {
          return gatewayMap.get(record.gatewayId)?.name;
        }
        return <Text type="secondary">默认启用网关</Text>;
      }
    },
    {
      title: '状态',
      key: 'enabled',
      render: (_: any, record: MailTarget) => (
        <Switch checked={record.enabled} onChange={(val) => handleToggleTarget(record, val)} />
      )
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: MailTarget) => (
        <Button type="link" icon={<EditOutlined />} onClick={() => openTargetModal(record)}>
          编辑
        </Button>
      )
    }
  ];

  const logColumns = [
    { title: '业务Key', dataIndex: 'bizKey', key: 'bizKey' },
    { title: '主题', dataIndex: 'subject', key: 'subject', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: MailSendLog['status']) => (
        <Tag color={status === 'SUCCESS' ? 'green' : 'red'}>{status}</Tag>
      )
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text: string) => new Date(text).toLocaleString()
    }
  ];

  const activeTemplateState = (bizKey: TemplateKey) => {
    const target = targets.find((t) => t.bizKey === bizKey);
    const gateway = target?.gatewayId ? gatewayMap.get(target.gatewayId) : gateways.find((g) => g.enabled);
    return { target, gateway };
  };

  return (
    <div className="mail-gateway-page">
      <div className="page-header">
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>邮件网关</Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            为内部业务提供简洁的邮件发送能力：网关配置、业务目标绑定（默认 bizKey = ops.alert / ops.traffic_report），同步发送并留痕。
          </Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { loadGateways(); loadTargets(); loadLogs(logBizKeyFilter); }}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openGatewayModal()}>
            新建网关
          </Button>
        </Space>
      </div>

      <Card className="template-card" bodyStyle={{ padding: 16 }}>
        <Row gutter={16}>
          {(Object.keys(templates) as TemplateKey[]).map((key) => {
            const tpl = templates[key];
            const state = activeTemplateState(key);
            return (
              <Col xs={24} md={12} key={key}>
                <div className="template-item">
                  <div className="template-title">
                    <MailOutlined />
                    <div>
                      <Text strong>{tpl.title}</Text>
                      <div className="template-sub">{key}</div>
                    </div>
                  </div>
                  <Paragraph className="template-desc">{tpl.desc}</Paragraph>
                  <Space size="small" style={{ marginBottom: 8 }}>
                    <Tag color={state.target?.enabled ? 'green' : 'default'}>
                      目标{state.target ? '已配置' : '未配置'}
                    </Tag>
                    <Tag color={state.gateway?.enabled ? 'blue' : 'default'}>
                      网关{state.gateway ? ` ${state.gateway.name}` : '未绑定'}
                    </Tag>
                  </Space>
                  <Space>
                    <Button size="small" icon={<PlusOutlined />} onClick={() => applyTemplateToForms(key)}>
                      一键建档
                    </Button>
                    <Button size="small" icon={<SendOutlined />} type="primary" onClick={() => {
                      sendForm.setFieldsValue({
                        bizKey: key,
                        subject: tpl.defaultSubject,
                        content: tpl.defaultContent,
                        contentType: tpl.contentType
                      });
                    }}>
                      填充发送表单
                    </Button>
                  </Space>
                </div>
              </Col>
            );
          })}
        </Row>
      </Card>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={10}>
          <Card
            title={<Space><MailOutlined />SMTP 网关</Space>}
            extra={<Button size="small" icon={<PlusOutlined />} onClick={() => openGatewayModal()}>新增</Button>}
          >
            <Table
              rowKey="id"
              columns={gatewayColumns}
              dataSource={gateways}
              loading={loadingGateways}
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card
            title={<Space><ThunderboltOutlined />业务投递目标</Space>}
            extra={<Button size="small" icon={<PlusOutlined />} onClick={() => openTargetModal()}>新增</Button>}
          >
            <Table
              rowKey="id"
              columns={targetColumns}
              dataSource={targets}
              loading={loadingTargets}
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={10}>
          <Card
            title={<Space><SendOutlined />内部发送（同步）</Space>}
            extra={<Tag color="purple">bizKey + 内容 = 发送</Tag>}
          >
            <Form
              layout="vertical"
              form={sendForm}
              initialValues={{ contentType: 'text/plain', bizKey: 'ops.alert' }}
            >
              <Form.Item label="业务 Key" name="bizKey" rules={[{ required: true, message: '请选择业务 Key' }]}>
                <Select placeholder="选择 bizKey" options={targets.map((t) => ({ label: t.bizKey, value: t.bizKey }))} />
              </Form.Item>
              <Form.Item label="主题" name="subject" rules={[{ required: true, message: '请输入主题' }]}>
                <Input placeholder="例如： [告警] ..." />
              </Form.Item>
              <Row gutter={12}>
                <Col span={16}>
                  <Form.Item label="正文" name="content" rules={[{ required: true, message: '请输入正文' }]}>
                    <TextArea rows={6} placeholder="支持 text/plain 或 text/html" />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="内容类型" name="contentType">
                    <Select
                      options={[
                        { value: 'text/plain', label: 'text/plain' },
                        { value: 'text/html', label: 'text/html' }
                      ]}
                    />
                  </Form.Item>
                  <Form.Item label="请求ID(可选)" name="requestId">
                    <Input placeholder="用于幂等，例如 traceId" />
                  </Form.Item>
                </Col>
              </Row>
              <Space>
                <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={sending}>
                  发送
                </Button>
                <Tooltip title="用模板填充告警示例">
                  <Button onClick={() => applyTemplateToForms('ops.alert')}>填充告警模板</Button>
                </Tooltip>
              </Space>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card
            title={<Space><SafetyOutlined />发送日志</Space>}
            extra={
              <Space>
                <Select
                  allowClear
                  placeholder="按 bizKey 过滤"
                  style={{ width: 180 }}
                  value={logBizKeyFilter}
                  onChange={(val) => { setLogBizKeyFilter(val); loadLogs(val); }}
                  options={targets.map((t) => ({ label: t.bizKey, value: t.bizKey }))}
                  size="small"
                />
                <Button size="small" icon={<ReloadOutlined />} onClick={() => loadLogs(logBizKeyFilter)}>刷新</Button>
              </Space>
            }
          >
            <Table
              rowKey="id"
              columns={logColumns}
              dataSource={logs}
              loading={loadingLogs}
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={editingGateway ? '编辑网关' : '新增网关'}
        open={gatewayModalOpen}
        onCancel={() => setGatewayModalOpen(false)}
        onOk={handleGatewaySubmit}
        destroyOnClose
      >
        <Form layout="vertical" form={gatewayForm}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：默认网关" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={16}>
              <Form.Item name="host" label="Host" rules={[{ required: true, message: '请输入SMTP主机' }]}>
                <Input placeholder="smtp.example.com" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
                <InputNumber style={{ width: '100%' }} min={1} max={65535} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="fromAddress" label="默认发件人">
            <Input placeholder="留空则使用用户名" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="protocol" label="协议" initialValue="smtp">
                <Select options={[{ label: 'SMTP', value: 'smtp' }]} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="sslEnabled" label="SSL" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="starttlsEnabled" label="STARTTLS" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch defaultChecked />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingTarget ? '编辑投递目标' : '新增投递目标'}
        open={targetModalOpen}
        onCancel={() => setTargetModalOpen(false)}
        onOk={handleTargetSubmit}
        destroyOnClose
      >
        <Form layout="vertical" form={targetForm}>
          <Form.Item name="bizKey" label="业务 Key" rules={[{ required: true, message: '请输入唯一bizKey' }]}>
            <Input placeholder="例如：ops.alert" />
          </Form.Item>
          <Form.Item name="toList" label="收件人 To（逗号分隔）" rules={[{ required: true, message: '请输入收件人' }]}>
            <TextArea rows={2} placeholder="a@example.com,b@example.com" />
          </Form.Item>
          <Form.Item name="ccList" label="抄送 CC（可选）">
            <TextArea rows={2} />
          </Form.Item>
          <Form.Item name="bccList" label="密送 BCC（可选）">
            <TextArea rows={2} />
          </Form.Item>
          <Form.Item name="gatewayId" label="绑定网关（可选）">
            <Select allowClear placeholder="不选则使用第一个启用网关">
              {gateways.map((g) => (
                <Select.Option value={g.id} key={g.id}>{g.name}</Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue>
            <Switch defaultChecked />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MailGatewayPage;
