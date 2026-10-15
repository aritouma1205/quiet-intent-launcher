# Issue #40 — 外部フロー復帰でexternalFlowActiveが残留しQuietリセットが抑制される

## 工程状態

- 現在工程: 完了（ユーザーのマージ判断待ち）
- A独立PRレビュー: APPROVE・MINOR1件（既知制限の明記 → 本ファイル「既知制限」節と
  design-v1.md 3章に反映）
- PR #46: OPEN、base=main、本文に「Depends on PR #39」明記（ブランチは
  `feature/issue-38-app-uninstall` 先端 8f47354 を基点に派生。#39 マージ後に
  diff が自動縮小する）
- 作業ブランチ: `feature/issue-40-external-flow`
- 作業ディレクトリ: `/home/aritouma/projects/qil-issue40-flow`（git worktree）
- A設計レビュー反映済みの確定仕様に従って実装

## 背景

`HomeNavigation.externalFlowActive` は「アプリ自身が開いたOSフローからの復帰は
元の画面を復元する」（design 3）ためのフラグで、`beginExternalFlow()` で arm し
`nav.onForegrounded()`（= `MainActivity.onStart` 内の `viewModel.onForegrounded()`
経由）で解除する設計だった。

しかしダイアログ型の外部OS画面（アンインストール確認・ホームロール要求画面等）は
MainActivity を **onPause のみ** で覆うため、復帰時に onStart が走らずフラグが
解除されない。arm のまま残ると次の通常の背景化（HOME・ロック等）でも
`nav.onBackgrounded()` が Quiet リセットを抑制し続け、design 3 の不変条件
「ホーム復帰・ロック解除はQuiet」を壊す。

## 確定仕様（A設計レビュー反映済み）

1. **フラグ解除を onResume でも行う**: `MainActivity.onResume` を追加し
   `viewModel.nav.onForegrounded()`（フラグ解除のみの軽量経路）を呼ぶ。
   `viewModel.onForegrounded()` 全体は呼ばない（torch/systemActions
   observing・refresh群の二重実行を避ける。A設計レビュー確定事項）。
   onStart 側の既存解除は維持（冪等）。
2. **ActivityResult コールバックへの明示クリアは入れない**（結果コールバックは
   onResume より先に発火するため冗長。A設計レビューで不要と確定 — I40-T04 不採用）。
3. **残存arm経路のロールバック拡大**: `openWallpaperPicker`・
   `openDefaultHomeSettings`・`openAccessibilitySettings` の ANFE 経路にも
   `launchExternalOrToast` 相当のロールバックを適用する（#39で導入した共通関数に
   統一。フォールバック内の入れ子 startActivity も同様に扱う）。
4. design 3 の不変条件（HOME復帰・ロック解除はQuiet）を全経路で回復させる。

## 実装サマリ

- `MainActivity.kt`
  - `onResume()` を追加: `viewModel.nav.onForegrounded()` のみ呼ぶ軽量経路。
    ダイアログ型外部画面からの復帰（onPause→onResume、onStart不経由）で
    フラグを解放する。
  - `launchExternalOrToast(intent, failureTextRes, fallbackIntent)` に
    フォールバック引数を追加。主Intentが ANFE → フォールバック発射を試み、
    成功すれば実ハンドオフとして arm を維持（`return`）、フォールバックも
    ANFE なら `cancelExternalFlow()` + Toast に巻戻す。
  - `openWallpaperPicker` / `openDefaultHomeSettings` /
    `openAccessibilitySettings` を同関数に統一し internal 化・Intent注入可能に
    （計装 seam）。`openDefaultHomeSettings` / `openAccessibilitySettings` は
    `ACTION_SETTINGS` をフォールバックに渡す。これによりロール要求失敗時の
    二段フォールバック（requestHomeRole→openDefaultHomeSettings）にも
    ロールバックが及ぶ。
  - コールバック配線はデフォルト引数を持つ関数参照が `() -> Unit` に
    適合しないためラムダへ変更（`onRestoreHome` / `onChangeWallpaper` /
    `onOpenAccessibilitySettings`）。
  - `openCalendarEvent` は arm しない経路のため変更なし（ANFE時は従来どおり
    Toast のみ）。

## 検証結果

