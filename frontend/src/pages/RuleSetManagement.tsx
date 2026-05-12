import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ApiOutlined,
  CloudSyncOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  FilterOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { apiService } from '../services/api';
import {
  CreateRuleSetRequest,
  RuleSetCategory,
  RuleSetItem,
  RuleSetItemType,
  RuleSetMatchTarget,
  RuleSetPageResponse,
  RuleSetQueryParams,
  RuleSetSummaryDTO,
  RuleSetSourceFormat,
  RuleSetSourceType,
  RuleSetSyncResult,
  RuleSetSyncStatus,
  UpdateRuleSetRequest,
  RULE_SET_CATEGORY_LABELS,
  RULE_SET_ITEM_TYPE_LABELS,
  RULE_SET_MATCH_TARGET_LABELS,
  RULE_SET_SOURCE_FORMAT_OPTIONS,
  RULE_SET_SOURCE_LABELS,
  RULE_SET_SYNC_STATUS_COLORS,
} from '../types/ruleset';
import './RuleSetManagement.css';

const { Text, Title, Paragraph } = Typography;
const { TextArea } = Input;
const DETAIL_DEFAULT_PAGE_SIZE = 50;

interface RuleSetFormValues {
  ruleKey: string;
  name: string;
  category: RuleSetCategory;
  matchTarget: RuleSetMatchTarget;
  sourceType: RuleSetSourceType;
  sourceUrl?: string;
  sourceFormat?: RuleSetSourceFormat;
  enabled: boolean;
  published: boolean;
  description?: string;
  items: RuleSetItem[];
}

const emptyRuleItem = (): RuleSetItem => ({
  type: RuleSetItemType.DOMAIN_SUFFIX,
  value: '',
});

const buildSourceConfig = (sourceType: RuleSetSourceType, values: RuleSetFormValues): string | undefined => {
  if (sourceType === RuleSetSourceType.MANUAL) {
    return undefined;
  }

  const sourceUrl = values.sourceUrl?.trim();
  if (!sourceUrl) {
    return undefined;
  }

  return JSON.stringify({
    url: sourceUrl,
    format: values.sourceFormat || RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY,
  });
};

const parseSourceConfig = (sourceConfig?: string): { sourceUrl?: string; sourceFormat?: RuleSetSourceFormat } => {
  if (!sourceConfig) {
    return {};
  }

  try {
    const parsed = JSON.parse(sourceConfig);
    return {
      sourceUrl: parsed.url,
      sourceFormat: parsed.format,
    };
  } catch {
    return { sourceUrl: sourceConfig };
  }
};

