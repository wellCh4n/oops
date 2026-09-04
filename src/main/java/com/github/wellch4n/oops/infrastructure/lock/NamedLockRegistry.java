package com.github.wellch4n.oops.infrastructure.lock;

import java.util.Set;

/**
 * Cluster-wide named locks that let several OOPS servers share the scheduled work without stepping on each other:
 * a pipeline is driven by whichever server holds its lock, and a once-a-minute scan runs on whichever server holds
 * that scan's lock.
 *
 * <p>A lock belongs to the process that acquired it until that process releases it or dies — there is no lease to
 * renew and no clock to compare. {@link #tryAcquire} never blocks and is idempotent for the holder, so a job can
 * call it on every tick to learn whether it is still the one supposed to act.
 *
 * <p>What it is not: a fence. A holder whose connection the database has cut keeps believing it holds the lock until
 * it next talks to the registry, and another server may already have taken over in the meantime. Anything that
 * writes on the strength of a lock must therefore still guard the write itself; for pipelines that guard is
 * {@code PipelineRepository.updateStatusIfMatch}, and the lock only makes the collisions it resolves rare.
 */
public interface NamedLockRegistry {

    /**
     * Takes the lock if nobody else holds it. Returns {@code true} when this process holds the lock afterwards —
     * including when it already did — and {@code false} when another process holds it or the registry is
     * unreachable. Never waits.
     */
    boolean tryAcquire(String name);

    /** Gives the lock back. A no-op for a lock this process does not hold. */
    void release(String name);

    /** The names this process currently believes it holds. */
    Set<String> heldLocks();
}
