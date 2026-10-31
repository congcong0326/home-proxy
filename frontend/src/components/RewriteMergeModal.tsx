import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Checkbox, Form, Input, List, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { RouteAction, RouteDTO } from '../types/route';
import { RewriteMergePreview } from '../types/rewrite';
import { apiService } from '../services/api';

const { Text } = Typography;

interface RewriteMergeModalProps {
  open: boolean;
  routes: RouteDTO[];
  onCancel: () => void;
  onMerged: () => void;
}

interface MergeFormValues {
  tableName: string;
  routeName: string;
}

const isMergeable = (route: RouteDTO) => route.action === RouteAction.REWRITE_DESTINATION && !route.rewriteTableId;

/**
 * 把多条固定目标改写路由合并为改写表：先预览（dryRun），确认权限扩大与顺序变化后再执行。
 */
const RewriteMergeModal: React.FC<RewriteMergeModalProps> = ({ open, routes, onCancel, onMerged }) => {
  const [form] = Form.useForm<MergeFormValues>();
  const [preview, setPreview] = useState<RewriteMergePreview | null>(null);
  const [routeNames, setRouteNames] = useState<Record<number, string>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [loading, setLoading] = useState(false);
  const invalid = routes.filter((route) => !isMergeable(route));
  const warnings = useMemo(() => (preview?.bindings ?? [])
    .filter((binding) => binding.gainedRoutes.length || binding.reorderedRoutes.length), [preview]);

  useEffect(() => {
    if (!open) return;
    setPreview(null);
    setConfirmed(false);
    form.resetFields();
    apiService.getEnabledRoutes()
      .then((all) => setRouteNames(Object.fromEntries((all ?? []).map((route) => [route.id, route.name]))))
      .catch(() => setRouteNames({}));
  }, [open, form]);

  const request = async (dryRun: boolean) => {
    const values = await form.validateFields();
    setLoading(true);
    try {
      const result = await apiService.mergeRoutesIntoRewriteTable({
        routeIds: routes.map((route) => route.id),
        tableName: values.tableName.trim(),
        routeName: values.routeName.trim(),
        dryRun,
      });
      if (dryRun) {
        setPreview(result);
        setConfirmed(false);
      } else {
        message.success(`已合并为改写表，共 ${result.entries.length} 条，旧路由已禁用`);
        onMerged();
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '合并失败');
    } finally {
      setLoading(false);
    }
  };

  const describe = (ids: (number | null)[]) => ids
    .map((id) => (id === null ? '【新改写表路由】' : routeNames[id] ?? `#${id}`))
    .join(' → ');

  return (
    <Modal
      title="合并为改写表"
      open={open}
      width={860}
      onCancel={onCancel}
      okText={preview ? '确认合并' : '预览'}
      okButtonProps={{ disabled: invalid.length > 0 || (preview !== null && warnings.length > 0 && !confirmed) }}
      confirmLoading={loading}
      onOk={() => request(preview === null)}
      destroyOnClose
    >
      {invalid.length > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="只能合并固定目标的目标改写路由"
          description={invalid.map((route) => route.name).join('、')}
        />
      )}
      <Form form={form} layout="inline" onValuesChange={() => setPreview(null)} style={{ marginBottom: 16 }}>
        <Form.Item name="tableName" label="改写表名称" rules={[{ required: true, message: '请输入改写表名称' }]}>
          <Input placeholder="例如：日常服务" />
        </Form.Item>
        <Form.Item name="routeName" label="新路由名称" rules={[{ required: true, message: '请输入路由名称' }]}>
          <Input placeholder="例如：日常服务改写" />
        </Form.Item>
      </Form>
      <Text type="secondary">
        共 {routes.length} 条路由。新路由会放在每个绑定组中被合并路由第一次出现的位置，旧路由会被禁用但不删除。
      </Text>

      {preview && (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Table
            size="small"
            rowKey="domainPattern"
            pagination={false}
            dataSource={preview.entries}
            columns={[
              { title: '域名模式', dataIndex: 'domainPattern' },
              { title: '改写目标', render: (_, entry) => `${entry.targetHost}:${entry.targetPort}` },
              { title: '来源路由', dataIndex: 'notes' },
              { title: '状态', render: (_, entry) => (entry.enabled ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>) },
            ]}
          />
          <List
            size="small"
            header={<Text strong>受影响的绑定组（{preview.bindings.length}）</Text>}
            dataSource={preview.bindings}
            renderItem={(binding) => (
              <List.Item>
                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                  <Text strong>{binding.inboundName} · 绑定组 #{binding.bindingIndex}</Text>
                  <Text type="secondary">合并前：{describe(binding.before)}</Text>
                  <Text>合并后：{describe(binding.after)}</Text>
                  {binding.gainedRoutes.length > 0 && (
                    <Alert type="warning" showIcon message={`权限扩大：该组将新增 ${binding.gainedRoutes.join('、')}`} />
                  )}
                  {binding.reorderedRoutes.length > 0 && (
                    <Alert type="info" showIcon message={`顺序变化：${binding.reorderedRoutes.join('、')} 将排在改写条目之后`} />
                  )}
                </Space>
              </List.Item>
            )}
          />
          {warnings.length > 0 && (
            <Checkbox checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}>
              我已确认上述权限扩大和顺序变化
            </Checkbox>
          )}
        </Space>
      )}
    </Modal>
  );
};

export default RewriteMergeModal;
