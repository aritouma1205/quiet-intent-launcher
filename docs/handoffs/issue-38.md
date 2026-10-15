# Issue #38 — 全アプリ長押しメニューからのアンインストール

## 工程状態

- 現在工程: 完了（ユーザーのマージ判断待ち）
- PR #39: OPEN、head `801e0ed`（feat 2994e03 + docs 801e0ed）→ レビュー記録追加コミットでhead更新予定
- B検証結果レビュー: TEST READY（docs/handoffs/pr39/b-test-review.md）
- A独立PRレビュー: APPROVE・MINOR1件（docs/handoffs/pr39/a-pr-review.md）
- ベース: `origin/main` @ e391cd6、作業ブランチ `feature/issue-38-app-uninstall`
- 作業ディレクトリ: `/home/aritouma/projects/qil-issue38-uninstall`（git worktree。mainワーキングツリーはCRLF差分が残っているため隔離）
- A設計レビュー: CHANGES REQUIRED → 以下のv2仕様で全指摘を解消済み

## 背景

現行 design 9.3 は「長押しで『アプリ情報』。アンインストールはアプリ情報側のOS操作に任せる」。
ユーザー要求により直接アンインストールへ進めるようにする。**既存仕様の変更**であり、
design-v1.md 9.3・13章権限表・16.2受入条件を更新する。

## 確定仕様 v2（A設計レビュー反映後）

### 権限・起動

- `<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />` を追加する。
  protectionLevel=normal で宣言のみ（ランタイム要求なし）。**必須**: targetSdk≥P の呼び出し元に
  UninstallerActivity がこの権限を要求し、未宣言では例外を投げず即 finish する
  （AOSP UninstallerActivity で確認済み。サイレント失敗になる）。
- 起動Intentは `Intent.ACTION_DELETE` + `Uri.fromParts("package", pkg, null)` を使う。
  `ACTION_UNINSTALL_PACKAGE` は API 29 で deprecated のため使わない（受け手は同一）。
- `ActivityNotFoundException` 時は `nav` の外部フローフラグをロールバックし、
  Toast「アンインストールを開始できませんでした」を出す。ロールバック手段として
  `HomeNavigation` に `cancelExternalFlow()` を追加し、既存のアプリ情報経路
  （同じ残留バグを持つ）にも適用する。
- 結果コールバックは不要: 削除検知は `LauncherApps.Callback.onPackageRemoved` が担う
  （起動元を問わず発火。EXTRA_RETURN_RESULT・startActivityForResult は使わない）。
- `queries` 宣言は不要（startActivity はパッケージ可視性フィルタの対象外）。

### AppEntry.canUninstall

- `AppEntry` に `canUninstall: Boolean = false` を追加（永続化対象外、既存コンストラ互換）。
- 判定を AppCatalog 内の1関数に集約し、`query()` と `resolveApp()` の両生成経路で適用する
  （「最近」セクション行は resolveApp 経由で来るため、片方だけだと不整合になる）。
- 条件: `applicationInfo != null` かつ `packageName != 自パッケージ`（AppCatalog が
  `context.packageName` を保持して除外）かつ
  `flags & FLAG_SYSTEM == 0` または `flags & FLAG_UPDATED_SYSTEM_APP != 0`。
  `applicationInfo == null` は安全側 false。
- テスト制御: AppEntry のコンストラクタ引数で直接指定可能（`replaceAppsForTest` 経由の
  fixture がそのまま使える）。
- 備考: canUninstall は表示可否の近似。device admin / `setUninstallBlocked` / プロファイル
  管理下など OS 側の拒否があり得る。その場合は OS の案内・トーストに委ねる。
- `FLAG_UPDATED_SYSTEM_APP` のアプリでは OS が「更新の削除＝工場版へ戻す」動作になり、
  パッケージ自体は残る。復帰後は FLAG_SYSTEM のみの工場版エントリが残り、
  アンインストール行は以後表示されない。

