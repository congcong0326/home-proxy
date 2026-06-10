# Worker Polling Control Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a single-worker pull control channel that dispatches consume-once WOL tasks and reports worker metrics.

**Architecture:** `control-manager` persists worker tasks and latest worker status, exposes `POST /api/worker/poll`, and changes WOL wake requests to enqueue `WOL_WAKE` tasks. `proxy-worker` periodically polls that endpoint, executes returned tasks from host networking, and returns task results on the next poll.

**Tech Stack:** Java 17, Maven, Spring Boot MVC/JPA/Security, Flyway, Java `HttpClient`, Netty UDP datagrams, React TypeScript.

---

### Task 1: Shared Worker-Control DTOs

**Files:**
- Create: `backend/common/src/main/java/org/congcong/common/dto/WorkerMetricsDTO.java`
- Create: `backend/common/src/main/java/org/congcong/common/dto/WorkerTaskDTO.java`
- Create: `backend/common/src/main/java/org/congcong/common/dto/WorkerTaskResultDTO.java`
- Create: `backend/common/src/main/java/org/congcong/common/dto/WorkerPollRequest.java`
- Create: `backend/common/src/main/java/org/congcong/common/dto/WorkerPollResponse.java`
- Create: `backend/common/src/main/java/org/congcong/common/dto/WolWakeTaskPayload.java`
- Test: `backend/common/src/test/java/org/congcong/common/dto/WorkerControlDtoTest.java`

- [ ] **Step 1: Write failing DTO serialization tests**

Create `WorkerControlDtoTest` that builds a request with metrics and one result, serializes it with Jackson JavaTimeModule, deserializes it, and asserts `workerId`, `heapUsedBytes`, and `taskId` round-trip.

- [ ] **Step 2: Run DTO test and verify missing classes fail**

Run: `mvn -f backend/pom.xml -pl common test -Dtest=WorkerControlDtoTest`

Expected: compilation fails because worker-control DTO classes do not exist.

- [ ] **Step 3: Add minimal Lombok DTO classes**

Add DTO classes with `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`, and fields matching the design document:
`workerId`, `hostname`, `startedAt`, `lastConfigHash`, `metrics`, `taskResults`, `serverTime`, `nextPollIntervalMillis`, `tasks`, `taskId`, `type`, `payload`, `success`, `message`, `finishedAt`, WOL payload fields.

- [ ] **Step 4: Run DTO test and verify pass**

Run: `mvn -f backend/pom.xml -pl common test -Dtest=WorkerControlDtoTest`

Expected: 1 test passes.

### Task 2: Control-Manager Poll Persistence And API

**Files:**
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/entity/WorkerTask.java`
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/entity/WorkerStatus.java`
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/repository/WorkerTaskRepository.java`
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/repository/WorkerStatusRepository.java`
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/service/WorkerControlService.java`
- Create: `backend/control-manager/src/main/java/org/congcong/controlmanager/controller/WorkerController.java`
- Create: `backend/control-manager/src/main/resources/db/migration/V19__create_worker_control_tables.sql`
- Modify: `backend/control-manager/src/main/java/org/congcong/controlmanager/security/SecurityConfig.java`
- Test: `backend/control-manager/src/test/java/org/congcong/controlmanager/service/WorkerControlServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Test three behaviors:
polling consumes a pending task so the second poll returns no tasks; `taskResults` update result fields; metrics update the latest `WorkerStatus`.

- [ ] **Step 2: Run service test and verify missing classes fail**

Run: `mvn -f backend/pom.xml -pl control-manager -DskipTests=false test -Dtest=WorkerControlServiceTest`

Expected: compilation fails because worker-control service/entity classes do not exist.

- [ ] **Step 3: Implement entities, repositories, service, controller, migration, and security allowlist**

Use `consumedAt IS NULL` to find pending tasks. In `poll`, update worker status, apply reported results, read pending tasks, set `consumedAt`, save, and return DTOs. Add `/api/worker/poll` to `SecurityConfig` permitAll.

- [ ] **Step 4: Run service test and verify pass**

Run: `mvn -f backend/pom.xml -pl control-manager -DskipTests=false test -Dtest=WorkerControlServiceTest`

Expected: service tests pass.

### Task 3: WOL Dispatch Moves To Worker Task

