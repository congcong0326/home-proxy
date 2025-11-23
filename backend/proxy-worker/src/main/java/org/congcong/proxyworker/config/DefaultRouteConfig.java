package org.congcong.proxyworker.config;

import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;

import java.util.Collections;

@Deprecated
public class DefaultRouteConfig extends RouteConfig {


    private DefaultRouteConfig() {

    }

    public static RouteConfig getInstance() {
        return DefaultRouteConfig.Holder.INSTANCE;
    }

    private static class Holder {
        private static final DefaultRouteConfig INSTANCE = new DefaultRouteConfig();

        static {
            INSTANCE.setName("DefaultRouteConfig");
            INSTANCE.setPolicy(RoutePolicy.DIRECT);
            INSTANCE.setId(-1L);
            RouteRule routeRule = new RouteRule();
            routeRule.setConditionType(RouteConditionType.DOMAIN);
            routeRule.setOp(MatchOp.IN);
            routeRule.setValue("*");
            INSTANCE.setRules(Collections.singletonList(routeRule));
        }
    }

}
