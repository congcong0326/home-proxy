package org.congcong.controlmanager.persistence;

import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.enums.RuleSetItemType;
import org.congcong.controlmanager.config.DataSourceConfig;
import org.congcong.controlmanager.config.WolConfig;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.entity.RuleSetPayloadEntity;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.entity.WorkerTask;
import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.congcong.controlmanager.entity.mail.MailGateway;
import org.congcong.controlmanager.entity.mail.MailTarget;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetPayloadRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.congcong.controlmanager.repository.WolConfigRepository;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.congcong.controlmanager.repository.disk.DiskMonitorSampleRepository;
import org.congcong.controlmanager.repository.mail.MailGatewayRepository;
import org.congcong.controlmanager.repository.mail.MailTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer regression tests against a real SQLite file migrated by Flyway ({@code V1__baseline.sql}).
 * Run with {@code mvn -pl backend/control-manager test -DskipTests=false}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig.class)
@DirtiesContext
class SqliteRepositoryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RouteRepository routes;
    @Autowired UserRepository users;
    @Autowired AdminUserRepository adminUsers;
    @Autowired ProxyWorkerRepository workers;
    @Autowired InboundConfigRepository inbounds;
    @Autowired RuleSetPayloadRepository payloads;
    @Autowired WorkerStatusRepository workerStatuses;
    @Autowired WorkerTaskRepository workerTasks;
    @Autowired WolConfigRepository wolConfigs;
    @Autowired DiskMonitorSampleRepository diskSamples;
    @Autowired MailGatewayRepository mailGateways;
    @Autowired MailTargetRepository mailTargets;

    @Test
    void connectionsApplyForeignKeysAndWal() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isPositive();
    }

    @Test
    void routeJsonColumnsRoundTripAndNamesAreCaseInsensitive() {
        Route route = route("Direct");
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue("example.com");
        route.setRules(List.of(rule));
        route.setOutboundProxyConfig(Map.of("sni", "a.example", "port", 443));
        routes.saveAndFlush(route);

        Route loaded = routes.findById(route.getId()).orElseThrow();
        assertThat(loaded.getRules()).hasSize(1);
        assertThat(loaded.getRules().get(0).getValue()).isEqualTo("example.com");
        assertThat(loaded.getOutboundProxyConfig()).containsEntry("sni", "a.example").containsEntry("port", 443);
        assertThat(routes.existsByName("direct")).isTrue();
        assertThat(routes.findByConditions("IRECT", null, null, PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT rules_json FROM routes WHERE id = ?", String.class, route.getId()))
                .contains("example.com");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO routes (name, rules_json, policy, created_at, updated_at) "
                + "VALUES ('DIRECT', '[]', 'DIRECT', 'x', 'x')")).hasStackTraceContaining("SQLITE_CONSTRAINT");
    }

    @Test
    void usersAndAdminsLookUpByUsernameIgnoringCase() {
        User user = new User();
        user.setUsername("Alice");
        users.saveAndFlush(user);
        assertThat(users.findByUsername("alice")).isPresent();
        assertThat(users.existsByUsername("ALICE")).isTrue();
        assertThat(users.findByStatusAndUsernameContaining(1, "lic", PageRequest.of(0, 5)).getContent()).hasSize(1);
        assertThat(user.getCreatedAt()).isNotNull();

        // Constraint failures are checked with plain JDBC so a failed flush does not poison the JPA session.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (username, created_at, updated_at) VALUES ('aLiCe', 'x', 'x')"))
                .hasStackTraceContaining("SQLITE_CONSTRAINT");

        AdminUser admin = new AdminUser();
        admin.setUsername("Admin");
        admin.setPasswordHash("hash");
        admin.setRoles("ADMIN");
        adminUsers.saveAndFlush(admin);
        AdminUser loaded = adminUsers.findByUsername("admin").orElseThrow();
        assertThat(loaded.isMustChangePassword()).isTrue();
        assertThat(loaded.getVer()).isEqualTo(1);
    }

    @Test
    void identityKeysAreNotReusedAfterDelete() {
        Route first = routes.saveAndFlush(route("first"));
        routes.deleteById(first.getId());
        routes.flush();
        Route second = routes.saveAndFlush(route("second"));
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    @Test
    void workerInboundForeignKeysAreEnforced() {
        ProxyWorker worker = worker("edge-1");
        worker.setTokenHash(new byte[32]);
        worker.getTokenHash()[0] = 7;
        workers.saveAndFlush(worker);
        ProxyWorker loaded = workers.findById("EDGE-1").orElseThrow();
        assertThat(loaded.getTokenHash()).hasSize(32).startsWith(7);
        assertThat(workers.findByTokenHash(worker.getTokenHash())).isPresent();
        assertThat(loaded.getLogLevel()).isNotNull();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO inbound_configs (worker_id, name, protocol, listen_ip, port, "
                + "inbound_route_bindings, created_at, updated_at) VALUES ('missing-worker', 'x', 'TP_PROXY', '0.0.0.0', 1, '[]', 'x', 'x')"))
                .hasStackTraceContaining("FOREIGN KEY");

        Route fallback = routes.saveAndFlush(route("fallback"));
        InboundConfig inbound = inbound("edge-1", 1081);
        inbound.setFallbackRouteId(fallback.getId());
        inbound.setInboundRouteBindings(List.of(binding(List.of(1L), List.of(fallback.getId()))));
        inbounds.saveAndFlush(inbound);
        InboundConfig reloaded = inbounds.findById(inbound.getId()).orElseThrow();
        assertThat(reloaded.getInboundRouteBindings().get(0).getRouteIds()).containsExactly(fallback.getId());
        assertThat(reloaded.getTlsEnabled()).isFalse();
        assertThat(inbounds.findByConditions(ProtocolType.TP_PROXY, null, null, 1, "edge-1", PageRequest.of(0, 5))
                .getContent()).hasSize(1);

        // ON DELETE RESTRICT: neither the worker nor the referenced route can be removed.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM proxy_worker WHERE worker_id = 'edge-1'"))
                .hasStackTraceContaining("SQLITE_CONSTRAINT");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM routes WHERE id = ?", fallback.getId()))
                .hasStackTraceContaining("SQLITE_CONSTRAINT");

        // Same listen address on a different worker is allowed; on the same worker it is not.
        workers.saveAndFlush(worker("edge-2"));
        inbounds.saveAndFlush(inbound("edge-2", 1081));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO inbound_configs (worker_id, name, protocol, listen_ip, port, "
                + "inbound_route_bindings, created_at, updated_at) VALUES ('EDGE-1', 'x', 'TP_PROXY', '0.0.0.0', 1081, '[]', 'x', 'x')"))
                .hasStackTraceContaining("UNIQUE");
    }

    @Test
    void ruleSetPayloadIsCascadeDeleted() {
        jdbc.update("INSERT INTO rule_set (rule_key, name, category, match_target, source_type, created_at, updated_at) "
                + "VALUES ('k', 'n', 'CUSTOM', 'DOMAIN', 'MANUAL', '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000')");
        Long ruleSetId = jdbc.queryForObject("SELECT id FROM rule_set WHERE rule_key = 'K'", Long.class);
        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(RuleSetItemType.DOMAIN_SUFFIX);
        item.setValue("example.org");
        RuleSetPayloadEntity payload = new RuleSetPayloadEntity();
        payload.setRuleSetId(ruleSetId);
        payload.setItems(List.of(item));
        payloads.saveAndFlush(payload);

        assertThat(payloads.findById(ruleSetId).orElseThrow().getItems().get(0).getValue()).isEqualTo("example.org");
        jdbc.update("DELETE FROM rule_set WHERE id = ?", ruleSetId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_payload", Integer.class)).isZero();
    }

    @Test
    void workerStatusAndTasksKeepLongTextTimestampsAndBooleans() {
        workers.saveAndFlush(worker("w1"));
        WorkerStatus status = new WorkerStatus();
        status.setWorkerId("w1");
        status.setLastSeenAt(LocalDateTime.of(2026, 10, 30, 8, 27, 38, 123_456_000));
        status.setApplySuccess(true);
        status.setCapabilitiesJson("[\"TP_PROXY\"]");
        status.setUptimeSeconds(9_007_199_254_740_993L);
        workerStatuses.saveAndFlush(status);
        WorkerStatus loaded = workerStatuses.findById("w1").orElseThrow();
        assertThat(loaded.getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 10, 30, 8, 27, 38, 123_456_000));
        assertThat(loaded.getApplySuccess()).isTrue();
        assertThat(loaded.getUptimeSeconds()).isEqualTo(9_007_199_254_740_993L);
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM worker_status", String.class))
                .isEqualTo("2026-10-30 08:27:38.123456");

        WorkerTask task = new WorkerTask();
        task.setWorkerId("w1");
        task.setTaskType("WOL");
        task.setPayloadJson("{\"mac\":\"aa:bb\"}");
        workerTasks.saveAndFlush(task);
        assertThat(workerTasks.findByWorkerIdAndConsumedAtIsNullOrderByCreatedAtAsc("w1", PageRequest.of(0, 10)))
                .hasSize(1);
        task.setConsumedAt(LocalDateTime.now());
        workerTasks.saveAndFlush(task);
        assertThat(workerTasks.findByWorkerIdAndConsumedAtIsNullOrderByCreatedAtAsc("w1", PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void wolConfigUpdatedAtIsMaintainedByApplication() throws Exception {
        WolConfig config = new WolConfig();
        config.setName("nas");
        config.setIpAddress("192.168.1.10");
        config.setMacAddress("AA:BB:CC:DD:EE:FF");
        workers.saveAndFlush(worker("default"));
        wolConfigs.saveAndFlush(config);
        LocalDateTime created = config.getUpdatedAt();
        Thread.sleep(5);
        config.setNotes("changed");
        wolConfigs.saveAndFlush(config);
        assertThat(config.getUpdatedAt()).isAfter(created);
        assertThat(wolConfigs.findAll().get(0).getMacAddress()).isEqualToIgnoringCase("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void diskSamplesUseUtcInstantsInQueries() {
        Instant t1 = Instant.parse("2026-10-30T01:00:00.123456Z");
        Instant t2 = Instant.parse("2026-10-30T02:00:00Z");
        diskSamples.save(sample("host-a", "sda", t1));
        diskSamples.save(sample("host-a", "sda", t2));
        diskSamples.saveAndFlush(sample("host-a", "sdb", t1));

        assertThat(diskSamples.findLatestSamplesByHostId("HOST-A")).extracting(DiskMonitorSampleEntity::getDevice)
                .containsExactly("sda", "sdb");
        assertThat(diskSamples.findTopByHostIdAndDeviceOrderBySampledAtDesc("host-a", "sda").orElseThrow().getSampledAt())
                .isEqualTo(t2);
        assertThat(diskSamples.findInspectionSamples("host-a", "sda", t1, t2)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT MIN(sampled_at) FROM disk_monitor_sample", String.class))
                .isEqualTo("2026-10-30 01:00:00.123456");

        diskSamples.deleteBySampledAtBefore(t2);
        diskSamples.flush();
        assertThat(diskSamples.count()).isEqualTo(1);
    }

    @Test
    void deletingMailGatewayNullsTargetReference() {
        MailGateway gateway = new MailGateway();
        gateway.setName("g");
        gateway.setHost("smtp.example");
        gateway.setPort(25);
        gateway.setUsername("u");
        gateway.setPassword("p");
        mailGateways.saveAndFlush(gateway);
        MailTarget target = new MailTarget();
        target.setBizKey("Report");
        target.setToList("a@example.com");
        target.setGatewayId(gateway.getId());
        mailTargets.saveAndFlush(target);

        jdbc.update("DELETE FROM mail_gateway WHERE id = ?", gateway.getId());
        assertThat(jdbc.queryForObject("SELECT gateway_id FROM mail_target WHERE biz_key = 'report'", Long.class)).isNull();
    }

    /** Config writes racing with a long-running writer must wait on busy_timeout instead of failing with SQLITE_BUSY. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWritersDoNotFailWithBusy() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int threads = 6;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                Callable<Integer> job = () -> {
                    for (int i = 0; i < perThread; i++) {
                        int n = i;
                        tx.executeWithoutResult(status -> {
                            // read first, then write: the shape that fails under deferred transactions in WAL mode
                            users.findByUsername("u-" + id + "-" + n);
                            User u = new User();
                            u.setUsername("u-" + id + "-" + n);
                            users.saveAndFlush(u);
                        });
                    }
                    return perThread;
                };
                futures.add(pool.submit(job));
            }
            int written = 0;
            for (Future<Integer> f : futures) {
                written += f.get(60, TimeUnit.SECONDS);
            }
            assertThat(written).isEqualTo(threads * perThread);
            assertThat(users.count()).isEqualTo(threads * perThread);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM users");
        }
    }

    private static Route route(String name) {
        Route route = new Route();
        route.setName(name);
        route.setRules(List.of());
        route.setPolicy(RoutePolicy.DIRECT);
        return route;
    }

    private static ProxyWorker worker(String id) {
        ProxyWorker worker = new ProxyWorker();
        worker.setWorkerId(id);
        worker.setDisplayName(id);
        return worker;
    }

    private static InboundConfig inbound(String workerId, int port) {
        InboundConfig inbound = new InboundConfig();
        inbound.setWorkerId(workerId);
        inbound.setName("in-" + port);
        inbound.setProtocol(ProtocolType.TP_PROXY);
        inbound.setListenIp("0.0.0.0");
        inbound.setPort(port);
        inbound.setInboundRouteBindings(List.of());
        return inbound;
    }

    private static InboundRouteBinding binding(List<Long> userIds, List<Long> routeIds) {
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setUserIds(userIds);
        binding.setRouteIds(routeIds);
        return binding;
    }

    private static DiskMonitorSampleEntity sample(String host, String device, Instant at) {
        DiskMonitorSampleEntity sample = new DiskMonitorSampleEntity();
        sample.setHostId(host);
        sample.setDevice(device);
        sample.setSampledAt(at);
        sample.setDetailJson("{}");
        sample.setRawSmartOutput("smartctl output\nline 2");
        return sample;
    }
}
