import React, { useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Input, Row, Space, Typography, message } from 'antd';
import { CloudDownloadOutlined, DatabaseOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { apiService } from '../services/api';
import { DATABASE_RESTORE_CONFIRMATION_PHRASE } from '../types/backup';
import './Dashboard.css';

const { Title, Text } = Typography;

const DatabaseBackup: React.FC = () => {
  const [messageApi, contextHolder] = message.useMessage();
  const [exporting, setExporting] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [confirmationPhrase, setConfirmationPhrase] = useState('');

  const canRestore = useMemo(
    () => Boolean(selectedFile) && confirmationPhrase === DATABASE_RESTORE_CONFIRMATION_PHRASE && !restoring,
    [confirmationPhrase, restoring, selectedFile]
  );

  const handleExport = async () => {
    try {
      setExporting(true);
      await apiService.exportDatabaseBackup();
      messageApi.success('备份文件已开始下载');
    } catch (e: any) {
      messageApi.error(e?.message || '导出备份失败');
    } finally {
      setExporting(false);
    }
  };

  const handleRestore = async () => {
    if (!selectedFile || confirmationPhrase !== DATABASE_RESTORE_CONFIRMATION_PHRASE) {
      return;
    }

    try {
      setRestoring(true);
      await apiService.restoreDatabaseBackup(selectedFile, confirmationPhrase);
      messageApi.success('备份已校验并暂存，重启控制端后生效');
      setSelectedFile(null);
      setConfirmationPhrase('');
    } catch (e: any) {
      messageApi.error(e?.message || '恢复失败');
    } finally {
      setRestoring(false);
    }
  };

  return (
    <div className="dashboard">
      {contextHolder}
      <div className="dashboard-header">
        <Title level={2} className="dashboard-title">数据库备份恢复</Title>
      </div>

      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 24 }}
        message="恢复文件会在控制端下次重启时替换当前数据库（原数据库自动保留在备份目录），请先导出现有备份并确认上传文件来源。"
      />

      <Row gutter={[24, 24]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><CloudDownloadOutlined />导出备份</Space>}>
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Text type="secondary">下载当前 SQLite 数据库的一致性快照（.db）。控制端另会按计划自动备份并保留最近若干份。</Text>
              <Button
                type="primary"
                icon={<CloudDownloadOutlined />}
                loading={exporting}
                onClick={handleExport}
              >
                导出备份
              </Button>
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title={<Space><DatabaseOutlined />恢复备份</Space>}>
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <div>
                <label htmlFor="database-backup-file">备份文件</label>
                <Input
                  id="database-backup-file"
                  aria-label="备份文件"
                  type="file"
                  accept=".db,.sqlite,.sqlite3"
                  onChange={(event) => setSelectedFile(event.target.files?.[0] || null)}
                />
              </div>

              <Input
                aria-label="确认短语"
                value={confirmationPhrase}
                onChange={(event) => setConfirmationPhrase(event.target.value)}
                placeholder={DATABASE_RESTORE_CONFIRMATION_PHRASE}
                prefix={<ReloadOutlined />}
              />

              <Button
                danger
                type="primary"
                icon={<UploadOutlined />}
                loading={restoring}
                disabled={!canRestore}
                onClick={handleRestore}
              >
                开始恢复
              </Button>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default DatabaseBackup;
