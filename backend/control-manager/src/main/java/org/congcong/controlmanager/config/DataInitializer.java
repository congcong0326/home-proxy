package org.congcong.controlmanager.config;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.OutboundService;
import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.RuleSetService;
import org.congcong.controlmanager.service.UserService;
import org.congcong.controlmanager.service.InboundConfigService;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.service.WorkerControlService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 业务默认数据初始化器。
 */
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserService userService;

    private final RouteService routeService;
    private final OutboundService outboundService;

    private final RuleSetService ruleSetService;
    private final InboundConfigService inboundConfigService;
    private final RouteRepository routeRepository;
    private final UserRepository userRepository;
    private final ProxyWorkerRepository proxyWorkerRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureDefaultWorkerExists();
        userService.ensureDefaultAnonymousUserExists();
        outboundService.ensureBuiltinOutboundsExist();
        routeService.ensureDefaultRouteExists();
        ruleSetService.ensureDefaultRuleSetsExist();
        Long fallbackRouteId = routeRepository.findAll().stream()
                .filter(route -> "兜底直连路由规则".equals(route.getName()))
                .map(org.congcong.controlmanager.entity.Route::getId)
                .findFirst().orElse(null);
        Long anonymousUserId = userRepository.findByUsername("Anonymous user")
                .map(org.congcong.controlmanager.entity.User::getId)
                .orElse(null);
        if (fallbackRouteId != null && anonymousUserId != null) {
            inboundConfigService.backfillLegacyDefaultReferences(fallbackRouteId, anonymousUserId);
        }
    }

    /** The legacy {@code default} worker used to be inserted by a SQL migration; create it idempotently instead. */
    private void ensureDefaultWorkerExists() {
        if (proxyWorkerRepository.existsById(WorkerControlService.DEFAULT_WORKER_ID)) {
            return;
        }
        ProxyWorker worker = new ProxyWorker();
        worker.setWorkerId(WorkerControlService.DEFAULT_WORKER_ID);
        worker.setDisplayName(WorkerControlService.DEFAULT_WORKER_ID);
        proxyWorkerRepository.save(worker);
    }
}
