# Issue #48 確定仕様 — TODAYパネルの日付タップでカレンダーアプリを開く

## 経路（A設計レビュー確定）

- **armなし**。予定行と同じ経路に揃える:
  `onDateTapped()` → `_eventIntents.tryEmit(intent)` → HomeUi が `onOpenEvent`（`openCalendarEvent` 独自try/catch→Toast）へ。MainActivity変更なし
- armするとonBackgroundedのoverlays.clear()でパネルは結局閉じるため実質no-op。予定行と挙動を揃える方が設計意図に忠実

## 実装

- `CalendarAccess.viewDayIntent(atMillis: Long)`: `CalendarContract.CONTENT_URI.buildUpon().appendPath("time")` + `ContentUris.appendId(builder, atMillis)` で `content://com.android.calendar/time/<ms>`。ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK（viewEventIntentと揃える）
- `CalendarAccess`に純粋関数 `internal fun dayStartMillis(atMillis: Long): Long`（端末TZの当日0時。java.util.Calendar使用。JVM単体テスト可）
- VM `onDateTapped()`: `canOpenEvent(intent)` 事前チェック（予定行と同じseam `eventHandlerCheck` が効く）→ OKなら tryEmit、NGなら `HomeMessage` 新規文言「カレンダーを開けるアプリがありません」（`event_no_handler`は予定限定のため新設）
- TodayPanel 日付行を `clickable` + `defaultMinSize(minHeight=48.dp)` + `onClickLabel`（「%1$sをカレンダーで開く」形式のstring resource。combinedClickableは不要）
- `queries` 追記不要（既存 `host=com.android.calendar` が path非指定で全体をカバー）
- `TodayPanel` に `onDateTap: () -> Unit` 引数追加 → QuietScreen/QuietLauncherRoot/TodayPanelStatesTest fixture へ波及させる

## テスト（B計画要約）

- L1: `dayStartMillis` の日付境界、（URIは純粋関数経路かL2へ）
- L2: 日付行 hasClickAction+OnClick.label、クリック→eventIntentsに `content://com.android.calendar/time/<ms>` で1件、ハンドラなし→message+intent 0件、blocking monitorで実発射検証、GLANCE日付行は非クリック維持
- L4: 実機でカレンダー当日表示・ハンドラなしToast・大きな文字でのタップ領域（pending-device-checks追記）

## 検証結果（2026-10-26 実装完了時）

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I48-01 | `dayStartMillis`: 当日23:59:59.999→当日0時 | 一致 | PASS | L1単体 | `DayStartMillisTest.last millisecond of the day collapses to that days midnight` |
| I48-02 | `dayStartMillis`: 当日00:01→当日0時 | 一致 | PASS | L1単体 | `DayStartMillisTest.first minute of the day collapses to midnight` |
| I48-03 | `dayStartMillis`: ちょうど0時→不変 | 一致 | PASS | L1単体 | `DayStartMillisTest.exact midnight stays midnight` |
| I48-04 | `dayStartMillis`: 月末夜（1/31 23:59）→当月末日0時、年跨ぎ（12/31→1/1）それぞれ自年側0時 | 一致 | PASS | L1単体 | `DayStartMillisTest.month-end… / new years eve…`（計2ケース） |
| I48-05 | `dayStartMillis`: 同一instantでも端末TZに従う（UTC 15:30→JSTは翌日0時・NYは当日0時） | 一致 | PASS | L1単体 | `DayStartMillisTest.the same instant resolves in whatever zone the device is in` |
| I48-06 | 日付行が hasClickAction かつ OnClick.label＝「<日付 曜日>をカレンダーで開く」 | 一致 | PASS | L2計装 | `TodayDateLaunchTest.dateRowIsClickableAndNamesItsAction` |
| I48-07 | eventHandlerCheck={true} でタップ→eventIntentsに `content://com.android.calendar/time/<当日0時ms>` が1件・ACTION_VIEW・NEW_TASK付き | 一致 | PASS | L2計装 | `TodayDateLaunchTest.dateTapEmitsDayViewIntent` |
| I48-08 | eventHandlerCheck={false} でタップ→HomeMessage.NoCalendarHandler1件・intent 0件 | 一致 | PASS | L2計装 | `TodayDateLaunchTest.dateTapWithoutHandlerEmitsMessageOnly` |
| I48-09 | blocking monitor で実 startActivity を捕捉（filter型monitorはresolveTypeでtime/URIにMIMEが返り一致しないため、`onStartActivity` フックの no-arg monitor で正確捕捉） | monitor.hits=1・pathSegments=[time,<ms>] | PASS | L2計装 | `TodayDateLaunchTest.dateTapFiresRealCalendarActivity` |
| I48-10 | GLANCEの日付行は非クリック維持 | hasNoClickAction・clickableノード0件 | PASS | L2計装 | `TodayDateLaunchTest.glanceDateRowStaysNonInteractive` |

全体再実行（emulator-5556 / API 36、flock 直列化）:
`testDebugUnitTest` 343件 0失敗、`connectedDebugAndroidTest` 189件 0失敗、
`lintDebug` 0 errors / 既存warningのみ。
証跡は `docs/handoffs/prNN/evidence/` に保存（PR番号確定後に確定）。

## 実機確認（ラウンド7候補・pending-device-checks.md）

- 日付タップでカレンダーが当日ビューで実起動
- ハンドラなし環境でのToast「カレンダーを開けるアプリがありません」
- 大きな文字設定での日付行タップ領域（48dp最低高さ・行全体）
- TalkBackでの操作ラベル読み上げとダブルタップ起動
