import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import RouteForm from './RouteForm';
import {
  MatchOp,
  RouteConditionType,
  RouteDTO,
  RoutePolicy,
  RouteStatus,
} from '../types/route';

const destinationOverrideRoute: RouteDTO = {
  id: 101,
  name: 'rewrite route',
  rules: [{ conditionType: RouteConditionType.DOMAIN, op: MatchOp.IN, value: 'example.com' }],
  policy: RoutePolicy.DESTINATION_OVERRIDE,
  outboundTag: 'legacy-tag',
  outboundProxyHost: 'rewrite.example.com',
  outboundProxyPort: 443,
  status: RouteStatus.ENABLED,
  notes: 'rewrite target only',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

test('does not show outbound tag for destination override routes', () => {
  render(
    <RouteForm
      initialValues={destinationOverrideRoute}
      mode="edit"
      onSubmit={jest.fn()}
      onCancel={jest.fn()}
    />
  );

  expect(screen.queryByText('出站标签')).not.toBeInTheDocument();
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

test('submits destination override routes without outbound tag', async () => {
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
    policy: RoutePolicy.DESTINATION_OVERRIDE,
    outboundProxyHost: 'rewrite.example.com',
    outboundProxyPort: 443,
  });
  expect(onSubmit.mock.calls[0][0]).not.toHaveProperty('outboundTag');
});
