import React, { useState, useEffect } from 'react';
import apiService from '../services/api';
import { WolConfig, PcStatus } from '../types/dashboard';
import './Dashboard.css';

// WOL设备表单组件
interface WolDeviceFormProps {
  device?: WolConfig;
  onSave: (device: Partial<WolConfig>) => void;
  onCancel: () => void;
}

const WolDeviceForm: React.FC<WolDeviceFormProps> = ({ device, onSave, onCancel }) => {
  const [formData, setFormData] = useState({
    name: device?.name || '',
    ipAddress: device?.ipAddress || '',
    subnetMask: device?.subnetMask || '255.255.255.255',
    macAddress: device?.macAddress || '',
    wolPort: device?.wolPort || 9,
    notes: device?.notes || '',
    enabled: device?.enabled !== false,
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave(formData);
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const { name, value, type } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: type === 'checkbox' ? (e.target as HTMLInputElement).checked : 
              name === 'wolPort' ? parseInt(value) || 9 : value
    }));
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <div className="modal-header">
          <h3>{device ? '编辑设备' : '添加设备'}</h3>
          <button className="modal-close" onClick={onCancel}>×</button>
        </div>
        
        <form onSubmit={handleSubmit} className="wol-form">
          <div className="form-group">
            <label htmlFor="name">设备名称 *</label>
            <input
              type="text"
              id="name"
              name="name"
              value={formData.name}
              onChange={handleChange}
              required
              placeholder="请输入设备名称"
            />
          </div>
          
          <div className="form-group">
            <label htmlFor="ipAddress">IP地址 *</label>
            <input
              type="text"
              id="ipAddress"
              name="ipAddress"
              value={formData.ipAddress}
              onChange={handleChange}
              required
              placeholder="192.168.1.100"
              pattern="^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
            />
            <p className="form-help">
              目标主机的 IP 地址。可填写局域网固定 IP；
              若使用广播唤醒，可填写局域网广播地址（如 192.168.10.255）。
            </p>
          </div>
          
          <div className="form-group">
            <label htmlFor="subnetMask">子网掩码</label>
            <input
              type="text"
              id="subnetMask"
              name="subnetMask"
              value={formData.subnetMask}
              onChange={handleChange}
              placeholder="255.255.255.255"
              pattern="^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
            />
            <p className="form-help">
              子网掩码。通常保持默认值 255.255.255.255（点对点广播）即可。
            </p>
          </div>
          
          <div className="form-group">
            <label htmlFor="macAddress">MAC地址 *</label>
            <input
              type="text"
              id="macAddress"
              name="macAddress"
              value={formData.macAddress}
              onChange={handleChange}
              required
              placeholder="AA:BB:CC:DD:EE:FF"
              pattern="^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
            />
            <p className="form-help">
              目标主机的网卡 MAC 地址。可通过命令行 ipconfig /all（Windows）
              或 ip addr（Linux）获取。支持短横线或冒号分隔格式，如 D8:BB:C1:D4:48:BF 或 D8-BB-C1-D4-48-BF。
            </p>
          </div>
          
          <div className="form-group">
            <label htmlFor="wolPort">WOL端口</label>
            <input
              type="number"
              id="wolPort"
              name="wolPort"
              value={formData.wolPort}
              onChange={handleChange}
              min="1"
              max="65535"
              placeholder="9"
            />
            <p className="form-help">
              唤醒包端口。常用端口为 9 或 7，通常保持默认值即可。
            </p>
          </div>
          
          <div className="form-group">
            <label htmlFor="notes">备注</label>
            <textarea
              id="notes"
              name="notes"
              value={formData.notes}
              onChange={handleChange}
              placeholder="设备备注信息"
              rows={3}
            />
          </div>
          
          <div className="form-group checkbox-group">
            <label>
              <input
                type="checkbox"
                name="enabled"
                checked={formData.enabled}
                onChange={handleChange}
              />
              启用设备
            </label>
          </div>
          
          <div className="form-actions">
            <button type="button" className="btn-secondary" onClick={onCancel}>
              取消
            </button>
            <button type="submit" className="btn-primary">
              {device ? '更新' : '添加'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

// WOL设备管理主组件
const WolManagement: React.FC = () => {
  const [devices, setDevices] = useState<WolConfig[]>([]);
  const [pcStatuses, setPcStatuses] = useState<PcStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingDevice, setEditingDevice] = useState<WolConfig | undefined>();
  const [searchTerm, setSearchTerm] = useState('');

  const fetchDevices = async () => {
    try {
      setLoading(true);
      const [configs, statuses] = await Promise.all([
        apiService.getWolConfigs(),
        apiService.getAllPcStatus(),
      ]);
      setDevices(configs);
      setPcStatuses(statuses);
      setError(null);
    } catch (err) {
      setError('获取设备信息失败');
      console.error('Error fetching devices:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDevices();
  }, []);

  const handleAddDevice = () => {
    setEditingDevice(undefined);
    setShowForm(true);
  };

  const handleEditDevice = (device: WolConfig) => {
    setEditingDevice(device);
    setShowForm(true);
  };

  const handleSaveDevice = async (deviceData: Partial<WolConfig>) => {
    try {
      if (editingDevice) {
        // 更新设备：确保传递 status 字段（1 启用 / 0 禁用）
        const updateData: Partial<WolConfig> = {
          name: deviceData.name,
          ipAddress: deviceData.ipAddress,
          subnetMask: deviceData.subnetMask,
          macAddress: deviceData.macAddress,
          wolPort: deviceData.wolPort,
          enabled: deviceData.enabled !== false,
          status: deviceData.enabled !== false ? 1 : 0,
          notes: deviceData.notes
        };
        await apiService.updateWolConfig(editingDevice.id, updateData);
      } else {
        // 添加设备 - 验证必需字段并创建设备
        if (!deviceData.name || !deviceData.ipAddress || !deviceData.macAddress) {
          setError('请填写所有必需字段');
          return;
        }
        
        const createData: Omit<WolConfig, 'id' | 'createdAt' | 'updatedAt' | 'online'> = {
          name: deviceData.name,
          ipAddress: deviceData.ipAddress,
          subnetMask: deviceData.subnetMask || '255.255.255.255',
          macAddress: deviceData.macAddress,
          wolPort: deviceData.wolPort || 9,
          status: deviceData.enabled !== false ? 1 : 0,
          enabled: deviceData.enabled !== false,
          notes: deviceData.notes || ''
        };
        
        await apiService.createWolConfig(createData);
      }
      setShowForm(false);
      fetchDevices();
    } catch (err) {
      console.error('Error saving device:', err);
      setError('保存设备失败');
    }
  };

  const handleDeleteDevice = async (id: number) => {
    if (!window.confirm('确定要删除这个设备吗？')) {
      return;
    }
    
    try {
      // 删除设备 - 这里需要根据实际API调整
      await apiService.deleteWolConfig(id);
      fetchDevices();
    } catch (err) {
      console.error('Error deleting device:', err);
      setError('删除设备失败');
    }
  };

  const handleWakeDevice = async (id: number) => {
    try {
      await apiService.wakeById(id);
      // 刷新设备状态
      setTimeout(() => {
        fetchDevices();
      }, 2000);
    } catch (err) {
      console.error('Error waking device:', err);
      setError('唤醒设备失败');
    }
  };

  const handleCheckStatus = async (ip: string) => {
    try {
      await apiService.checkIpStatus(ip);
      // 刷新设备状态
      fetchDevices();
    } catch (err) {
      console.error('Error checking status:', err);
      setError('检测设备状态失败');
    }
  };

  const handleRefreshConfigs = async () => {
    try {
      await apiService.refreshWolConfigs();
      fetchDevices();
    } catch (err) {
      console.error('Error refreshing configs:', err);
      setError('刷新配置失败');
    }
  };

  // 过滤设备
  const filteredDevices = devices.filter(device =>
    device.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    device.ipAddress.includes(searchTerm) ||
    device.macAddress.toLowerCase().includes(searchTerm.toLowerCase())
  );

  if (loading) {
    return <div className="loading">加载中...</div>;
  }

  return (
    <div className="wol-management">
      <div className="page-header">
        <h2>WOL设备管理</h2>
        <p>管理网络唤醒设备，支持设备的添加、编辑、删除和唤醒操作</p>
      </div>

      {error && (
        <div className="error">
          {error}
          <button onClick={() => setError(null)}>×</button>
        </div>
      )}

      <div className="dashboard-grid">
        <div className="dashboard-card wol-section">
          <div className="wol-controls">
            <input
              className="trend-date-input"
              type="text"
              placeholder="搜索设备名称、IP或MAC地址..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
            <button className="wol-button secondary" onClick={handleRefreshConfigs}>
              刷新配置
            </button>
            <button className="wol-button primary" onClick={handleAddDevice}>
              添加设备
            </button>
          </div>

          {filteredDevices.length === 0 ? (
            <div className="empty-state">
              {searchTerm ? '没有找到匹配的设备' : '暂无WOL设备配置'}
            </div>
          ) : (
            <div className="wol-devices-grid">
              {filteredDevices.map(device => {
                const status = pcStatuses.find(s => s.ip === device.ipAddress);
                const isOnline = device.online || status?.online;
                
                return (
                  <div
                    key={device.id}
                    className={`wol-device-card ${isOnline ? 'online' : 'offline'} ${!device.enabled ? 'disabled' : ''}`}
                  >
                  <div className="device-header">
                    <h4 className="device-name">{device.name}</h4>
                    <div className="device-status-container">
                      <span className={`device-status ${isOnline ? 'online' : 'offline'}`}>
                        {isOnline ? '在线' : '离线'}
                      </span>
                      {!device.enabled && (
                        <span className="device-disabled">已禁用</span>
                      )}
                    </div>
                  </div>
                  
                  <div className="device-info">
                    <div className="device-info-item">
                      <span className="device-info-label">IP地址:</span>
                      <span className="device-info-value">{device.ipAddress}</span>
                    </div>
                    <div className="device-info-item">
                      <span className="device-info-label">MAC地址:</span>
                      <span className="device-info-value">{device.macAddress}</span>
                    </div>
                    <div className="device-info-item">
                      <span className="device-info-label">WOL端口:</span>
                      <span className="device-info-value">{device.wolPort}</span>
                    </div>
                    {device.notes && (
                      <div className="device-info-item">
                        <span className="device-info-label">备注:</span>
                        <span className="device-info-value">{device.notes}</span>
                      </div>
                    )}
                  </div>
                  
                  <div className="device-actions">
                    <button
                      className="device-action-btn wake"
                      onClick={() => handleWakeDevice(device.id)}
                      disabled={!device.enabled}
                      title={!device.enabled ? '设备已禁用' : '唤醒设备'}
                    >
                      唤醒
                    </button>
                    <button
                      className="device-action-btn check"
                      onClick={() => handleCheckStatus(device.ipAddress)}
                      title="检测设备状态"
                    >
                      检测
                    </button>
                    <button
                      className="device-action-btn edit"
                      onClick={() => handleEditDevice(device)}
                      title="编辑设备"
                    >
                      编辑
                    </button>
                    <button
                      className="device-action-btn delete"
                      onClick={() => handleDeleteDevice(device.id)}
                      title="删除设备"
                    >
                      删除
                    </button>
                  </div>
                </div>
              );
              })}
            </div>
          )}
        </div>
      </div>

      {showForm && (
        <WolDeviceForm
          device={editingDevice}
          onSave={handleSaveDevice}
          onCancel={() => setShowForm(false)}
        />
      )}
    </div>
  );
};

export default WolManagement;