import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { apiService } from '../services/api';
import {
  OUTBOUND_KIND_COLORS,
  OUTBOUND_KIND_LABELS,
  OutboundDTO,
  OutboundRequest,
} from '../types/outbound';
import { PROTOCOL_TYPE_LABELS } from '../types/route';
import OutboundForm from '../components/OutboundForm';

const { Text } = Typography;

/** 出站管理：上游代理与 DNS 上游；直连、阻断为内置出站，只读。 */
export const OutboundManagement: React.FC = () => {
  const [outbounds, setOutbounds] = useState<OutboundDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<OutboundDTO | undefined>();
  const [saving, setSaving] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setOutbounds(await apiService.getOutbounds());
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载出站失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openForm = (outbound?: OutboundDTO) => {
    setEditing(outbound);
    setFormOpen(true);
  };

  const handleSubmit = async (values: OutboundRequest) => {
    setSaving(true);
    try {
      if (editing) {
        await apiService.updateOutbound(editing.id, values);
        message.success('出站已更新，引用它的路由会随配置下发生效');
      } else {
        await apiService.createOutbound(values);
        message.success('出站已创建');
      }
      setFormOpen(false);
      load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存出站失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (outbound: OutboundDTO) => {
    try {
      await apiService.deleteOutbound(outbound.id);
      message.success('出站已删除');
      load();
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : '删除出站失败');
    }
  };

  return (
    <Card
      title="出站管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openForm()}>新建出站</Button>
        </Space>
      }
    >
      <Table
        rowKey="id"
        loading={loading}
        dataSource={outbounds}
        pagination={false}
        columns={[
          {
            title: '名称',
            dataIndex: 'name',
            render: (name: string, record) => (
              <Space>
                <Text strong>{name}</Text>
                {record.builtin && <Tag>内置</Tag>}
              </Space>
            ),
          },
          {
            title: '类别',
            dataIndex: 'kind',
            render: (kind: OutboundDTO['kind']) => <Tag color={OUTBOUND_KIND_COLORS[kind]}>{OUTBOUND_KIND_LABELS[kind]}</Tag>,
          },
          {
            title: '协议',
            dataIndex: 'proxyType',
            render: (type?: OutboundDTO['proxyType']) =>
              type ? PROTOCOL_TYPE_LABELS[type as keyof typeof PROTOCOL_TYPE_LABELS] : '-',
          },
          {
            title: '地址',
            render: (_, record) => (record.host ? <Text code>{record.host}:{record.port}</Text> : '-'),
          },
          { title: '引用路由数', dataIndex: 'routeCount' },
          { title: '备注', dataIndex: 'notes', render: (notes?: string) => notes || '-' },
          {
            title: '操作',
            render: (_, record) => record.builtin ? <Text type="secondary">内置，不可修改</Text> : (
              <Space>
                <Button size="small" icon={<EditOutlined />} onClick={() => openForm(record)}>编辑</Button>
                <Popconfirm title={`确定删除出站“${record.name}”吗？`} onConfirm={() => handleDelete(record)}>
                  <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editing ? '编辑出站' : '新建出站'}
        open={formOpen}
        footer={null}
        width={760}
        onCancel={() => setFormOpen(false)}
        destroyOnClose
      >
        {editing?.routeCount ? (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`有 ${editing.routeCount} 条路由引用该出站，保存后它们会一起使用新参数`}
          />
        ) : null}
        <OutboundForm
          initialValues={editing}
          onSubmit={handleSubmit}
          onCancel={() => setFormOpen(false)}
          loading={saving}
        />
      </Modal>

      <Modal title="无法删除出站" open={deleteError !== null} onOk={() => setDeleteError(null)}
        onCancel={() => setDeleteError(null)} cancelButtonProps={{ style: { display: 'none' } }}>
        <Text style={{ whiteSpace: 'pre-wrap' }}>{deleteError}</Text>
      </Modal>
    </Card>
  );
};
