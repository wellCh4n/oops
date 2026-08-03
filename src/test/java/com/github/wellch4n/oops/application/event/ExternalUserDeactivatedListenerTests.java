package com.github.wellch4n.oops.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.repository.ExternalAccountRepository;
import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.application.service.UserService;
import com.github.wellch4n.oops.domain.identity.ExternalAccount;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import com.github.wellch4n.oops.domain.shared.UserRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A resignation arriving from an external directory must land on exactly one account, or on none — never on the
 * wrong one, and never on the last way into the installation.
 */
class ExternalUserDeactivatedListenerTests {

    private ExternalAccountRepository externalAccountRepository;
    private UserRepository userRepository;
    private ExternalUserDeactivatedListener listener;

    @BeforeEach
    void setUp() {
        externalAccountRepository = mock(ExternalAccountRepository.class);
        userRepository = mock(UserRepository.class);
        listener = new ExternalUserDeactivatedListener(
                externalAccountRepository,
                new UserService(userRepository, mock(PasswordEncoder.class)));
        when(userRepository.countEnabledByRole(UserRole.ADMIN)).thenReturn(3L);
    }

    @Test
    void disablesTheUserBehindTheLinkedAccount() {
        linkAccount("e33ggbyz", "user-1");
        givenUser("user-1", UserRole.USER, true);

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        assertThat(savedUser().getEnabled()).isFalse();
    }

    /** Accounts linked before the provider id was recorded are still the same person. */
    @Test
    void fallsBackToTheEmailWhenNoAccountIsLinked() {
        when(externalAccountRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        User user = givenUser("user-2", UserRole.USER, true);
        when(userRepository.findByEmail("zhangsan@example.com")).thenReturn(Optional.of(user));

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        assertThat(savedUser().getEnabled()).isFalse();
    }

    /** Most of a directory never signs in to OOPS; those resignations must be a no-op, not a failure. */
    @Test
    void ignoresSomeoneWhoNeverUsedOops() {
        when(externalAccountRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void keepsTheLastEnabledAdmin() {
        linkAccount("e33ggbyz", "admin-1");
        givenUser("admin-1", UserRole.ADMIN, true);
        when(userRepository.countEnabledByRole(UserRole.ADMIN)).thenReturn(1L);

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        verify(userRepository, never()).save(any());
    }

    /** One of several admins leaving is an ordinary resignation. */
    @Test
    void disablesAnAdminWhileOthersRemain() {
        linkAccount("e33ggbyz", "admin-1");
        givenUser("admin-1", UserRole.ADMIN, true);

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        assertThat(savedUser().getEnabled()).isFalse();
    }

    @Test
    void doesNotRewriteAnAlreadyDisabledUser() {
        linkAccount("e33ggbyz", "user-1");
        givenUser("user-1", UserRole.USER, false);

        listener.onExternalUserDeactivated(event("e33ggbyz", "zhangsan@example.com"));

        verify(userRepository, never()).save(any());
    }

    private ExternalUserDeactivatedEvent event(String providerUserId, String email) {
        return new ExternalUserDeactivatedEvent(
                ExternalAccountProvider.FEISHU, providerUserId, email, "张三", LocalDateTime.now());
    }

    private void linkAccount(String providerUserId, String userId) {
        ExternalAccount account = new ExternalAccount();
        account.setProvider(ExternalAccountProvider.FEISHU);
        account.setProviderUserId(providerUserId);
        account.setUserId(userId);
        when(externalAccountRepository.findByProviderAndProviderUserId(ExternalAccountProvider.FEISHU, providerUserId))
                .thenReturn(Optional.of(account));
    }

    private User givenUser(String id, UserRole role, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setUsername("zhangsan");
        user.setEmail("zhangsan@example.com");
        user.setRole(role);
        user.setEnabled(enabled);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }
}
