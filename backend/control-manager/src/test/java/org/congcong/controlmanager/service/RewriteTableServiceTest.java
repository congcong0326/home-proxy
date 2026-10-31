package org.congcong.controlmanager.service;

import org.congcong.controlmanager.config.DataSourceConfig;
import org.congcong.controlmanager.dto.rewrite.RewriteEntryDTO;
import org.congcong.controlmanager.dto.rewrite.RewriteTableImportRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableView;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.common.enums.RoutePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DataSourceConfig.class, RewriteTableService.class})
@DirtiesContext
class RewriteTableServiceTest {
    @Autowired RewriteTableService service;
    @Autowired RouteRepository routes;

    @Test
    void keepsEntryIdsStableAcrossReorderAndEdits() {
        RewriteTableView created = service.createTable(request("NAS",
                entry("my-emby.com", "192.168.10.134", 8096),
                entry("*.My-Emby.com.", "192.168.10.134", 8096),
                entry("my-photos.com", "192.168.10.85", 18080)));
        Map<String, Long> ids = idsByPattern(created);
        assertThat(ids).containsKeys("my-emby.com", "*.my-emby.com", "my-photos.com");

        RewriteTableView updated = service.updateTable(created.getId(), request("NAS",
                entry("my-photos.com", "192.168.10.85", 18081),
                entry("my-emby.com", "192.168.10.134", 8096),
                entry("my-pve.com", "192.168.10.100", 8006)));

        assertThat(updated.getEntries()).extracting(RewriteEntryDTO::getDomainPattern)
                .containsExactly("my-photos.com", "my-emby.com", "my-pve.com");
        Map<String, Long> updatedIds = idsByPattern(updated);
        assertThat(updatedIds.get("my-photos.com")).isEqualTo(ids.get("my-photos.com"));
        assertThat(updatedIds.get("my-emby.com")).isEqualTo(ids.get("my-emby.com"));
        assertThat(updatedIds).doesNotContainKey("*.my-emby.com");
        assertThat(updated.getEntries().get(0).getTargetPort()).isEqualTo(18081);
    }

    @Test
    void importAppendsAndUpdatesByPatternAndExportRoundTrips() {
        RewriteTableView table = service.createTable(request("Dev", entry("my-dev-server.com", "192.168.10.85", 3222)));
        Long devId = table.getEntries().get(0).getId();
        RewriteTableImportRequest importRequest = new RewriteTableImportRequest();
        importRequest.setText("""
                # comment
                my-dev-server.com 192.168.10.85:3333 dev box

                my-nas.com 192.168.10.85:22
                """);

        RewriteTableView imported = service.importEntries(table.getId(), importRequest);

        assertThat(imported.getEntries()).extracting(RewriteEntryDTO::getDomainPattern)
                .containsExactly("my-dev-server.com", "my-nas.com");
        assertThat(imported.getEntries().get(0).getId()).isEqualTo(devId);
        assertThat(imported.getEntries().get(0).getTargetPort()).isEqualTo(3333);
        assertThat(service.exportEntries(table.getId()))
                .isEqualTo("my-dev-server.com 192.168.10.85:3333 dev box\nmy-nas.com 192.168.10.85:22\n");
    }

    @Test
    void rejectsInvalidPatternsDuplicatesAndPorts() {
        assertThatThrownBy(() -> service.createTable(request("A", entry("*", "1.1.1.1", 80))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("域名模式无效");
        assertThatThrownBy(() -> service.createTable(request("B", entry("a.com", "1.1.1.1", 80), entry("A.com", "1.1.1.1", 81))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("域名模式重复");
        assertThatThrownBy(() -> service.createTable(request("C", entry("a.com", "1.1.1.1", 0))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("1-65535");
    }

    @Test
    void refusesDeletingReferencedTable() {
        RewriteTableView table = service.createTable(request("Media", entry("my-emby.com", "192.168.10.134", 8096)));
        Route route = new Route();
        route.setName("媒体改写");
        route.setRules(List.of());
        route.setPolicy(RoutePolicy.DESTINATION_OVERRIDE);
        route.setAction(RouteAction.REWRITE_DESTINATION);
        route.setRewriteTableId(table.getId());
        routes.saveAndFlush(route);

        assertThatThrownBy(() -> service.deleteTable(table.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("“媒体改写”");
    }

    private static Map<String, Long> idsByPattern(RewriteTableView view) {
        return view.getEntries().stream().collect(Collectors.toMap(RewriteEntryDTO::getDomainPattern, RewriteEntryDTO::getId));
    }

    private static RewriteTableRequest request(String name, RewriteEntryDTO... entries) {
        RewriteTableRequest request = new RewriteTableRequest();
        request.setName(name);
        request.setEntries(List.of(entries));
        return request;
    }

    private static RewriteEntryDTO entry(String pattern, String host, int port) {
        RewriteEntryDTO entry = new RewriteEntryDTO();
        entry.setDomainPattern(pattern);
        entry.setTargetHost(host);
        entry.setTargetPort(port);
        return entry;
    }
}
