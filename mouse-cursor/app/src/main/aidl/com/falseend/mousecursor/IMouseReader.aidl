package com.falseend.mousecursor;

import com.falseend.mousecursor.IMouseCallback;

interface IMouseReader {
    /** Shizuku 規定的銷毀交易碼 */
    void destroy() = 16777114;
    /** 開始讀取所有滑鼠，回傳裝置描述 */
    String start(IMouseCallback callback) = 1;
    void stop() = 2;
    int uid() = 3;
    /**
     * 接管模式：獨佔 (EVIOCGRAB) 滑鼠，由本服務計算位置並注入事件。
     * clickMode 0 = 滑鼠事件，1 = 觸控事件。回傳空字串代表成功，否則為錯誤訊息（已自動退回估算模式）。
     */
    String setTakeover(boolean enable, int clickMode) = 4;
    oneway void setScreen(int width, int height) = 5;
    oneway void setPosition(float x, float y) = 6;
    oneway void setMotion(int modelType, int systemSpeed, float sensitivity) = 7;
}
