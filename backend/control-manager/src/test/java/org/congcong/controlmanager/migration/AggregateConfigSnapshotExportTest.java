package org.congcong.controlmanager.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.controlmanager.config.DataSourceConfig;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.service.AggregateConfigService;
import org.congcong.controlmanager.service.CanonicalConfigHasher;
import org.congcong.controlmanager.service.FakeIpConfigValidator;
import org.congcong.controlmanager.service.InboundConfigService;
import org.congcong.controlmanager.service.RouteCompiler;
import org.congcong.controlmanager.service.RuleSetService;
import org.congcong.controlmanager.service.UserService;
import org.congcong.controlmanager.service.WorkerConfigDependencyResolver;
import org.congcong.controlmanager.service.ruleset.RuleSetSourceSyncService;
import org.congcong.controlmanager.service.ruleset.RuleSetTextParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports the per-worker aggregate config computed from a copy of a production database.
 *
 * <p>Used as the equivalence baseline for config model migrations: run it on the current code to
 * capture the baseline, then again after adding a migration (Flyway migrates the fresh copy) and
 * compare. Only the beans needed to build the aggregate config are loaded, so nothing connects to the
 * workers recorded in the database.
 *
 * <pre>
 * mvn -f backend/pom.xml -pl control-manager test -DskipTests=false \
 *   -Dtest=AggregateConfigSnapshotExportTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dsnapshot.db=$PWD/.tmp/baseline/home-proxy-xxx.db -Dsnapshot.out=$PWD/.tmp/baseline/before
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({DataSourceConfig.class, AggregateConfigService.class, InboundConfigService.class, RouteCompiler.class,
        UserService.class, RuleSetService.class, RuleSetSourceSyncService.class, RuleSetTextParser.class,
        WorkerConfigDependencyResolver.class, CanonicalConfigHasher.class, FakeIpConfigValidator.class})
@EnabledIfSystemProperty(named = "snapshot.db", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AggregateConfigSnapshotExportTest {
    private static final ObjectMapper OUTPUT = JsonMapper.builder()
            .findAndAddModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @DynamicPropertySource
    static void copiedDatabase(DynamicPropertyRegistry registry) {
        try {
            Path workDir = Files.createTempDirectory("aggregate-snapshot-");
            Path copy = workDir.resolve("home-proxy.db");
            Files.copy(Path.of(System.getProperty("snapshot.db")), copy, StandardCopyOption.REPLACE_EXISTING);
            registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + copy);
            registry.add("observability.sqlite.url", () -> "jdbc:sqlite:" + workDir.resolve("telemetry.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired AggregateConfigService aggregateConfigService;
    @Autowired ProxyWorkerRepository proxyWorkerRepository;

    @Test
    void exportPerWorkerAggregateConfig() throws IOException {
        Path out = Path.of(System.getProperty("snapshot.out", "target/aggregate-snapshot"));
        Files.createDirectories(out);
        int exported = 0;
        for (ProxyWorker worker : proxyWorkerRepository.findAll()) {
            AggregateConfigResponse config = aggregateConfigService.getAggregateConfig(worker.getWorkerId());
            config.setGeneratedAt(null);
            OUTPUT.writeValue(out.resolve("aggregate-" + worker.getWorkerId() + ".json").toFile(), config);
            exported++;
        }
        assertThat(exported).isPositive();
    }
}
