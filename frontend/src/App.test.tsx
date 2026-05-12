import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';
import { apiService } from './services/api';

jest.mock('./pages/ChangePassword', () => () => <div>ChangePassword</div>);
jest.mock('./pages/SetupAdmin', () => () => <div>SetupAdmin</div>);
jest.mock('./pages/ProxyConfig', () => () => <div>ProxyConfig</div>);
jest.mock('./pages/UserManagement', () => () => <div>UserManagement</div>);
jest.mock('./pages/RouteManagement', () => () => <div>RouteManagement</div>);
jest.mock('./pages/InboundManagement', () => () => <div>InboundManagement</div>);
jest.mock('./pages/LogAudit', () => () => <div>LogAudit</div>);
jest.mock('./pages/AggregatedAnalysis', () => () => <div>AggregatedAnalysis</div>);
jest.mock('./pages/Dashboard', () => () => <div>Dashboard</div>);
jest.mock('./pages/TrafficOverview', () => () => <div>TrafficOverview</div>);
jest.mock('./pages/WolManagement', () => () => <div>WolManagement</div>);
jest.mock('./pages/DiskMonitor', () => () => <div>DiskMonitor</div>);
jest.mock('./pages/MailGateway', () => () => <div>MailGateway</div>);
jest.mock('./services/api', () => ({
  apiService: {
    getSetupStatus: jest.fn(),
    getCurrentUser: jest.fn(),
    login: jest.fn(),
    changePassword: jest.fn(),
    logout: jest.fn(),
  },
}));

beforeEach(() => {
  localStorage.clear();
  window.history.pushState({}, '', '/');
  jest.clearAllMocks();
});

test('renders setup page when the backend has no admin user', async () => {
  (apiService.getSetupStatus as jest.Mock).mockResolvedValue({ setupRequired: true });

  render(<App />);

  expect(await screen.findByText('SetupAdmin')).toBeInTheDocument();
});

test('renders login page for initialized unauthenticated users', async () => {
  (apiService.getSetupStatus as jest.Mock).mockResolvedValue({ setupRequired: false });

  render(<App />);

  expect(await screen.findByText('NAS代理管理后台')).toBeInTheDocument();
});