const RuleSetManagement: React.FC = () => {
  const [form] = Form.useForm<RuleSetFormValues>();
  const [ruleSets, setRuleSets] = useState<RuleSetSummaryDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  const [syncingIds, setSyncingIds] = useState<number[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<RuleSetCategory | undefined>();
  const [enabledFilter, setEnabledFilter] = useState<boolean | undefined>();
  const [publishedFilter, setPublishedFilter] = useState<boolean | undefined>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRuleSet, setEditingRuleSet] = useState<RuleSetSummaryDTO | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailItemsLoading, setDetailItemsLoading] = useState(false);
  const [detailRuleSet, setDetailRuleSet] = useState<RuleSetSummaryDTO | null>(null);
  const [detailItems, setDetailItems] = useState<RuleSetItem[]>([]);
  const [detailItemsTotal, setDetailItemsTotal] = useState(0);
  const [detailItemsPage, setDetailItemsPage] = useState(1);
  const [detailItemsPageSize, setDetailItemsPageSize] = useState(DETAIL_DEFAULT_PAGE_SIZE);
  const [fetchingDetailId, setFetchingDetailId] = useState<number | null>(null);
  const [latestBatchResults, setLatestBatchResults] = useState<RuleSetSyncResult[]>([]);

  const currentSourceType = Form.useWatch('sourceType', form) ?? RuleSetSourceType.MANUAL;
  const isManual = currentSourceType === RuleSetSourceType.MANUAL;
  const preserveExistingManualItems = Boolean(
    editingRuleSet &&
    editingRuleSet.sourceType === RuleSetSourceType.MANUAL &&
    currentSourceType === RuleSetSourceType.MANUAL
  );
  const showManualItemEditor = isManual && !preserveExistingManualItems;

  const loadRuleSets = useCallback(async () => {
    setLoading(true);
    try {
      const params: RuleSetQueryParams = {
        page,
        size: pageSize,
        sort: 'updatedAt',
        direction: 'desc',
        name: keyword || undefined,
        category: categoryFilter,
        enabled: enabledFilter,
        published: publishedFilter,
      };
      const response: RuleSetPageResponse<RuleSetSummaryDTO> = await apiService.getRuleSets(params);
      setRuleSets(response.items);
      setTotal(response.total);
      setPage(response.page);
      setPageSize(response.pageSize);
    } catch (error) {
      console.error('Failed to load rule sets:', error);
      message.error('加载规则集失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, keyword, categoryFilter, enabledFilter, publishedFilter]);

  useEffect(() => {
    loadRuleSets();
  }, [loadRuleSets]);

  const stats = useMemo(() => ({
    total,
    enabled: ruleSets.filter((item) => item.enabled).length,
    published: ruleSets.filter((item) => item.published).length,
    external: ruleSets.filter((item) => item.sourceType !== RuleSetSourceType.MANUAL).length,
  }), [ruleSets, total]);

  const openCreateModal = () => {
    setEditingRuleSet(null);
    setLatestBatchResults([]);
    form.resetFields();
    form.setFieldsValue({
      category: RuleSetCategory.AI,
      matchTarget: RuleSetMatchTarget.DOMAIN,
      sourceType: RuleSetSourceType.MANUAL,
      enabled: true,
      published: false,
      sourceFormat: RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY,
      items: [emptyRuleItem()],
    });
    setModalOpen(true);
  };

  const openEditModal = async (summary: RuleSetSummaryDTO) => {
    setFetchingDetailId(summary.id);
    try {
      const ruleSet = await apiService.getRuleSetById(summary.id);
      setEditingRuleSet(ruleSet);
      setLatestBatchResults([]);
      form.resetFields();
      const source = parseSourceConfig(ruleSet.sourceConfig);
      form.setFieldsValue({
        ruleKey: ruleSet.ruleKey,
        name: ruleSet.name,
        category: ruleSet.category,
        matchTarget: ruleSet.matchTarget,
        sourceType: ruleSet.sourceType,
        sourceUrl: source.sourceUrl,
        sourceFormat: source.sourceFormat ?? RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY,
        enabled: ruleSet.enabled,
        published: ruleSet.published,
        description: ruleSet.description,
        items: [emptyRuleItem()],
      });
      setModalOpen(true);
    } catch (error) {
      console.error('Failed to load rule set detail for editing:', error);
      message.error('加载规则集详情失败');
    } finally {
      setFetchingDetailId(null);
    }
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditingRuleSet(null);
    setFormLoading(false);
    form.resetFields();
  };

  const handleSubmit = async (values: RuleSetFormValues) => {
    setFormLoading(true);
    try {
      const manualItems = (values.items || [])
        .filter((item) => item?.value?.trim())
        .map((item) => ({ type: item.type, value: item.value.trim() }));
      const payload: CreateRuleSetRequest | UpdateRuleSetRequest = {
        ruleKey: values.ruleKey?.trim(),
        name: values.name?.trim(),
        category: values.category,
        matchTarget: values.matchTarget,
        sourceType: values.sourceType,
        sourceConfig: buildSourceConfig(values.sourceType, values),
        enabled: values.enabled,
        published: values.published,
        description: values.description?.trim(),
      };

      if (!editingRuleSet) {
        payload.items = values.sourceType === RuleSetSourceType.MANUAL ? manualItems : [];
      } else if (editingRuleSet.sourceType === RuleSetSourceType.MANUAL) {
        if (values.sourceType !== RuleSetSourceType.MANUAL) {
          payload.items = [];
        }
      } else if (values.sourceType === RuleSetSourceType.MANUAL) {
        payload.items = manualItems;
      }

      if (editingRuleSet) {
        await apiService.updateRuleSet(editingRuleSet.id, payload as UpdateRuleSetRequest);
        message.success('规则集更新成功');
      } else {
        await apiService.createRuleSet(payload as CreateRuleSetRequest);
        message.success('规则集创建成功');
      }

      closeModal();
      loadRuleSets();
    } catch (error) {
      console.error('Failed to save rule set:', error);
      message.error(editingRuleSet ? '更新规则集失败' : '创建规则集失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await apiService.deleteRuleSet(id);
      message.success('规则集删除成功');
      setSelectedRowKeys((prev) => prev.filter((key) => key !== id));
      loadRuleSets();
    } catch (error) {
      console.error('Failed to delete rule set:', error);
      message.error('删除规则集失败');
    }
  };

  const handleSyncOne = async (ruleSet: Pick<RuleSetSummaryDTO, 'id' | 'ruleKey'>) => {
    setSyncingIds((prev) => [...prev, ruleSet.id]);
    try {
      await apiService.syncRuleSet(ruleSet.id);
      message.success(`规则集 ${ruleSet.ruleKey} 同步成功`);
      loadRuleSets();
    } catch (error) {
      console.error('Failed to sync rule set:', error);
      message.error(`规则集 ${ruleSet.ruleKey} 同步失败`);
    } finally {
      setSyncingIds((prev) => prev.filter((id) => id !== ruleSet.id));
    }
  };

  const loadDetailItems = useCallback(async (ruleSetId: number, targetPage: number, targetPageSize: number) => {
    setDetailItemsLoading(true);
    try {
      const response = await apiService.getRuleSetItems(ruleSetId, {
        page: targetPage,
        size: targetPageSize,
      });
      setDetailItems(response.items);
      setDetailItemsTotal(response.total);
      setDetailItemsPage(response.page);
      setDetailItemsPageSize(response.pageSize);
    } catch (error) {
      console.error('Failed to load rule set items:', error);
      message.error('加载规则项失败');
    } finally {
      setDetailItemsLoading(false);
    }
  }, []);

  const handleViewDetail = async (ruleSetId: number) => {
    setDetailLoading(true);
    setFetchingDetailId(ruleSetId);
    setDetailRuleSet(null);
    setDetailItems([]);
    setDetailItemsTotal(0);
    setDetailItemsPage(1);
    setDetailItemsPageSize(DETAIL_DEFAULT_PAGE_SIZE);
    setDetailOpen(true);
    try {
      const detail = await apiService.getRuleSetById(ruleSetId);
      setDetailRuleSet(detail);
      await loadDetailItems(ruleSetId, 1, DETAIL_DEFAULT_PAGE_SIZE);
    } catch (error) {
      console.error('Failed to load rule set detail:', error);
      message.error('加载规则集详情失败');
      setDetailOpen(false);
    } finally {
      setDetailLoading(false);
      setFetchingDetailId(null);
    }
  };

  const handleBatchSync = async () => {
    const ids = selectedRowKeys.map((key) => Number(key));
    setLatestBatchResults([]);
    try {
      const results = await apiService.syncRuleSets({
        ruleSetIds: ids.length ? ids : undefined,
        enabledOnly: ids.length ? false : true,
        publishedOnly: false,
      });
      setLatestBatchResults(results);
      const failedCount = results.filter((item) => item.status === RuleSetSyncStatus.FAILED).length;
      if (failedCount > 0) {
        message.warning(`批量同步完成，${failedCount} 条失败`);
      } else {
        message.success(`批量同步完成，共处理 ${results.length} 条`);
      }
      loadRuleSets();
    } catch (error) {
      console.error('Failed to batch sync rule sets:', error);
      message.error('批量同步失败');
    }
  };

  const columns = [
    {
      title: '规则集',
      key: 'ruleKey',
      render: (_: unknown, record: RuleSetSummaryDTO) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.name}</Text>
          <Text type="secondary" code>{record.ruleKey}</Text>
        </Space>
      ),
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      width: 110,
      render: (value: RuleSetCategory) => <Tag color="blue">{RULE_SET_CATEGORY_LABELS[value]}</Tag>,
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      key: 'sourceType',
      width: 140,
      render: (value: RuleSetSourceType) => (
        <Space size={4}>
          {value === RuleSetSourceType.MANUAL ? <EditOutlined /> : <LinkOutlined />}
          <Text>{RULE_SET_SOURCE_LABELS[value]}</Text>
        </Space>
      ),
    },
    {
      title: '匹配目标',
      dataIndex: 'matchTarget',
      key: 'matchTarget',
      width: 100,
      render: (value: RuleSetMatchTarget) => RULE_SET_MATCH_TARGET_LABELS[value],
    },
    {
      title: '规则项',
      key: 'items',
      width: 120,
      render: (_: unknown, record: RuleSetSummaryDTO) => <Badge count={record.itemCount ?? 0} color="#1677ff" />,
    },
    {
      title: '状态',
      key: 'status',
      width: 140,
      render: (_: unknown, record: RuleSetSummaryDTO) => (
        <Space direction="vertical" size={4}>
          <Tag color={record.enabled ? 'green' : 'default'}>{record.enabled ? '启用' : '禁用'}</Tag>
          <Tag color={record.published ? 'gold' : 'default'}>{record.published ? '已发布' : '未发布'}</Tag>
        </Space>
      ),
    },
    {
      title: '版本',
      dataIndex: 'versionNo',
      key: 'versionNo',
      width: 90,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (value: string) => new Date(value).toLocaleString(),
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_: unknown, record: RuleSetSummaryDTO) => (
        <Space wrap>
          <Button
            size="small"
            icon={<EyeOutlined />}
            loading={fetchingDetailId === record.id && detailOpen}
            onClick={() => handleViewDetail(record.id)}
          >
            详情
          </Button>
          <Button
            size="small"
            icon={<EditOutlined />}
            loading={fetchingDetailId === record.id && !detailOpen}
            onClick={() => openEditModal(record)}
          >
            编辑
          </Button>
          {record.sourceType !== RuleSetSourceType.MANUAL && (
            <Button
              size="small"
              icon={<CloudSyncOutlined />}
              loading={syncingIds.includes(record.id)}
              onClick={() => handleSyncOne(record)}
            >
              同步
            </Button>
          )}
          <Popconfirm
            title={`确定删除规则集 ${record.name} 吗？`}
            onConfirm={() => handleDelete(record.id)}
            okText="删除"
            cancelText="取消"
          >
            <Button size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="rule-set-management">
      <div>
        <Title level={2}>
          <RobotOutlined /> 规则集管理
        </Title>
        <Paragraph type="secondary">
          统一管理 AI / GEO / 广告等规则集。外部规则源只在控制面同步，路由规则只引用内部 `ruleKey`。
        </Paragraph>
      </div>

      <div className="rule-set-grid">
        <Card><Statistic title="规则集总数" value={stats.total} prefix={<ApiOutlined />} /></Card>
        <Card><Statistic title="启用中" value={stats.enabled} valueStyle={{ color: '#1677ff' }} /></Card>
        <Card><Statistic title="已发布" value={stats.published} valueStyle={{ color: '#d48806' }} /></Card>
        <Card><Statistic title="外部源" value={stats.external} valueStyle={{ color: '#389e0d' }} /></Card>
      </div>

      <Card>
        <div className="rule-set-toolbar">
          <div className="rule-set-toolbar-left">
            <Input.Search
              placeholder="搜索规则集名称或 key"
              allowClear
              style={{ width: 320 }}
              onSearch={(value) => {
                setKeyword(value);
                setPage(1);
              }}
            />
            <Select
              allowClear
              placeholder="分类"
              style={{ width: 140 }}
              onChange={(value) => {
                setCategoryFilter(value);
                setPage(1);
              }}
              options={Object.values(RuleSetCategory).map((value) => ({
                value,
                label: RULE_SET_CATEGORY_LABELS[value],
              }))}
            />
            <Select
              allowClear
              placeholder="启用状态"
              style={{ width: 120 }}
              suffixIcon={<FilterOutlined />}
              onChange={(value) => {
                setEnabledFilter(value);
                setPage(1);
              }}
              options={[
                { value: true, label: '启用' },
                { value: false, label: '禁用' },
              ]}
            />
            <Select
              allowClear
              placeholder="发布状态"
              style={{ width: 120 }}
              suffixIcon={<FilterOutlined />}
              onChange={(value) => {
                setPublishedFilter(value);
                setPage(1);
              }}
              options={[
                { value: true, label: '已发布' },
                { value: false, label: '未发布' },
              ]}
            />
          </div>

          <div className="rule-set-toolbar-right">
            <Button
              icon={<CloudSyncOutlined />}
              disabled={loading}
              onClick={handleBatchSync}
            >
              {selectedRowKeys.length > 0 ? `同步选中 (${selectedRowKeys.length})` : '同步全部外部规则集'}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={loadRuleSets} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
              新建规则集
            </Button>
          </div>
        </div>

        {latestBatchResults.length > 0 && (
          <div className="rule-set-sync-result">
            <Divider orientation="left">最近一次批量同步</Divider>
            <Space wrap>
              {latestBatchResults.map((result) => (
                <Tooltip key={result.ruleSetId} title={result.message || `${result.itemCount} 条规则，版本 ${result.versionNo}`}>
                  <Tag color={RULE_SET_SYNC_STATUS_COLORS[result.status]}>
                    {result.ruleKey}: {result.status}
                  </Tag>
                </Tooltip>
              ))}
            </Space>
          </div>
        )}
      </Card>

      <Card>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={ruleSets}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys,
          }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (value, range) => `第 ${range[0]}-${range[1]} 条，共 ${value} 条`,
            onChange: (nextPage, nextPageSize) => {
              setPage(nextPage);
              setPageSize(nextPageSize);
            },
          }}
          scroll={{ x: 1280 }}
        />
      </Card>

      <Modal
        title={editingRuleSet ? '编辑规则集' : '新建规则集'}
        open={modalOpen}
        onCancel={closeModal}
        footer={null}
        width={960}
        destroyOnClose
      >
        <Form<RuleSetFormValues>
          layout="vertical"
          form={form}
          onFinish={handleSubmit}
          initialValues={{
            category: RuleSetCategory.AI,
            matchTarget: RuleSetMatchTarget.DOMAIN,
            sourceType: RuleSetSourceType.MANUAL,
            enabled: true,
            published: false,
            sourceFormat: RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY,
            items: [emptyRuleItem()],
          }}
        >
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="ruleKey" label="规则集 Key" rules={[{ required: true, message: '请输入规则集 key' }]}>
                <Input placeholder="例如：ai-openai" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入规则集名称' }]}>
                <Input placeholder="例如：OpenAI 规则集" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="category" label="分类" rules={[{ required: true }]}>
                <Select
                  options={Object.values(RuleSetCategory).map((value) => ({
                    value,
                    label: RULE_SET_CATEGORY_LABELS[value],
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="matchTarget" label="匹配目标" rules={[{ required: true }]}>
                <Select
                  options={Object.values(RuleSetMatchTarget).map((value) => ({
                    value,
                    label: RULE_SET_MATCH_TARGET_LABELS[value],
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="sourceType" label="来源类型" rules={[{ required: true }]}>
                <Select
                  options={Object.values(RuleSetSourceType).map((value) => ({
                    value,
                    label: RULE_SET_SOURCE_LABELS[value],
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>

          {!isManual && (
            <>
              <Row gutter={16}>
                <Col span={16}>
                  <Form.Item
                    name="sourceUrl"
                    label="规则源 URL"
                    rules={[{ required: true, message: '请输入规则源地址' }]}
                  >
                    <Input placeholder="https://raw.githubusercontent.com/.../OpenAI.yaml" />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    name="sourceFormat"
                    label="解析格式"
                    rules={[{ required: true, message: '请选择解析格式' }]}
                  >
                    <Select options={RULE_SET_SOURCE_FORMAT_OPTIONS} />
                  </Form.Item>
                </Col>
              </Row>
              <Card size="small" className="rule-set-source-help">
                <Text strong>sourceConfig 预览</Text>
                <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  前端会自动把 URL 和格式拼成后端需要的 JSON。推荐 `domain-list-community` 用于 `v2fly/domain-list-community`，
                  `Clash Classical` 用于 `blackmatrix7/ios_rule_script`。
                </Paragraph>
              </Card>
            </>
          )}

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="published" label="发布到 Worker" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="description" label="描述">
            <TextArea rows={3} placeholder="说明这个规则集的用途、来源和分流策略" />
          </Form.Item>

          {preserveExistingManualItems && (
            <Alert
              type="info"
              showIcon
              message="当前编辑页不加载规则项"
              description={`该手工规则集现有 ${editingRuleSet?.itemCount ?? 0} 条规则项，保存时会保留现有数据；如需查看明细，请打开详情页。`}
              style={{ marginBottom: 16 }}
            />
          )}

          {showManualItemEditor && (
            <>
              <Divider orientation="left">规则项</Divider>
              <Form.List name="items">
                {(fields, { add, remove }) => (
                  <Space direction="vertical" style={{ width: '100%' }} size={12}>
                    {fields.map((field, index) => (
                      <Row gutter={12} key={field.key}>
                        <Col span={7}>
                          <Form.Item
                            {...field}
                            name={[field.name, 'type']}
                            label={index === 0 ? '类型' : ' '}
                            rules={[{ required: true, message: '请选择规则类型' }]}
                          >
                            <Select
                              options={Object.values(RuleSetItemType).map((value) => ({
                                value,
                                label: RULE_SET_ITEM_TYPE_LABELS[value],
                              }))}
                            />
                          </Form.Item>
                        </Col>
                        <Col span={13}>
                          <Form.Item
                            {...field}
                            name={[field.name, 'value']}
                            label={index === 0 ? '值' : ' '}
                            rules={[{ required: true, message: '请输入规则值' }]}
                          >
                            <Input placeholder="例如：openai.com 或 chat.openai.com" />
                          </Form.Item>
                        </Col>
                        <Col span={4}>
                          <Form.Item label={index === 0 ? '操作' : ' '}>
                            <Space>
                              <Button onClick={() => add(emptyRuleItem())}>添加</Button>
                              {fields.length > 1 && (
                                <Button danger onClick={() => remove(field.name)}>
                                  删除
                                </Button>
                              )}
                            </Space>
                          </Form.Item>
                        </Col>
                      </Row>
                    ))}
                  </Space>
                )}
              </Form.List>
            </>
          )}

          <Divider />
          <Space>
            <Button type="primary" htmlType="submit" loading={formLoading}>
              {editingRuleSet ? '保存变更' : '创建规则集'}
            </Button>
            <Button onClick={closeModal}>取消</Button>
          </Space>
        </Form>
      </Modal>

      <Drawer
        title={detailRuleSet ? `规则集详情 · ${detailRuleSet.name}` : '规则集详情'}
        width={960}
        open={detailOpen}
        onClose={() => {
          setDetailOpen(false);
          setDetailRuleSet(null);
          setDetailItems([]);
          setDetailItemsTotal(0);
          setDetailItemsPage(1);
          setDetailItemsPageSize(DETAIL_DEFAULT_PAGE_SIZE);
        }}
        destroyOnClose
      >
        {detailRuleSet && (
          <div className="rule-set-detail">
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label="规则集 Key">{detailRuleSet.ruleKey}</Descriptions.Item>
              <Descriptions.Item label="名称">{detailRuleSet.name}</Descriptions.Item>
              <Descriptions.Item label="分类">{RULE_SET_CATEGORY_LABELS[detailRuleSet.category]}</Descriptions.Item>
              <Descriptions.Item label="匹配目标">{RULE_SET_MATCH_TARGET_LABELS[detailRuleSet.matchTarget]}</Descriptions.Item>
              <Descriptions.Item label="来源类型">{RULE_SET_SOURCE_LABELS[detailRuleSet.sourceType]}</Descriptions.Item>
              <Descriptions.Item label="规则数量">{detailRuleSet.itemCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="启用状态">
                <Tag color={detailRuleSet.enabled ? 'green' : 'default'}>{detailRuleSet.enabled ? '启用' : '禁用'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="发布状态">
                <Tag color={detailRuleSet.published ? 'gold' : 'default'}>{detailRuleSet.published ? '已发布' : '未发布'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="版本">{detailRuleSet.versionNo}</Descriptions.Item>
              <Descriptions.Item label="更新时间">{new Date(detailRuleSet.updatedAt).toLocaleString()}</Descriptions.Item>
              <Descriptions.Item label="规则源配置" span={2}>
                <pre className="rule-set-detail-code">
                  <code>{detailRuleSet.sourceConfig || 'MANUAL 规则集，无外部 sourceConfig'}</code>
                </pre>
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={2}>
                {detailRuleSet.description || '-'}
              </Descriptions.Item>
            </Descriptions>

            <Divider orientation="left">规则项数据</Divider>
            <Table
              rowKey={(_, index) => `${detailRuleSet.ruleKey}-${detailItemsPage}-${index ?? 0}`}
              loading={detailLoading || detailItemsLoading}
              size="small"
              dataSource={detailItems}
              pagination={{
                current: detailItemsPage,
                pageSize: detailItemsPageSize,
                total: detailItemsTotal,
                showSizeChanger: true,
                showTotal: (value, range) => `第 ${range[0]}-${range[1]} 条，共 ${value} 条`,
                onChange: (nextPage, nextPageSize) => {
                  void loadDetailItems(detailRuleSet.id, nextPage, nextPageSize);
                },
              }}
              scroll={{ y: 420, x: 720 }}
              columns={[
                {
                  title: '序号',
                  key: 'index',
                  width: 80,
                  render: (_value, _record, index) => (detailItemsPage - 1) * detailItemsPageSize + index + 1,
                },
                {
                  title: '类型',
                  dataIndex: 'type',
                  key: 'type',
                  width: 160,
                  render: (value: RuleSetItemType) => RULE_SET_ITEM_TYPE_LABELS[value],
                },
                {
                  title: '值',
                  dataIndex: 'value',
                  key: 'value',
                  render: (value: string) => <Text code>{value}</Text>,
                },
              ]}
            />
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default RuleSetManagement;
