import React, { useEffect } from 'react';
import { Button, Col, Divider, Form, Input, InputNumber, Row, Select, Space } from 'antd';
import { CloseOutlined, SaveOutlined } from '@ant-design/icons';
import { ProtocolType, PROTOCOL_TYPE_LABELS, OutboundProxyEncAlgo } from '../types/route';
import {
  DNS_UPSTREAM_PROTOCOLS,
  OUTBOUND_KIND_LABELS,
  OutboundDTO,
  OutboundKind,
  OutboundRequest,
  PROXY_PROTOCOLS,
} from '../types/outbound';
import {
  PROXY_ENC_ALGO_OPTIONS,
  SHADOWSOCKS_2022_PSK_LENGTH,
  isShadowsocks2022Algo,
} from '../types/proxyEncAlgo';

const { TextArea } = Input;

interface OutboundFormProps {
  initialValues?: OutboundDTO;
  onSubmit: (values: OutboundRequest) => Promise<void>;
  onCancel: () => void;
  loading?: boolean;
}

interface FormValues {
  name: string;
  kind: OutboundKind;
  proxyType: ProtocolType;
  host: string;
  port: number;
  username?: string;
  password?: string;
  encAlgo?: OutboundProxyEncAlgo;
  realityServerName?: string;
  realityPublicKey?: string;
  realityShortId?: string;
  realityUuid?: string;
  realityFlow?: string;
  realityConnectTimeoutMillis?: number;
  notes?: string;
}

const HOST_PATTERN = /^[a-zA-Z0-9.:-]+$/;

