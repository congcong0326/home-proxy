import React, { useState, useEffect } from 'react';
import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  TimeScale,
} from 'chart.js';
import 'chartjs-adapter-date-fns';
import { format, subHours } from 'date-fns';
import apiService from '../services/api';
import {
  UserTrafficStats,
  TimeSeriesPoint,
  TrafficTrendParams,
} from '../types/dashboard';
import { UserDTO } from '../types/user';
import './Dashboard.css';

// 注册Chart.js组件
ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  TimeScale
);

// 格式化字节数
const formatBytes = (bytes: number): string => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

// 流量统计卡片组件
const TrafficStatsCard: React.FC = () => {
  const [dailyStats, setDailyStats] = useState<UserTrafficStats[]>([]);
  const [monthlyStats, setMonthlyStats] = useState<UserTrafficStats[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchStats = async () => {
      try {
        setLoading(true);
        const [daily, monthly] = await Promise.all([
          apiService.getDailyUserTrafficStats(),
          apiService.getMonthlyUserTrafficStats()
        ]);
        setDailyStats(daily);
        setMonthlyStats(monthly);
      } catch (error) {
        console.error('Error fetching traffic stats:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchStats();
  }, []);

  if (loading) {
    return <div className="loading">加载中...</div>;
  }

  // 计算总计
  const dailyTotal = dailyStats.reduce((sum, stat) => sum + stat.totalBytes, 0);
  const monthlyTotal = monthlyStats.reduce((sum, stat) => sum + stat.totalBytes, 0);

  return (
    <>
      <div className="dashboard-card traffic-summary-card">
        <h3 className="card-title">流量统计</h3>
        <div className="traffic-stats-grid">
          <div className="traffic-stat-item">
            <div className="stat-label">今日流量</div>
            <div className="stat-value total">{formatBytes(dailyTotal)}</div>
            <div className="stat-label">{dailyStats.length} 个用户</div>
          </div>
          <div className="traffic-stat-item">
            <div className="stat-label">本月流量</div>
            <div className="stat-value total">{formatBytes(monthlyTotal)}</div>
            <div className="stat-label">{monthlyStats.length} 个用户</div>
          </div>
        </div>
      </div>

      <div className="dashboard-card user-details-card">
        <h4 className="card-title">用户流量详情</h4>
        <div className="user-stats-table">
          <table>
          <thead>
            <tr>
              <th>用户名</th>
              <th>今日上传</th>
              <th>今日下载</th>
              <th>今日总计</th>
              <th>本月总计</th>
            </tr>
          </thead>
          <tbody>
            {dailyStats.slice(0, 10).map((dailyStat) => {
              const monthlyStat = monthlyStats.find(m => m.userId === dailyStat.userId);
              return (
                <tr key={dailyStat.userId}>
                  <td>{dailyStat.username}</td>
                  <td>{formatBytes(dailyStat.byteIn)}</td>
                  <td>{formatBytes(dailyStat.byteOut)}</td>
                  <td>{formatBytes(dailyStat.totalBytes)}</td>
                  <td>{monthlyStat ? formatBytes(monthlyStat.totalBytes) : '-'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  </>
  );
};

// 流量趋势图表组件
const TrafficTrendChart: React.FC = () => {
  const [trendData, setTrendData] = useState<TimeSeriesPoint[]>([]);
  const [selectedUser, setSelectedUser] = useState<number | null>(null);
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [dateRange, setDateRange] = useState({
    startTime: format(subHours(new Date(), 3), "yyyy-MM-dd'T'HH:mm:ss"),
    endTime: format(new Date(), "yyyy-MM-dd'T'HH:mm:ss"),
  });
  const [loading, setLoading] = useState(false);

  const fetchTrendData = async () => {
    try {
      setLoading(true);
      const params: TrafficTrendParams = {
        startTime: dateRange.startTime,
        endTime: dateRange.endTime
      };

      let data: TimeSeriesPoint[];
      if (selectedUser) {
        data = await apiService.getUserTrafficTrend(selectedUser, params);
      } else {
        data = await apiService.getGlobalTrafficTrend(params);
      }
      
      setTrendData(data);
    } catch (error) {
      console.error('Error fetching trend data:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTrendData();
  }, [selectedUser, dateRange]);

  useEffect(() => {
    const fetchUsers = async () => {
      try {
        const resp = await apiService.getUsers({ page: 1, pageSize: 100, status: 1 });
        setUsers(resp.items || []);
      } catch (error) {
        console.error('Error fetching users:', error);
      }
    };

    fetchUsers();
  }, []);

  // 准备图表数据
  const chartData = {
    labels: trendData.map(point => new Date(point.ts)),
    datasets: [
      {
        label: '上传流量',
        data: trendData.map(point => point.byteIn),
        borderColor: 'rgb(75, 192, 192)',
        backgroundColor: 'rgba(75, 192, 192, 0.2)',
        tension: 0.4,
        cubicInterpolationMode: 'monotone' as const,
      },
      {
        label: '下载流量',
        data: trendData.map(point => point.byteOut),
        borderColor: 'rgb(255, 99, 132)',
        backgroundColor: 'rgba(255, 99, 132, 0.2)',
        tension: 0.4,
        cubicInterpolationMode: 'monotone' as const,
      },
    ],
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index' as const, intersect: false },
    plugins: {
      legend: {
        position: 'bottom' as const,
      },
      tooltip: {
        callbacks: {
          label: function(context: any) {
            const label = context.dataset.label || '';
            const value = context.parsed?.y ?? context.raw;
            return `${label}: ${formatBytes(value)}`;
          },
          title: function(items: any[]) {
            if (!items || items.length === 0) return '';
            const ts = items[0].parsed?.x ?? items[0].label;
            try {
              return format(new Date(ts), 'MM-dd HH:mm');
            } catch {
              return String(items[0].label);
            }
          },
        },
      },
      title: {
        display: true,
        text: selectedUser ? `用户 ${selectedUser} 流量趋势` : '全局流量趋势',
      },
    },
    scales: {
      x: {
        stacked: false,
        type: 'time' as const,
        time: {
          displayFormats: {
            minute: 'HH:mm',
            hour: 'MM-dd HH:mm',
            day: 'MM-dd',
          },
        },
      },
      y: {
        stacked: false,
        beginAtZero: true,
        ticks: {
          callback: function(value: any) {
            return formatBytes(value);
          },
        },
      },
    },
  };

  return (
    <div className="dashboard-card traffic-trend-card">
      <h3 className="card-title">流量趋势</h3>
      
      <div className="trend-controls">
        <div className="trend-control-group">
          <label className="trend-control-label">用户筛选:</label>
          <select 
            className="trend-select"
            value={selectedUser || ''} 
            onChange={(e) => setSelectedUser(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">全部用户</option>
            {users.map(user => (
              <option key={user.id} value={user.id}>{user.username}</option>
            ))}
          </select>
        </div>
        
        <div className="trend-control-group">
          <label className="trend-control-label">开始时间:</label>
          <input
            className="trend-date-input"
            type="datetime-local"
            value={dateRange.startTime.slice(0, 16)}
            onChange={(e) => setDateRange(prev => ({
              ...prev,
              startTime: e.target.value + ':00'
            }))}
          />
        </div>
        
        <div className="trend-control-group">
          <label className="trend-control-label">结束时间:</label>
          <input
            className="trend-date-input"
            type="datetime-local"
            value={dateRange.endTime.slice(0, 16)}
            onChange={(e) => setDateRange(prev => ({
              ...prev,
              endTime: e.target.value + ':00'
            }))}
          />
        </div>
        
        <button onClick={fetchTrendData} disabled={loading}>
          {loading ? '加载中...' : '刷新'}
        </button>
      </div>

      <div className="trend-chart-container">
        {loading ? (
          <div className="loading">加载中...</div>
        ) : (
          <Line data={chartData} options={chartOptions} />
        )}
      </div>
    </div>
  );
};

// 流量概览主组件
const TrafficOverview: React.FC = () => {
  return (
    <div className="traffic-overview">
      <div className="page-header">
        <h2>流量概览</h2>
        <p>查看用户流量统计和趋势分析</p>
      </div>
      
      <div className="dashboard-grid">
        <TrafficStatsCard />
        <TrafficTrendChart />
      </div>
    </div>
  );
};

export default TrafficOverview;