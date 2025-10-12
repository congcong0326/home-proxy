# 前端集成到Spring Boot指南

本文档说明如何将React前端应用打包并集成到Spring Boot后端中。

## 项目结构

```
nas-proxy/
├── frontend/                    # React前端项目
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── .env.production         # 生产环境配置
├── backend/
│   └── control-manager/        # Spring Boot后端
│       └── src/main/resources/
│           └── static/         # 静态资源目录（前端构建文件放这里）
└── scripts/
    ├── build-frontend.ps1      # Windows构建脚本
    └── build-frontend.sh       # Linux/Mac构建脚本
```

## 配置说明

### 1. 前端配置

#### package.json 配置
- 添加了 `homepage: "/"` 确保资源路径正确
- 添加了 `build:prod` 脚本，禁用source map以减小文件大小

#### .env.production 配置
```bash
PUBLIC_URL=/
BUILD_PATH=build
```

#### 路由配置
前端使用 `BrowserRouter`，需要后端支持路由回退到 `index.html`。

### 2. 后端配置

#### WebConfig.java
创建了Web配置类处理：
- 静态资源映射
- 前端路由回退（SPA路由支持）
- API请求不进行回退

#### SecurityConfig.java
更新了安全配置，允许访问：
- 静态资源文件（JS、CSS、图片等）
- 根路径和常见静态文件

## 构建和部署

### 方法一：使用自动化脚本（推荐）

#### Windows (PowerShell)
```powershell
# 完整构建（清理+安装依赖+构建+部署）
.\scripts\build-frontend.ps1 -Clean

# 仅构建和部署
.\scripts\build-frontend.ps1

# 生产环境构建
.\scripts\build-frontend.ps1 -Profile prod

# 跳过构建，仅部署已有的build文件
.\scripts\build-frontend.ps1 -SkipBuild
```

#### Linux/Mac (Bash)
```bash
# 完整构建
./scripts/build-frontend.sh --clean

# 仅构建和部署
./scripts/build-frontend.sh

# 生产环境构建
./scripts/build-frontend.sh --profile prod

# 跳过构建，仅部署
./scripts/build-frontend.sh --skip-build
```

### 方法二：手动构建

1. **构建前端**
   ```bash
   cd frontend
   npm install
   npm run build:prod  # 或 npm run build
   ```

2. **复制文件到后端**
   ```bash
   # 清理旧文件
   rm -rf backend/control-manager/src/main/resources/static/*
   
   # 复制新文件
   cp -r frontend/build/* backend/control-manager/src/main/resources/static/
   ```

3. **启动Spring Boot应用**
   ```bash
   cd backend
   mvn spring-boot:run
   ```

## 路由处理说明

### 前端路由
前端使用以下主要路由：
- `/login` - 登录页面
- `/change-password` - 修改密码
- `/config/users` - 用户管理
- `/config/routing` - 路由管理
- `/config/inbound` - 入站配置
- `/config/ratelimit` - 限流配置
- `/config/overview/log-audit` - 日志审计
- `/config/overview/aggregate` - 聚合分析

### 后端API路由
后端API使用 `/api/` 前缀，不会被前端路由回退影响：
- `/api/config/aggregate` - 聚合配置
- `/admin/login` - 管理员登录
- 其他管理API

### 路由回退机制
- 静态资源请求直接返回对应文件
- API请求（`/api/`开头）不进行回退
- 其他所有请求回退到 `index.html`，由前端路由处理

## 注意事项

### 1. 路由冲突避免
- 确保前端路由不与后端API路由冲突
- 后端API统一使用 `/api/` 前缀
- 管理员相关API使用 `/admin/` 前缀

### 2. 静态资源缓存
- 生产环境建议配置适当的缓存策略
- JS/CSS文件通常有hash，可以长期缓存
- `index.html` 不应缓存，确保更新能及时生效

### 3. 开发环境
开发时前后端分离：
- 前端：`npm start` (http://localhost:3000)
- 后端：Spring Boot (http://localhost:8080)
- 前端通过proxy配置代理API请求到后端

### 4. 生产环境
生产环境前后端集成：
- 前端构建文件部署到Spring Boot的static目录
- 通过同一端口访问前后端功能
- 访问 http://localhost:8080 即可使用完整应用

## 验证部署

1. **启动应用**
   ```bash
   cd backend
   mvn spring-boot:run
   ```

2. **访问测试**
   - 打开浏览器访问 http://localhost:8080
   - 应该看到前端登录页面
   - 检查浏览器开发者工具，确保静态资源正常加载
   - 测试前端路由跳转功能

3. **API测试**
   - 确保API请求正常工作
   - 检查登录功能
   - 验证各个管理功能页面

## 故障排除

### 常见问题

1. **静态资源404**
   - 检查文件是否正确复制到 `backend/control-manager/src/main/resources/static/`
   - 检查SecurityConfig是否允许访问静态资源

2. **前端路由404**
   - 检查WebConfig中的路由回退配置
   - 确保非API请求都回退到index.html

3. **API请求失败**
   - 检查API路径是否正确
   - 确认SecurityConfig中的API权限配置

4. **构建失败**
   - 检查Node.js和npm版本
   - 清理node_modules重新安装依赖
   - 检查网络连接和npm源配置

### 日志检查
- Spring Boot启动日志
- 浏览器开发者工具Network面板
- 浏览器控制台错误信息