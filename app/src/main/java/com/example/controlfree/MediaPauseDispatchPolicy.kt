package com.example.controlfree

/** 跟踪锁定阶段切换，保证每个锁定阶段只发送一次媒体暂停。 */
class MediaPauseDispatchPolicy {
    private var isLockStageActive = false

    fun enterLockStage(): Boolean {
        if (isLockStageActive) return false
        isLockStageActive = true
        return true
    }

    fun allowMediaPlayback() {
        isLockStageActive = false
    }
}
