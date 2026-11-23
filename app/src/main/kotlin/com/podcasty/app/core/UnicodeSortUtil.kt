/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.core

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Memory-safe, allocation-minimal sorting and search-normalization utilities.
 *
 * Precomputed sort keys avoid allocating temporary lowercase Strings inside
 * TimSort / Arrays.sort O(N log N) comparator callbacks, eliminating the
 * CaseMapper.toLowerCase heap churn on large (5k–25k+ track) libraries.
 */
object UnicodeSortUtil {
    private val collatorThreadLocal: ThreadLocal<Collator> = ThreadLocal.withInitial {
        Collator.getInstance().apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
    }

    /**
     * Precomputes a normalized Unicode sort/filter key once when indexing or saving tags.
     */
    fun normalizeKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val nfkc = if (Normalizer.isNormalized(trimmed, Normalizer.Form.NFKC)) {
            trimmed
        } else {
            Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        }
        return nfkc.lowercase(Locale.ROOT)
    }

    /**
     * Allocation-free comparison suitable for already-normalized keys or arbitrary international text.
     */
    fun compareInternational(a: String?, b: String?): Int {
        val left = a.orEmpty()
        val right = b.orEmpty()
        if (left === right || left == right) return 0
        if (left.isEmpty()) return -1
        if (right.isEmpty()) return 1
        // Fast ASCII path avoids ICU Collator native overhead when both strings are basic ASCII
        if (isAsciiOnly(left) && isAsciiOnly(right)) {
            return left.compareTo(right, ignoreCase = true)
        }
        return collatorThreadLocal.get()!!.compare(left, right)
    }

    /**
     * Shared comparator using precomputed keys cleanly without allocating inside compare().
     */
    inline fun <T> precomputedKeyComparator(crossinline keySelector: (T) -> String): Comparator<T> =
        Comparator { a, b ->
            val ka = keySelector(a)
            val kb = keySelector(b)
            compareInternational(ka, kb)
        }

    private fun isAsciiOnly(s: String): Boolean {
        for (i in 0 until s.length) {
            if (s[i].code >= 0x80) return false
        }
        return true
    }
}
