# Issue #42 — Store層の正確性: CancellationExceptionを保存失敗として飲まない・open前の書込みを落とさない

対象: `settings/SettingsStore.kt`、`recent/RecentStore.kt`、`weather/WeatherCache.kt`
（`WeatherCacheStore` は `weather/WeatherCache.kt` 内）

## 確定仕様と実装

### A. CancellationException の伝播

`kotlinx.coroutines.CancellationException` は `IllegalStateException` 派生のため、
`catch (e: IllegalStateException)` / `is IllegalStateException` がキャンセルを
「書き込み失敗」として飲み込んでいた。各 catch 節の**先頭**に
`catch (e: CancellationException) { throw e }`（`when` 内では
`is CancellationException -> throw e`）を追加。

| ファイル | 箇所 | 対応 |
| --- | --- | --- |
| SettingsStore.kt | `update()` の try/catch | CE先行catch追加（degrade回避） |
| SettingsStore.kt | `openDataStore()` の factory try/catch | CE先行catch追加（degrade回避）※下記「実装判断」 |
| SettingsStore.kt | `store.data.catch {}` の `when` | 先頭に `is CancellationException -> throw e` |
| RecentStore.kt | `record()` / `prune()` / `clear()` | CE先行catch追加 |
| RecentStore.kt | `openDataStore()` の factory try/catch | CE先行catch + `signal.complete(null)` |
| RecentStore.kt | `store.data.catch {}` の `when` | 先頭に `is CancellationException -> throw e` |
| RecentStore.kt | collect内の書き戻し try/catch | CE先行catch追加 |
| WeatherCache.kt | `save()` / `clear()` | CE先行catch追加 |
| WeatherCache.kt | `openDataStore()` の factory try/catch | CE先行catch + `signal.complete(null)` |
| WeatherCache.kt | `store.data.catch {}` の `when` | 先頭に `is CancellationException -> throw e` |

### B. open 前書込みの待機（RecentStore / WeatherCacheStore）

`dataStore ?: return false` で open 前の書込みを静かに落としていた問題を、
`CompletableDeferred<DataStore<T>?>` の `openSignal` で open 完了を公開する方式に変更。

- `start()` が `openSignal` を新規生成してから collect ジョブを起動。
  `openDataStore(signal)` は open 成功で `signal.complete(store)`、
  factory 失敗で `signal.complete(null)`。
- `start()` 内 launch を try/finally で囲み、ジョブが open 経路に到達する前に
  終わった場合（別の `start()` で置き換えられた等）も
  `signal.complete(dataStore)` で待機中の writer を必ず解放する。
- `record()` / `prune()` / `clear()` / `save()` は `awaitStore()`
  （`dataStore ?: openSignal?.await()`）を経由して書き込む。
- `dataStoreFactory` の型を `suspend (CoroutineScope) -> DataStore<T>` に変更
  （open タイミングをテストから制御する seam。非 suspend ラムダは
  suspend 関数型に適合するため既存呼出しは無修正）。

#### 待機契約（確定仕様として明文化）

- `start()` を**一度も呼んでいない**場合: `openSignal` が null → 即 `false`。
  待機しない・hang しない。
- `start()` 済みで open 進行中: open 完了まで suspend で待ち、成功したら書き込む。
- open 失敗（factory が例外）/ open 試行が別の `start()` に置き換えられた場合:
  signal が null で完了 → 速やかに `false`。
- writer 自身が待機中にキャンセルされた場合: `await()` が CE を投げてそのまま伝播。
- `SettingsStore.update` の open 前 write は本Issue範囲外（従来どおり
  `dataStore ?: return false`）。

## 実装判断（確定仕様の列挙外・同種バグの一括修正）

- 確定仕様の A は SettingsStore の列挙を「`update()` catch と `data.catch`」と
  していたが、`openDataStore()` の factory catch（旧 `:163`）も
  `catch (IllegalStateException)` による CE → `degrade()` の誤変換経路だったため、
  同じ先行 catch を追加。追加の副作用はなく、列挙の意図
  （キャンセルを保存失敗・Degradedにしない）と一致。
- signal の完了は「open 成功=store / open失敗・試行終了=null」のみ。
  待機のタイムアウトは設けず、「必ず signal が完了する」構造
  （finally保証）で hang を防止 — Issue本文の実装者判断委託に基づく。
- RecentStoreTest の `fakeStore` seam を `failWrites: Boolean` から
  `failure: Throwable?` に一般化（CE・CorruptionException の注入用）。

