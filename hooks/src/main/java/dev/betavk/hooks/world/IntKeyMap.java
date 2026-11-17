package dev.betavk.hooks.world;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Set;

/**
 * A Map&lt;Integer, V&gt; for the packed chunk coordinates ChunkProvider keys its loaded chunks by. The game used a HashMap, and that key's low bits are x XOR z
 * after the HashMap's hash spreading, so whole diagonals of chunks collided into treeified buckets. This is open addressing on the int key (linear probing,
 * Fibonacci hashing, backward-shift deletion). Only the methods the game calls are implemented; not thread-safe, as the HashMap was not.
 */
public final class IntKeyMap<V> extends AbstractMap<Integer, V> {
    private int[] keys = new int[64];
    private Object[] vals = new Object[64];
    private int size, mask = 63, shift = 32 - 6;
    private int lastKey;
    private Object lastVal;
    private final int[] cKeys = new int[64];
    private final Object[] cVals = new Object[64];

    private int slot(int key) {
        return (key * 0x9E3779B9) >>> shift;   // top bits of the product: well mixed for any key pattern
    }

    /** Direct-mapped 8x8 chunk neighborhood slot matching ChunkCoordIntPair.chunkXZ2Int layout (bits 0..15=z, 16..31=x). */
    private static int cacheSlot(int key) {
        return (key & 7) | ((key >>> 13) & 0x38);
    }

    @SuppressWarnings("unchecked")
    public V getInt(int key) {
        if (key == lastKey && lastVal != null) return (V) lastVal;
        int cs = cacheSlot(key);
        Object cv = cVals[cs];
        if (cv != null && cKeys[cs] == key) {
            lastKey = key;
            lastVal = cv;
            return (V) cv;
        }
        int i = slot(key);
        Object v;
        while ((v = vals[i]) != null) {
            if (keys[i] == key) {
                lastKey = key;
                lastVal = v;
                cKeys[cs] = key;
                cVals[cs] = v;
                return (V) v;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override public V get(Object k) {
        return getInt((Integer) k);
    }

    @Override public boolean containsKey(Object k) {
        return getInt((Integer) k) != null;
    }

    @SuppressWarnings("unchecked")
    @Override public V put(Integer k, V value) {
        if (value == null) return remove(k);
        int key = k;
        if ((size + 1) * 2 > keys.length) grow();
        lastKey = key;
        lastVal = value;
        int cs = cacheSlot(key);
        cKeys[cs] = key;
        cVals[cs] = value;
        int i = slot(key);
        Object v;
        while ((v = vals[i]) != null) {
            if (keys[i] == key) {
                vals[i] = value;
                return (V) v;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        vals[i] = value;
        size++;
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override public V remove(Object k) {
        int key = (Integer) k, i = slot(key);
        if (key == lastKey) lastVal = null;
        int cs = cacheSlot(key);
        if (cKeys[cs] == key) cVals[cs] = null;
        Object v;
        while ((v = vals[i]) != null) {
            if (keys[i] == key) {
                V old = (V) v;
                int j = i;   // backward-shift deletion keeps probe sequences intact without tombstones
                while (true) {
                    j = (j + 1) & mask;
                    Object jv = vals[j];
                    if (jv == null) break;
                    int home = slot(keys[j]);
                    boolean movable = i <= j ? (home <= i || home > j) : (home <= i && home > j);
                    if (movable) {
                        keys[i] = keys[j];
                        vals[i] = jv;
                        i = j;
                    }
                }
                vals[i] = null;
                size--;
                return old;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    @Override public int size() {
        return size;
    }

    @Override public Set<Entry<Integer, V>> entrySet() {
        java.util.LinkedHashMap<Integer, V> copy = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) if (vals[i] != null) copy.put(keys[i], cast(vals[i]));
        return copy.entrySet();   // a snapshot: the game never iterates this map
    }

    @SuppressWarnings("unchecked")
    private V cast(Object o) {
        return (V) o;
    }

    private void grow() {
        lastVal = null;
        Arrays.fill(cVals, null);
        int[] ok = keys;
        Object[] ov = vals;
        int n = ok.length * 2;
        keys = new int[n];
        vals = new Object[n];
        mask = n - 1;
        shift--;
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ov[i] != null) put(ok[i], cast(ov[i]));
    }
}
