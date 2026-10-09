import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import apiService from '../services/api';
import { LegacyTrafficOverview } from './LegacyTrafficOverview';

jest.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="traffic-chart" />,
}));
jest.mock('chartjs-adapter-date-fns', () => ({}));
jest.mock('../services/api', () => ({
  __esModule: true,
  default: {
    getWorkers: jest.fn(),
    getDailyUserTrafficStats: jest.fn(),
    getMonthlyUserTrafficStats: jest.fn(),
    getGlobalTrafficTrend: jest.fn(),
    getUserTrafficTrend: jest.fn(),
    getUsers: jest.fn(),
  },
}));

describe('LegacyTrafficOverview', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (apiService.getWorkers as jest.Mock).mockResolvedValue([
      { workerId: 'default', displayName: '默认节点', enabled: true },
      { workerId: 'edge-a', displayName: '边缘节点 A', enabled: true },
    ]);
    (apiService.getDailyUserTrafficStats as jest.Mock).mockResolvedValue([]);
    (apiService.getMonthlyUserTrafficStats as jest.Mock).mockResolvedValue([]);
    (apiService.getGlobalTrafficTrend as jest.Mock).mockResolvedValue([]);
    (apiService.getUserTrafficTrend as jest.Mock).mockResolvedValue([]);
    (apiService.getUsers as jest.Mock).mockResolvedValue({ items: [] });
  });

  it('defaults to the default worker and refreshes all traffic data when worker changes', async () => {
    render(<LegacyTrafficOverview />);

    await waitFor(() => {
      expect(apiService.getDailyUserTrafficStats).toHaveBeenCalledWith({ workerId: 'default' });
      expect(apiService.getMonthlyUserTrafficStats).toHaveBeenCalledWith({ workerId: 'default' });
      expect(apiService.getGlobalTrafficTrend).toHaveBeenCalledWith(
        expect.objectContaining({ workerId: 'default' })
      );
    });

    fireEvent.change(screen.getByLabelText('代理节点'), { target: { value: 'edge-a' } });

    await waitFor(() => {
      expect(apiService.getDailyUserTrafficStats).toHaveBeenCalledWith({ workerId: 'edge-a' });
      expect(apiService.getMonthlyUserTrafficStats).toHaveBeenCalledWith({ workerId: 'edge-a' });
      expect(apiService.getGlobalTrafficTrend).toHaveBeenCalledWith(
        expect.objectContaining({ workerId: 'edge-a' })
      );
    });
  });
});
