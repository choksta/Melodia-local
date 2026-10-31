# 调试与发版流程

## 调试阶段：本地构建，不要推 tag

推 tag 那条链路（CI 构建 → 发 Release）是给**分发**用的，一次 8 分钟左右。
改一行 UI 就走它很浪费：等 8 分钟、下载 18MB、再装机。

本地构建快得多，而且**签名与 Release 完全一致**（都是 `~/.android/debug.keystore`），
所以本地的包和 GitHub 上的包可以互相覆盖安装，装哪个都不冲突、不丢数据。

```bash
# 1. 构建（增量通常 1~2 分钟；改了 Gradle 配置才需要全量 4~5 分钟）
./gradlew :app:assembleDebug -PreleaseVersionCode=20549

# 2. 装机（MIUI 拦截普通 adb install，需要 root）
MSYS_NO_PATHCONV=1 adb push app/build/outputs/apk/debug/*.apk /data/local/tmp/x.apk
MSYS_NO_PATHCONV=1 adb shell "su -c 'chmod 644 /data/local/tmp/x.apk && pm install -r -d /data/local/tmp/x.apk'"
```

`-PreleaseVersionCode` 一定要比手机上已装的大，否则装完 App 会一直提示"有新版本"
（它比较的是 versionCode，而 debug 包默认是 1）。

debug 包没有 R8 混淆和资源压缩，体积比 release 大，但**交互行为、布局、动画与 release 一致**，
调 UI 完全够用。只有涉及混淆/裁剪的问题（比如反射、序列化）才必须用 release 包验证。

## 定稿阶段：推 tag

```bash
git tag v2.1.0-beta.1-localNN
git push mine-ssh v2.1.0-beta.1-localNN
```

然后用轮询等结果，不要 sleep 固定时长硬等：

```bash
python3 .github/scripts/wait_release.py v2.1.0-beta.1-localNN
```

每 20 秒查一次，构建一结束就打印结论、Release 链接和 APK 大小。

## 发版前的三条硬要求

1. **签名必须是 `~/.android/debug.keystore`**（指纹 `0D:32:1B:9D:…:00:7C`）。
   换任何别的 keystore 都会让已安装用户无法覆盖升级，必须先卸载。
   workflow 里「校验签名指纹与预期一致」是硬门禁，配错直接失败。
2. **versionCode = 20512 + N**，`-localN` 必须参与计算，否则同一个 beta.1
   反复发时 code 不变，App 的更新检查永远判不出新版本。
3. **Release 正文必须有 `<!-- versionCode=N -->` 标记**（workflow 自动加），
   客户端解析 tag 时会跳过含 `-local` 的，只认这个标记。

更新日志：想写用户视角的详细说明，就放一份 `.github/release-notes/<tag>.md`，
存在则优先用它；否则用 `gen_release_notes.py` 从 commit 自动生成。
