import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';

jest.mock('./pages/ChangePassword', () => () => <div>ChangePassword</div>);
jest.mock('./pages/ProxyConfig', () => () => <div>ProxyConfig</div>);
jest.mock('./pages/UserManagement', () => () => <div>UserManagement</div>);
jest.mock('./pages/RouteManagement', () => () => <div>RouteManagement</div>);
jest.mock('./pages/RateLimitManagement', () => () => <div>RateLimitManagement</div>);
jest.mock('./pages/InboundManagement', () => () => <div>InboundManagement</div>);
jest.mock('./pages/LogAudit', () => () => <div>LogAudit</div>);
jest.mock('./pages/AggregatedAnalysis', () => () => <div>AggregatedAnalysis</div>);
jest.mock('./pages/Dashboard', () => () => <div>Dashboard</div>);
jest.mock('./pages/TrafficOverview', () => () => <div>TrafficOverview</div>);
jest.mock('./pages/WolManagement', () => () => <div>WolManagement</div>);
jest.mock('./pages/DiskMonitor', () => () => <div>DiskMonitor</div>);
jest.mock('./pages/MailGateway', () => () => <div>MailGateway</div>);

test('renders login page for unauthenticated users', () => {
  render(<App />);
  expect(screen.getByText('NAS代理管理后台')).toBeInTheDocument();
});
