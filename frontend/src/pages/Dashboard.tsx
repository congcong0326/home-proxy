import React, { useState } from 'react';
import TrafficOverview from './TrafficOverview';
import WolManagement from './WolManagement';
import './Dashboard.css';

// 子页面类型
type DashboardTab = 'traffic' | 'wol';

// 主Dashboard组件
const Dashboard: React.FC = () => {
  const [activeTab, setActiveTab] = useState<DashboardTab>('traffic');

  const renderTabContent = () => {
    switch (activeTab) {
      case 'traffic':
        return <TrafficOverview />;
      case 'wol':
        return <WolManagement />;
      default:
        return <TrafficOverview />;
    }
  };

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <h1 className="dashboard-title">仪表盘</h1>
        <div className="dashboard-tabs">
          <button
            className={`tab-button ${activeTab === 'traffic' ? 'active' : ''}`}
            onClick={() => setActiveTab('traffic')}
          >
            <svg className="tab-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
            流量概览
          </button>
          <button
            className={`tab-button ${activeTab === 'wol' ? 'active' : ''}`}
            onClick={() => setActiveTab('wol')}
          >
            <svg className="tab-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
            </svg>
            WOL设备管理
          </button>
        </div>
      </div>
      
      <div className="dashboard-content">
        {renderTabContent()}
      </div>
    </div>
  );
};

export default Dashboard;