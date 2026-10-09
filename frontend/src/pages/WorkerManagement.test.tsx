import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { WorkerManagement } from './WorkerManagement';
import { apiService } from '../services/api';

jest.mock('../services/api', () => ({
  apiService: {
    getWorkers: jest.fn(),
    createWorker: jest.fn(),
    updateWorker: jest.fn(),
    disableWorker: jest.fn(),
    deleteWorker: jest.fn(),
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
  (apiService.getWorkers as jest.Mock).mockResolvedValue([]);
  (apiService.createWorker as jest.Mock).mockResolvedValue({});
  (apiService.updateWorker as jest.Mock).mockResolvedValue({});
});

test('creates a worker with INFO below the log reporting switch', async () => {
  render(<WorkerManagement />);
  fireEvent.click(await screen.findByRole('button', { name: /新建节点/ }));
  const dialog = await screen.findByRole('dialog');
  const formItems = Array.from(dialog.querySelectorAll('.ant-form-item'));
  const reporting = formItems.findIndex(item => item.textContent?.includes('代理网关日志上报'));
  expect(formItems[reporting + 1]).toHaveTextContent('日志级别');
  expect(within(dialog).getByText('INFO')).toBeInTheDocument();
  await act(async () => {
    fireEvent.change(within(dialog).getByLabelText('节点标识'), { target: { value: 'edge-01' } });
    fireEvent.change(within(dialog).getByLabelText('显示名称'), { target: { value: 'Edge 01' } });
    fireEvent.change(within(dialog).getByLabelText('Worker WSS 地址'), { target: { value: 'wss://edge.test/management/ws' } });
    fireEvent.change(within(dialog).getByLabelText('Worker 管理 Token'), { target: { value: 'hpw_example-token' } });
  });
  await act(async () => {
    fireEvent.click(within(dialog).getByRole('button', { name: /OK|确.*定/ }));
  });

  await waitFor(() => expect(apiService.createWorker).toHaveBeenCalledWith(expect.objectContaining({
    workerId: 'edge-01', logLevel: 'INFO', logReportingEnabled: true,
  })));
});

test('edits the saved level without displaying a log level column', async () => {
  (apiService.getWorkers as jest.Mock).mockResolvedValue([{
    workerId: 'edge-01', displayName: 'Edge 01', enabled: true,
    managementUrl: 'wss://edge.test/management/ws', managementToken: 'hpw_example-token',
    logLevel: 'WARN', logReportingEnabled: false,
  }]);
  render(<WorkerManagement />);
  await screen.findByText('Edge 01');
  expect(screen.queryByRole('columnheader', { name: '日志级别' })).not.toBeInTheDocument();
  expect(screen.queryByText('WARN')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /编辑/ }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText('WARN')).toBeInTheDocument();
  fireEvent.mouseDown(within(dialog).getByRole('combobox').closest('.ant-select-selector') as Element);
  const debugOption = await screen.findByTitle('DEBUG');
  await act(async () => { fireEvent.click(debugOption); });
  await act(async () => {
    fireEvent.click(within(dialog).getByRole('button', { name: /OK|确.*定/ }));
  });

  await waitFor(() => expect(apiService.updateWorker).toHaveBeenCalledWith('edge-01', expect.objectContaining({
    logLevel: 'DEBUG', logReportingEnabled: false,
  })));
});

test('defaults older workers without a configured level to INFO when editing', async () => {
  (apiService.getWorkers as jest.Mock).mockResolvedValue([{
    workerId: 'edge-01', displayName: 'Edge 01', enabled: true,
    managementUrl: 'wss://edge.test/management/ws', managementToken: 'hpw_example-token',
  }]);
  render(<WorkerManagement />);
  fireEvent.click(await screen.findByRole('button', { name: /编辑/ }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText('INFO')).toBeInTheDocument();
});
