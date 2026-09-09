package com.example.controlfree

/**
 * 拒绝在 Home、最近任务或熄屏之前产生的迟到白名单命令。
 * elapsedRealtime 在同一次开机内单调递增，不受用户修改系统时间影响。
 */
object AllowedAppOpenRequestPolicy {
    const val MAX_REQUEST_AGE_MILLIS = 15_000L

    fun isCurrent(
        requestedElapsedMillis: Long,
        nowElapsedMillis: Long,
        lastInvalidationElapsedMillis: Long
    ): Boolean {
        if (requestedElapsedMillis <= 0L || nowElapsedMillis < requestedElapsedMillis) {
            return false
        }
        if (requestedElapsedMillis <= lastInvalidationElapsedMillis) return false
        return nowElapsedMillis - requestedElapsedMillis <= MAX_REQUEST_AGE_MILLIS
    }
}