### UI

- 長押しダイアログ: title=アプリ名、本文先頭にパッケージ名行（既存テスト assert 維持）＋
  操作行「アプリ情報」「アンインストール（canUninstall=true のときのみ）」を縦並びで表示、
  dismiss=「戻る」は維持。外側タップでのキャンセルも維持。
- 「アプリ情報」タップ: ダイアログを閉じてから遷移（従来どおりの非対称でよい。
  アンインストールはOS確認を挟むため、復帰・一覧更新と連動して閉じる）。
- 「アンインストール」タップ: `beginExternalFlow()` → startActivity。復帰時または一覧更新時に
  対象の packageName が一覧に存在しなければダイアログを閉じる（照合は packageName 単位、
  catalog の削除反映と同じ粒度）。
- a11y: セルの `combinedClickable` に `onLongClickLabel = R.string.do_action_menu`
  （既存「操作メニューを開く」を流用）を追加。
- 長押しの配線は AllAppsScreen 側の `combinedClickable` が供給し、「最近」を含む
  全セクション共通の items ループに適用される（AppGridCell ではない）。

### 設計書の更新（design-v1.md）

- 9.3: 「アンインストールはアプリ情報側のOS操作に任せる」を本仕様に書き換え。
- 13章権限表: REQUEST_DELETE_PACKAGES（宣言のみ・全アプリのアンインストール導線用）を追加。
- 16.2受入条件表: 全アプリ長押し→アンインストールの行を追加。
- 3章 外部フロー復帰の例示にアンインストール確認を追加（任意・軽微）。

## 却下した代替案

- 長押し即アンインストール確認: 誤操作時の確認段が薄い・既存ダイアログとの二重経路になるため不採用。
- ModalBottomSheet/ポップアップメニュー（Pixel風）: 現行AlertDialogの最小拡張で要件を満たせるため不採用。操作増加時の候補として記録。
- 検索結果アプリ行への長押しメニュー: 検索行は現状タップのみ。範囲外（将来候補）。

## 受入条件（v2）

1. 全アプリでユーザーアプリを長押し→「アンインストール」行が表示され、タップでOSのアンインストール確認へ進む
2. システムアプリ・ランチャー自身の長押しでは「アンインストール」が表示されない
3. 「アプリ情報」は従来どおり動作する
4. FLAG_SYSTEM を持たない対象のアンインストール完了後、一覧から対象が消えている。
   更新済みシステムアプリは工場版が残り、以後アンインストール行が出ない
5. TalkBackで長押し操作が読み上げられる（onLongClickLabel）

## 要確認事項（実機）

- UninstallerActivity の表示形態（ダイアログ型か）と復帰時の画面状態
- OS側拒否（device admin / uninstallBlocked）時の実機挙動

## 実装サマリ

- `AndroidManifest.xml`: `REQUEST_DELETE_PACKAGES` を既存 uses-permission
  ブロックに追加（design 13 参照の日本語コメント付き）。
- `strings.xml`: `all_apps_uninstall`（アンインストール）、
  `all_apps_uninstall_failed`（アンインストールを開始できませんでした）を追加。
- `AppCatalog.kt`: `AppEntry.canUninstall: Boolean = false` 追加。
  判定を `canUninstallApp(flags, packageName, ownPackageName)` に集約し
  `query()` / `resolveApp()` の双方で適用。`ownPackageName` をコンストラクタ
  シーム（既定 `context.packageName`）として追加。
- `HomeNavigation.kt`: `cancelExternalFlow()` 追加（外部フローフラグの巻戻し）。
- `AllAppsScreen.kt`: 長押しダイアログを操作行リスト化。「アプリ情報」は
  閉じてからコールバック、「アンインストール」は閉じずにコールバック
  （`canUninstall` のときのみ表示）。対象パッケージ名が一覧（apps+recents）
  から外れた時点で自動的に閉じる。セルの `combinedClickable` に
  `onLongClickLabel = do_action_menu` を追加。
