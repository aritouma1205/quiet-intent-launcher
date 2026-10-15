# Issue #41 — バックグラウンド退出時の未保存ドラフトを確定的に破棄する

## 工程状態

- 現在工程: 実装・検証完了（レビュー・ユーザーのマージ判断待ち）
- PR #44: OPEN、head `118b52f`、base `main`、**Depends on PR #39**
- 作業ブランチ: `feature/issue-41-draft-discard`（feature/issue-38 先端 8f47354 基点）
- 作業ディレクトリ: `/home/aritouma/projects/qil-issue41-draft`（git worktree）

## 確定仕様

ナビゲーションのトップから外れた時点で未保存ドラフトは確定的に破棄する
（design 3 の拡張）。

- 回転等の構成変更ではドラフト保持（対側契約）。
- アプリ自身が開いたOSフロー往復（externalFlow armed 中）ではドラフト保持。
- `UnsavedChangesDialog` の確認範囲は不変（Back/キャンセル操作時のみの確認を維持）。
- design-v1.md 3章・11.2 に破棄ルールを明記（受入条件4）。

## 実装判断（記録用）

### 採用: 世代キー＋`key()` 一括ラップ

1. **`HomeNavigation.draftEpoch`**: `publish()`（navigateTo/back/resetToQuiet/
   onHomeInvoked の全経路が集約する既存1点）ごとに単調増加する
   `StateFlow<Long>`。トップから外れた画面の複合キーは二度と生成されない
   ことを保証する。`back()` がルートで何もしない場合・externalFlow の
   arm/disarm/return では publish 不発のため世代不変（armed 中のドラフト
   保持はここから導かれる）。
2. **`HomeViewModel.sessionId`**: `UUID.randomUUID()` の公開プロパティ。
   VM は構成変更を跨ぐため id も構成変更を跨ぐ。プロセス kill で VM が
   新生すると epoch が 0 に戻るため、kill 前にバンドルへ保存された複合
   キーとの世代衝突でゾンビドラフトが蘇り得る — sessionId がそれを防ぐ
   nonce。
3. **`HomeUi`**: `else -> when (screen)` 全体を
   `key(draftEpoch, viewModel.sessionId)` でラップ。`rememberSaveable`
   の自動キーは compositeKeyHash 由来のため key() が変わると保存キーも
   変わり、旧キーの値は二度と consume されず実質破棄となる。when 全体を
   囲むので将来の saveable 追加にも規則が継承される。

### 画面引数方式を採らなかった理由

各設定画面 composable に世代相当の引数を渡して個別差替えする方式は、
画面ごとの call-site 変更が必要で、将来 saveable を持つ画面追加時に
配線漏れの余地が残る。`key()` 一括ラップは差分最小・規則の網羅性が高い。
I41-T02〜T05（個別画面の差替え検証）は T07（E2E・実nav経由）と
T08（実Activity recreate）で担保すると判断した。

### 副次影響（仕様として受容）

- `SearchScreen.visibleCount`・TargetPicker/検索クエリ・IntroScreen の
  step/draft も再訪時に初期化される（現行は復元。visit 単位の初期化は
  仕様として正当と判断）。
- ナビゲーション発行と同時にオーバーレイが閉じる経路（起動成功・
  HOME・バックグラウンド）では、パネルの close アニメーションが世代
  更新による再生成で即時終了に置き換わる。いずれも画面離脱・背景化と
  同時のため体感影響は軽微。design 7 のスクリーンショット handshake は
  `overlays.clear()` のみで publish を伴わず影響なし。

## 実装サマリ

- `HomeNavigation.kt`: `draftEpoch` 追加（`publish()` で +1）。クラス KDoc
  に破棄ルールを追記。
- `HomeViewModel.kt`: `sessionId`（UUID nonce）追加。
- `HomeUi.kt`: `draftEpoch` を collect、`else ->` を
  `key(draftEpoch, viewModel.sessionId) { when (screen) { ... } }` でラップ。