対象: `feature/issue-40-external-flow` head `57438c9`（PR #46）、
emulator-5556（ops_emu AVD / API 36）。証跡一式は
`docs/handoffs/pr46/evidence/` にコミット済み。

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I40-T01 | 外部フロー往復でフラグ消費・次の背景化はQuiet | 消費・Quiet | PASS | 単体 | `HomeNavigationTest.external flow keeps the originating screen`（既存） |
| I40-T02 | Settingsでarm→onPauseのみ→onResume復帰→onBackgrounded→Quiet | Quietへ遷移 | PASS | 計装 | `ExternalFlowLifecycleTest.pauseOnlyExternalFlowReleasesFlagOnResume` |
| I40-T03 | arm→onStop往復→復帰直後Settings保持→その後onBackgrounded→Quiet | 両条件一致 | PASS | 計装 | `ExternalFlowLifecycleTest.stoppedExternalFlowKeepsScreenThenConsumesFlag` |
| I40-T04 | ActivityResultコールバック明示クリア | — | NOT TESTED（不採用） | — | A判断で不採用（結果コールバックはonResumeより先に発火するため冗長） |
| I40-T05 | navigateTo(Settings)→recreate()→Settings保持 | 保持 | PASS | 計装 | `ExternalFlowLifecycleTest.recreateKeepsTheCurrentScreen` |
| I40-T06 | wallpaper/defaultHome/accessibility の3経路でANFE→巻戻し→Quiet。フォールバック発射成功時はarm維持 | 全経路Quiet・fallback経路はSettings保持 | PASS | 計装 | `ExternalFlowLifecycleTest.unresolvedExternalLaunchesRollBackArmedFlow` / `dispatchedFallbackKeepsTheArmedFlow` |
| I40-T07 | onForegrounded解除の冪等性・再arm可 | 二重解除no-op・再arm保護 | PASS | 単体 | `HomeNavigationTest.foreground release is idempotent and a new flow can re-arm`（新規1件） |
| 回帰 | UninstallFlowIntegrationTest 4件・AllAppsIntegrationTest 15件・TimeBroadcastIntegrationTest・InfoIntegrationTest を含む全量 | 0失敗 | PASS | 計装 | `instrumentation-results.xml`（164件） |

### 識別性の確認（T02）

修正前相当（onResume の `nav.onForegrounded()` 呼出を一時無効化）で
`pauseOnlyExternalFlowReleasesFlagOnResume` を単独実行 →
`expected:<Quiet> but was:<Settings>` で失敗することを実測確認
（`docs/handoffs/pr46/evidence/i40-t02-prefix-failure.txt`）。
修正復元後に最終コードで全量計装を再実行済み。

### テスト計画との差分（記録）

- I40-T06 は計画の「wallpaper等の経路」に対し、確定仕様3の3経路すべてを1テストで
  網羅し、加えてフォールバック発射成功でarm維持を確認する
  `dispatchedFallbackKeepsTheArmedFlow` を追加（blocking monitor で
  `ACTION_SETTINGS` を傍受し、アプリを実際には離れさせずに発射成功を再現）。
- I40-T01/T07 は既存単体テストに冪等解除・再armの1件を追加してカバー。
- I40-T04（ActivityResultコールバック明示クリア）はA設計レビュー判断により
  実装不採用のためテストなし。

### 検証コマンド

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`: BUILD SUCCESSFUL
- `testDebugUnitTest`: **311件 0失敗**（`app/build/test-results/testDebugUnitTest/TEST-*.xml`、
  証跡 `docs/handoffs/pr46/evidence/unit/`）
- `lintDebug`: **0 errors / 7 warnings**（`docs/handoffs/pr46/evidence/lint-results.txt`。
  ベースライン4件 + upstream版数通知3件。本差分起因の警告なし）
- `connectedDebugAndroidTest`（emulator-5556、flock直列化）: **164件 0失敗**
  （`docs/handoffs/pr46/evidence/instrumentation-results.xml`、
  ExternalFlowLifecycleTest 5/5 を含む）

## 既知制限

- **マルチウィンドウでの早期消費**（A独立レビュー MINOR-1 指摘・記載対応）:
  分割画面等で arm 済み外部画面が隣接ウィンドウに表示中に本アプリへ再フォーカス
  すると、onResume でフラグが消費され、以後の通常背景化は元画面復元ではなく
  Quiet に戻る。「再フォーカス=フロー復帰」という意味論として一貫しており
  許容範囲と判断。design-v1.md 3章にも既知制限として記載済み。

## 実機確認（ラウンド7候補・pending-device-checks.md）

- ダイアログ型外部フロー（アンインストール確認・ホームロール要求）からの復帰後、
  HOME/ロック→復帰で Quiet に戻ること
- 壁紙ピッカー・ホーム設定・アクセシビリティ設定経路でも同様に復帰後のQuietが
  壊れないこと
- 外部フロー中の画面は従来どおり復帰時に復元されること（回帰なし）
