package android.util

import java.util.TreeMap

/** Android's LongSparseArray for the JVM tests, as [SparseArray]: the Matroska extractor keeps its chapters in one. */
@Suppress("unused")
class LongSparseArray<E>() {
    private val map = TreeMap<Long, E>()

    constructor(initialCapacity: Int) : this()

    fun get(key: Long): E? = map[key]

    fun get(key: Long, valueIfKeyNotFound: E): E = map[key] ?: valueIfKeyNotFound

    fun put(key: Long, value: E) {
        map[key] = value
    }

    fun append(key: Long, value: E) = put(key, value)

    fun remove(key: Long) {
        map.remove(key)
    }

    fun delete(key: Long) = remove(key)

    fun size(): Int = map.size

    fun keyAt(index: Int): Long = map.keys.elementAt(index)

    fun valueAt(index: Int): E = map.values.elementAt(index)

    fun indexOfKey(key: Long): Int = map.keys.indexOf(key)

    fun clear() = map.clear()
}
