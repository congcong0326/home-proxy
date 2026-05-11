# Repository Guidelines

## 项目结构与模块组织
- `backend/` Maven 多模块：`common`(DTO/工具/配置模型)、`proxy-worker`(Netty 转发与协议嗅探)、`control-manager`(Spring Boot API、配置发布、指标聚合)。
- `frontend/` React + TypeScript 单页，源码在 `frontend/src`，构建产物输出到 `frontend/build`。
- `docs/` 存放研发文档，内容一般以中文书写，实施前按需读取。

## 渐进式披露
- 第一层：先读本文件，掌握项目结构、构建命令、编码风格、测试与安全约束。
- 第二层：需要定位代码时读 `docs/code-index.md`，从模块职责、入口文件和关键调用链缩小范围。
- 第三层：只打开当前任务涉及的 controller/service/page/DTO/迁移脚本；避免一次性通读 `target`、`build`、静态产物或无关历史文档。
- 涉及跨模块协议、配置字段或 API 契约时，按 `docs/code-index.md` 的“常见改动定位”检查 `common`、`control-manager`、`proxy-worker`、`frontend` 是否需要同步。

## 构建、测试与本地运行
- 推荐使用根目录 `Makefile` 作为统一入口；默认 Maven 缓存为 `./.m2/repository`，npm 缓存为 `./.npm`，两者均不应提交。
- 常用统一命令：`make build`（后端打包 + 前端构建）、`make package`（前端构建并同步到 `control-manager` 静态目录后再打后端包）。
- 后端命令：`make backend-build`、`make backend-test`、`make backend-dev`、`make worker-build`。
- 前端命令：`make frontend-install`、`make frontend-build`、`make frontend-test`、`make frontend-dev`、`make frontend-sync-static`。

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
