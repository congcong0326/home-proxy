import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import UserManagement from './UserManagement';
import { apiService } from '../services/api';
import { UserStatus } from '../types/user';

jest.mock('../services/api', () => ({
  apiService: {
    getUsers: jest.fn(),
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
