# 11 · librime 升级（1.7.3 → 1.17.0）与 12 键简拼性能

> **本地魔改总清单在 [15-librime-local-mods.md](15-librime-local-mods.md)**（5 个补丁的影响面、症状对照、调试探针、排查铁律）；本篇只讲性能补丁的来龙去脉与实测数据。

> **状态：定稿（2026-11-09）**。这一篇讲两件绑在一起的事：
> ① `rime_jni` 从 trime 仓库里**拆出来独立构建**、librime 升到 1.17.0；
> ② 升级后**踩到的最大坑**——旧库里的 12 键简拼性能补丁被弄丢，导致长输入偶发卡顿
> （实测单键 200~800ms），以及补丁的移植方法与验证数据。
>
> 相关文档：`docs/02-jni-so.md`（1.7.3 时代的 JNI 事实快照，已被本篇取代）、
> `docs/03-build.md`（App 构建）、`docs/10-twelve-key-input-design.md` §15（12 键 UI/面板演进）、
> `../rime-jni/README.md`（独立工程说明）。

---

## 0. TL;DR（先看这 6 条）

1. `librime_jni.so` **只能**由 `trime-workspace/rime-jni` 构建，**必须 Release**（Debug 下 librime 1.17 单键 ~2.4s）。
2. 构建前必须确保 `rime-jni/librime` 带 **12 键简拼性能补丁**（`rime-jni/patches/0001-12key-abbrev-perf.patch`，librime commit `c3c5969`）。**用上游原版 1.17 会偶发卡顿到 800ms。**
3. 换库后必须跑 `hkrime/script/verify-jni-symbols.sh --so app/libs/arm64-v8a/librime_jni.so`，**75 个符号全导出、0 个旧包名残留**。
4. 当前库：**6,175,752 B**，sha256 `982af48d9e1a4e0dbb93541682da060e2ad97808f6b8e3d9f2d2d982aba2665d`（2026-11-09 05:51，含性能补丁）。
5. 只改 Java/主题不用重编 `.so`；但装 APK 后必须重做 `appops MANAGE_EXTERNAL_STORAGE allow` 与 `ime set`。
6. 判断「按键卡顿」先看 `process_key` 的 native 耗时（`onkey() keycode=X, mask=0` → `process_key result=` 的时间差），而不是先怀疑 UI/快照构建。

---

## 1. 为什么要拆出来

- **旧链路**：`hkrime/script/build-jni-so.sh` 会 clone `osfans/trime v3.2.7` 到 `jni-build/`，再从其
  `app/src/main/jni/` 里构建 `rime_jni`。版本被 trime 仓库钉死（librime 1.7.3 / 2022-06-23）。
- **需求变化**：
  - 雾凇拼音（rime-ice）要求 `librime > 1.11.2`（`enable_word_completion` 等），且要用
    `lua_translator@*xxx` 这类 Lua 组件；
  - 12 键简拼在长输入下需要**词库/词典遍历上的性能补丁**（见 §4），这些补丁本来打在
    trime 仓库内部那份 librime 源码上，属于**易失资产**；
  - 希望自定义（更多插件、更新 librime）时不被 trime 版本拖着走。
- **结论**：`rime_jni` 独立成 `rime-jni` 工程，librime 与插件版本由我们自己 pin，补丁以
  `patches/*.patch` + 独立 git 仓库双保险保存。

---

## 2. 工程结构（`trime-workspace/rime-jni`）

