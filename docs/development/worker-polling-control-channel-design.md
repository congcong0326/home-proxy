# Worker Polling Control Channel Design

## Summary

本设计为 `proxy-worker` 增加一条由 worker 主动轮询 `control-manager` 的控制通道，用于下发轻量任务和回传 worker 运行指标。第一阶段只面向单 worker 部署，不引入 worker 入站 HTTP 服务，不做 worker 地址上报与服务发现。

核心请求形态：

```text
proxy-worker -> POST /api/worker/poll
```

每次轮询时，worker 上报心跳、运行指标和上一轮任务执行结果；`control-manager` 返回当前可领取任务。任务一旦被本次 poll 返回，即视为已消费，后续 poll 不再返回同一任务。管理员如果需要再次执行同类操作，需要重新派发任务。

## Background

当前 WOL 由 `control-manager` 容器直接发送 UDP 广播包。默认 Docker Compose 中 `control-manager` 运行在普通 bridge 网络内，广播包很难可靠进入宿主机物理 LAN，因此 WOL 调试不通很可能与容器网络隔离有关。

`proxy-worker` 已经使用 `network_mode: host` 部署，并且本身通过定时任务主动从 `control-manager` 拉取聚合配置。让 worker 继续保持主动出站连接，可以复用当前部署模型，避免新增 worker HTTP 端口、worker IP/端口注册、control-manager 主动调用 worker 等复杂度。

## Goals

- 解决 WOL 需要从宿主机/LAN 网络栈发包的问题。
- 建立一条轻量 worker 控制通道，兼容后续下发简单操作任务。
- 复用同一轮询请求采集 worker 监控信息，例如内存、运行时长、运行入站数量、连接数。
- 保持单 worker 假设，避免引入 worker 调度、选择、路由和任务分片。
- 保持接口白名单放行，延续当前 worker 拉配置和日志上报接口的访问方式。
- 任务只做一次性下发，不做完整状态机。

## Non-Goals

- 不实现多 worker 调度。
- 不要求 worker 开 HTTP 端口。
- 不要求 worker 上报可被控制面回调的 IP 和端口。
- 不实现任务状态机，例如 `PENDING -> DISPATCHED -> SUCCEEDED/FAILED/TIMEOUT`。
- 不实现可靠任务队列、重试队列、幂等执行保障或分布式锁。
- 不在第一阶段做长期指标趋势存储；先保存 worker 最新状态。

## Architecture

新增模块分为三部分：

- `control-manager` 的 worker 控制接口：接收 poll 请求，保存 worker 最新指标，返回未消费任务。
- `control-manager` 的任务派发服务：由管理端业务触发，例如 WOL 页面点击唤醒后创建一条待领取任务。
- `proxy-worker` 的轮询执行器：定时调用 poll 接口，执行返回任务，并在下一次 poll 中回传执行结果。

整体数据流：

```text
管理员点击 WOL
  -> control-manager 创建 WOL_WAKE 任务
  -> proxy-worker 定时 POST /api/worker/poll
  -> control-manager 返回未消费任务并标记已消费
  -> proxy-worker 在宿主机网络栈执行 WOL 发包
  -> proxy-worker 下一次 poll 回传上次任务结果和运行指标
  -> control-manager 保存 worker 最新状态和最近任务结果
```

## API Design

### Worker Poll

```text
POST /api/worker/poll
```

该接口加入 Spring Security 白名单，和现有 `/api/config/aggregate`、`/api/logs/**` 一样不要求管理员 JWT。

请求体：

```json
{
  "workerId": "default",
  "hostname": "proxy-host",
  "startedAt": "2026-05-16T10:00:00",
  "lastConfigHash": "abc123",
  "metrics": {
    "uptimeSeconds": 3600,
    "heapUsedBytes": 134217728,
    "heapMaxBytes": 536870912,
    "runningInboundCount": 3,
    "activeConnectionCount": 12
  },
  "taskResults": [
    {
      "taskId": 1001,
      "success": true,
      "message": "WOL packet sent",
      "finishedAt": "2026-05-16T10:01:05"
    }
  ]
}
```

响应体：

```json
{
  "serverTime": "2026-05-16T10:01:06",
  "nextPollIntervalMillis": 2000,
  "tasks": [
    {
      "taskId": 1002,
      "type": "WOL_WAKE",
      "payload": {
        "deviceName": "NAS",
        "macAddress": "AA:BB:CC:DD:EE:FF",
        "broadcastIp": "192.168.1.255",
        "port": 9
      }
    }
  ]
}
```

第一阶段固定 `workerId=default` 即可。后续如果要支持多 worker，可以把 `workerId` 扩展为配置项，并在任务表上增加 worker 绑定字段。

## Task Semantics

第一阶段采用“领取即消费”语义：

- 管理员触发操作时，`control-manager` 新建一条任务记录。
- worker poll 时，`control-manager` 查询未消费任务。
- 接口返回任务前，将这些任务标记为已消费。
- 已消费任务不会再次下发。
- worker 执行失败也不自动重试。
- 管理员再次点击唤醒，会创建新任务。

建议任务表只保留最小字段：

```text
worker_tasks
- id
- task_type
- payload_json
- created_at
- consumed_at
- result_success
- result_message
- result_reported_at
```

这里的 `consumed_at` 不是状态机，只用于避免重复下发。`result_*` 仅用于展示最近执行结果和排查问题，不参与任务调度。

## WOL Task

任务类型：

```text
WOL_WAKE
```

任务 payload 在创建时由 `control-manager` 计算完整发包参数：

