package ru.randgor.wearenginebridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Real distinct JVM ClassLoaders and concurrent callbacks; not Android/ART tests. */
public final class ClassHookRegistryTest {
    private static int checks;
    private static void check(String name, boolean result) {
        checks++;
        if (!result) throw new AssertionError(name);
    }
    private static final class CopyLoader extends ClassLoader {
        Class<?> copy(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = AccessPolicy.class.getResourceAsStream("AccessPolicy.class")) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
        }
        Class<?> base = new CopyLoader().copy(bytes.toByteArray());
        Class<?> plugin = new CopyLoader().copy(bytes.toByteArray());
        check("same name", base.getName().equals(plugin.getName()));
        check("different class and loader", base != plugin && base.getClassLoader() != plugin.getClassLoader());
        ClassHookRegistry registry = new ClassHookRegistry();
        check("early class is queued", registry.observe(base) == null);
        check("duplicate early event", registry.observe(base) == null && registry.knownCount() == 1);
        List<Class<?>> pending = registry.activate();
        check("activation returns queued class", pending.size() == 1 && pending.get(0) == base);
        ClassHookRegistry.Ticket first = registry.observe(base);
        check("claim once after activation", first != null && registry.observe(base) == null);
        registry.installed(first);
        registry.installed(first);
        check("success counted once", registry.installedCount() == 1);
        ClassHookRegistry.Ticket second = registry.observe(plugin);
        check("late plugin gets separate hook", second != null && second.id != first.id);
        registry.installed(second);
        check("two loaded copies installed", registry.knownCount() == 2 && registry.installedCount() == 2);
        check("delegated duplicate ignored", registry.observe(plugin) == null);

        Class<?> concurrent = new CopyLoader().copy(bytes.toByteArray());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        Thread[] workers = new Thread[16];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (registry.observe(concurrent) != null) claims.incrementAndGet();
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        check("concurrent events claim exactly once", claims.get() == 1);
        check("failed/uncompleted hook not counted", registry.knownCount() == 3 && registry.installedCount() == 2);
        check("no recursive retry on same class", registry.observe(concurrent) == null);
        Class<?> next = new CopyLoader().copy(bytes.toByteArray());
        check("one failure does not block next class", registry.observe(next) != null);
        System.out.println("PASS: " + checks + " class identity/lifecycle/concurrency checks");
    }
}
