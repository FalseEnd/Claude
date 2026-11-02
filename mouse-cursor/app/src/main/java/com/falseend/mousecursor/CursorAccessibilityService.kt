package com.falseend.mousecursor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 無障礙服務只用來提供 TYPE_ACCESSIBILITY_OVERLAY 視窗：
 * 永遠在最上層、100% 不透明、系統視為可信任視窗（點擊不會被擋）。
 * 不讀取任何畫面內容，也不攔截任何輸入。
 */
class CursorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CursorHub.init(this)
        CursorHub.onHostChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        CursorHub.onHostChanged()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: CursorAccessibilityService? = null
    }
}