```
rime-jni/
├── CMakeLists.txt          # 顶层：CXX17 / BUILD_SHARED_LIBS=OFF / ENABLE_LOGGING=OFF，add_subdirectory 顺序见下
├── cmake/                  # 复用的 trime CMake 模块（FindBoost/FindIconv/RimePlugins…）+ 新工程适配
├── glue/                   # JNI 胶水（= 旧 librime_jni，包名已固定 com.hazukieq.hkrime）
│   ├── rime_jni.cc / levers.cc / opencc.cc / key_table.cc / jni-utils.h
│   ├── rime_api_compat.h   # 生成：59 个 static inline 把 1.11+ 的 RimeApi 成员包回旧全局 C API
│   └── rime_compat.cc      # 重写 trime 扩展 RimeGetCompositionSegments（1.17 已删该 API）
├── librime/                # clone 1.17.0（独立 git，含性能补丁 commit c3c5969）
├── plugins/                # librime-lua / librime-charcode / librime-octagram
├── third_party/            # boost 1.79 / capnproto / libiconv / OpenCC / snappy（旧构建资产直接复用）
├── patches/0001-12key-abbrev-perf.patch
└── script/{setup-deps.sh,build.sh}
```

**add_subdirectory 顺序**（错顺序会撞 target 名或拿不到 Boost 头）：

`third_party/boost` → `third_party/capnproto` → `third_party/snappy` → `librime/deps/leveldb` →
`librime/deps/yaml-cpp` → `librime/deps/opencc` → `librime` → `glue`

**版本 pin（2026-11-09）**

| 组件 | 版本 / commit | 说明 |
|---|---|---|
| librime | 1.17.0 tag + 本地 commit `c3c5969` | `set(rime_version 1.17.0)`，CXX17 |
| librime-lua | `6da5d6c`（2026-10-10） | 需要其 `thirdparty` 分支的 lua5.4 |
| librime-charcode | `55e7f56`（2024-03-19） | |
| librime-octagram | `57d18b9`（2026-08-31） | **必须** `github.com/lotem/librime-octagram`（rime 组织下 404） |
| NDK | `24.0.8215888`（clang 14.0.1） | |
| CMake / Ninja | 3.22.1 / 1.10.2 | SDK 自带 |
| 目标 | `arm64-v8a`，`android-21`，**Release** | |

---

## 3. 构建、校验、装机（一条链）

```bash
# 1) 首次/依赖变更：拉 librime + 插件，复制 third_party，应用补丁（见 setup-deps.sh）
cd trime-workspace/rime-jni && ./script/setup-deps.sh

# 2) 构建（默认 Release，自动装到 hkrime/app/libs/<abi>/ 并写 .sha256）
./script/build.sh                     # ≈3~5 分钟；产物 build-arm64/glue/librime_jni.so → strip 后 ~6.2MB

# 3) 符号契约（必须 75/75、0 旧包名）
cd ../hkrime && ./script/verify-jni-symbols.sh --so app/libs/arm64-v8a/librime_jni.so

# 4) App
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow   # 每次重装都要做
adb shell ime set com.hazukieq.hkrime/.TrimeImeService
```

产物事实：未 strip 的 `build-arm64/glue/librime_jni.so` ≈ 92.8MB，`llvm-strip --strip-unneeded` 后
6,175,752 B；`NEEDED` 只有 `liblog/libz/libm/libdl/libc`（`c++_static`，不需要打包 `libc++_shared.so`）。

---

## 4. 三处关键补丁（缺一不可）

> 为什么重要：librime 1.17 的 `ScriptTranslator` 在**简拼（abbrev）+ 长输入**时，用户词库 DFS 与
> 词典 `Table::Query` 的 BFS 会指数级展开。实测用户手打 289 键中 40 键 >200ms、最慢 804ms，
> UI 明显卡顿；打补丁后中位 2~4ms、无 >150ms。

### 4.1 `src/rime/dict/table.cc::Table::Query`（词典遍历预算）

- `kMaxChunksPerEnd = 48`：同一个 `end_pos` 最多收 48 条 chunk（词典本身按词频排序，取前 48 已足够）。
- `kMaxExpansions = 12000`：单次 Query 的 `Access`+`Advance` 总预算，超了直接 break（保留已收结果）。
- 每个位置的音节分支先 `query.Access(syll_id)` 预取 `TableAccessor`，按 `remaining()` 降序排序后展开
  （`remaining()` 大 = 该音节开头词条多 = 高价值分支先走），同权按 `SyllableId` 保证结果确定。

