# Issue #29（一部） — UX27-01 / UX28-01 / UX28-02 の計装テスト化

依頼: `docs/handoffs/implementer-request-29-30.md`。
手動実機確認の代わりに `connectedDebugAndroidTest` の実経路テストとして追加。
`pickerSearchFiltersGridAndPickSetsTarget` と同型（実コンポーザブル＋
fakeアプリ一覧でセマンティクス経路を検証）。実装コードの変更なし。

## 追加テスト

### UX27-01 — `home/TodayPanelStatesTest.kt`（新規・3件）

`TodayPanel` に `TodayUi` を直接注入して3状態を検証:

- `weatherOnlyShowsNoUpcomingCluster`: 天気あり・予定なし —
  「これから」見出し非存在、Open-Meteo行あり、日付行・バッテリー表示
- `eventsOnlyHideWeatherAndKeepUpcoming`: 天気なし・予定あり —
  Open-Meteo非存在、「これから」が heading semantics 付きで存在、
  予定タイトル・バッテリー表示
- `missingOptionalClustersLeaveNoDebris`: 両方＋バッテリーなし —
  「これから」・Open-Meteo・バッテリー全て非存在、日付行は残る

hairline自体は drawBehind のためセマンティクスを持たないが、
各クラスタの見出し・本文ノードと同一条件ブロック内に描画されるため、
ノード非存在＝hairline/余白残骸なしの代理assertとする。

### UX28-01 — `DoSettingsDraftTest.derivedOpPickerPickSavesAndPersistsOnReopen`

派生操作エディタ経路（`pickerSlot = "actionId|opId"` の別onChange経路）:
「派生操作を追加」→ op の「起動先を変更」→ picker で「メール」選択 →
保存 → `saved.actions[0].derivedOps[0].target == App("…mail/.Main")` →
画面再生成（`key(visit)` で初期draft差替え）→ サマリーに「メール」残存。

### UX28-02 — `DoSettingsDraftTest.toolPickerPickSavesAndPersistsOnReopen`

ツールエディタ経路: `focusToolId = "calculator"`（製品の正規導線 —
`slot = "tool:$id"` でpickerを直接開く）→ picker で「メール」選択 →
保存 → `saved.tools.items["calculator"].target == App(...)` →
再表示でサマリー残存。

## 実装上の注意・試行錯誤（透明記録）

- 「再表示」は `rule.setContent` の1テスト1回制約のため、
  `key(visit)` でサブツリーを再生成する方式にした。
- UX28-02初回試行: `起動先を変更` の index 6 直叩きでは pick が
  draft に届かない挙動を示した（pickerは開くがセルclickの書込みが
  反映されず）。`focusToolId` による正規導線に切替えたところ
  pick→save→再表示まで一貫して動作。index指定はツールカードの
  表示順変化に脆いため、`focusToolId` はより製品経路に忠実でもある。
- UX27-01: 0件状態でもパネルコンテナ（日付行）は残ることを確認。

## 検証

- `compileDebugAndroidTestKotlin assembleDebug testDebugUnitTest lintDebug`:
  BUILD SUCCESSFUL — unit **302件 0失敗** / lint **0 errors / 4 warnings**
- 対象2クラス単独実行: TodayPanelStatesTest 3件 + DoSettingsDraftTest
  15件（新規2件含む）= **18件 全緑**
- `connectedDebugAndroidTest` 全量実行（qil_test / API 36, 10m6s）:
  **140件 0失敗・一回完走**（135+新規5件）
