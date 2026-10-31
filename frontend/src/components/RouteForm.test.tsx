import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RouteForm from './RouteForm';
import {
  MatchOp,
  RouteAction,
  RouteConditionType,
  RouteDTO,
  RoutePolicy,
  RouteStatus,
} from '../types/route';
import { RewriteTableDTO } from '../types/rewrite';

const destinationOverrideRoute: RouteDTO = {
  id: 101,
  name: 'rewrite route',
  rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: 'example.com' }],
  action: RouteAction.REWRITE_DESTINATION,
  outboundId: 1,
  rewriteTargetHost: 'rewrite.example.com',
  rewriteTargetPort: 443,
  policy: RoutePolicy.DESTINATION_OVERRIDE,
  outboundTag: 'legacy-tag',
  status: RouteStatus.ENABLED,
  notes: 'rewrite target only',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const nasTable: RewriteTableDTO = {
  id: 5,
  name: 'NAS',
  routeCount: 1,
  entries: [{ id: 1, domainPattern: 'my-emby.com', targetHost: '192.168.10.134', targetPort: 8096, enabled: true }],
};

test('shows fixed rewrite target without outbound selection', () => {
  render(
    <RouteForm
      initialValues={destinationOverrideRoute}
      mode="edit"
      onSubmit={jest.fn()}
      onCancel={jest.fn()}
    />
  );

  expect(screen.queryByText('出站标签')).not.toBeInTheDocument();
  expect(screen.queryByText('上游代理和 DNS 上游在「出站管理」中维护')).not.toBeInTheDocument();
  expect(screen.getByText('目标地址')).toBeInTheDocument();
});

test('does not expose deprecated ad block route condition', () => {
  render(
    <RouteForm
      mode="create"
      onSubmit={jest.fn()}
      onCancel={jest.fn()}
    />
  );

  expect(screen.queryByText('去除广告')).not.toBeInTheDocument();
});

test('submits fixed destination rewrite with its target', async () => {
  const onSubmit = jest.fn().mockResolvedValue(undefined);

  render(
    <RouteForm
      initialValues={destinationOverrideRoute}
      mode="edit"
      onSubmit={onSubmit}
      onCancel={jest.fn()}
    />
  );

  fireEvent.click(screen.getByRole('button', { name: /保存修改/ }));

  await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  expect(onSubmit.mock.calls[0][0]).toMatchObject({
    action: RouteAction.REWRITE_DESTINATION,
    rewriteTargetHost: 'rewrite.example.com',
    rewriteTargetPort: 443,
    rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: 'example.com' }],
  });
  expect(onSubmit.mock.calls[0][0].outboundId).toBeUndefined();
  expect(onSubmit.mock.calls[0][0].rewriteTableId).toBeUndefined();
});

test('rewrite table route hides conditions and submits empty rules', async () => {
  const onSubmit = jest.fn().mockResolvedValue(undefined);

  render(
    <RouteForm
      initialValues={{
        ...destinationOverrideRoute,
        rules: [],
        rewriteTargetHost: undefined,
        rewriteTargetPort: undefined,
        rewriteTableId: 5,
      }}
      mode="edit"
      onSubmit={onSubmit}
      onCancel={jest.fn()}
      rewriteTables={[nasTable]}
    />
  );

  expect(screen.queryByText('路由规则说明')).not.toBeInTheDocument();
  expect(screen.getByText('表中 1 个域名即匹配条件，命中后改写到对应目标并直连')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /保存修改/ }));

  await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  expect(onSubmit.mock.calls[0][0]).toMatchObject({
    action: RouteAction.REWRITE_DESTINATION,
    rewriteTableId: 5,
    rules: [],
  });
  expect(onSubmit.mock.calls[0][0].rewriteTargetHost).toBeUndefined();
});
