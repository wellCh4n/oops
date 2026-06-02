package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.port.repository.PageResult;
import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTests {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = org.mockito.Mockito.mock(UserRepository.class);
        passwordEncoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        userService = new UserService(userRepository, passwordEncoder);
    }

    private User userWithId(String id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("encoded");
        user.setRole(UserRole.USER);
        return user;
    }

    // --- findByUsernameOrEmail ---

    @Test
    void findByUsernameOrEmailReturnsUserWhenUsernameMatches() {
        User user = userWithId("u1", "alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        Optional<User> result = userService.findByUsernameOrEmail("alice");

        assertTrue(result.isPresent());
        assertEquals("alice", result.get().getUsername());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void findByUsernameOrEmailFallsBackToEmailLookup() {
        User user = userWithId("u1", "alice");
        when(userRepository.findByUsername("alice@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        Optional<User> result = userService.findByUsernameOrEmail("alice@example.com");

        assertTrue(result.isPresent());
    }

    @Test
    void findByUsernameOrEmailReturnsEmptyWhenNeitherMatches() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost")).thenReturn(Optional.empty());

        Optional<User> result = userService.findByUsernameOrEmail("ghost");

        assertFalse(result.isPresent());
    }

    // --- getUsernameMapByIds ---

    @Test
    void getUsernameMapByIdsReturnsEmptyMapForNullInput() {
        Map<String, String> result = userService.getUsernameMapByIds(null);
        assertTrue(result.isEmpty());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void getUsernameMapByIdsReturnsEmptyMapForEmptyInput() {
        Map<String, String> result = userService.getUsernameMapByIds(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void getUsernameMapByIdsMapsIdToUsername() {
        User alice = userWithId("u1", "alice");
        User bob = userWithId("u2", "bob");
        when(userRepository.findAllById(List.of("u1", "u2"))).thenReturn(List.of(alice, bob));

        Map<String, String> result = userService.getUsernameMapByIds(List.of("u1", "u2"));

        assertEquals("alice", result.get("u1"));
        assertEquals("bob", result.get("u2"));
    }

    // --- createUser ---

    @Test
    void createUserEncodesPasswordAndSaves() {
        when(passwordEncoder.encode("secret")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = userService.createUser("alice", "alice@example.com", "secret", UserRole.USER);

        assertEquals("alice", created.getUsername());
        assertEquals("alice@example.com", created.getEmail());
        assertEquals("hashed", created.getPassword());
        assertEquals(UserRole.USER, created.getRole());
    }

    @Test
    void createUserSkipsPasswordEncodingWhenPasswordIsBlank() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = userService.createUser("alice", null, "  ", UserRole.USER);

        assertNull(created.getPassword());
        verify(passwordEncoder, never()).encode(anyString());
    }

    // --- checkPassword ---

    @Test
    void checkPasswordReturnsTrueWhenPasswordMatches() {
        User user = userWithId("u1", "alice");
        when(passwordEncoder.matches("raw", "encoded")).thenReturn(true);

        assertTrue(userService.checkPassword(user, "raw"));
    }

    @Test
    void checkPasswordReturnsFalseWhenPasswordDoesNotMatch() {
        User user = userWithId("u1", "alice");
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertFalse(userService.checkPassword(user, "wrong"));
    }

    // --- updateMyProfile ---

    @Test
    void updateMyProfileSetsEmail() {
        User user = userWithId("u1", "alice");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.updateMyProfile("u1", "new@example.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("new@example.com", captor.getValue().getEmail());
    }

    @Test
    void updateMyProfileClearsEmailWhenBlank() {
        User user = userWithId("u1", "alice");
        user.setEmail("old@example.com");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.updateMyProfile("u1", "  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertNull(captor.getValue().getEmail());
    }

    @Test
    void updateMyProfileThrowsWhenUserNotFound() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> userService.updateMyProfile("missing", "x@x.com"));
    }

    // --- changeMyPassword ---

    @Test
    void changeMyPasswordUpdatesPasswordSuccessfully() {
        User user = userWithId("u1", "alice");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPass", "encoded")).thenReturn(true);
        when(passwordEncoder.encode("newPass")).thenReturn("newHashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.changeMyPassword("u1", "oldPass", "newPass");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("newHashed", captor.getValue().getPassword());
    }

    @Test
    void changeMyPasswordThrowsWhenNewPasswordIsBlank() {
        assertThrows(BizException.class, () -> userService.changeMyPassword("u1", "old", "  "));
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    void changeMyPasswordThrowsWhenUserNotFound() {
        when(userRepository.findById("u1")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> userService.changeMyPassword("u1", "old", "new"));
    }

    @Test
    void changeMyPasswordThrowsWhenOldPasswordIncorrect() {
        User user = userWithId("u1", "alice");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(BizException.class, () -> userService.changeMyPassword("u1", "wrong", "newPass"));
        verify(userRepository, never()).save(any());
    }

    // --- listUsers (paginated) ---

    @Test
    void listUsersPagedReturnsMappedPage() {
        User user = userWithId("u1", "alice");
        PageResult<User> pageResult = new PageResult<>(1L, List.of(user), 10, 1);
        when(userRepository.findPage("alice", 1, 10)).thenReturn(pageResult);

        Page<User> page = userService.listUsers("alice", 1, 10);

        assertEquals(1L, page.total());
        assertEquals(1, page.data().size());
        assertEquals(10, page.size());
        assertEquals(1, page.totalPages());
    }

    // --- resetMyAccessToken ---

    @Test
    void resetMyAccessTokenGeneratesTokenWithPrefix() {
        User user = userWithId("u1", "alice");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String token = userService.resetMyAccessToken("u1");

        assertNotNull(token);
        assertTrue(token.startsWith("sk-oops-"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(token, captor.getValue().getAccessToken());
    }

    @Test
    void resetMyAccessTokenThrowsWhenUserNotFound() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> userService.resetMyAccessToken("missing"));
    }

    // --- hasAdmin ---

    @Test
    void hasAdminDelegatesToRepository() {
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);
        assertTrue(userService.hasAdmin());
    }
}
