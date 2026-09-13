package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WorkerConfigDependencyResolver dependencyResolver = mock(WorkerConfigDependencyResolver.class);
    private final UserService userService = new UserService();

    UserServiceTest() {
        ReflectionTestUtils.setField(userService, "userRepository", userRepository);
        ReflectionTestUtils.setField(userService, "workerConfigDependencyResolver", dependencyResolver);
    }

    @Test
    void includesCredentialWhenLoadingUserDetails() {
        User user = new User();
        user.setId(11L);
        user.setUsername("alice");
        user.setCredential("secret-password");
        when(userRepository.findById(11L)).thenReturn(Optional.of(user));

        var result = userService.findByIdWithCredential(11L);

        assertTrue(result.isPresent());
        assertEquals("secret-password", result.get().getCredential());
        assertEquals("alice", result.get().getUsername());
    }

    @Test
    void refusesDeletingUserReferencedByInboundConfig() {
        User user = new User();
        user.setId(11L);
        when(userRepository.findById(11L)).thenReturn(Optional.of(user));
        when(dependencyResolver.isUserReferenced(11L)).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> userService.deleteUser(11L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("用户被入站配置引用，无法删除", error.getReason());
        verify(userRepository, never()).delete(user);
    }
}