### 4.2 `src/rime/gear/script_translator.cc::ScriptTranslation::Evaluate`（简拼跳过用户词库）

- 先扫 `syllable_graph.edges` 判断**是否用了缩写**：1.17 的结构是三层 map
  `map<start, map<end, map<SyllableId, EdgeProperties>>>`，判断条件是 `EdgeProperties.type == kAbbreviation`
  （写成 `kt.type` 会编译失败：`no member named 'type' in 'std::pair<const int, rime::EdgeProperties>'`）。
- `user_dict->Lookup` 策略：
  - 非简拼 → 照旧 `kUnlimitedDepth`（完整拼音输入不受影响）；
  - 简拼且 `interpreted_length <= 2` → `depth_limit = 2`（保留短码选词的记忆效果）；
  - 简拼且长输入 → **跳过用户词库**（主词典照常查，简拼词仍能命中）。
- 句子装配：要求 `!has_abbrev` 且音节数 `<= 12`（`vertices.size() > 12+1` 视为太长），避免长输入再爆一次。

### 4.3 `glue/rime_compat.cc::RimeGetCompositionSegments`（API 重写）

1.11+ 删掉了 trime 当年加的这个导出版本，但 App 的 `TwelveKeyPreedit`/分段展示还在用。
现在用 librime 内部 API 重写：`an<Session> session(Service::instance().GetSession(id))` →
`ctx->composition()` 迭代 `Segment`，填 `starts/ends/statuses`。**不需要**改 librime 的 `rime_api.h`。

补丁保存形式：`rime-jni/patches/0001-12key-abbrev-perf.patch`（`git am` 可直接应用），
同时 `rime-jni/librime` 里已有对应 commit `c3c5969`。

---

## 5. 性能数据（真机 vivo V2520A，1080×2376，480dpi）

测试输入：`kai ciak ji fu cu` 的 12 键按键序列
`kl abc ghi abc ghi abc kl zj ghi def vur abc vur`（= `5aiaia58ie3a35aiaia58ie3a3`）。

| 指标 | 旧库 1.7.3（带补丁） | 1.17 上游原版 | 1.17 + 移植补丁 |
|---|---|---|---|
| process_key 中位 | 4ms | 4ms | 2ms |
| >200ms 的按键 | 0 / 20 | **40 / 289** | 0 / 52 |
| 最慢一鍵 | 12ms | **778~804ms** | 10ms |

**simpleperf**（`simpleperf record --app com.hazukieq.hkrime -f 400 -g --duration 90`，10,767 samples）
在「上游原版」上录得：`librime_jni.so` 占 **50.94%**，热点为
`basic_string::append` 5.4% / `leveldb::InternalKeyComparator::Compare` 4.5% /
`leveldb::Block::Iter::Seek` 4.1% / `__memcmp_aarch64` 4.1% / `ParseNextKey` 3.8% /
`snappy::DecompressBranchless` 3.6% / `TableQuery::Access` 2.4% / `UserDictionary::DfsLookup` 2.1%。
→ 完全对应 §4.1/§4.2 治理的两条路径（用户词库 leveldb DFS、主词典 Table BFS）。

> 经验：**注入式连点复现不出 spike**（adb input tap 的节奏恰好避开），必须用真实手打 + logcat
> 抓 `process_key` 时间差，或用 simpleperf 看 native 热点。

---

## 6. 重建/升级检查清单（照做，别跳步）

1. [ ] `rime-jni/librime` 是否在带补丁的 commit？（`git log -1 --grep=12key` 或检查 `table.cc` 有 `kMaxExpansions`）
2. [ ] `build.sh` 默认 `--build-type Release`；不要用 Debug 出正式库。
3. [ ] `setup-deps.sh` 拉的插件 commit 是否符合 §2 表（octagram 必须 lotem 源）。
4. [ ] `verify-jni-symbols.sh` 75/75、0 旧包名。
5. [ ] App 侧 `Rime.java` 的 `isEngineReady()` 等 NPE 防护仍在（后台维护期间会无 session）。
6. [ ] 装机后 `appops` + `ime set`；冷启动确认 `Rime.check(false): start_maintenance=false` 或后台维护，
       UI 不冻结。
