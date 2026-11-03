package org.congcong.controlmanager.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.DispatcherType;
import org.congcong.controlmanager.controller.DatabaseBackupController;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.service.SqliteBackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseBackupController.class)
@Import(SecurityConfig.class)
class DatabaseBackupSecurityTest {
    private static final String EXPORT = "/api/system-ops/database-backup/export";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private SqliteBackupService backupService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AdminUserRepository users;
    @MockitoBean
    private AdminTokenBlacklistRepository blacklist;

    @Test
    void authenticatedDownloadCompletesItsAsyncDispatchWithoutCorruptingTheBody() throws Exception {
        var user = new AdminUser();
        user.setId(1L);
        user.setMustChangePassword(false);
        var token = JWT.create().withSubject("1").withJWTId("backup-test").withClaim("ver", 1)
                .withArrayClaim("roles", new String[]{"SUPER_ADMIN"}).sign(Algorithm.HMAC256("test-only"));
        when(jwtService.verify("backup-token")).thenReturn(JWT.decode(token));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(blacklist.findById("backup-test")).thenReturn(Optional.empty());
        byte[] snapshot = new byte[65536];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = (byte) i;
        doAnswer(call -> {
            OutputStream output = call.getArgument(0);
            output.write(snapshot);
            output.flush();
            return null;
        }).when(backupService).exportBackup(any(OutputStream.class));

        var result = mvc.perform(get(EXPORT).header("Authorization", "Bearer backup-token"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        SecurityContextHolder.clearContext();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(content().bytes(snapshot));
    }

    @Test
    void downloadStillRequiresAuthentication() throws Exception {
        mvc.perform(get(EXPORT)).andExpect(status().isUnauthorized());
        verifyNoInteractions(backupService);
    }

    @Test
    void asyncDispatchDoesNotBypassAuthentication() throws Exception {
        mvc.perform(get(EXPORT).with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andExpect(status().isUnauthorized());
        verifyNoInteractions(backupService);
    }
}
