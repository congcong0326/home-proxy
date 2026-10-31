import React from 'react';
import { render, screen } from '@testing-library/react';
import RouteOrderEditor from './RouteOrderEditor';

describe('RouteOrderEditor', () => {
  it('shows route name, target and status for disabled and deleted routes', () => {
    render(
      <RouteOrderEditor
        routeOptions={[
          { value: 1, label: '直连 → direct', name: '直连', target: 'direct' },
          { value: 16, label: '海外 → hk（已禁用）', name: '海外', target: 'hk', disabled: true },
        ]}
        value={[1, 16, 99]}
      />
    );

    expect(screen.getByText('直连')).toBeInTheDocument();
    expect(screen.getByText('海外')).toBeInTheDocument();
    expect(screen.getByText('→ hk · ID 16')).toBeInTheDocument();
    expect(screen.getByText('已禁用 · 不生效')).toBeInTheDocument();
    expect(screen.getByText('历史路由 #99（已删除或不可用）')).toBeInTheDocument();
    expect(screen.getByText('已删除')).toBeInTheDocument();
  });
});
