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
    private boolean[] used = new boolean[64];
    private int size, mask = 63, shift = 32 - 6;

    private int slot(int key) {
        return (key * 0x9E3779B9) >>> shift;   // top bits of the product: well mixed for any key pattern
    }

    @SuppressWarnings("unchecked")
    @Override public V get(Object k) {
        int key = (Integer) k, i = slot(key);
        while (used[i]) {
            if (keys[i] == key) return (V) vals[i];
            i = (i + 1) & mask;
        }
        return null;
    }

    @Override public boolean containsKey(Object k) {
        int key = (Integer) k, i = slot(key);
        while (used[i]) {
            if (keys[i] == key) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override public V put(Integer k, V value) {
        int key = k;
        if ((size + 1) * 2 > keys.length) grow();
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                V old = (V) vals[i];
                vals[i] = value;
                return old;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        vals[i] = value;
        size++;
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override public V remove(Object k) {
        int key = (Integer) k, i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                V old = (V) vals[i];
                int j = i;   // backward-shift deletion keeps probe sequences intact without tombstones
                while (true) {
                    j = (j + 1) & mask;
                    if (!used[j]) break;
                    int home = slot(keys[j]);
                    boolean movable = i <= j ? (home <= i || home > j) : (home <= i && home > j);
                    if (movable) {
                        keys[i] = keys[j];
                        vals[i] = vals[j];
                        i = j;
                    }
                }
                used[i] = false;
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
        for (int i = 0; i < keys.length; i++) if (used[i]) copy.put(keys[i], cast(vals[i]));
        return copy.entrySet();   // a snapshot: the game never iterates this map
    }

    @SuppressWarnings("unchecked")
    private V cast(Object o) {
        return (V) o;
    }

    private void grow() {
        int[] ok = keys;
        Object[] ov = vals;
        boolean[] ou = used;
        int n = ok.length * 2;
        keys = new int[n];
        vals = new Object[n];
        used = new boolean[n];
        mask = n - 1;
        shift--;
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], cast(ov[i]));
    }
}