```json
{
  "deviceName": "NAS",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "broadcastIp": "192.168.1.255",
  "port": 9
}
```

这样 worker 不需要读取 WOL 配置表，也不需要理解设备配置，只负责解析 payload 并发送 UDP 魔术包。广播地址仍由管理端基于设备 IP 和子网掩码计算，保持现有 `WolService` 行为一致。

WOL 页面点击唤醒后的语义调整为：

- 旧行为：`control-manager` 同步发送 WOL 包并返回发送结果。
- 新行为：`control-manager` 创建 WOL 任务并返回“任务已派发，等待 worker 执行”。

如果需要前端立即显示结果，可以在第一阶段只显示派发成功；最近一次执行结果可在刷新设备列表或后续 worker 状态区域中展示。

## Worker Metrics

worker 每次 poll 上报最新指标。第一阶段只保存最新快照，不做历史趋势。

建议字段：

```text
worker_status
- worker_id
- hostname
- started_at
- last_seen_at
- last_config_hash
- uptime_seconds
- heap_used_bytes
- heap_max_bytes
- running_inbound_count
- active_connection_count
```

指标来源：

- JVM 内存：`Runtime.getRuntime()`。
- uptime：worker 启动时记录 `startedAt`，每次计算差值。
- running inbound count：从 `ProxyContext` 当前运行服务数量读取。
- active connection count：第一阶段可以先接入全局连接计数器；如改动风险较高，可先上报 `0` 并在后续补齐。

## Polling Interval

建议第一阶段默认 2 秒轮询一次：

```text
WORKER_CONTROL_POLL_INTERVAL_MS=2000
```

响应体中的 `nextPollIntervalMillis` 允许控制面动态调整下一次轮询间隔。第一阶段可以先返回固定值。

轮询失败时 worker 不退出，记录 warn 日志并按退避策略重试：

```text
正常：2s
连续失败：5s
持续失败：10s 上限
成功后恢复：2s
```

## Security

按当前要求，第一阶段接口加入白名单，不校验管理员 JWT，也不新增 worker token。

部署前提：

- `control-manager` 只暴露在可信 LAN/VPN 或本机反向代理之后。
- 不建议把 `/api/worker/poll` 暴露到公网。
- 后续如果需要多 worker 或公网部署，应增加 worker token 或签名认证。

## Failure Handling

本设计接受以下第一阶段行为：

- worker 离线时任务留在未消费状态，worker 恢复后会领取。
- 任务已返回给 worker 但 worker 执行前崩溃，该任务不会再次下发。
- worker 执行失败只记录最近结果，不自动重试。
- 管理员需要再次执行时重新派发任务。

这些行为符合“只管下发、消费后不再下发”的约束。

## Implementation Scope

### common

新增 DTO：

- `WorkerPollRequest`
- `WorkerPollResponse`
- `WorkerMetricsDTO`
- `WorkerTaskDTO`
- `WorkerTaskResultDTO`

### control-manager

新增：

- `WorkerController`：提供 `/api/worker/poll`。
- `WorkerTask` 实体和 repository。
- `WorkerStatus` 实体和 repository。
- `WorkerControlService`：处理 poll、任务消费、结果保存和指标更新。

调整：

- `SecurityConfig` 白名单放行 `/api/worker/poll`。
- `WolService` 或 `WolController` 创建 `WOL_WAKE` 任务，不再由 control-manager 直接发包。

### proxy-worker

新增：

- `WorkerControlService`：定时 poll 控制面。
- `WorkerMetricsCollector`：采集 JVM 和代理运行指标。
- `WorkerTaskExecutor`：按任务类型执行任务。
- `WolTaskExecutor`：发送 UDP WOL 魔术包。

调整：

- `ProxyWorkerConfig` 增加 worker poll URL 和轮询间隔配置。
- `ProxyContext` 暴露运行入站数量；连接数可以第一阶段先预留或补全轻量计数。

### frontend

第一阶段最小调整：

- WOL 点击唤醒后提示“任务已派发，等待 worker 执行”。

可选后续调整：

- 增加 worker 状态展示。
- 展示最近一次 WOL 任务执行结果。

## Testing Strategy

后端控制面：

- 单测 poll 接口领取任务后不再重复返回。
- 单测 taskResults 能更新任务结果字段。
- 单测 metrics 能更新 worker 最新状态。
- 单测 WOL 点击会创建 `WOL_WAKE` 任务。

worker：

- 单测 `WolTaskExecutor` 构造正确魔术包并向指定广播地址和端口发送。
- 单测 poll 响应包含任务时会调用对应 executor。
- 单测执行结果在下一次 poll 请求中回传。
- 单测 poll 失败时不会终止 worker。

集成验证：

- 启动 control-manager 和 host-network worker。
- UI 点击 WOL。
- worker 日志显示领取并执行 `WOL_WAKE`。
- 宿主机 LAN 网卡 `tcpdump` 能抓到 UDP 7/9 发包。

## Risks

- 白名单接口没有 worker 身份认证，必须依赖网络边界保护。
- 领取即消费会牺牲可靠性，worker 崩溃可能导致任务丢失。
- 单 worker 假设会限制后续横向扩展；未来多 worker 需要补 worker 绑定和鉴权。
- 活动连接数如果要精确统计，需要在 Netty channel 生命周期中增加计数，需避免遗漏异常关闭路径。

## Review Notes

本设计刻意把第一阶段范围压小：先解决 WOL 发包位置问题，并顺手建立 worker 心跳和指标快照通道。任务可靠性、多 worker 调度、worker 认证、指标历史趋势都作为后续演进点，不进入第一阶段。
