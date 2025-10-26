import React, { useEffect, useMemo, useState } from 'react';
import { Card, Tag, Table, Descriptions, Space, Button, Statistic, Row, Col, Alert } from 'antd';
import { HddOutlined, ReloadOutlined } from '@ant-design/icons';
import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title as ChartTitle,
  Tooltip,
  Legend,
} from 'chart.js';
import { apiService } from '../services/api';
import { DiskInfo, DiskDetail } from '../types/disk';
import './Dashboard.css';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, ChartTitle, Tooltip, Legend);

const temperatureLabels = Array.from({ length: 144 }, (_, i) => {
  const hour = Math.floor(i / 6);
  const minute = (i % 6) * 10;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
});

const healthColor = (health?: string) => {
  if (!health) return 'default';
  const h = health.toUpperCase();
  if (h.includes('PASS') || h.includes('OK')) return 'success';
  if (h.includes('WARN')) return 'warning';
  return 'error';
};

const formatBytes = (val?: number) => {
  if (val === undefined || val === null) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = val;
  let idx = 0;
  while (v >= 1024 && idx < units.length - 1) {
    v /= 1024;
    idx++;
  }
  return `${v.toFixed(2)} ${units[idx]}`;
};

const DiskMonitor: React.FC = () => {
  const [disks, setDisks] = useState<DiskInfo[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [errorList, setErrorList] = useState<string | null>(null);

  const [selectedDevice, setSelectedDevice] = useState<string | null>(null);
  const [detail, setDetail] = useState<DiskDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [errorDetail, setErrorDetail] = useState<string | null>(null);

  const loadDisks = async () => {
    try {
      setLoadingList(true);
      setErrorList(null);
      const data = await apiService.getDisks();
      setDisks(data);
      if (!selectedDevice && data.length > 0) {
        handleSelectDevice(data[0].device);
      }
    } catch (e: any) {
      setErrorList(e?.message || '加载磁盘列表失败');
    } finally {
      setLoadingList(false);
    }
  };

  const handleSelectDevice = async (device: string) => {
    try {
      setSelectedDevice(device);
      setLoadingDetail(true);
      setErrorDetail(null);
      const d = await apiService.getDiskDetail(device);
      setDetail(d);
    } catch (e: any) {
      setErrorDetail(e?.message || '加载磁盘详情失败');
    } finally {
      setLoadingDetail(false);
    }
  };

  useEffect(() => {
    loadDisks();
  }, []);

  const riskTips = useMemo(() => {
    if (!detail) return [] as string[];
    const tips: string[] = [];
    const h = (detail.health || '').toUpperCase();
    if (!h.includes('PASS') && !h.includes('OK')) {
      tips.push('SMART健康状态异常，请尽快备份数据并检查磁盘');
    }
    if (detail.diskType === 'HDD') {
      if ((detail.reallocatedSectorCount ?? 0) > 0) tips.push('HDD重映射扇区计数大于0，存在坏道风险');
      if ((detail.spinRetryCount ?? 0) > 0) tips.push('HDD电机重试计数异常，可能存在启动问题');
      if ((detail.udmaCrcErrorCount ?? 0) > 0) tips.push('HDD UDMA CRC错误计数异常，检查数据线与接口');
    }
    if (detail.diskType === 'NVME_SSD' || detail.diskType === 'SATA_SSD') {
      if ((detail.percentageUsed ?? 0) >= 80) tips.push('SSD寿命使用超过80%，建议评估更换计划');
      if ((detail.mediaErrors ?? 0) > 0) tips.push('SSD介质错误计数异常，可能存在数据完整性风险');
      if ((detail.unsafeShutdowns ?? 0) > 0) tips.push('SSD存在非正常断电记录，可能影响设备健康');
    }
    if (detail.diskType === 'SATA_SSD') {
      if ((detail.ssdLifeLeft ?? 100) <= 20) tips.push('SATA SSD剩余寿命低于20%，建议及时更换');
    }
    return tips;
  }, [detail]);

  const temperatureData = useMemo(() => {
    const temps = detail?.historyTemperature ?? [];
    // 补齐到144槽位，缺失以null表示断点
    const data = Array.from({ length: 144 }, (_, i) => (i < temps.length ? temps[i] : null));
    return {
      labels: temperatureLabels,
      datasets: [
        {
          label: '温度 (°C)',
          data,
          borderColor: '#3b82f6',
          backgroundColor: 'rgba(59, 130, 246, 0.15)',
          tension: 0.2,
          spanGaps: true,
          pointRadius: 0,
        },
      ],
    };
  }, [detail]);

  const chartOptions = useMemo(() => ({
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { position: 'top' as const },
      title: { display: true, text: '今日温度曲线（每10分钟采样）' },
      tooltip: { mode: 'index' as const, intersect: false },
    },
    scales: {
      x: { display: true },
      y: {
        display: true,
        suggestedMin: 0,
        suggestedMax: 100,
        title: { display: true, text: '°C' },
      },
    },
  }), []);

  return (
    <div className="traffic-overview">
      <div className="page-header">
        <h2>磁盘监控</h2>
        <p>查看磁盘健康状态与当天温度曲线</p>
      </div>

      {/* 顶部汇总与刷新 */}
      <Card style={{ marginBottom: 16 }}>
        <Row gutter={16} align="middle">
          <Col span={6}>
            <Statistic title="磁盘数量" value={disks.length} prefix={<HddOutlined />} />
          </Col>
          <Col span={12}>
            {detail && (
              <Space size="middle">
                <Tag color={healthColor(detail.health)}>
                  健康：{detail.health || '-'}
                </Tag>
                <Tag color="blue">温度：{detail.temperature}°C</Tag>
                <Tag color="default">类型：{detail.diskType}</Tag>
              </Space>
            )}
          </Col>
          <Col span={6} style={{ textAlign: 'right' }}>
            <Button icon={<ReloadOutlined />} onClick={loadDisks} loading={loadingList}>
              刷新列表
            </Button>
          </Col>
        </Row>
      </Card>

      <div className="dashboard-grid">
        {/* 左侧：磁盘列表 */}
        <Card className="dashboard-card">
          <h3>磁盘列表</h3>
          {errorList && <Alert type="error" message={errorList} style={{ marginBottom: 12 }} />}
          <Table
            rowKey="device"
            dataSource={disks}
            loading={loadingList}
            pagination={false}
            size="small"
            onRow={(record) => ({ onClick: () => handleSelectDevice(record.device) })}
            rowClassName={(record) => (record.device === selectedDevice ? 'selected-row' : '')}
            columns={[
              { title: '设备', dataIndex: 'device', key: 'device' },
              { title: '型号', dataIndex: 'model', key: 'model' },
              { title: '序列号', dataIndex: 'serial', key: 'serial' },
              { title: '容量', dataIndex: 'size', key: 'size' },
              { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => <Tag color={healthColor(v)}>{v}</Tag> },
              { title: '温度(°C)', dataIndex: 'temperature', key: 'temperature' },
            ]}
          />
        </Card>

        {/* 右侧：磁盘详情与温度曲线 */}
        <Card className="dashboard-card" style={{ minHeight: 420 }}>
          <h3>磁盘详情</h3>
          {errorDetail && <Alert type="error" message={errorDetail} style={{ marginBottom: 12 }} />}
          {!detail ? (
            <div className="loading">请选择左侧磁盘查看详情</div>
          ) : (
            <>
              <Descriptions bordered column={2} size="small" style={{ marginBottom: 12 }}>
                <Descriptions.Item label="设备">{detail.device}</Descriptions.Item>
                <Descriptions.Item label="型号">{detail.model}</Descriptions.Item>
                <Descriptions.Item label="序列号">{detail.serial}</Descriptions.Item>
                <Descriptions.Item label="容量">{detail.size}</Descriptions.Item>
                <Descriptions.Item label="当前温度">{detail.temperature} °C</Descriptions.Item>
                <Descriptions.Item label="SMART支持">{detail.smartSupported ? '是' : '否'}</Descriptions.Item>
                <Descriptions.Item label="SMART启用">{detail.smartEnabled ? '是' : '否'}</Descriptions.Item>
                <Descriptions.Item label="类型">{detail.diskType}</Descriptions.Item>
                <Descriptions.Item label="读取总量">{formatBytes(detail.dataUnitsRead)}</Descriptions.Item>
                <Descriptions.Item label="写入总量">{formatBytes(detail.dataUnitsWritten)}</Descriptions.Item>
                <Descriptions.Item label="健康状态" span={2}>
                  <Tag color={healthColor(detail.health)}>{detail.health}</Tag>
                </Descriptions.Item>
              </Descriptions>

              {/* 风险提示 */}
              {riskTips.length > 0 && (
                <Alert
                  type="warning"
                  message="风险提示"
                  description={<div>{riskTips.map((t, i) => (<div key={i}>• {t}</div>))}</div>}
                  showIcon
                  style={{ marginBottom: 12 }}
                />
              )}

              {/* 类型特定指标 */}
              {detail.diskType === 'HDD' && (
                <Descriptions bordered column={3} size="small" title="HDD指标" style={{ marginBottom: 12 }}>
                  <Descriptions.Item label="重映射扇区">{detail.reallocatedSectorCount}</Descriptions.Item>
                  <Descriptions.Item label="寻道错误率">{detail.seekErrorRate}</Descriptions.Item>
                  <Descriptions.Item label="电机重试计数">{detail.spinRetryCount}</Descriptions.Item>
                  <Descriptions.Item label="UDMA CRC错误">{detail.udmaCrcErrorCount}</Descriptions.Item>
                  <Descriptions.Item label="累计开机小时">{detail.powerOnHours}</Descriptions.Item>
                  <Descriptions.Item label="上电次数">{detail.powerCycleCount}</Descriptions.Item>
                </Descriptions>
              )}

              {detail.diskType === 'NVME_SSD' && (
                <Descriptions bordered column={3} size="small" title="NVMe SSD指标" style={{ marginBottom: 12 }}>
                  <Descriptions.Item label="寿命使用(%)">{detail.percentageUsed}</Descriptions.Item>
                  {/* 通用读写指标已在上方展示 */}
                  <Descriptions.Item label="非正常断电">{detail.unsafeShutdowns}</Descriptions.Item>
                  <Descriptions.Item label="介质错误">{detail.mediaErrors}</Descriptions.Item>
                  <Descriptions.Item label="累计开机小时">{detail.powerOnHours}</Descriptions.Item>
                </Descriptions>
              )}

              {detail.diskType === 'SATA_SSD' && (
                <Descriptions bordered column={3} size="small" title="SATA SSD指标" style={{ marginBottom: 12 }}>
                  <Descriptions.Item label="剩余寿命(%)">{detail.ssdLifeLeft}</Descriptions.Item>
                  <Descriptions.Item label="Flash写入(GiB)">{detail.flashWritesGiB}</Descriptions.Item>
                  <Descriptions.Item label="寿命写入(GiB)">{detail.lifetimeWritesGiB}</Descriptions.Item>
                  <Descriptions.Item label="寿命读取(GiB)">{detail.lifetimeReadsGiB}</Descriptions.Item>
                  <Descriptions.Item label="平均擦除次数">{detail.averageEraseCount}</Descriptions.Item>
                  <Descriptions.Item label="最大擦除次数">{detail.maxEraseCount}</Descriptions.Item>
                  <Descriptions.Item label="总擦除次数">{detail.totalEraseCount}</Descriptions.Item>
                  <Descriptions.Item label="介质错误">{detail.mediaErrors}</Descriptions.Item>
                  <Descriptions.Item label="累计开机小时">{detail.powerOnHours}</Descriptions.Item>
                </Descriptions>
              )}

              {/* 温度曲线 */}
              <div className="trend-chart-container" style={{ height: 360 }}>
                <Line data={temperatureData} options={chartOptions} />
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  );
};

export default DiskMonitor;