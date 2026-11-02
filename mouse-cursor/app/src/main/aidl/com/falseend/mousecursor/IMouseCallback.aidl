package com.falseend.mousecursor;

oneway interface IMouseCallback {
    /** 估算模式：一個 SYN_REPORT 內累積的相對移動。buttons: bit0 左鍵 bit1 右鍵 bit2 中鍵 */
    void onMotion(int dx, int dy, int wheel, int buttons, long timeUs);
    /** 目前偵測到的滑鼠裝置描述 */
    void onDevices(String description);
    /** 接管模式：服務算出的游標位置（也就是實際注入點擊的位置）；pressed = 這次新按下的鍵 */
    void onPosition(float x, float y, int pressed);
}