- `docs/design-v1.md`: 3章に破棄ルール・保持経路・確認範囲の限定を追記、
  11.2 に下書き寿命（訪問単位）を追記。

## 検証結果

対象: `feature/issue-41-draft-discard` head `118b52f`（PR #44）、
emulator-5556（ops_emu AVD / API 36）。証跡一式は
`docs/handoffs/pr44/evidence/` にコミット済み。

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I41-T01 | navigateTo/back/resetToQuiet/onHomeInvoked で世代が単調増加、非publish操作で不変、ルートback不変、onBackgrounded→Quietで世代増加 | 全条件一致 | PASS | 単体 | `HomeNavigationTest` 4件 |
| I41-T07a | EdgeSettings dirty→戻る→「破棄する」→再navigateTo→初期値表示・確認ダイアログ非表示 | 初期値・ダイアログ0件 | PASS | 計装 | `StackDraftDiscardTest.edgeDraftIsGoneAfterDiscardExitAndRevisit` |
| I41-T07b | EdgeSettings dirty→`viewModel.onBackgrounded()`→Quiet→再訪→初期値 | 初期値 | PASS | 計装 | `StackDraftDiscardTest.edgeDraftIsGoneAfterBackgrounding` |
| I41-T07c | DoSettings 名前変更→back→破棄→再訪→保存値表示 | 保存名表示 | PASS | 計装 | `StackDraftDiscardTest.doDraftIsGoneAfterDiscardExitAndRevisit` |
| I41-T08 | EdgeSettings dirty→`scenario.recreate()`→dirty保持（構成変更の対側契約） | dirty保持 | PASS | 計装 | `StackDraftRecreateTest.unsavedDraftSurvivesRecreation` |
| I41-T10 | DoSettings 名前変更→保存→再訪→保存値表示 | 保存値表示 | PASS | 計装 | `StackDraftDiscardTest.savedNameSurvivesRevisit` |
| I41-T06 | 既存Draft系4クラス（DoSettings/ContextSlots/EdgeSettings/SystemActions）全件PASS・StateRestorationTester経路無変更 | 0失敗 | PASS | 計装 | 回帰全量に含む |

JUnit XML: `app/build/test-results/testDebugUnitTest/TEST-*.xml`（単体）。
計装結果: `docs/handoffs/pr44/evidence/instrumentation-results.xml`。

### 検証コマンド

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`: BUILD SUCCESSFUL
- `testDebugUnitTest`: **314件 0失敗**（I41-T01 追加4件含む）
- `lintDebug`: **0 errors / 7 warnings**。全件ベースライン
  （EmptySuperCall・ObsoleteSdkInt・UnusedResources）と版数通知系で、
  本差分起因の警告なし。
- `connectedDebugAndroidTest`（emulator-5556、`flock` 直列化）:
  **164件 0失敗**（ベースライン159 + 新規5件）

### テスト計画との差分（記録）

- I41-T07a の「`nav.back()`→「破棄する」」は、プログラム的な `nav.back()`
  では `UnsavedChangesDialog` を経由しないため、画面内「戻る」ボタン→
  確認ダイアログ→「破棄する」の UI 経路で実施（仕様文意どおり確認
  ダイアログの存在を含む流れ）。
- I41-T08 は `createAndroidComposeRule<MainActivity>` +
  `activityRule.scenario.recreate()` で実施。VM・epoch・sessionId が
  recreate を跨ぐ本番経路で対側契約（構成変更での保持）を検証。

## 実機確認（ラウンド7候補・pending-device-checks.md）

- 編集中のHOME/回転/別画面遷移で「意図せず残らない」「意図せず消えない」体感確認。

## 残留意点

- 起動成功/HOME/背景化と同時のパネル close アニメーションが即時終了に
  なる副次影響（上記受容済み）。体感が気になる場合は世代適用範囲を
  `when` 内の非Quiet分岐に絞る改訂が可能（その場合 Quiet の saveable 化を
  将来別途検討）。
