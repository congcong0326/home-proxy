# Proxy Worker 代理工作节点

这是一个轻量级的代理工作节点，负责从控制管理端获取配置并应用到代理服务中。

## 功能特性

- ✅ 使用 Java 17 内置 HTTP 客户端
- ✅ 支持 HTTP 304 缓存机制，减少不必要的网络传输
- ✅ 最小化依赖，轻量级设计
- ✅ 定期自动获取配置更新
- ✅ 配置变更监听机制
- ✅ 完整的日志记录

## 项目结构

```
proxy-worker/
├── src/main/java/org/congcong/proxyworker/
│   ├── ProxyWorkerApplication.java          # 主应用程序
│   ├── config/
│   │   └── ProxyWorkerConfig.java          # 配置文件读取
│   ├── http/
│   │   └── HttpClientManager.java          # HTTP客户端管理
│   └── service/
│       └── AggregateConfigService.java     # 聚合配置服务
├── src/main/resources/
│   ├── proxy-worker.properties             # 配置文件
│   └── logback.xml                         # 日志配置
└── pom.xml                                 # Maven依赖配置
```

## 依赖说明

本项目采用最小化依赖设计：

- **Java 17**: 使用内置的 HTTP 客户端，无需额外的 HTTP 库
- **Jackson**: 用于 JSON 序列化/反序列化
- **SLF4J + Logback**: 日志框架
- **Common 模块**: 共享的 DTO 类定义
 - **Guava**: 可选缓存（GeoIP 查询性能优化）
 - **MaxMind GeoIP2**: 通过 GeoLite2-City.mmdb 解析 IP 地理位置（国家/城市）

## 配置说明

### proxy-worker.properties

```properties
# 控制端主机地址
control.host=localhost

# 控制端端口
control.port=8080

# 配置获取间隔（秒）
config.fetch.interval=30

# HTTP客户端超时配置（毫秒）
http.client.connect.timeout=5000
http.client.read.timeout=10000

## GeoIP2（可选）

若提供 GeoLite2-City.mmdb，将启用地理位置解析与缓存。mmdb 默认查找顺序：
- 显式传入路径（代码构造函数参数）
- 工作目录 `data/GeoLite2-City.mmdb`
- 环境变量 `GEOIP2_MMDB_PATH`

未找到 mmdb 时，地理位置不可用，但域名/IP 解析仍可用。
```

## 使用方法

### 1. 编译项目

```bash
cd proxy-worker
mvn clean compile
```

### 2. 运行应用程序

```bash
mvn exec:java -Dexec.mainClass="org.congcong.proxyworker.ProxyWorkerApplication"
```

### 3. 集成到现有项目

```java
// 创建配置服务
AggregateConfigService configService = new AggregateConfigService();

// 设置配置变更监听器
configService.setConfigChangeListener(newConfig -> {
    // 处理配置变更
    System.out.println("配置已更新: " + newConfig.getVersion());
});

// 启动服务
configService.start();

// 获取当前配置
AggregateConfigResponse currentConfig = configService.getCurrentConfig();

// GeoIP 使用示例
GeoIPUtil geo = GeoIPUtil.createDefault();
if (geo.isAvailable()) {
    geo.lookup("www.example.com").ifPresent(loc -> {
        System.out.println("Country=" + loc.getCountry() + ", City=" + loc.getCity());
    });
}
```

## HTTP 304 缓存机制

本实现支持标准的 HTTP 304 缓存机制：

1. **首次请求**: 发送 GET 请求到 `/api/config/aggregate`
2. **服务端响应**: 返回配置数据和 ETag 头
3. **后续请求**: 自动添加 `If-None-Match` 头
4. **缓存命中**: 服务端返回 304 状态码，使用本地缓存
5. **缓存失效**: 服务端返回 200 状态码和新配置

## 日志配置

日志文件位置：`logs/proxy-worker.log`

- 自动按日期和大小滚动
- 保留最近 30 天的日志
- 单个文件最大 10MB

## API 接口

### 获取聚合配置

```
GET /api/config/aggregate
```

**请求头**:
- `If-None-Match`: ETag 值（可选）

**响应**:
- `200 OK`: 返回配置数据
- `304 Not Modified`: 配置未变更
- `500 Internal Server Error`: 服务器错误

## 开发说明

### 扩展配置变更处理

实现 `ConfigChangeListener` 接口来处理配置变更：

```java
public class MyConfigHandler implements AggregateConfigService.ConfigChangeListener {
    @Override
    public void onConfigChanged(AggregateConfigResponse newConfig) {
        // 应用新配置
        applyRoutes(newConfig.getRoutes());
        applyRateLimits(newConfig.getRateLimits());
        // ...
    }
}
```

### 自定义 HTTP 客户端

如需自定义 HTTP 客户端行为，可以修改 `HttpClientManager` 类：

```java
// 自定义超时时间
this.httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(customTimeout))
    .build();
```

## 故障排除

### 常见问题

1. **连接超时**: 检查 `control.host` 和 `control.port` 配置
2. **JSON 解析错误**: 确保 common 模块版本匹配
3. **配置未更新**: 检查网络连接和服务端状态

### 调试模式

修改 `logback.xml` 中的日志级别：

```xml
<logger name="org.congcong.proxyworker" level="DEBUG" />
```

## 许可证

本项目采用与主项目相同的许可证。