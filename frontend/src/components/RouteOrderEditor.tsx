import React, { useMemo, useState } from 'react';
import { Button, Empty, Select, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, MenuOutlined, PlusOutlined } from '@ant-design/icons';

interface RouteSelectOption {
  value: number;
  label: string;
  disabled?: boolean;
}

interface RouteOrderEditorProps {
  routeOptions: RouteSelectOption[];
  value?: number[];
  onChange?: (value: number[]) => void;
}

const isNumber = (value: unknown): value is number => typeof value === 'number';

const RouteOrderEditor: React.FC<RouteOrderEditorProps> = ({ routeOptions, value, onChange }) => {
  const routeIds = useMemo(
    () => (value ?? []).filter(isNumber),
    [value]
  );
  const [draggingIndex, setDraggingIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  const mergedRouteOptions = useMemo(() => {
    const optionMap = new Map<number, RouteSelectOption>();

    routeOptions.forEach((option) => {
      optionMap.set(option.value, option);
    });

    routeIds.forEach((routeId) => {
      if (!optionMap.has(routeId)) {
        optionMap.set(routeId, {
          value: routeId,
          label: `历史路由 #${routeId}（已删除或不可用）`,
        });
      }
    });

    return Array.from(optionMap.values());
  }, [routeOptions, routeIds]);

  const selectedRouteOptions = useMemo(() => {
    const optionMap = new Map(mergedRouteOptions.map((option) => [option.value, option] as const));
    return routeIds.map((routeId) => ({
      value: routeId,
      label: optionMap.get(routeId)?.label ?? `历史路由 #${routeId}`,
      disabled: optionMap.get(routeId)?.disabled ?? false,
    }));
  }, [mergedRouteOptions, routeIds]);

  const addableRouteOptions = useMemo(
    () => mergedRouteOptions.filter((option) => !routeIds.includes(option.value) && !option.disabled),
    [mergedRouteOptions, routeIds]
  );

  const moveRoute = (fromIndex: number, toIndex: number) => {
    if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0) {
      return;
    }

    const next = [...routeIds];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    onChange?.(next);
  };

  const removeRoute = (routeId: number) => {
    onChange?.(routeIds.filter((id) => id !== routeId));
  };

  const appendRoute = (routeId: number) => {
    if (routeIds.includes(routeId)) {
      return;
    }
    onChange?.([...routeIds, routeId]);
  };

  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      {selectedRouteOptions.length ? (
        selectedRouteOptions.map((option, index) => {
          const isActiveDropTarget = dragOverIndex === index && draggingIndex !== index;

          return (
            <div
              key={option.value}
              onDragOver={(event) => {
                event.preventDefault();
                if (dragOverIndex !== index) {
                  setDragOverIndex(index);
                }
              }}
              onDrop={(event) => {
                event.preventDefault();
                if (draggingIndex !== null) {
                  moveRoute(draggingIndex, index);
                }
                setDraggingIndex(null);
                setDragOverIndex(null);
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '10px 12px',
                borderRadius: 10,
                border: isActiveDropTarget ? '1px solid #1677ff' : '1px solid #d9d9d9',
                background: isActiveDropTarget ? '#e6f4ff' : '#fafafa',
                transition: 'all 0.2s ease',
              }}
            >
              <div
                draggable
                onDragStart={() => {
                  setDraggingIndex(index);
                  setDragOverIndex(index);
                }}
                onDragEnd={() => {
                  setDraggingIndex(null);
                  setDragOverIndex(null);
                }}
                style={{
                  cursor: 'grab',
                  color: '#8c8c8c',
                  display: 'flex',
                  alignItems: 'center',
                }}
                aria-label={`拖动排序 ${option.label}`}
              >
                <MenuOutlined />
              </div>

              <Tag color="blue" style={{ marginInlineEnd: 0, minWidth: 28, textAlign: 'center' }}>
                {index + 1}
              </Tag>

              <div style={{ flex: 1, minWidth: 0 }}>
                <Typography.Text strong ellipsis={{ tooltip: option.label }} style={{ display: 'block' }}>
                  {option.label}
                </Typography.Text>
                <Typography.Text type="secondary">路由 ID: {option.value}</Typography.Text>
              </div>

              {option.disabled ? <Tag color="default">已禁用</Tag> : null}

              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => removeRoute(option.value)}
              >
                删除
              </Button>
            </div>
          );
        })
      ) : (
        <div
          style={{
            border: '1px dashed #d9d9d9',
            borderRadius: 10,
            padding: '18px 12px',
            background: '#fafafa',
          }}
        >
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有添加路由" />
        </div>
      )}

      <div
        style={{
          borderRadius: 10,
          border: '1px dashed #d9d9d9',
          padding: 12,
          background: '#fff',
        }}
      >
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          拖动左侧手柄调整顺序，顶部优先匹配。
        </Typography.Text>
        <Select<number>
          showSearch
          placeholder="追加一个路由"
          optionFilterProp="label"
          options={addableRouteOptions}
          onSelect={(routeId) => appendRoute(routeId)}
          value={undefined}
          style={{ width: '100%' }}
          suffixIcon={<PlusOutlined />}
          disabled={!addableRouteOptions.length}
        />
      </div>
    </Space>
  );
};

export default RouteOrderEditor;
