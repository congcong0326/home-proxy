import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DatabaseBackup from './DatabaseBackup';
import { apiService } from '../services/api';

jest.mock('../services/api', () => ({
  apiService: {
    exportDatabaseBackup: jest.fn(),
    restoreDatabaseBackup: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
});

test('exports database backup when export button is clicked', async () => {
  (apiService.exportDatabaseBackup as jest.Mock).mockResolvedValue(undefined);

  render(<DatabaseBackup />);

  fireEvent.click(screen.getByRole('button', { name: /导出备份/ }));

  await waitFor(() => expect(apiService.exportDatabaseBackup).toHaveBeenCalledTimes(1));
});

test('restores uploaded backup only after confirmation phrase is entered', async () => {
  (apiService.restoreDatabaseBackup as jest.Mock).mockResolvedValue({ message: 'restore staged' });
  const file = new File(['SQLite format 3'], 'backup.db', { type: 'application/octet-stream' });

  render(<DatabaseBackup />);

  const restoreButton = screen.getByRole('button', { name: /开始恢复/ });
  expect(restoreButton).toBeDisabled();

  fireEvent.change(screen.getByLabelText('备份文件'), { target: { files: [file] } });
  fireEvent.change(screen.getByLabelText('确认短语'), { target: { value: 'RESTORE DATABASE' } });

  expect(restoreButton).not.toBeDisabled();
  fireEvent.click(restoreButton);

  await waitFor(() => expect(apiService.restoreDatabaseBackup).toHaveBeenCalledWith(file, 'RESTORE DATABASE'));
});