7. [ ] 真机输入验证：12 键 `nzia` 出候选 → `▼` 面板正常；全字母方案（客语26键/朙月/雾凇）候选与面板正常；
       长输入（上面那条序列）无卡顿。

---

## 7. 升级过程中踩过的坑（排错索引）

| 现象 | 原因 | 处理 |
|---|---|---|
| 构建报 `marisa` target 冲突 | OpenCC 自带 `deps/marisa-0.2.6` 与 `librime/deps/marisa-trie` 重名 | 不 add `marisa-trie`，`FindMarisa` 指向 opencc 的 marisa-0.2.6 |
| `opencc/Common.hpp` 找不到 | glue 需要 `opencc/` 前缀 | `${CMAKE_BINARY_DIR}/opencc-staging/opencc` 软链到 `librime/deps/opencc/src` |
| Boost 头找不到 | librime/插件用 `#include <boost/...>` 但 include 路径没加 | 顶层 `include_directories(third_party/boost + 各 libs/*/include)`；排除 `context;coroutine;fiber;log;log_setup` |
| 一堆 `undefined reference RimeSetup/RimeProcessKey…` | 1.11+ 只剩 `RimeApi` 结构体成员 | 生成 `glue/rime_api_compat.h`（59 个 inline 转发）；`RimeFindModule` 改 `rime_get_api()->find_module` |
| `RimeGetCompositionSegments` 缺失 | 1.11+ 删除 | `glue/rime_compat.cc` 用内部 API 重写（§4.3） |
| 单键 2.4s | Debug `-O0` | `--build-type Release` |
| 长输入偶发 200~800ms | 丢 §4 补丁 | 打补丁 + 重编 |
| 启动白屏数十秒 | librime 维护在主线程 join | 维护改后台线程（App 侧 `Rime.java`，docs/10 §15.26） |
| 部署大方案（雾凇 60MB table）卡几分钟 | 全量重建 + 主线程等待 | 部署改增量（docs/10 §15.25）+ 后台维护 |
| 雾凇的日期/农历/纠错不生效 | 缺根目录 `rime.lua`（`lua_*@*name` 需要全局变量） | 随包 `assets/rime/rime.lua` + `SchemaCompat.ensureRimeLua`（docs/10 §15.28） |
| 点 ☰ 崩 / 打字崩 NPE | 后台维护期间无 session/schema | `Rime.isEngineReady()` 防护 + `getSchemaId()` 替代 `get_current_schema()` |

---

## 8. 历史时间线（这一条线的来龙去脉）

- 2026-11-08：12 键简拼 ANR 修复——在 trime 内置 librime 1.7.3 上打三处补丁（§4 的 1)+2)+3)），
  打出的库 13,624,480 B（`docs/02-jni-so.md` 记录的就是这个状态）。
- 2026-11-09：
  - 存档点 `snapshot/pre-librime-upgrade-20261109`（HEAD `1959dbb`）+ 备份
    `backups/librime_jni-1.7.3-arm64-v8a-20261109.so`（sha256 `22f2c518…`）；
  - 新建 `rime-jni` 独立工程，librime 升 1.17.0 + 三插件，打通构建（hkrime `61ddb8d`）；
  - 修 lua 方案（`rime.lua` 随包、`always_show_comments`、`spelling_hints`），hkrime `52d82e2` 等；
  - 发现「新库偶发卡顿」= 丢补丁 → 移植回 1.17（librime `c3c5969`、hkrime `c3db63d`）；
  - 之后 App 侧统一走「12 键快照候选条 + 展开面板 + 全字母方案共用分组」这条路（docs/10）。