**Files:**
- Modify: `backend/control-manager/src/main/java/org/congcong/controlmanager/service/WolService.java`
- Modify: `backend/control-manager/src/test/java/org/congcong/controlmanager/service/WolServiceTest.java`
- Modify: `frontend/src/pages/WolManagement.tsx`

- [ ] **Step 1: Update WOL service tests first**

Change `WolServiceTest` to assert `sendWolPacketById` enqueues one `WOL_WAKE` task with `macAddress`, resolved `broadcastIp`, and `port`, and returns `WOL唤醒任务已派发到设备: Windows`.

- [ ] **Step 2: Run WOL service test and verify fail**

Run: `mvn -f backend/pom.xml -pl control-manager -DskipTests=false test -Dtest=WolServiceTest`

Expected: test fails because `WolService` still sends UDP directly and does not create worker tasks.

- [ ] **Step 3: Modify `WolService` to enqueue tasks**

Inject `WorkerControlService`, create a `WolWakeTaskPayload`, and call `createTask("WOL_WAKE", payload)`. Keep existing broadcast address calculation and existing compatibility method for direct packet construction where still useful.

- [ ] **Step 4: Update frontend success message**

Change WOL click success alert to communicate task dispatch, using the backend message if present.

- [ ] **Step 5: Run WOL service test and verify pass**

Run: `mvn -f backend/pom.xml -pl control-manager -DskipTests=false test -Dtest=WolServiceTest`

Expected: updated WOL tests pass.

### Task 4: Proxy-Worker Polling, Metrics, And WOL Executor

**Files:**
- Modify: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/config/ProxyWorkerConfig.java`
- Modify: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/ProxyWorkerApplication.java`
- Modify: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/server/ProxyContext.java`
- Create: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WorkerMetricsCollector.java`
- Create: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WorkerTaskExecutor.java`
- Create: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WolTaskExecutor.java`
- Create: `backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WorkerControlService.java`
- Test: `backend/proxy-worker/src/test/java/org/congcong/proxyworker/service/WolTaskExecutorTest.java`
- Test: `backend/proxy-worker/src/test/java/org/congcong/proxyworker/service/WorkerControlServiceTest.java`

- [ ] **Step 1: Write failing worker tests**

`WolTaskExecutorTest` verifies a WOL task payload sends a 102-byte magic packet to the configured address and port using an injectable sender. `WorkerControlServiceTest` verifies a returned task is executed and its result is included in the next poll request using an injectable transport.

- [ ] **Step 2: Run worker tests and verify missing classes fail**

Run: `mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false -Dtest=WolTaskExecutorTest,WorkerControlServiceTest`

Expected: compilation fails because worker-control classes do not exist.

- [ ] **Step 3: Implement worker control services**

Add config getters for `getWorkerPollUrl()`, `getWorkerId()`, and `getWorkerControlPollIntervalMs()`. Implement metrics collection from `Runtime` and `ProxyContext`. Implement polling loop with pending result queue, fixed interval, and simple failure backoff. Implement WOL UDP sender with Netty `NioDatagramChannel` and `SO_BROADCAST`.

- [ ] **Step 4: Start and stop worker control service in application lifecycle**

In `ProxyWorkerApplication`, construct and start `WorkerControlService` after access log startup and stop it in the shutdown hook.

- [ ] **Step 5: Run worker tests and verify pass**

Run: `mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false -Dtest=WolTaskExecutorTest,WorkerControlServiceTest`

Expected: worker tests pass.

### Task 5: Verification

**Files:**
- All files above.

- [ ] **Step 1: Run focused backend tests**

Run:
`mvn -f backend/pom.xml -pl common,control-manager,proxy-worker -am test -DskipTests=false -Dtest=WorkerControlDtoTest,WorkerControlServiceTest,WolServiceTest,WolTaskExecutorTest`

Expected: focused worker-control and WOL tests pass.

- [ ] **Step 2: Run control-manager tests**

Run: `mvn -f backend/pom.xml -pl control-manager -am test -DskipTests=false`

Expected: control-manager tests pass.

- [ ] **Step 3: Run proxy-worker tests**

Run: `mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false`

Expected: proxy-worker tests pass.

- [ ] **Step 4: Run frontend test or build check**

Run: `npm test -- --watch=false WolManagement` from `frontend` if available; if that selector cannot run, run `npm test -- --watch=false`.

Expected: frontend tests pass or pre-existing unrelated failures are documented.
