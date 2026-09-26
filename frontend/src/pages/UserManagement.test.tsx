import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import UserManagement from './UserManagement';
import { apiService } from '../services/api';
import { UserStatus } from '../types/user';

jest.mock('../services/api', () => ({
  apiService: {
    getUsers: jest.fn(),
    getUserById: jest.fn(),
    createUser: jest.fn(),
    updateUser: jest.fn(),
    deleteUser: jest.fn(),
    resetUserCredential: jest.fn(),
    batchDeleteUsers: jest.fn(),
    batchUpdateUserStatus: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 500 });
  window.dispatchEvent(new Event('resize'));
  (apiService.getUsers as jest.Mock).mockResolvedValue({
    items: [
      {
        id: 1,
        username: 'mobile-user',
        ipAddress: '10.0.0.10',
        status: UserStatus.ENABLED,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-02T00:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    pageSize: 10,
  });
  (apiService.getUserById as jest.Mock).mockResolvedValue({
    id: 2,
    username: 'worker-e2e',
    ipAddress: null,
    status: UserStatus.ENABLED,
    remark: 'persistent worker proxy E2E fixture',
    createdAt: '2026-09-12T06:32:23.743725',
    updatedAt: '2026-09-12T06:32:23.743725',
    credential: 'worker-e2e-pass',
  });
});

test('uses a compact column set on mobile', async () => {
  render(<UserManagement />);

  expect(await screen.findByText('mobile-user')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByRole('columnheader', { name: 'ID' })).not.toBeInTheDocument());
  expect(screen.queryByRole('columnheader', { name: '设备IP' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '创建时间' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: '更新时间' })).not.toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '用户名' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '操作' })).toBeInTheDocument();
});

test('shows every user field in the detail modal', async () => {
  render(<UserManagement />);

  expect(await screen.findByText('mobile-user')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '查看详情' }));

  expect(await screen.findByText('worker-e2e')).toBeInTheDocument();
  expect(screen.getByText('persistent worker proxy E2E fixture')).toBeInTheDocument();
  expect(screen.getAllByText('启用').length).toBeGreaterThanOrEqual(2);
  expect(screen.getByDisplayValue('worker-e2e-pass')).toBeInTheDocument();
  expect(screen.getByText('用户凭证')).toBeInTheDocument();
  expect(screen.getByText('设备IP')).toBeInTheDocument();
  expect(screen.getByText('备注')).toBeInTheDocument();
  expect(apiService.getUserById).toHaveBeenCalledWith(1);
});
