package ru.randgor.wearenginebridge;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Deduplicates actual Class identities, never class names or loader descriptions. */
final class ClassHookRegistry {
    static final class Ticket {
        final Class<?> type;
        final int id;
        private boolean attempted;
        private boolean installed;
        Ticket(Class<?> type, int id) { this.type = type; this.id = id; }
    }
    private final Map<Class<?>, Ticket> entries = new IdentityHashMap<>();
    private boolean active;
    private int installed;

    synchronized Ticket observe(Class<?> type) {
        Ticket entry = entries.get(type);
        if (entry == null) {
            entry = new Ticket(type, entries.size() + 1);
            entries.put(type, entry);
        }
        if (!active || entry.attempted) return null;
        entry.attempted = true;
        return entry;
    }

    synchronized List<Class<?>> activate() {
        active = true;
        return new ArrayList<>(entries.keySet());
    }

    synchronized void installed(Ticket entry) {
        if (!entry.installed) { entry.installed = true; installed++; }
    }

    synchronized int knownCount() { return entries.size(); }
    synchronized int installedCount() { return installed; }
}
