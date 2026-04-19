package com.example.myapplication1.region

/**
 * 选区边框条与右下角缩放手柄的触摸尺寸（dp），供 [RegionGestureHandler] 与 [RegionOverlayService] 共用，避免布局与命中判断不一致。
 */
object RegionOverlayTouchSpec {
    /** 左、上侧边框可拖条宽度（dp）。 */
    const val SLOP_LEFT_TOP_DP = 44f

    /** 右侧边框可拖条宽度（dp）。 */
    const val SLOP_RIGHT_DP = 64f

    /** 下侧边框可拖条宽度（dp）。 */
    const val SLOP_BOTTOM_DP = 64f

    /**
     * 右下角缩放手柄：触摸区为边长 `2 * HANDLE_TOUCH_HALF_DP` 的正方形（dp），中心贴近选区右下角。
     */
    const val HANDLE_TOUCH_HALF_DP = 48f
}