const OutboundForm: React.FC<OutboundFormProps> = ({ initialValues, onSubmit, onCancel, loading = false }) => {
  const [form] = Form.useForm<FormValues>();
  const kind = Form.useWatch('kind', form);
  const proxyType = Form.useWatch('proxyType', form);
  const encAlgo = Form.useWatch('encAlgo', form);
  const isDns = kind === OutboundKind.DNS_UPSTREAM;
  const pskLength = encAlgo ? SHADOWSOCKS_2022_PSK_LENGTH[encAlgo] : undefined;

  useEffect(() => {
    if (!initialValues) return;
    const config = (initialValues.config || {}) as Record<string, unknown>;
    form.setFieldsValue({
      name: initialValues.name,
      kind: initialValues.kind,
      proxyType: initialValues.proxyType,
      host: initialValues.host,
      port: initialValues.port,
      username: initialValues.username,
      password: initialValues.password,
      encAlgo: initialValues.encAlgo,
      realityServerName: config.serverName as string | undefined,
      realityPublicKey: config.publicKey as string | undefined,
      realityShortId: config.shortId as string | undefined,
      realityUuid: config.uuid as string | undefined,
      realityFlow: (config.flow as string | undefined) || 'xtls-rprx-vision',
      realityConnectTimeoutMillis: (config.connectTimeoutMillis as number | undefined) || 10000,
      notes: initialValues.notes,
    });
  }, [initialValues, form]);

  const handleKindChange = (value: OutboundKind) => {
    form.setFieldsValue({ proxyType: value === OutboundKind.DNS_UPSTREAM ? ProtocolType.DNS_SERVER : ProtocolType.SOCKS5 });
  };

  const handleFinish = async (values: FormValues) => {
    const isReality = values.proxyType === ProtocolType.VLESS_REALITY;
    await onSubmit({
      name: values.name.trim(),
      kind: values.kind,
      proxyType: values.proxyType,
      host: values.host.trim(),
      port: values.port,
      username: isReality || values.kind === OutboundKind.DNS_UPSTREAM ? undefined : values.username,
      password: isReality || values.kind === OutboundKind.DNS_UPSTREAM ? undefined : values.password,
      encAlgo: values.proxyType === ProtocolType.SHADOW_SOCKS ? values.encAlgo : undefined,
      config: isReality
        ? {
            serverName: values.realityServerName?.trim(),
            publicKey: values.realityPublicKey?.trim(),
            shortId: values.realityShortId?.trim(),
            uuid: values.realityUuid?.trim(),
            flow: values.realityFlow || 'xtls-rprx-vision',
            connectTimeoutMillis: values.realityConnectTimeoutMillis || 10000,
          }
        : {},
      notes: values.notes,
    });
  };

  const protocolOptions = (isDns ? DNS_UPSTREAM_PROTOCOLS : PROXY_PROTOCOLS)
    .map((value) => ({ value, label: PROTOCOL_TYPE_LABELS[value as keyof typeof PROTOCOL_TYPE_LABELS] }));

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleFinish}
      initialValues={{
        kind: OutboundKind.PROXY,
        proxyType: ProtocolType.SOCKS5,
        realityFlow: 'xtls-rprx-vision',
        realityConnectTimeoutMillis: 10000,
      }}
    >
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item name="name" label="出站名称" rules={[{ required: true, message: '请输入出站名称' }, { max: 64 }]}>
            <Input placeholder="例如：机场 Reality" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="kind" label="出站类别" rules={[{ required: true }]}>
            <Select
              onChange={handleKindChange}
              disabled={Boolean(initialValues && initialValues.routeCount > 0)}
              options={[OutboundKind.PROXY, OutboundKind.DNS_UPSTREAM]
                .map((value) => ({ value, label: OUTBOUND_KIND_LABELS[value] }))}
            />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Form.Item name="proxyType" label="协议" rules={[{ required: true, message: '请选择协议' }]}>
            <Select options={protocolOptions} />
          </Form.Item>
        </Col>
        {proxyType === ProtocolType.SHADOW_SOCKS && (
          <Col span={12}>
            <Form.Item name="encAlgo" label="加密算法（Shadowsocks）" rules={[{ required: true, message: '请选择加密算法' }]}>
              <Select placeholder="请选择加密算法" options={PROXY_ENC_ALGO_OPTIONS} />
            </Form.Item>
          </Col>
        )}
      </Row>

      <Row gutter={16}>
        <Col span={16}>
          <Form.Item
            name="host"
            label={isDns ? 'DNS 上游地址' : '服务器地址'}
            rules={[
              { required: true, message: '请输入地址' },
              {
                validator: (_, value: string) => {
                  if (!value) return Promise.resolve();
                  const parts = isDns ? value.split(',').map((p) => p.trim()).filter(Boolean) : [value.trim()];
                  return parts.length && parts.every((p) => HOST_PATTERN.test(p))
                    ? Promise.resolve()
                    : Promise.reject(new Error(isDns ? '请输入 IP 地址，多个用英文逗号分隔' : '请输入有效的主机地址'));
                },
              },
            ]}
            extra={isDns ? '必须是 IP 地址，多个上游用英文逗号分隔，例如：1.1.1.1,8.8.8.8' : undefined}
          >
            <Input placeholder={isDns ? '1.1.1.1,8.8.8.8' : '代理服务器地址'} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item
            name="port"
            label="端口"
            rules={[{ required: true, message: '请输入端口' }, { type: 'number', min: 1, max: 65535, message: '端口范围应在1-65535之间' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} max={65535} />
          </Form.Item>
        </Col>
      </Row>

      {!isDns && proxyType !== ProtocolType.VLESS_REALITY && (
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="username" label="用户名（可选）">
              <Input placeholder="代理认证用户名" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="password"
              label="密码（可选）"
              extra={proxyType === ProtocolType.SHADOW_SOCKS && isShadowsocks2022Algo(encAlgo)
                ? `支持单个 Base64 uPSK，或 iPSK:uPSK 形式；每段都必须是 ${pskLength} 字节 Base64`
                : undefined}
            >
              <Input.Password placeholder="代理认证密码" />
            </Form.Item>
          </Col>
        </Row>
      )}

      {proxyType === ProtocolType.VLESS_REALITY && (
        <>
          <Divider orientation="left">REALITY 配置</Divider>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="realityServerName" label="Server Name" rules={[{ required: true, message: '请输入 REALITY serverName' }]}>
                <Input placeholder="例如：www.example.com" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="realityPublicKey" label="Public Key" rules={[{ required: true, message: '请输入 REALITY publicKey' }]}>
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
              <Form.Item name="realityUuid" label="UUID" rules={[{ required: true, message: '请输入 VLESS UUID' }]}>
                <Input.Password placeholder="请输入 VLESS UUID" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="realityFlow" label="Flow" rules={[{ required: true, message: '请选择 VLESS flow' }]}>
                <Select options={[{ value: 'xtls-rprx-vision', label: 'xtls-rprx-vision' }]} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="realityConnectTimeoutMillis"
                label="连接超时（毫秒）"
                rules={[{ type: 'number', min: 1, message: '连接超时必须大于0' }]}
              >
                <InputNumber placeholder="10000" style={{ width: '100%' }} min={1} />
              </Form.Item>
            </Col>
          </Row>
        </>
      )}

      <Form.Item name="notes" label="备注说明">
        <TextArea rows={2} maxLength={200} showCount placeholder="可选" />
      </Form.Item>

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" loading={loading} icon={<SaveOutlined />}>
            {initialValues ? '保存修改' : '创建出站'}
          </Button>
          <Button onClick={onCancel} icon={<CloseOutlined />}>取消</Button>
        </Space>
      </Form.Item>
    </Form>
  );
};

export default OutboundForm;