## 追加/更新テスト（unit, L1）

| Test ID | 場所 | 内容 | 結果 |
| --- | --- | --- | --- |
| I42-T01 | SettingsStoreTest `a cancelled update propagates instead of degrading` | updateData が CE → 呼出側に CE 再throw・state は Ready のまま | PASS |
| I42-T01対照 | SettingsStoreTest `an update closed-store failure degrades as before` | IllegalStateException → false + Degraded（既存挙動維持） | PASS |
| I42-T02 | SettingsStoreTest `a cancelled data flow does not degrade` | data flow が CE → Ready 到達後も Degraded にならない | PASS |
| I42-T03 | RecentStoreTest `a cancelled write propagates without touching the file` | record/prune/clear で CE 再throw・entries 不変・ファイル保持 | PASS |
| I42-T03対照 | RecentStoreTest `a corruption write failure still reports false and drops the file` | CorruptionException → false + ファイル削除（分岐の区別） | PASS |
| I42-T04 | WeatherCacheStoreTest（新規）`a cancelled write propagates and keeps the snapshot` | save/clear で CE 再throw・snapshot 不変 | PASS |
| I42-T05 | RecentStoreTest `a record issued before the open finishes waits and lands` | gate付きfactory: open待ち中は pending → gate解放で true + entries 追加 | PASS |
| I42-T06 | RecentStoreTest `a failed open makes writes return false without hanging` | factory が throw → withTimeout(5s) 内に false（hang なし） | PASS |
| I42-T07 | WeatherCacheStoreTest `a save issued before the open finishes waits and lands` / `a failed open makes writes return false without hanging` / `a region mismatch keeps the stored snapshot` | save の open待ち・open失敗 false・region不一致 save が true で current 保持 | PASS |
| I42-T08 | RecentStoreTest / WeatherCacheStoreTest `writes before start return false immediately` | `start()` 未呼出 → 即 false・hang なし（契約検証） | PASS |

## 検証結果

- `compileDebugKotlin` / `assembleDebug` / `assembleDebugAndroidTest`: BUILD SUCCESSFUL
- `lintDebug`: **0 errors / 7 warnings**（すべて既存・環境起因: 依存バージョン通知、
  HomeViewModelのEmptySuperCall、未使用リソース、mipmap構成。本変更由来の新規警告なし）
- `testDebugUnitTest` 全量: **315件 0失敗**（+13件: SettingsStore 15、RecentStore 17、
  WeatherCacheStore 5、WeatherService 12）
- `connectedDebugAndroidTest` 全量: **145件 0失敗**（emulator-5556 / ops_emu(AVD)-16、
  `flock /tmp/qil-androidtest.lock` で直列化、7m20s）
- 証跡: `docs/handoffs/pr45/evidence/`（対象commit `721fa83`）
- PR: https://github.com/aritouma1205/quiet-intent-launcher/pull/45（base=main、マージ未実施）

### 既存フレークの記録

- `WeatherServiceTest.aFailureSuppressesAutoRetriesForFiveMinutes` は
  base commit（e391cd6・変更前コード）でも 6回中3回 FAIL を確認した
  **既存フレーク**（`_lastFailureAtElapsedMs` の読み取りが collect 側コルーチンの
  反映と競合するテスト側のタイミング依存）。本変更による回帰ではない。
- `RecentStoreTest.recording the same target keeps a single entry` も同様に
  `entries.first { it.size == 1 }` が stale 値を取り得る既存の競合で、
  全量実行時に1度だけ FAIL を観測（再実行で緑）。

## seam 追加の記録

- `RecentStore` / `WeatherCacheStore` の `dataStoreFactory` を suspend 化:
  `dataStoreFactory = { gate.await(); fake }` で open タイミングを制御可能に
  （I42-T05/T07）。本番の `QuietLauncherApp` 側のラムダは非 suspend のまま適合。
- `RecentStoreTest.fakeStore` に `failure: Throwable?` パラメータ追加。
- 新規 `WeatherCacheStoreTest`（`WeatherServiceTest` の fakeCacheStore パターンを土台）。

## 回帰確認の観点

- `SettingsStore.update` の既存失敗系（IOException→false、Corruption/IllegalState→degrade）は不変。
- `RecentStore` / `WeatherCacheStore` の失敗分岐（Corruption→ファイル削除、
  IOException/IllegalState→false）と region 一致ガードは不変（I42-T03対照・I42-T07cで検証）。
- `WeatherServiceTest` 12件・他全unitが緑。
