-- =============================================================================
-- 小倆口 2026-10：再多一些安撫
-- 安撫從 15 種變成 29 種：安慰、甜蜜、關心各多三種，再加一組「逗你開心」。
-- 原本的種類都還在，已經存的資料不受影響；舊版 App 看不懂新的種類，兩支手機都更新到 0.2.6 就好。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

-- not valid：已經存的資料不再檢查一次。upgrade-v2.sql 每次都從頭跑，後面的步驟會把清單換成更多種類；
-- 資料表裡已經有後來才多的種類時（例如 0.2.6 的安撫、0.3.0 小貓的通知設定），這一步才不會失敗。新存的資料照樣檢查
alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat', 'listen', 'holdhand', 'proud',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night', 'heart', 'cuddle', 'date',
  'water', 'meal', 'rest', 'warm', 'gethome', 'safe',
  'joke', 'silly', 'kitty', 'dessert', 'gift', 'custom')) not valid;

-- 通知用的文字，和 App 的 Catalog 一樣（Android 的 MoreMoodsTest 會比對）
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'listen' then '說想聽你說說今天' when 'holdhand' then '牽起你的手'
    when 'proud' then '說你是最棒的'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'heart' then '對你比了一個愛心' when 'cuddle' then '蹭蹭你撒嬌'
    when 'date' then '說想跟你約會'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息' when 'warm' then '提醒你穿暖一點'
    when 'gethome' then '問你到家了沒' when 'safe' then '叫你路上小心'
    when 'joke' then '講了一個冷笑話給你' when 'silly' then '對你扮了一個鬼臉'
    when 'kitty' then '送你一隻可愛的小貓' when 'dessert' then '說要請你吃甜點'
    when 'gift' then '說準備了小禮物給你'
    else '傳了悄悄話給你' end
$$;

-- App 用 schema 判斷資料庫有沒有更新：…5 再加更多心情和安撫；6 再加更多安撫
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 6)
$$;

-- 請 Data API（PostgREST）重新讀取函式清單
notify pgrst, 'reload schema';
