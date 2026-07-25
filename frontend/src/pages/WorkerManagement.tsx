import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd';
import { DeleteOutlined, KeyOutlined, PlusOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { apiService } from '../services/api';
import { ProxyWorkerDTO, WorkerCreateRequest } from '../types/worker';

const WorkerManagement: React.FC = () => {
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [token, setToken] = useState<string | null>(null);
  const [form] = Form.useForm<WorkerCreateRequest>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setWorkers(await apiService.getWorkers());
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载节点失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const create = async () => {
    try {
      const value = await form.validateFields();
      const response = await apiService.createWorker(value);
      form.resetFields();
      setCreateOpen(false);
      setToken(response.token);
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    }
  };

  const createToken = async (workerId: string) => {
    try {
      const response = await apiService.createWorkerToken(workerId);
      setToken(response.token);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '创建 Token 失败');
    }
  };

  const columns = [
    { title: '节点', key: 'worker', render: (_: unknown, worker: ProxyWorkerDTO) => <><strong>{worker.displayName}</strong><br /><Typography.Text type="secondary">{worker.workerId}</Typography.Text></> },
    { title: '状态', key: 'state', render: (_: unknown, worker: ProxyWorkerDTO) => <Space><Tag color={worker.enabled ? 'green' : 'red'}>{worker.enabled ? '启用' : '禁用'}</Tag><Tag color={worker.status?.online ? 'blue' : 'default'}>{worker.status?.online ? '在线' : '离线'}</Tag></Space> },
    { title: '主机名', dataIndex: ['status', 'hostname'], key: 'hostname', render: (value?: string) => value || '-' },
    { title: '入站', dataIndex: ['status', 'runningInboundCount'], key: 'inbounds', render: (value?: number) => value ?? 0 },
    { title: '连接', dataIndex: ['status', 'activeConnectionCount'], key: 'connections', render: (value?: number) => value ?? 0 },
    { title: '应用配置', key: 'hash', render: (_: unknown, worker: ProxyWorkerDTO) => worker.status?.appliedConfigHash?.slice(0, 12) || '-' },
    { title: '最近错误', key: 'error', render: (_: unknown, worker: ProxyWorkerDTO) => worker.status?.applyError || '-' },
    {
      title: '操作', key: 'actions', render: (_: unknown, worker: ProxyWorkerDTO) => (
        <Space>
          {worker.tokenPresent ? <Popconfirm title="删除后节点将立即无法访问管理端，确认删除？" onConfirm={() => apiService.deleteWorkerToken(worker.workerId).then(load)}><Button size="small" icon={<KeyOutlined />} danger>删除 Token</Button></Popconfirm>
            : <Button size="small" icon={<KeyOutlined />} onClick={() => void createToken(worker.workerId)}>创建 Token</Button>}
          {worker.enabled && <Popconfirm title="确认禁用节点？" onConfirm={() => apiService.disableWorker(worker.workerId).then(load)}><Button size="small" icon={<StopOutlined />}>禁用</Button></Popconfirm>}
          <Popconfirm title="仅无入站、任务和状态记录的节点可删除，确认继续？" onConfirm={() => apiService.deleteWorker(worker.workerId).then(load)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm>
        </Space>
      )
    }
  ];

  return <div className="inbound-management">
    <div className="page-header"><Typography.Title level={2}>代理节点</Typography.Title></div>
    <Card extra={<Space><Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} /><Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建节点</Button></Space>}>
      <Table rowKey="workerId" loading={loading} columns={columns} dataSource={workers} scroll={{ x: 1100 }} />
    </Card>
    <Modal title="新建代理节点" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => void create()}>
      <Form form={form} layout="vertical"><Form.Item name="workerId" label="节点标识" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9._-]{0,127}$/, message: '仅限小写字母、数字、点、下划线和连字符' }]}><Input /></Form.Item><Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="description" label="说明"><Input.TextArea rows={3} /></Form.Item></Form>
    </Modal>
    <Modal title="Worker Token" open={token !== null} footer={<Button type="primary" onClick={() => setToken(null)}>关闭</Button>} closable={false}>
      <Typography.Paragraph copyable={{ text: token || '' }}><Typography.Text code>{token}</Typography.Text></Typography.Paragraph>
    </Modal>
  </div>;
};

export default WorkerManagement;
