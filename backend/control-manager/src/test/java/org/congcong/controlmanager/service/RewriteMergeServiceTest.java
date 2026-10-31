package org.congcong.controlmanager.service;

import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.config.DataSourceConfig;
import org.congcong.controlmanager.dto.rewrite.RewriteEntryDTO;
import org.congcong.controlmanager.dto.rewrite.RewriteMergePreview;
import org.congcong.controlmanager.dto.rewrite.RewriteMergeRequest;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DataSourceConfig.class, RewriteMergeService.class, RewriteTableService.class})
@DirtiesContext
class RewriteMergeServiceTest {
    @Autowired RewriteMergeService service;
    @Autowired RouteRepository routes;
    @Autowired InboundConfigRepository inbounds;
    @Autowired ProxyWorkerRepository workers;
    @Autowired OutboundRepository outbounds;
    @Autowired RewriteEntryRepository entries;

    private Route emby;
    private Route photos;
    private Route ssh;
    private Route gpt;
    private InboundConfig inbound;

    @BeforeEach
    void setUp() {
        ProxyWorker worker = new ProxyWorker();
        worker.setWorkerId("merge-worker");
        worker.setDisplayName("merge-worker");
        workers.saveAndFlush(worker);
        emby = rewrite("emby", "192.168.10.134", 8096, "my-emby.com", "*.my-emby.com");
        photos = rewrite("immich", "192.168.10.85", 18080, "my-photos.com");
        ssh = rewrite("nas-ssh", "192.168.10.85", 22, "my-nas.com");
        gpt = new Route();
        gpt.setName("gpt");
        gpt.setRules(List.of(domain("s2a.mjbbs.com")));
        gpt.setAction(RouteAction.FORWARD);
        gpt.setOutboundId(outbounds.findByBuiltinKey(OutboundService.BUILTIN_DIRECT).orElseThrow().getId());
        gpt.setPolicy(RoutePolicy.DIRECT);
        routes.saveAndFlush(gpt);
        inbound = new InboundConfig();
        inbound.setWorkerId("merge-worker");
        inbound.setName("透明代理");
        inbound.setProtocol(ProtocolType.TP_PROXY);
        inbound.setListenIp("0.0.0.0");
        inbound.setPort(10808);
        inbound.setInboundRouteBindings(new ArrayList<>(List.of(
                binding(List.of(7L), List.of(emby.getId(), gpt.getId(), photos.getId(), ssh.getId())),
                binding(List.of(18L), List.of(emby.getId())))));
        inbounds.saveAndFlush(inbound);
    }

    @Test
    void previewsReorderingAndGainedServicesWithoutWriting() {
        RewriteMergePreview preview = service.merge(request(true, emby, photos));

        assertThat(preview.getEntries()).extracting(RewriteEntryDTO::getDomainPattern)
                .containsExactly("my-emby.com", "*.my-emby.com", "my-photos.com");
        assertThat(preview.getBindings()).hasSize(2);
        RewriteMergePreview.BindingChange first = preview.getBindings().get(0);
        assertThat(first.getAfter()).containsExactly(null, gpt.getId(), ssh.getId());
        // gpt matches s2a.mjbbs.com only, which cannot overlap the merged domains.
        assertThat(first.getReorderedRoutes()).isEmpty();
        assertThat(first.getGainedRoutes()).isEmpty();
        RewriteMergePreview.BindingChange second = preview.getBindings().get(1);
        assertThat(second.getGainedRoutes()).containsExactly("immich");
        assertThat(routes.existsByName("NAS 改写")).isFalse();
        assertThat(entries.findAll()).isEmpty();
    }

    @Test
    void mergesIntoTableRewritesBindingsAndDisablesOldRoutes() {
        RewriteMergePreview result = service.merge(request(false, photos, ssh));

        Route merged = routes.findById(result.getRouteId()).orElseThrow();
        assertThat(merged.getRewriteTableId()).isEqualTo(result.getTableId());
        assertThat(merged.getRules()).isEmpty();
        assertThat(entries.findByTableIdOrderBySortOrderAscIdAsc(result.getTableId()))
                .extracting(entry -> entry.getDomainPattern() + "->" + entry.getTargetHost() + ":" + entry.getTargetPort())
                .containsExactly("my-photos.com->192.168.10.85:18080", "my-nas.com->192.168.10.85:22");
        InboundConfig saved = inbounds.findById(inbound.getId()).orElseThrow();
        assertThat(saved.getInboundRouteBindings().get(0).getRouteIds())
                .containsExactly(emby.getId(), gpt.getId(), result.getRouteId());
        assertThat(saved.getInboundRouteBindings().get(1).getRouteIds()).containsExactly(emby.getId());
        assertThat(routes.findById(photos.getId()).orElseThrow().getStatus()).isZero();
        assertThat(routes.findById(ssh.getId()).orElseThrow().getStatus()).isZero();
    }

    @Test
    void rejectsRoutesThatAreNotPlainDomainRewrites() {
        assertThatThrownBy(() -> service.merge(request(true, emby, gpt)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("只能合并固定目标的目标改写路由: gpt");
        inbound.setFallbackRouteId(ssh.getId());
        inbounds.saveAndFlush(inbound);
        assertThatThrownBy(() -> service.merge(request(true, photos, ssh)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("兜底路由");
    }

    @Test
    void reportsReorderingOnlyForRoutesThatMayMatchMergedDomains() {
        Set<String> patterns = Set.of("my-emby.com", "*.my-emby.com");
        assertThat(RewriteMergeService.mayOverlap(List.of(domain("s2a.mjbbs.com")), patterns)).isFalse();
        assertThat(RewriteMergeService.mayOverlap(List.of(), patterns)).isFalse();
        assertThat(RewriteMergeService.mayOverlap(List.of(domain("tv.my-emby.com")), patterns)).isTrue();
        assertThat(RewriteMergeService.mayOverlap(List.of(domain("*")), patterns)).isTrue();
        RouteRule ruleSet = domain("geo-not-cn");
        ruleSet.setConditionType(RouteConditionType.RULE_SET);
        assertThat(RewriteMergeService.mayOverlap(List.of(ruleSet), patterns)).isTrue();
    }

    private Route rewrite(String name, String host, int port, String... domains) {
        Route route = new Route();
        route.setName(name);
        route.setRules(Arrays.stream(domains).map(RewriteMergeServiceTest::domain).toList());
        route.setAction(RouteAction.REWRITE_DESTINATION);
        route.setOutboundId(outbounds.findByBuiltinKey(OutboundService.BUILTIN_DIRECT).orElseThrow().getId());
        route.setRewriteTargetHost(host);
        route.setRewriteTargetPort(port);
        route.setPolicy(RoutePolicy.DESTINATION_OVERRIDE);
        return routes.saveAndFlush(route);
    }

    private static RouteRule domain(String value) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue(value);
        return rule;
    }

    private static InboundRouteBinding binding(List<Long> userIds, List<Long> routeIds) {
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setUserIds(userIds);
        binding.setRouteIds(routeIds);
        return binding;
    }

    private static RewriteMergeRequest request(boolean dryRun, Route... merged) {
        RewriteMergeRequest request = new RewriteMergeRequest();
        request.setRouteIds(Arrays.stream(merged).map(Route::getId).toList());
        request.setTableName("NAS");
        request.setRouteName("NAS 改写");
        request.setDryRun(dryRun);
        return request;
    }
}
