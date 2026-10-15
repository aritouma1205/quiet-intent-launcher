# PR #39 A 独立PRレビュー記録

- 対象: base `main` @ e391cd6 / head `801e0ed`
- レビュアー: 新規Aサブエージェント（設計レビュー会話は非継承、確定資料と差分のみ）
- 投稿: PR #39 へ review comment として投稿済み（同一アカウントのため COMMENT 判定）

## 結論: APPROVE

受入条件1-5は実装・配線・テストの実コードで確認。BLOCKER/MAJOR なし。

## AC判定（要約）

- AC1 合格: AllAppsScreen.kt:184-193 → HomeUi.kt:281-284（beginExternalFlow先行）→ MainActivity.kt:192-200（ACTION_DELETE + package:URI）。T16/T30が実検証。
- AC2 合格: canUninstallApp（AppCatalog.kt:48-57）の判定式・両経路適用・自己除外を確認。Kotlin infix `and` の優先度も検証済み。
- AC3 合格: アプリ情報は従来どおり（閉じてから遷移）。失敗時ロールバックが新たに効く（既存バグ改善）。
- AC4 ほぼ合格: 削除→onPackageRemoved→refreshPackage→グリッド消失、detailStillListed（pkg照合）でダイアログ自動閉鎖、recentRowsもresolveApp nullで行消失。MINOR指摘1を参照。
- AC5 合格: onLongClickLabel=do_action_menu、T15がSemanticsActions.OnLongClick.labelを直接検証。

## 指摘（1件・MINOR）

更新済みシステムアプリの工場版復帰後、開きっぱなしのダイアログ内に stale な「アンインストール」行が残る（detailTargetがスナップショットのため）。再タップ時は純粋FLAG_SYSTEM pkgへACTION_DELETEが飛ぶがOS側が拒否/案内するため実害はOS許容範囲。次回長押しでは正しく非表示。対処案: 行表示を最新一覧エントリから導出。実機確認（ラウンド6 T43）で観測可能性あり。マージ阻止しない。

## 確認済み（指摘なし）

- ロールバック配線: launchExternalOrToast（MainActivity.kt:167-174）がANFE→cancelExternalFlow+Toast。openAppInfoも同関数に統一済み
- フラグ遷移: arm→onStop保持→復帰onStartでクリア、HOMEはonHomeInvoked。design 3と整合
- スコープ外既存バグ（情報共有のみ）: openWallpaperPicker/requestHomeRole/openAccessibilitySettingsはarm後失敗でrollbackなし（main同一）
- Manifest: REQUEST_DELETE_PACKAGES宣言のみ・回帰なし。queries不要はT30実起動で裏付け
- design-v1.md: 3章:71・9.3:240・13章:314・16.2:433（A19）すべて仕様どおり
- テスト実効性: 形だけのテストなし。証跡XML（計装159件0失敗）とhandoff一致
- 差分範囲: 宣言された変更以外の差分なし

## 未確認範囲

- 最近セクション経由の canUninstall=true 実描画（実APK要、機構共通のため低リスク）
- 実機項目は pending-device-checks ラウンド6に列挙済み
