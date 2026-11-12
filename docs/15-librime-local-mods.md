# librime 本地补丁与诊断

## 默认构建必须包含

| 内容 | 位置 | 当前行为 |
|---|---|---|
| 0001 性能 | `patches/0001-12key-abbrev-perf.patch` | Table::Query 预算/桶配额、分支选择；简拼跳过用户词典 DFS，限制句子装配 |
| 0002 方案扫描守卫 | `patches/0002-switcher-empty-dir-guard.patch` | 空目录返回，文件系统异常不穿出 JNI |
| 0003 路径覆盖 | `patches/0003-table-query-full-path-fallback.patch` | 终点桶放宽、简拼预算按需增加、完整路径补查 |
| glog | CMake 与 JNI setup | ENABLE_LOGGING=ON，日志写 userDataDir/logs；部署汇总本次增量 |

补丁 0001 的基准为每 end 桶 48、总展开 12000；当前 0003 检测简拼后桶/展开预算各乘 8，
终点桶额外放宽。完整路径补查不以终点桶为空为前提，按当前路径上限精确 QueryPhrases 补候选。
这套预算直接决定能否枚举到词条，查缺词时必须检查实际 table.cc 与补丁一致性。
具体上限应以源码和补丁同步修改，不从过时执行报告复用数值。

简拼会扩大音节图分支，完全放开预算可能增加按键延迟；补丁修改必须同时评价覆盖与耗时。
候选缺失的定位顺序是 App → 本地补丁 → 数据与部署 → 上游逻辑。

## 按需诊断补丁

`0004-librime-debug-probes.patch` 和 `0005-rime-jni-debug-probes.patch` 默认不应用。
0004 作用于 librime，提供 ProbeDictEntry/ProbeSyllable 与 Session::engine；
0005 作用于 rime-jni，提供相应 probe_dict/probe_syllable JNI 入口。
二者应用后必须重建库，并同步需要调用它们的 Java 声明；单独加入补丁不等于 App 已有触发入口。
当前生产 App 不包含 CandidateProbe 文件请求协议，不能直接靠写请求文件调用这些探针。

```bash
git -C librime am ../patches/0004-librime-debug-probes.patch
git am patches/0005-rime-jni-debug-probes.patch
```

仅在补丁尚未应用且适用于当前树时执行，诊断完成移除诊断改动并重新验证生产符号契约。

## 现成 PC 诊断

在 `rime-jni/host-test` 使用真实数据运行：

```bash
./run-host-test.sh --probe 5i4a57i
./run-host-test.sh --dump 547
./run-host-test.sh --queryd "li nak si"
./run-host-test.sh --syll 5i4a57i
./run-host-test.sh --prism w
./run-host-test.sh --probe 5i4ii8i9934i54iei9 --select 1
```

查表回答条目是否存在；syll/prism 回答路径是否存在；逐键 feed 与枚举回答翻译器是否给出。
全量覆盖与对照测试见 [16](16-librime-coverage-test.md)。
部署判断使用 `hkrime-deploy.log` 中实际源文件、REBUILT 项以及本次 glog 的
collecting entries / dropping entry / invalid syllable 等输出，不能从 UI 没候选倒推建表失败。
