package io.casehub.langchain4j.governance.gate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serialises gate decisions for the same call inside this JVM. */
final class FingerprintLocks {

    private record Entry(ReentrantLock lock, int users) {}

    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

    <T> T withLock(String fingerprint, Supplier<T> action) {
        ReentrantLock lock = locks.compute(fingerprint,
                (k, e) -> e == null ? new Entry(new ReentrantLock(), 1) : new Entry(e.lock(), e.users() + 1)).lock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            locks.computeIfPresent(fingerprint, (k, e) -> e.users() == 1 ? null : new Entry(e.lock(), e.users() - 1));
        }
    }
}
