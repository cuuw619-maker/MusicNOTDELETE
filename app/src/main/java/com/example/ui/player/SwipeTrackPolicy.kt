package com.example.ui.player

import com.example.model.Song
import kotlin.math.abs

enum class SwipeTrackAction {
    PREVIOUS,
    NEXT,
    NONE
}

object SwipeTrackPolicy {

    const val DIR_RIGHT_NEXT_LEFT_PREV = "RIGHT_NEXT_LEFT_PREV"
    const val DIR_LEFT_NEXT_RIGHT_PREV = "LEFT_NEXT_RIGHT_PREV"

    fun resolveDragDirectionHint(
        dragOffsetPx: Float,
        swipeDirectionPref: String = DIR_RIGHT_NEXT_LEFT_PREV
    ): SwipeTrackAction {
        if (abs(dragOffsetPx) < 8f) return SwipeTrackAction.NONE
        val isLeftSwipe = dragOffsetPx < 0f
        return if (swipeDirectionPref == DIR_LEFT_NEXT_RIGHT_PREV) {
            if (isLeftSwipe) SwipeTrackAction.NEXT else SwipeTrackAction.PREVIOUS
        } else {
            if (isLeftSwipe) SwipeTrackAction.PREVIOUS else SwipeTrackAction.NEXT
        }
    }

    fun resolveHorizontalSwipeAction(
        dragOffsetPx: Float,
        velocityPxPerSec: Float = 0f,
        thresholdPx: Float = 56f,
        swipeDirectionPref: String = DIR_RIGHT_NEXT_LEFT_PREV
    ): SwipeTrackAction {
        val passedDistance = abs(dragOffsetPx) >= thresholdPx
        val passedFlick = abs(velocityPxPerSec) >= 520f && abs(dragOffsetPx) >= thresholdPx * 0.45f
        if (!passedDistance && !passedFlick) return SwipeTrackAction.NONE
        return resolveDragDirectionHint(dragOffsetPx, swipeDirectionPref)
    }

    fun previewTargetSong(
        queue: List<Song>,
        currentIndex: Int,
        action: SwipeTrackAction
    ): Song? {
        if (queue.isEmpty() || action == SwipeTrackAction.NONE) return null
        return when (action) {
            SwipeTrackAction.NEXT -> queue.getOrNull(currentIndex + 1)
            SwipeTrackAction.PREVIOUS -> queue.getOrNull(currentIndex - 1)
            SwipeTrackAction.NONE -> null
        }
    }
}
