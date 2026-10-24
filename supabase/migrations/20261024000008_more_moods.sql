-- =============================================================================
-- 小倆口 2026-10：更多心情和安撫
-- 心情從 9 種變成 21 種（好心情、日常、需要抱抱），安撫從 6 種變成 15 種（安慰、甜蜜、關心）。
-- 原本的種類都還在，已經存的資料不受影響；舊版 App 看不懂新的種類，兩支手機都更新到 0.2.3 就好。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

-- 欄位上原本的 check 換成新的清單（名字是 Postgres 自動取的 moods_mood_check、comforts_kind_check）
alter table public.moods drop constraint if exists moods_mood_check, add constraint moods_mood_check check (mood in (
  'happy', 'sweet', 'excited', 'laugh', 'calm', 'relaxed',
  'missyou', 'shy', 'sleepy', 'hungry', 'bored', 'busy',
  'tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick'));
alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night',
  'water', 'meal', 'rest', 'custom'));

-- 通知標題用的文字，和 App 的 Catalog 一樣（Android 的 CatalogSqlTest 會比對）
create or replace function public.mood_label(p_mood text) returns text
language sql immutable as $$
  select case p_mood
    when 'happy' then '開心' when 'sweet' then '甜蜜' when 'excited' then '興奮' when 'laugh' then '好好笑'
    when 'calm' then '平靜' when 'relaxed' then '放鬆'
    when 'missyou' then '想你' when 'shy' then '害羞' when 'sleepy' then '想睡' when 'hungry' then '肚子餓'
    when 'bored' then '無聊' when 'busy' then '忙翻了'
    when 'tired' then '好累' when 'anxious' then '焦慮' when 'stressed' then '壓力大' when 'wronged' then '委屈'
    when 'sad' then '難過' when 'angry' then '生氣' when 'jealous' then '吃醋' when 'lonely' then '寂寞'
    when 'sick' then '不舒服' else p_mood end
$$;

create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息'
    else '傳了悄悄話給你' end
$$;

-- 「需要抱抱」的心情，對方的通知會問「要不要安撫一下？」（和 App 的 Catalog 一樣）
create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note, 'sticker', new.sticker));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(nullif(new.note, ''), case when new.sticker is not null then '附上一張貼圖 ✨' end, '') end,
    jsonb_build_object('mood', new.mood, 'sticker', new.sticker));
  return new;
end $$;

-- App 用 schema 判斷資料庫有沒有更新：2 有個人資料、日曆紀錄、備忘錄；3 再加手機名稱與電信；4 再加 GIPHY 貼圖；
-- 5 再加更多心情和安撫
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 5)
$$;

-- 請 Data API（PostgREST）重新讀取函式清單
notify pgrst, 'reload schema';
