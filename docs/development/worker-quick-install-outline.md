# Worker 快捷部署方案大纲

> 状态：方向已讨论确定，**只记录大纲与关键决策，细节待实施前再展开**。排在《[控制端数据库裁剪设计](controller-sqlite-migration-design.md)》之后；近期不新增 Worker，因此不急于落地。

## 1. 问题与目标

现有 Worker 部署要手动处理 Docker、`network_mode: host`、控制端地址、`WORKER_ID`、管理 token、证书、GeoIP 等多项参数，并另行在宿主机运行 `tproxy_netty.sh`。每增加一台机器都要重复一遍。

目标：**在目标机器上执行一条命令，完成 Worker 的安装、启动和自启**，并支持升级、回滚、卸载，过程可重复执行且失败可回退。目标用户是项目作者本人，不追求通用发行版级别的覆盖面。

## 2. 关键决策

| 决策 | 结论 | 理由 |
|---|---|---|
| Worker 运行形态 | 宿主机直接运行，不再用 Docker | 本来就要 host 网络并改宿主机 iptables；去掉 Docker 依赖，也避免了 Controller 无法向 Worker 传输镜像的问题 |
| 打包方式 | Maven assembly 生成 `tar.gz`（jar、脚本、配置、JRE），沿用公司惯例 | 包体几十 MB，便于托管与下载 |
| Java 运行时 | 包内自带官方 Temurin 17 完整 JRE；不做 jlink、不做 native-image | 目标机免装 JDK；完整 JRE 无"缺模块"风险；GraalVM 原生镜像适配成本和调试代价高、吞吐不占优 |
| 安装方式 | `curl -fsSL <地址>/install.sh \| sudo sh -s -- --token … --mem …` | 脚本在目标机本地执行，天然有终端与 sudo；不需要 SSH 账密、sudoers、PTY 处理；纯密钥登录的机器也不受影响 |
| 安装包托管 | 公共下载地址（GitHub Releases 或自有静态站，`--base-url` 可配）；支持 `--package` 离线安装 | Worker 无需访问控制端，家里内网与公网 VPS 通吃 |
| 控制端与 Worker 的连接方向 | 保持**控制端主动 WSS 连接 Worker 的 18444**，Worker 不需要知道控制端地址，只校验 token | 现状不变；要求控制端能到达 Worker（控制端在家里内网，Worker 在内网或公网 VPS 均可） |
| 进程托管 | systemd，`Restart=always`；`MemoryMax`（老 systemd 用 `MemoryLimit`）+ JVM `-Xmx`（约为上限 70%）+ `-XX:+ExitOnOutOfMemoryError` | 不依赖 Docker 也能限制内存并在 OOM 后自动拉起 |
| 透明代理（iptables/TPROXY） | 与 Worker 安装**分离**，作为可选的"网关模式" | 网关拓扑因家而异，不追求通用；Worker 本体通用 |

不做的事：控制端 SSH 推送安装、保存目标机账密、worker 容器镜像分发。若日后确有需要，可在同一套 `install.sh` 之上以增量方式追加，不在第一版预留。

## 3. 目录与版本布局（目标机）

```
/opt/home-proxy/
├── releases/<version>/   bin/ conf/ lib/ jre/ VERSION
├── current -> releases/<version>      # 软链接切换版本
├── data/                              # last-known-good 配置、management token 等，与 release 分离
└── state.json                         # 当前版本、已完成步骤，供幂等与续跑
```

systemd 单元指向 `current`；回滚 = 软链接指回上一版本并重启。数据目录独立，升级回滚都不丢。

## 4. install.sh 能力

- 子命令：`install`、`status`、`start`/`stop`/`restart`、`upgrade [--version]`、`rollback`、`uninstall [--purge]`。
- **预检（只读）**：CPU 架构、glibc 版本、systemd 版本、内存与磁盘、端口占用（Worker 端口与 18444）、是否已安装；并执行一次自带 JRE 的 `java -version` 验证系统兼容。预检失败给出明确原因并停止。
- **每一步幂等**：先检查，已满足则跳过；步骤具备 `check / apply / undo`。
- **失败回滚**：新增 release 目录而不覆盖旧版本；失败按步骤逆序撤销，保留失败日志。
- **校验**：下载包校验 sha256。
- **参数**：token、内存上限、`--base-url`、`--package`、数据目录等。
- **卸载**：默认保留数据目录，`--purge` 彻底清除；同时撤销网关模式规则。

## 5. 兼容性与待实测事项

- 范围：先只做 x86_64；arm64 待有实际需求再增加第二份包。
- glibc：Temurin 官方 Linux 包基线较老，预期覆盖 CentOS 7+ / Debian / Ubuntu 主流版本，**上线前须在 CentOS 7、Debian 11/12、Ubuntu 20.04/22.04/24.04 容器中实测** `java -version` 与 Worker 启动；Alpine（musl）不支持，预检直接拒绝。
- `netty-transport-native-epoll` 的 `.so` 同样依赖架构与 glibc，按架构只放对应 classifier；纳入同一轮兼容性测试。
- 老 systemd（如 CentOS 7 的 219）不支持 `MemoryMax`，按版本生成 `MemoryLimit`。无 systemd 的环境不在支持范围。

## 6. 网关模式（透明代理）

- 现状 `backend/tproxy_netty.sh` 假设本机即 PPPoE 网关（硬编码 `ppp0`、`LAN_IF`、`LAN_CIDR`，并做 `MASQUERADE`），仅适用于"主路由"拓扑。
- 改进方向（独立于 Worker 安装，之后再做）：
  - 参数通过环境文件/命令行传入，不再改脚本；
  - WAN/LAN 网卡与网段自动探测（默认路由所在网卡），探测不到再要求显式指定；
  - 增加 `--dry-run` 与 `--cleanup`；
  - 规则带"确认窗口"（先应用，超时未确认自动还原），避免远程操作把自己锁在门外；
  - 文档写明已验证环境（PPPoE 主路由），不声称通用。
- `install.sh --gateway` 作为可选附加步骤调用上述脚本，不进入默认安装流程。

## 7. 控制台侧（最后做，价值最低）

- 「添加 Worker」页面：生成 `WORKER_ID` 与管理 token，展示可复制的安装命令；用户在目标机粘贴执行后，再在控制台填写该机地址，由控制端主动连接。
- 无需 SSH 库、无需保存任何目标机凭证。

## 8. 实施顺序（建议）

1. assembly 打包（含 JRE 下载与 sha256 校验）与 systemd 单元模板。
2. `install.sh` 最小闭环：预检 → 安装 → 启动 → 验证；在一台测试机手工验证**幂等**（连续执行两次）与**回滚**（中途故意失败）。
3. 补全 `upgrade` / `rollback` / `uninstall`。
4. 多发行版容器兼容性矩阵。
5. 网关模式脚本改造。
6. 控制台「添加 Worker」页面。

## 9. 待细化（实施前展开）

- 包托管位置与发布流程（GitHub Releases 还是自有静态站，是否与现有 CI/发布方式对接）。
- token 的生成、存放与轮换方式，以及 18444 端口的访问来源限制建议。
- 现有 Docker 部署方式的保留期与迁移说明（已有 Worker 如何从容器切换到原生包，配置与数据如何承接）。
- 日志位置与轮转（journald 还是文件）。
- 第一版是否需要 arm64。
