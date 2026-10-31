import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Typography,
  message,
} from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  ImportOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { apiService } from '../services/api';
import { RewriteEntryDTO, RewriteTableDTO } from '../types/rewrite';

const { Text } = Typography;
const { TextArea } = Input;

interface EditorRow extends RewriteEntryDTO {
  key: string;
}

let rowSeq = 0;
const toRow = (entry: RewriteEntryDTO): EditorRow => ({ ...entry, key: `row-${rowSeq++}` });

const formatTarget = (host: string, port: number) => `${host.includes(':') ? `[${host}]` : host}:${port}`;

/** 文本格式：每行 `域名模式 host:port [备注]`，# 开头为注释或已禁用条目。 */
export const formatEntries = (entries: RewriteEntryDTO[]): string => entries
  .map((entry) => {
    const line = `${entry.domainPattern} ${formatTarget(entry.targetHost, entry.targetPort)}${entry.notes ? ` ${entry.notes}` : ''}`;
    return entry.enabled ? line : `# ${line}`;
  })
  .join('\n');

export const parseEntries = (text: string): RewriteEntryDTO[] => text.split(/\r?\n/)
  .map((line, index) => ({ line: line.trim(), index }))
  .filter(({ line }) => line && !line.startsWith('#'))
  .map(({ line, index }) => {
    const [pattern, target, ...notes] = line.split(/\s+/);
    const colon = target ? target.lastIndexOf(':') : -1;
    const port = colon > 0 ? Number(target.slice(colon + 1)) : NaN;
    if (!pattern || colon <= 0 || !Number.isInteger(port)) {
      throw new Error(`第 ${index + 1} 行格式应为：域名模式 host:port [备注]`);
    }
    const host = target.slice(0, colon).replace(/^\[(.*)\]$/, '$1');
    return { domainPattern: pattern.toLowerCase(), targetHost: host, targetPort: port, enabled: true, notes: notes.join(' ') || undefined };
  });

