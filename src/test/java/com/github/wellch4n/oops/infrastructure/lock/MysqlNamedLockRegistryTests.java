package com.github.wellch4n.oops.infrastructure.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs against the configured MySQL like the rest of the Spring-backed tests: user-level locks are a server-side
 * behaviour, so two registries — two sessions, as two OOPS servers would be — are the only honest way to see that
 * a lock is exclusive and that closing a session hands its locks back.
 */
@SpringBootTest
class MysqlNamedLockRegistryTests {

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private MysqlNamedLockRegistry firstServer;
    private MysqlNamedLockRegistry secondServer;
    private String lockName;

    @BeforeEach
    void setUp() {
        firstServer = new MysqlNamedLockRegistry(dataSourceProperties);
        secondServer = new MysqlNamedLockRegistry(dataSourceProperties);
        lockName = "oops:test:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        firstServer.close();
        secondServer.close();
    }

    @Test
    void lockBelongsToOneServerUntilReleased() {
        assertTrue(firstServer.tryAcquire(lockName));
        assertTrue(firstServer.tryAcquire(lockName), "re-asking for a held lock keeps answering yes");
        assertFalse(secondServer.tryAcquire(lockName), "another server does not get a held lock");
        assertEquals(Set.of(lockName), firstServer.heldLocks());
        assertEquals(Set.of(), secondServer.heldLocks());

        firstServer.release(lockName);

        assertEquals(Set.of(), firstServer.heldLocks());
        assertTrue(secondServer.tryAcquire(lockName), "a released lock is free for the next server");
        assertFalse(firstServer.tryAcquire(lockName));
    }

    /** What happens when a server dies: its session goes, and with it every lock it held. */
    @Test
    void closingTheSessionFreesItsLocks() {
        assertTrue(firstServer.tryAcquire(lockName));
        assertFalse(secondServer.tryAcquire(lockName));

        firstServer.close();

        assertTrue(secondServer.tryAcquire(lockName));
        assertEquals(Set.of(), firstServer.heldLocks());
    }

    @Test
    void releasingALockNeverHeldIsANoOp() {
        firstServer.release(lockName);

        assertTrue(secondServer.tryAcquire(lockName));
    }

    @Test
    void rejectsNamesMysqlWouldReject() {
        assertThrows(IllegalArgumentException.class, () -> firstServer.tryAcquire(" "));
        assertThrows(IllegalArgumentException.class, () -> firstServer.tryAcquire("x".repeat(65)));
    }
}
