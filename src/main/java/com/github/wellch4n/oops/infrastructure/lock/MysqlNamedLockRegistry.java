package com.github.wellch4n.oops.infrastructure.lock;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link NamedLockRegistry} over MySQL's user-level locks ({@code GET_LOCK} / {@code RELEASE_LOCK}).
 *
 * <p>A user-level lock lives exactly as long as the session that took it, which is the whole point: when this
 * process dies, the TCP connection drops and MySQL frees every lock it held, with no expiry to tune and no heartbeat
 * to send. That also dictates the one unusual thing here — the registry keeps a single dedicated connection open
 * for its entire life, outside the Hikari pool. A pooled connection would either be returned (releasing the locks)
 * or held forever (shrinking the pool by one and tripping leak detection), and the pool's own retirement of
 * connections would silently drop the locks.
 *
 * <p>Losing that connection is the failure mode to understand. Whatever the cause, the server-side locks are gone
 * with it, so the registry forgets everything it held; the next {@link #tryAcquire} reconnects and starts over, and
 * until it succeeds every call answers {@code false}. Fail-closed on purpose: with the lock unreachable no server
 * drives anything, which is recoverable, whereas two servers driving the same thing is what the lock exists to
 * prevent. A 30-second validation keeps the idle session clear of {@code wait_timeout} and notices a cut connection
 * before the next tick does.
 *
 * <p>All access is serialised on the registry: a JDBC connection is not safe for concurrent use, and the scheduled
 * jobs that call in run on a pool of threads.
 */
@Slf4j
@Component
public class MysqlNamedLockRegistry implements NamedLockRegistry {

    /** MySQL rejects user-level lock names longer than this. */
    private static final int MAX_NAME_LENGTH = 64;
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final DataSourceProperties dataSourceProperties;
    private final Set<String> held = new LinkedHashSet<>();
    private Connection connection;
    private boolean connectionFailureLogged;

    public MysqlNamedLockRegistry(DataSourceProperties dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public synchronized boolean tryAcquire(String name) {
        requireValidName(name);
        if (held.contains(name)) {
            return true;
        }
        Connection lockConnection = connection();
        if (lockConnection == null) {
            return false;
        }
        try (PreparedStatement statement = lockConnection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                // 1 = acquired, 0 = held by another session, NULL = error; only 1 means it is ours.
                boolean acquired = resultSet.next() && resultSet.getInt(1) == 1;
                if (acquired) {
                    held.add(name);
                }
                return acquired;
            }
        } catch (SQLException exception) {
            dropConnection("acquiring lock " + name, exception.getMessage());
            return false;
        }
    }

    @Override
    public synchronized void release(String name) {
        if (!held.remove(name) || connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            statement.executeQuery().close();
        } catch (SQLException exception) {
            dropConnection("releasing lock " + name, exception.getMessage());
        }
    }

    @Override
    public synchronized Set<String> heldLocks() {
        return Set.copyOf(held);
    }

    /**
     * Pings the lock session so MySQL's {@code wait_timeout} never reaps it while idle, and forgets the held locks
     * as soon as the connection turns out to be gone rather than at the next acquire.
     */
    @Scheduled(fixedDelay = 30_000)
    public synchronized void keepAlive() {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                dropConnection("validating the lock connection", "connection is no longer valid");
            }
        } catch (SQLException exception) {
            dropConnection("validating the lock connection", exception.getMessage());
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        // Closing the session releases every lock it holds; another server picks the work up on its next tick.
        try {
            connection.close();
        } catch (SQLException exception) {
            log.debug("Closing the lock connection failed: {}", exception.getMessage());
        }
        connection = null;
        held.clear();
    }

    private Connection connection() {
        if (connection != null) {
            return connection;
        }
        try {
            Connection opened = DriverManager.getConnection(
                    dataSourceProperties.determineUrl(),
                    dataSourceProperties.determineUsername(),
                    dataSourceProperties.determinePassword());
            opened.setAutoCommit(true);
            connection = opened;
            if (connectionFailureLogged) {
                log.info("Lock connection re-established; this server takes part in scheduled work again");
                connectionFailureLogged = false;
            }
            return opened;
        } catch (SQLException exception) {
            if (!connectionFailureLogged) {
                log.warn("Cannot open the lock connection; scheduled work is paused on this server until it is back: {}",
                        exception.getMessage());
                connectionFailureLogged = true;
            }
            return null;
        }
    }

    private void dropConnection(String activity, String reason) {
        log.warn("Lock connection lost while {}; the {} lock(s) this server held are gone with it: {}",
                activity, held.size(), reason);
        held.clear();
        Connection lost = connection;
        connection = null;
        if (lost != null) {
            try {
                lost.close();
            } catch (SQLException exception) {
                log.debug("Closing the lost lock connection failed: {}", exception.getMessage());
            }
        }
    }

    private static void requireValidName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Lock name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Lock name exceeds " + MAX_NAME_LENGTH + " characters: " + name);
        }
    }
}