/** 改写表：按服务分组维护"域名 → 内网目标"，由目标改写路由引用。 */
export const RewriteTableManagement: React.FC = () => {
  const [tables, setTables] = useState<RewriteTableDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<RewriteTableDTO | undefined>();
  const [rows, setRows] = useState<EditorRow[]>([]);
  const [saving, setSaving] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState('');
  const [importReplace, setImportReplace] = useState(false);
  const [form] = Form.useForm<{ name: string; notes?: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTables(await apiService.getRewriteTables());
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载改写表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openEditor = (table?: RewriteTableDTO) => {
    setEditing(table);
    setRows((table?.entries ?? []).map(toRow));
    form.setFieldsValue({ name: table?.name ?? '', notes: table?.notes });
    setEditorOpen(true);
  };

  const updateRow = (key: string, patch: Partial<RewriteEntryDTO>) =>
    setRows((current) => current.map((row) => (row.key === key ? { ...row, ...patch } : row)));

  const moveRow = (index: number, offset: number) => setRows((current) => {
    const next = [...current];
    const [row] = next.splice(index, 1);
    next.splice(index + offset, 0, row);
    return next;
  });

  const applyImport = () => {
    try {
      const imported = parseEntries(importText);
      setRows((current) => {
        if (importReplace) return imported.map(toRow);
        const merged = [...current];
        imported.forEach((entry) => {
          const index = merged.findIndex((row) => row.domainPattern.toLowerCase() === entry.domainPattern);
          if (index >= 0) merged[index] = { ...merged[index], ...entry };
          else merged.push(toRow(entry));
        });
        return merged;
      });
      setImportOpen(false);
      setImportText('');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '导入失败');
    }
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const request = {
        name: values.name.trim(),
        notes: values.notes,
        entries: rows.map(({ key, ...entry }) => entry),
      };
      if (editing) {
        await apiService.updateRewriteTable(editing.id, request);
        message.success('改写表已保存，引用它的路由会随配置下发生效');
      } else {
        await apiService.createRewriteTable(request);
        message.success('改写表已创建，在路由中选择「目标改写 → 引用改写表」即可生效');
      }
      setEditorOpen(false);
      load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存改写表失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (table: RewriteTableDTO) => {
    try {
      await apiService.deleteRewriteTable(table.id);
      message.success('改写表已删除');
      load();
    } catch (error) {
      Modal.error({ title: '无法删除改写表', content: error instanceof Error ? error.message : '删除失败' });
    }
  };

  return (
    <Card
      title="改写表"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor()}>新建改写表</Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="按服务分组建表，各用户组在入站绑定中选择需要的改写表路由，以此控制谁能访问哪些内网服务。"
      />
      <Table
        rowKey="id"
        loading={loading}
        dataSource={tables}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name', render: (name: string) => <Text strong>{name}</Text> },
          {
            title: '条目',
            render: (_, table) => `${table.entries.filter((entry) => entry.enabled).length} / ${table.entries.length}`,
          },
          { title: '引用路由数', dataIndex: 'routeCount' },
          { title: '备注', dataIndex: 'notes', render: (notes?: string) => notes || '-' },
          {
            title: '操作',
            render: (_, table) => (
              <Space>
                <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(table)}>编辑</Button>
                <Popconfirm title={`确定删除改写表“${table.name}”吗？`} onConfirm={() => handleDelete(table)}>
                  <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editing ? `编辑改写表：${editing.name}` : '新建改写表'}
        open={editorOpen}
        width={1000}
        okText="保存"
        confirmLoading={saving}
        onOk={handleSave}
        onCancel={() => setEditorOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="inline" style={{ marginBottom: 16 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入改写表名称' }]}>
            <Input placeholder="例如：日常服务" />
          </Form.Item>
          <Form.Item name="notes" label="备注">
            <Input placeholder="可选" style={{ width: 320 }} />
          </Form.Item>
        </Form>
        <Space style={{ marginBottom: 8 }}>
          <Button
            icon={<PlusOutlined />}
            onClick={() => setRows((current) => [...current, toRow({ domainPattern: '', targetHost: '', targetPort: 80, enabled: true })])}
          >
            添加条目
          </Button>
          <Button icon={<ImportOutlined />} onClick={() => { setImportText(''); setImportOpen(true); }}>从文本导入</Button>
          <Button onClick={() => Modal.info({
            title: '导出为文本',
            width: 640,
            content: <TextArea readOnly autoSize={{ minRows: 4, maxRows: 16 }} value={formatEntries(rows)} />,
          })}
          >
            导出为文本
          </Button>
          <Text type="secondary">按顺序匹配：精确域名应放在通配之前。支持 a.com、.a.com（含子域）、*.a.com（仅子域）。</Text>
        </Space>
        <Table
          size="small"
          rowKey="key"
          dataSource={rows}
          pagination={false}
          scroll={{ y: 420 }}
          columns={[
            {
              title: '域名模式',
              render: (_, row) => (
                <Input value={row.domainPattern} placeholder="my-photos.com"
                  onChange={(event) => updateRow(row.key, { domainPattern: event.target.value })} />
              ),
            },
            {
              title: '目标地址',
              width: 180,
              render: (_, row) => (
                <Input value={row.targetHost} placeholder="192.168.10.85"
                  onChange={(event) => updateRow(row.key, { targetHost: event.target.value })} />
              ),
            },
            {
              title: '端口',
              width: 110,
              render: (_, row) => (
                <InputNumber value={row.targetPort} min={1} max={65535} style={{ width: '100%' }}
                  onChange={(value) => updateRow(row.key, { targetPort: Number(value) })} />
              ),
            },
            {
              title: '启用',
              width: 70,
              render: (_, row) => (
                <Switch size="small" checked={row.enabled} onChange={(checked) => updateRow(row.key, { enabled: checked })} />
              ),
            },
            {
              title: '备注',
              render: (_, row) => (
                <Input value={row.notes} onChange={(event) => updateRow(row.key, { notes: event.target.value })} />
              ),
            },
            {
              title: '操作',
              width: 130,
              render: (_, row, index) => (
                <Space size={4}>
                  <Button size="small" icon={<ArrowUpOutlined />} disabled={index === 0} onClick={() => moveRow(index, -1)} />
                  <Button size="small" icon={<ArrowDownOutlined />} disabled={index === rows.length - 1}
                    onClick={() => moveRow(index, 1)} />
                  <Button size="small" danger icon={<DeleteOutlined />}
                    onClick={() => setRows((current) => current.filter((item) => item.key !== row.key))} />
                </Space>
              ),
            },
          ]}
        />
      </Modal>

      <Modal title="从文本导入" open={importOpen} onOk={applyImport} onCancel={() => setImportOpen(false)} okText="导入">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Text type="secondary">每行一条：域名模式 host:port [备注]，# 开头的行会被忽略。同名域名模式按导入内容更新。</Text>
          <TextArea
            rows={10}
            value={importText}
            onChange={(event) => setImportText(event.target.value)}
            placeholder={'my-photos.com 192.168.10.85:18080 immich\nmy-emby.com 192.168.10.134:8096'}
          />
          <Checkbox checked={importReplace} onChange={(event) => setImportReplace(event.target.checked)}>
            替换现有条目（默认追加）
          </Checkbox>
        </Space>
      </Modal>
    </Card>
  );
};
