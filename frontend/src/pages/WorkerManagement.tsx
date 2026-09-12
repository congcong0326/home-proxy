import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography, message, Switch } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { apiService } from '../services/api';
import { ProxyWorkerDTO, WorkerCreateRequest } from '../types/worker';

const WorkerManagement: React.FC = () => {
  const [workers, setWorkers] = useState<ProxyWorkerDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingWorker, setEditingWorker] = useState<ProxyWorkerDTO | null>(null);
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

  const openCreate = () => {
    setEditingWorker(null);
    form.resetFields();
    setCreateOpen(true);
  };

  const openEdit = (worker: ProxyWorkerDTO) => {
    setEditingWorker(worker);
    form.setFieldsValue({
      workerId: worker.workerId,
      displayName: worker.displayName,
      description: worker.description,
      managementUrl: worker.managementUrl || '',
      managementToken: worker.managementToken || '',
      logReportingEnabled: worker.logReportingEnabled !== false,
    });
    setCreateOpen(true);
  };

  const save = async () => {
    try {
      const value = await form.validateFields();
      if (editingWorker) {
        await apiService.updateWorker(editingWorker.workerId, {
          displayName: value.displayName,
          description: value.description,
          managementUrl: value.managementUrl,
          managementToken: value.managementToken?.trim() || undefined,
          logReportingEnabled: value.logReportingEnabled,
        });
      } else {
        await apiService.createWorker(value);
      }
      form.resetFields();
      setCreateOpen(false);
      setEditingWorker(null);
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    }
  };

  const columns = [
    { title: '节点', key: 'worker', render: (_: unknown, worker: ProxyWorkerDTO) => <><strong>{worker.displayName}</strong><br /><Typography.Text type="secondary">{worker.workerId}</Typography.Text></> },
    { title: '状态', key: 'state', render: (_: unknown, worker: ProxyWorkerDTO) => <Space><Tag color={worker.enabled ? 'green' : 'red'}>{worker.enabled ? '启用' : '禁用'}</Tag><Tag color={worker.status?.online ? 'blue' : 'default'}>{worker.status?.online ? '在线' : '离线'}</Tag></Space> },
    { title: '日志上报', key: 'logReporting', render: (_: unknown, worker: ProxyWorkerDTO) => worker.logReportingEnabled !== false ? '开启' : '关闭' },
    { title: '管理地址', dataIndex: 'managementUrl', key: 'managementUrl', render: (value?: string) => value || '-' },
    { title: 'Worker 管理 Token', dataIndex: 'managementToken', key: 'managementToken', render: (value?: string) => value || '-' },
    { title: '主机名', dataIndex: ['status', 'hostname'], key: 'hostname', render: (value?: string) => value || '-' },
    { title: '入站', dataIndex: ['status', 'runningInboundCount'], key: 'inbounds', render: (value?: number) => value ?? 0 },
    { title: '连接', dataIndex: ['status', 'activeConnectionCount'], key: 'connections', render: (value?: number) => value ?? 0 },
    { title: '应用配置', key: 'hash', render: (_: unknown, worker: ProxyWorkerDTO) => worker.status?.appliedConfigHash?.slice(0, 12) || '-' },
    { title: '最近错误', key: 'error', render: (_: unknown, worker: ProxyWorkerDTO) => worker.status?.applyError || '-' },
    {
      title: '操作', key: 'actions', render: (_: unknown, worker: ProxyWorkerDTO) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(worker)}>编辑</Button>
          {worker.enabled && <Popconfirm title="确认禁用节点？" onConfirm={() => apiService.disableWorker(worker.workerId).then(load)}><Button size="small" icon={<StopOutlined />}>禁用</Button></Popconfirm>}
          <Popconfirm title="仅无入站、任务和状态记录的节点可删除，确认继续？" onConfirm={() => apiService.deleteWorker(worker.workerId).then(load)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm>
        </Space>
      )
    }
  ];

  return <div className="inbound-management">
    <div className="page-header"><Typography.Title level={2}>代理节点</Typography.Title></div>
    <Card extra={<Space><Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} /><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建节点</Button></Space>}>
      <Table rowKey="workerId" loading={loading} columns={columns} dataSource={workers} scroll={{ x: 1100 }} />
    </Card>
    <Modal title={editingWorker ? '编辑代理节点' : '新建代理节点'} open={createOpen} onCancel={() => { setCreateOpen(false); setEditingWorker(null); form.resetFields(); }} onOk={() => void save()}>
      <Form form={form} layout="vertical"><Form.Item name="workerId" label="节点标识"><Input disabled={Boolean(editingWorker)} /></Form.Item><Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="managementUrl" label="Worker WSS 地址" extra="填写 Worker 启动后监听的 wss:// 地址" rules={[{ required: true, pattern: /^wss:\/\/.+/, message: '请输入有效的 wss:// 地址' }]}><Input placeholder="wss://worker.example.com:18444/management/ws" /></Form.Item><Form.Item name="managementToken" label="Worker 管理 Token" extra="Token 由 Worker 生成，读取 worker.management.tokenFile 后粘贴到这里" rules={[{ required: true }]}><Input autoComplete="off" /></Form.Item><Form.Item name="logReportingEnabled" label="代理网关日志上报" valuePropName="checked" initialValue={true}><Switch checkedChildren="开启" unCheckedChildren="关闭" /></Form.Item><Form.Item name="description" label="说明"><Input.TextArea rows={3} /></Form.Item></Form>
    </Modal>
  </div>;
};

export default WorkerManagement;