- `HomeUi.kt` / `MainActivity.kt`: `onUninstallApp` 配線。`openUninstall`
  は `ACTION_DELETE` + `package:` URI。`launchExternalOrToast` に集約し、
  `ActivityNotFoundException` で `cancelExternalFlow()` + Toast。
  `openAppInfo` も同経路に統一。

## 検証結果

対象: `feature/issue-38-app-uninstall` head（`2994e03`、PR #39）、emulator-5556
（ops_emu AVD / API 36）。証跡一式は `docs/handoffs/pr39/evidence/` にコミット済み。

| ID | 確認内容（期待） | 実測 | 結果 | 実行方法 | 証跡 |
| --- | --- | --- | --- | --- | --- |
| I38-T01 | flags=null → canUninstall=false（安全側） | false | PASS | 単体 | `AppCatalogTest.null flags stay on the safe side` |
| I38-T02 | ユーザーアプリ（FLAG_SYSTEM=0）→ true | true | PASS | 単体 | `AppCatalogTest.plain user app can be uninstalled` |
| I38-T03 | 自パッケージ → false | false | PASS | 単体 | `AppCatalogTest.own package is never offered` |
| I38-T04 | FLAG_SYSTEM → false | false | PASS | 単体 | `AppCatalogTest.system app cannot be uninstalled` |
| I38-T05 | FLAG_UPDATED_SYSTEM_APP → true | true | PASS | 単体 | `AppCatalogTest.updated system app counts as uninstallable` |
| I38-T06 | 無関係フラグ併存のユーザーアプリ → true | true | PASS | 単体 | `AppCatalogTest.unrelated flags keep a user app uninstallable` |
| I38-T07 | `AppEntry` 既定 `canUninstall=false` | 既定false | PASS | 計装 | `AppCatalogIntegrationTest.appEntryDefaultsCanUninstallToFalse` |
| I38-T08 | `query()` が実 ApplicationInfo.flags を反映 | 実カタログで一致 | PASS | 計装 | `AppCatalogIntegrationTest.catalogEntriesCarryRealUninstallFlags` |
| I38-T09 | `resolveApp()` が同一規則を適用（自アプリ=false） | 一致 | PASS | 計装 | `AppCatalogIntegrationTest.resolveAppAppliesTheSameRule` |
| I38-T10 | canUninstall行で操作行が pkg→アプリ情報→アンインストールの順 | 行順一致 | PASS | 計装 | `AllAppsIntegrationTest.uninstallableAppDialogShowsAllActionsInOrder` |
| I38-T11 | システム相当・自アプリでアンインストール行なし | 行0件 | PASS | 計装 | `AllAppsIntegrationTest.uninstallRowHiddenForProtectedEntries` |
| I38-T12 | アンインストールtap→コールバック受領・All Apps維持・ダイアログ残留・onBackgrounded後も保持 | 全項目一致 | PASS | 計装 | `AllAppsIntegrationTest.uninstallTapInvokesCallbackAndKeepsScreen` |
| I38-T13 | 同pkg別componentでは残留、pkg消失で自動クローズ | 両条件一致 | PASS | 計装 | `AllAppsIntegrationTest.dialogFollowsDisplayedPackage` |
| I38-T14 | 「最近」行の長押しで同一ダイアログ | 同一ダイアログ | PASS | 計装 | `AllAppsIntegrationTest.recentSectionLongPressShowsSameDialog` |
| I38-T15 | セルの長押しラベル = `do_action_menu` | ラベル一致 | PASS | 計装 | `AllAppsIntegrationTest.cellExposesLongClickLabel` |
| I38-T16 | 「アンインストール」で ACTION_DELETE + package:pkg Intent が発射 | monitor hit=1 | PASS | 計装 | `UninstallFlowIntegrationTest.uninstallRowFiresActionDeleteIntent` |
| I38-T17 | manifest が REQUEST_DELETE_PACKAGES を宣言 | 宣言あり | PASS | 計装 | `UninstallFlowIntegrationTest.manifestDeclaresRequestDeletePackages` |
| I38-T18 | 未解決Intentで外部フロー巻戻し＋onBackgrounded→Quiet | Quietへ遷移 | PASS | 計装＋単体 | `UninstallFlowIntegrationTest.failedExternalLaunchRollsBackArmedFlow`、`HomeNavigationTest.cancel external flow …` 2件 |
| I38-T30 | 実OS確認の起動（block=false）→BACKでAll Apps保持・ダイアログ閉鎖 | hits=1・OS確認表示・復帰後ダイアログ非表示 | PASS | 計装 | `UninstallFlowIntegrationTest.osUninstallConfirmationReturnKeepsAllApps` |
| I38-T31 | 「戻る」でダイアログのみ閉じる | 閉鎖・画面不変 | PASS | 計装 | `AllAppsIntegrationTest.dialogBackDismissesAndKeepsScreen` |

