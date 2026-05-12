package org.congcong.controlmanager.security;

import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    @Test
    void diskPushEndpointBypassesAdminJwtAuthentication() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                mock(JwtService.class),
                mock(AdminUserRepository.class),
                mock(AdminTokenBlacklistRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/disk/push");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
