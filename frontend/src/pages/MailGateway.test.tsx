import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { apiService } from '../services/api';
import MailGatewayPage from './MailGateway';

jest.setTimeout(15000);

jest.mock('../services/api', () => ({
  apiService: {
    listMailGateways: jest.fn(), listMailTargets: jest.fn(), listMailSendLogs: jest.fn(),
    listScheduledTasks: jest.fn(), previewMail: jest.fn(), sendMail: jest.fn(), createMailTarget: jest.fn()
  }
}));

const preview = (subject = '[每日巡检] 2026-10-10') => ({
  bizKey: 'ops.inspection', subject, contentType: 'text/html',
  content: '<html><body><h1>每日巡检报告</h1><p>外网下行：123 B</p></body></html>',
  plainTextContent: '过去24小时\n外网下行：123 B\n认证：基线不足'
});

beforeEach(() => {
  jest.resetAllMocks();
  (apiService.listMailGateways as jest.Mock).mockResolvedValue([]);
  (apiService.listMailTargets as jest.Mock).mockResolvedValue([]);
  (apiService.listMailSendLogs as jest.Mock).mockResolvedValue({ items: [] });
  (apiService.listScheduledTasks as jest.Mock).mockResolvedValue([]);
  (apiService.previewMail as jest.Mock).mockResolvedValue(preview());
});

it('previews the inspection without a configured recipient or sending mail', async () => {
  render(<MailGatewayPage />);
  fireEvent.click(screen.getByRole('button', { name: /预览巡检邮件/ }));
  const frame = await screen.findByTitle('邮件正文预览');
  expect(apiService.previewMail).toHaveBeenCalledWith({ bizKey: 'ops.inspection' });
  expect(frame).toHaveAttribute('sandbox', '');
  expect(frame).toHaveAttribute('srcDoc', preview().content);
  fireEvent.click(screen.getByRole('tab', { name: '纯文本' }));
  expect(await screen.findByText(/认证：基线不足/)).toBeInTheDocument();
  expect(apiService.sendMail).not.toHaveBeenCalled();
  expect(apiService.createMailTarget).not.toHaveBeenCalled();
});

it('ignores a late response after closing and starting a new preview', async () => {
  let resolveOld: (value: ReturnType<typeof preview>) => void = () => {};
  (apiService.previewMail as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce(preview('新的巡检报告'));
  render(<MailGatewayPage />);
  fireEvent.click(screen.getByRole('button', { name: /预览巡检邮件/ }));
  await waitFor(() => expect(apiService.previewMail).toHaveBeenCalledTimes(1));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /close/i }));
  fireEvent.click(screen.getByRole('button', { name: /预览巡检邮件/ }));
  await screen.findByText('新的巡检报告');
  await act(async () => { resolveOld(preview('过期的巡检报告')); });
  expect(screen.queryByText('过期的巡检报告')).not.toBeInTheDocument();
  expect(screen.getByText('新的巡检报告')).toBeInTheDocument();
  expect(apiService.sendMail).not.toHaveBeenCalled();
});

it('shows preview failures without displaying stale content', async () => {
  (apiService.previewMail as jest.Mock).mockRejectedValue(new Error('巡检生成失败'));
  render(<MailGatewayPage />);
  fireEvent.click(screen.getByRole('button', { name: /预览巡检邮件/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('巡检生成失败');
  expect(screen.queryByTitle('邮件正文预览')).not.toBeInTheDocument();
  expect(apiService.sendMail).not.toHaveBeenCalled();
});
