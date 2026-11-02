package com.falseend.mousecursor;

import com.falseend.mousecursor.IMouseCallback;

interface IMouseReader {
    /** Shizuku 規定的銷毀交易碼 */
    void destroy() = 16777114;
    /** 開始讀取所有滑鼠，回傳裝置描述 */
    String start(IMouseCallback callback) = 1;
    void stop() = 2;
    int uid() = 3;
}
