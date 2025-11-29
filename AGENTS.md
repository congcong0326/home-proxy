# Repository Guidelines

## 项目结构与模块组织
- `backend/` Maven 多模块：`common`(DTO/工具/配置模型)、`proxy-worker`(Netty 转发与协议嗅探)、`control-manager`(Spring Boot API、配置发布、指标聚合)。
- `frontend/` React + TypeScript 单页，源码在 `frontend/src`，构建产物输出到 `frontend/build`。
- `docs/` 与 `images/` 存放架构和接口文档；`scripts/` 包含前端打包脚本；`README-Frontend-Integration.md` 说明端到端部署。

## 构建、测试与本地运行
- 后端全量构建：`cd backend && mvn clean package -DskipTests`，生成各模块 jar（管理端为可执行 Spring Boot jar，worker 为 shaded fat jar）。
- 仅运行管理面：`mvn -pl backend/control-manager spring-boot:run`（需提前准备 MySQL/Flyway 配置与 GeoLite2 数据文件）。
- 仅打包代理进程：`mvn -pl backend/proxy-worker -am package -DskipTests`。
- 前端开发：`cd frontend && npm install && npm start`（默认代理到 `localhost:8080`）。
- 前端生产构建：`npm run build` 或 `powershell ./scripts/build-frontend.ps1`（类 Unix 系统用 `./scripts/build-frontend.sh`）。

## 编码风格与命名约定
- Java 17，4 空格缩进；包名小写 `org.congcong.*`，类用帕斯卡命名，方法/变量用 camelCase，常量 UPPER_SNAKE_CASE；优先使用 Lombok（如 `@Slf4j`）和 Netty/Spring 约定的日志格式。
- TypeScript/React 默认 2 空格缩进；组件与文件使用帕斯卡命名（如 `Dashboard.tsx`），hooks 以 `use` 前缀；避免默认导出，保持命名导出一致。

## 测试指引
- 后端单元测试使用 Maven Surefire；`control-manager` 默认 `skipTests=true`，需要验证时运行 `mvn -pl backend/control-manager test -DskipTests=false`；集成测试推荐使用 Failsafe 并标记为 `*IT.java`。
- 前端使用 React Testing Library/Jest，测试文件放置为 `*.test.tsx`；运行 `npm test -- --watch=false` 以便 CI。

## Commit 与 Pull Request
- Git 历史遵循类 Conventional Commits（如 `feat ...`、`fix ...`、`chore ...`）；保持英文/中文描述一致、精准。
- PR 需包含：变更摘要、相关 issue/需求链接、测试/构建结果、前端改动的截图或录屏，以及风险与回滚计划；确保格式化后再提交（前端可借助 `npm test`/`npm run build` 进行快速检查）。

## 安全与配置提示
- 不要提交密钥/证书；前端 `.env.production` 与后端数据库/JWT/证书配置请使用本地或流水线密钥管理。
- `backend/GeoLite2-City.mmdb` 为 GeoIP 功能必需；数据库迁移由 Flyway 在 `backend/control-manager/src/main/resources/db/migration` 自动执行，变更时同步更新脚本与 README。
