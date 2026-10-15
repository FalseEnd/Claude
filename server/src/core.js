'use strict';
// 與 App 原型共用同一份核心邏輯（經期預測等），避免兩套演算法不一致
const path = require('path');
module.exports = require(process.env.CORE_JS || path.join(__dirname, '..', '..', 'prototype', 'core.js'));
