package dev.betavk.util;

import java.util.Arrays;

/**
 * Open-addressing int -> V map (linear probing, Fibonacci hashing, backward-shift deletion). Replaces
 * HashMap&lt;Integer, V&gt; where the key is a packed chunk coordinate: that key's low bits are x XOR z after HashMap's hash
 * spreading, so whole diagonals of chunks collided into treeified buckets. No boxing. Not thread-safe (neither was HashMap).
 */
public final class IntMap<V> {
    private static final int EMPTY = 0;   // slot marker kept in a parallel "used" array so key 0 stays legal
    private int[] keys = new int[64];
    private Object[] vals = new Object[64];
    private boolean[] used = new boolean[64];
    private int size, mask = 63, shift = 32 - 6;

    private int slot(int key) {
        return (key * 0x9E3779B9) >>> shift;   // top bits of the product: well mixed for any key pattern
    }

    @SuppressWarnings("unchecked")
    public V get(int key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) return (V) vals[i];
            i = (i + 1) & mask;
        }
        return null;
    }

    public boolean containsKey(int key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public V put(int key, V value) {
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
    public V remove(int key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                V old = (V) vals[i];
                // backward-shift deletion keeps probe sequences intact without tombstones
                int j = i;
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

    public int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int[] ok = keys;
        Object[] ov = vals;
        boolean[] ou = used;
        int cap = ok.length * 2;
        keys = new int[cap];
        vals = new Object[cap];
        used = new boolean[cap];
        mask = cap - 1;
        shift = 32 - Integer.numberOfTrailingZeros(cap);
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], (V) ov[i]);
    }

    /** Self-check used by the build: random operations against java.util.HashMap. */
    public static void main(String[] args) {
        java.util.Random r = new java.util.Random(1);
        IntMap<Integer> m = new IntMap<>();
        java.util.HashMap<Integer, Integer> ref = new java.util.HashMap<>();
        for (int n = 0; n < 2_000_000; n++) {
            int k = (r.nextInt(64) - 32) << 16 | (r.nextInt(64) & 0x7FFF);   // chunk-like keys with heavy diagonal structure
            switch (r.nextInt(3)) {
                case 0 -> { if (!java.util.Objects.equals(m.put(k, n), ref.put(k, n))) throw new AssertionError("put " + k); }
                case 1 -> { if (!java.util.Objects.equals(m.remove(k), ref.remove(k))) throw new AssertionError("remove " + k); }
                default -> { if (!java.util.Objects.equals(m.get(k), ref.get(k))) throw new AssertionError("get " + k); }
            }
            if (m.size() != ref.size()) throw new AssertionError("size");
        }
        System.out.println("IntMap OK, final size " + m.size());
    }
}
