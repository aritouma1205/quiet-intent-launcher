# PR #44 A 独立レビュー記録（Issue #41）

- 対象: https://github.com/aritouma1205/quiet-intent-launcher/pull/44
- base: `8f47354`（feature/issue-38-app-uninstall 先端、Depends on PR #39）
- head（レビュー時点）: `074b6e6`（実装 `118b52f` + docs）
- 結果: **APPROVE**（GitHub comment 投稿済み https://github.com/aritouma1205/quiet-intent-launcher/pull/44#issuecomment-6197518232）

## 受入条件判定
1. トップから外れた時点で未保存ドラフトを確定的に破棄 — PASS（`publish()` が唯一の集約点、`key(draftEpoch, sessionId)` が when 全体をラップ、全 rememberSaveable が自動キー）
2. 構成変更では保持 — PASS（VM保有の epoch/sessionId、StackDraftRecreateTest で実証）
3. externalFlow armed中は保持 — PASS（arm/disarm/return は publish 不発）
4. 確認ダイアログ範囲不変 — PASS（UnsavedChangesDialog・requestExit 無変更）
5. design-v1.md 追記 — PASS（§3, §11.2）

## 指摘
- MINOR-1: `pendingScreenshot` がHOME競合で残留 → `onHomeInvoked()` に `pendingScreenshot.set(false)` 追加で対応済み（commit `4f7c3be`、回帰テスト `anArmedScreenshotDiesOnHomeInvoke` 追加）
- MINOR-2（情報）: 破棄済み下書きの savedState エントリがバンドル内で単調増加 → design-v1.md 11.2 に既知限界として明記済み

## 未確認範囲
- armed外部フロー中のドラフト保持はUI計装未カバー（unit+機構論理で担保）。実機ラウンド7で確認予定。
