# Lock Waiter Retry FIFO Reproduction Tests

## Background

`waiterRetry` is currently set by the client after any local retry. It does not
strictly mean that the client was notified by the server as the queue head.

On the server side, `acquireLock()` skips the existing waiter check when
`waiterRetry=true`, then calls `tryLock()` before verifying that the requester is
actually the queue head. This allows a non-head waiter to acquire a clear lock and
break FIFO ordering.

## Reproduced Scenarios

### Non-head waiter retry acquires a clear lock

Timeline:

1. Client A acquires the lock.
2. Client B tries to acquire and enters the wait queue.
3. Client C tries to acquire and enters the wait queue behind B.
4. Client A releases the lock.
5. Client C retries with `waiterRetry=true` before B retries.
6. Current server behavior allows C to acquire the clear lock.

Expected behavior:

Client C must not acquire before Client B, because B is the queue head.

Added test:

- `WaitQueueLockITCase#testNonHeadWaiterRetryMustNotAcquireFreeLock`

### Out-of-order retry leaves a stale waiter

When C acquires out of order, the server only removes a waiter if the successful
request matches the queue head. Since C is not the head, C remains in the wait
queue even after acquiring and releasing the lock.

This can leave a stale queue entry that blocks later fresh lock requests while the
lock is clear.

Added test:

- `WaitQueueLockITCase#testOutOfOrderRetryMustNotLeaveStaleWaiter`

## Changed Files

- `test/lock-test/src/test/java/com/alibaba/nacos/test/lock/WaitQueueLockITCase.java`
  - Added deterministic IT repro for non-head `waiterRetry`.
  - Added deterministic IT repro for stale waiter after out-of-order acquire.
  - Uses `LockGrpcClient#lockWithResult()` to avoid relying on timing-heavy
    client notification loops.
- `test/lock-test/pom.xml`
  - Added `nacos-client` dependency.
  - Existing JUC lock integration tests already reference
    `com.alibaba.nacos.client.lock.*`; without this dependency, standalone
    `test/lock-test` compilation cannot resolve those classes.

## Validation

Commands run:

```bash
mvn -pl test/lock-test spotless:check -DskipTests
mvn -pl test/lock-test test-compile -DskipTests
```

Both commands passed.

End-to-end IT was not run because local `127.0.0.1:8848` was not listening.

## Notes

The added tests are expected to fail against the current server behavior and pass
after the server enforces queue-head verification before calling `tryLock()` for
`waiterRetry` requests.
