package org.congcong.controlmanager.security;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.congcong.controlmanager.controller.WorkerController;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.service.WorkerControlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkerController.class)
@Import(SecurityConfig.class)
class WorkerStatusSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkerControlService workerControlService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @MockitoBean
    private AdminTokenBlacklistRepository blacklistRepository;

    @MockitoBean
    private WorkerTokenAuthenticationService workerTokenAuthenticationService;

    @Test
    void superAdminCanReadWorkerStatus() throws Exception {
        DecodedJWT jwt = mock(DecodedJWT.class);
        Claim versionClaim = mock(Claim.class);
        Claim rolesClaim = mock(Claim.class);
        AdminUser user = new AdminUser();
        user.setId(1L);
        user.setStatus(1);
        user.setVer(1);
        user.setMustChangePassword(false);

        when(jwtService.verify("super-admin-token")).thenReturn(jwt);
        when(jwt.getId()).thenReturn("token-id");
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("ver")).thenReturn(versionClaim);
        when(versionClaim.asInt()).thenReturn(1);
        when(jwt.getClaim("roles")).thenReturn(rolesClaim);
        when(rolesClaim.asArray(String.class)).thenReturn(new String[]{"SUPER_ADMIN"});
        when(blacklistRepository.findById("token-id")).thenReturn(Optional.empty());
        when(adminUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(workerControlService.getLatestStatus()).thenReturn(null);

        mockMvc.perform(get("/api/worker/status")
                        .header("Authorization", "Bearer super-admin-token"))
                .andExpect(status().isNotFound());
    }
}
