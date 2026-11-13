package android.util

import java.util.TreeMap

/**
 * Android's SparseArray for the JVM tests, which otherwise get android.jar's stubs: those keep
 * nothing (put does nothing, size is 0), and Media3's Matroska extractor keeps its tracks in one.
 * Test classes come before the stubs on the test classpath. Only what Media3's extractors call.
 */
@Suppress("unused")
class SparseArray<E>() {
    private val map = TreeMap<Int, E>()

    constructor(initialCapacity: Int) : this()

    fun get(key: Int): E? = map[key]

    fun get(key: Int, valueIfKeyNotFound: E): E = map[key] ?: valueIfKeyNotFound

    fun put(key: Int, value: E) {
        map[key] = value
    }

    fun append(key: Int, value: E) = put(key, value)

    fun remove(key: Int) {
        map.remove(key)
    }

    fun delete(key: Int) = remove(key)

    fun contains(key: Int): Boolean = map.containsKey(key)

    fun size(): Int = map.size

    fun keyAt(index: Int): Int = map.keys.elementAt(index)

    fun valueAt(index: Int): E = map.values.elementAt(index)

    fun indexOfKey(key: Int): Int = map.keys.indexOf(key)

    fun clear() = map.clear()
}
