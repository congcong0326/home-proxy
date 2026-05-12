import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DatabaseBackup from './DatabaseBackup';
import { apiService } from '../services/api';

jest.mock('../services/api', () => ({
  apiService: {
    exportMysqlBackup: jest.fn(),
    restoreMysqlBackup: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
});

test('exports mysql backup when export button is clicked', async () => {
  (apiService.exportMysqlBackup as jest.Mock).mockResolvedValue(undefined);

  render(<DatabaseBackup />);

  fireEvent.click(screen.getByRole('button', { name: /导出备份/ }));

  await waitFor(() => expect(apiService.exportMysqlBackup).toHaveBeenCalledTimes(1));
});

test('restores uploaded backup only after confirmation phrase is entered', async () => {
  (apiService.restoreMysqlBackup as jest.Mock).mockResolvedValue({ message: 'restore completed' });
  const file = new File(['SELECT 1;'], 'backup.sql', { type: 'application/sql' });

  render(<DatabaseBackup />);

  const restoreButton = screen.getByRole('button', { name: /开始恢复/ });
  expect(restoreButton).toBeDisabled();

  fireEvent.change(screen.getByLabelText('备份文件'), { target: { files: [file] } });
  fireEvent.change(screen.getByLabelText('确认短语'), { target: { value: 'RESTORE MYSQL' } });

  expect(restoreButton).not.toBeDisabled();
  fireEvent.click(restoreButton);

  await waitFor(() => expect(apiService.restoreMysqlBackup).toHaveBeenCalledWith(file, 'RESTORE MYSQL'));
});
