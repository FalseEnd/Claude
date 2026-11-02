package com.falseend.mousecursor;

oneway interface IMouseCallback {
    /** 一個 SYN_REPORT 內累積的相對移動。buttons: bit0 左鍵 bit1 右鍵 bit2 中鍵 */
    void onMotion(int dx, int dy, int wheel, int buttons, long timeUs);
    /** 目前偵測到的滑鼠裝置描述 */
    void onDevices(String description);
}