JUnit XML: `app/build/test-results/testDebugUnitTest/TEST-*.xml`（単体）。
計装ログ: `app/build/outputs/androidTest-results/connected/debug/ops_emu(AVD) - 16/logcat-<Class>-<method>.txt`。

### テスト計画との差分（記録）

- 真理値表（T01〜T06）は JVM 単体テスト `AppCatalogTest` に配置。
  `ComponentName`/`UserHandle` の Android 実クラス構築を要する実カタログ経路は
  計装の `AppCatalogIntegrationTest`（T07〜T09）で検証 — 必須カバレッジの縮小なし。
- T16 は `waitForActivityWithTimeout` ではなく `monitor.hits` を判定根拠にした。
  block=true の monitor は Activity を生成しないため API 36 では同メソッドが常に
  null を返す（Intent 傍受自体は hits=1 で確認済み）。

### 試行錯誤（透明記録）

- `uninstallRowFiresActionDeleteIntent` 初回失敗: `waitForActivityWithTimeout`
  が null を返した。`monitor.hits=1` を確認し、傍受は成立していると判明。
  API 36 の同メソッドは生成済み Activity のみを対象とするため hits 判定へ変更。
- `AppCatalogIntegrationTest` の `runBlocking { Log.i(...) }` が Int 返却で
  JUnit4 に test メソッドとして認識されず — ブロック本体を Unit 返しに修正。

### 検証コマンド

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`: BUILD SUCCESSFUL
- `testDebugUnitTest`: **310件 0失敗**（`app/build/test-results/testDebugUnitTest/TEST-*.xml`）
- `lintDebug`: **0 errors / 7 warnings**（`app/build/reports/lint-results-debug.*`）。
  ベースライン4件（EmptySuperCall・ObsoleteSdkInt・UnusedResources・版数通知系）に
  加え、upstream 側の新版公開で Gradle 9.8.1・core-ktx 1.19.1・Kotlin 2.4.21 の
  版数通知が3件増えたもの。いずれも本差分のファイル外で、本変更起因の警告はない。
- `connectedDebugAndroidTest`（emulator-5556）: **159件 0失敗**
  （`app/build/outputs/androidTest-results/connected/debug/TEST-ops_emu(AVD) - 16.xml`、
  UninstallFlowIntegrationTest 4/4・AllAppsIntegrationTest 15/15・
  AppCatalogIntegrationTest 3/3 を含む）

## 実機確認（ラウンド6候補・pending-device-checks.md）

- 実アプリで「アンインストール」→OS確認の表示形態と「削除する」完走
- 削除完了時に一覧から対象が消えダイアログが閉じる挙動
- 更新済みシステムアプリで「更新のアンインストール（工場版へ戻す）」表記確認
- device admin / uninstallBlocked 対象でのOS側拒否挙動
- TalkBackでの長押し読み上げ（操作メニューを開く）と操作行の到達
