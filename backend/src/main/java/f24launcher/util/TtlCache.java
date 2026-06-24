package f24launcher.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caché en memoria sencilla con expiración por tiempo (TTL) y tamaño máximo (LRU).
 * Pensada para metadatos de sesión (proyectos, versiones, categorías) que no
 * cambian a cada momento. Thread-safe por sincronización (uso de baja contención).
 */
public final class TtlCache<K, V> {

    private final long ttlMillis;
    private final LinkedHashMap<K, Entry<V>> map;

    private record Entry<V>(V value, long expiresAt) {}

    public TtlCache(long ttlMillis, int maxSize) {
        this.ttlMillis = ttlMillis;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** Valor cacheado y aún vigente, o {@code null} si no está o caducó. */
    public synchronized V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAt()) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public synchronized void clear() {
        map.clear();
    }
}
