export interface DiskInfo {
  device: string;
  model: string;
  serial: string;
  size: string;
  status: string;
  temperature: number;
}

export type DiskType = 'HDD' | 'SATA_SSD' | 'NVME_SSD';

export interface DiskDetail {
  // 通用指标
  device: string;
  model: string;
  serial: string;
  size: string;
  temperature: number;
  health: string;
  smartSupported: boolean;
  smartEnabled: boolean;

  // 通用运行时指标（单位：字节）
  powerOnHours: number;
  powerCycleCount: number;
  dataUnitsRead: number;      // 通用读取总量（字节）
  dataUnitsWritten: number;   // 通用写入总量（字节）

  // HDD 指标（仅机械硬盘有效）
  reallocatedSectorCount: number;
  seekErrorRate: number;
  spinRetryCount: number;
  udmaCrcErrorCount: number;

  // SSD 通用指标（NVMe/SATA）
  percentageUsed: number;
  unsafeShutdowns: number;
  mediaErrors: number;

  // SATA SSD 指标（仅 SATA 固态有效）
  ssdLifeLeft: number;
  flashWritesGiB: number;
  lifetimeWritesGiB: number;
  lifetimeReadsGiB: number;
  averageEraseCount: number;
  maxEraseCount: number;
  totalEraseCount: number;

  // 类型与温度曲线
  diskType: DiskType;
  historyTemperature: number[];
  historyReadBytes?: number[];
  historyWriteBytes?: number[];
}
