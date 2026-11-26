# JNI 库与符号契约

## 当前库

| 项 | 值 |
|---|---|
| App 库 | `app/libs/arm64-v8a/librime_jni.so` |
| 构建入口 | `../rime-jni/script/build.sh`，默认 Release |
| 引擎 | librime 1.17.0，Lua/charcode/octagram 合并插件 |
| 大小 | 6,425,960 字节 |
| SHA-256 | `8b40fe0eb771758353a494f74e35ed70fa4b515c7a8fa406dc4a001184b9ea4d` |
| JNI 契约 | 源码声明 77，导出 77，缺失/额外/`Java_com_osfans_trime_` 残留均为 0 |
| ELF LOAD 对齐 | 当前三个 LOAD 段均为 `0x1000`（4 KB） |

当前 ELF 不满足 16 KB LOAD 对齐，不能标记为已支持 16 KB 页设备。
APK 的 zipalign 检查与 ELF 的 LOAD 对齐是不同检查，前者通过不能替代后者。

## 接口

Java 门面为 `core/Rime.java`；Native 使用 `Java_com_hazukieq_hkrime_*` 名字绑定。
JNI 胶水在 `../rime-jni/glue/`，`rime_api_compat.h` 将全局包装函数转发到 RimeApi，
`rime_compat.cc` 提供 composition segments 兼容实现。

`candidatesSnapshot` 优先使用 packed 枚举，每项以 `\u0002` 结束，字段以 `\u0001` 分隔：
`text / comment / start / end / sourceComment / index`。start/end 是引擎候选在原始输入中的真实范围，
sourceComment 来自穿透 Shadow/Uniquified 包装后的候选，用于读音与学习；显示 comment 独立保留。
index 是筛选前 menu 的全局下标；`hkrime_initial` 属性保存已确认拼音序列：`~l ~h =man` 中 `~` 按首拼**单元**匹配（`~th` 整段；th/ng/nz 不拆 t/h）、`=` 按完整音节匹配；
单字母值兼容底层单段探针；非空属性同时让 librime 保留简拼分支（0006），因此它既是过滤器
也是「确认态」开关。先匹配再截取窗口，
仍按原 menu 下标选择；部分选字可以覆盖确认序列的前几段，App 再校验原码边界。空属性解除约束。两字段、五字段记录继续兼容。
旧库的两字段记录仍可解析；没有元数据时只允许原有的可证明读音边界，不把未知说明当成全码选择。
该协议增加字段但不增加 JNI 符号。packed 符号不可用时回退逐条接口。
调用方必须持引擎锁。debug 枚举信息入口不能视为词典级诊断探针。

## 检查

在 App 仓库执行：

```bash
./script/verify-jni-symbols.sh --so app/libs/arm64-v8a/librime_jni.so
stat -c '%s' app/libs/arm64-v8a/librime_jni.so
sha256sum app/libs/arm64-v8a/librime_jni.so
readelf -lW app/libs/arm64-v8a/librime_jni.so
readelf -dW app/libs/arm64-v8a/librime_jni.so
```

校验脚本从源码提取声明，符号数量随真实接口变化更新；必须全部导出且无多余或错误包名。
改 JNI 接口要同步源码、胶水与符号验证；改 librime 要同步 `patches/`、PC 覆盖与真机验收。
只改 App/主题时使用现有库。构建步骤见 [11](11-native-build-performance.md)。
