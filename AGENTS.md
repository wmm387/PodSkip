# AGENTS.md

给接手这个仓库的 AI 看的工作手册。项目会换目录、会换对话，靠这份文件保住上下文。

---

## 一、这个项目是什么

**PodSkip（耳机切剧）**：Android 无障碍服务，把耳机线控按键翻译成屏幕手势，用来控制短剧 App。
单击暂停/播放、双击下一个视频、三击上一个视频。

- 包名 `com.zero.podskip`，minSdk 31 / targetSdk 35，纯 Kotlin 原生视图（无 Compose）
- 仓库 `git@github.com:wmm387/PodSkip.git`，分支 `main`
- 目前只在 **Xiaomi 14 / Android 16 / HyperOS** 实机验证过

---

## 二、硬约束（必须先看这条）

1. **不要操作手机界面**。禁止 `input tap` / `input swipe` / 截图代替用户操作。
   `input` 系列在这台设备上经常不生效，用户已多次明确要求自己实操。
2. 允许做的只有：`./gradlew assembleRelease`、`adb install -r`、`adb logcat`、`adb shell dumpsys`、`am start`、清日志。
3. 需要验证界面行为时，**编译安装后让用户自己点，然后等他反馈**。不要试图自己点。
4. 用户说「就改这一处」就只改这一处；说「改完就结束」就不要再顺手检查/整理/美化。
5. **不要把已经修好的 bug 改回去**，见第六节。

---

## 三、构建与安装

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

- 只产出 release 包（约 2.4MB，开了 R8 minify + shrinkResources）
- 签名信息在 `keystore.properties`（**已 gitignore，不入库**），指向 `keystore/podskip-release.jks`
- `local.properties` 里 `sdk.dir` 指向 SDK，与项目位置无关；**项目整个文件夹可以随意移动**
- 版本号在 `app/build.gradle.kts` 的 `versionName` / `versionCode`，主页底部固定显示

---

## 四、代码结构

| 文件 | 职责 |
| --- | --- |
| `core/SwipeKeyService.kt` | 无障碍服务。`onKeyEvent` 收有线耳机键；`trackForeground` 维护前台应用；`dispatchGesture` 注入手势；佩戴检测联动 |
| `core/BtKeys.kt` | 蓝牙耳机接管：静音 `AudioTrack` + `MediaSession` 抢 AVRCP 媒体键，`keeper` 定时巡检 |
| `core/MultiTap.kt` | 单/双/三击判定，窗口 200–600ms |
| `core/WearDetector.kt` | 音频设备增删 → 摘/戴耳机事件 |
| `core/Logs.kt` | 按天日志、过期清理、导出 |
| `core/KeyProbe.kt` | 按键记录，给「耳机按键测试」页显示 |
| `Settings.kt` | 全部偏好项与默认值 |
| `ui/` | 主页、引导页、设置页、高级设置、白名单、按键测试 |
| `README.md` | 对外说明 |
| `TODO.md` | 项目任务清单，改需求先看这里 |

---

## 五、必须理解的三个机制

### 1. 蓝牙耳机按键收不到（为什么要有 BtKeys）

蓝牙按键走 AVRCP，系统直接派给「正在放音频的那个应用」，**不产生 KeyEvent**，无障碍服务看不到。
解法：自己开一条静音 `AudioTrack`（`USAGE_MEDIA`）+ 一个 `STATE_PLAYING` 的 `MediaSession`，
让系统认为本应用是「最近开始播放的应用」，媒体键就会派过来。
代价：接管期间通知栏媒体卡片变成「耳机切剧」。这是设计取舍，不是 bug。

### 2. 什么时候必须让出（`shouldTakeBt()`）

```
Settings.btKeys && Settings.enabled && hasBtAudio() && (inSelf || fgPkg 在白名单)
```
任何一条不满足都要**彻底释放**：停音轨 + `session.release()` + 置 null。

### 3. `inSelf` 是「用户正停在本应用里」

为了让「耳机按键测试」页也能收到蓝牙键，停在本应用时不退出接管、但不注入手势。
它靠窗口事件维护，而**从本应用退回桌面时系统常常不重发 `TYPE_WINDOW_STATE_CHANGED`**，
所以 `shouldTakeBt()` 每 2 秒用 `liveAppPkg()` 实时自检一次。

---

## 六、踩过的坑（不要改回去）

| 现象 | 真因 | 修法 |
| --- | --- | --- |
| 打开短剧按耳机没反应，必须拉一下通知栏才生效 | MIUI `GreezeManager` 冻结后台进程 | 引导申请电池优化豁免 + `deviceidle whitelist` |
| 点一下搜索框，按键就失效 | 输入法窗口发了 `TYPE_WINDOW_STATE_CHANGED`，把 `fgPkg` 顶掉 | `trackForeground` 过滤 `enabledInputMethodList` 和系统覆盖层 |
| 音乐 App 只能暂停不能播放，杀进程才恢复 | `inSelf` 卡在 true → 永远接管；且 `setTakingOver(false)` 用 `!taken` 短路，半途失败时音轨残留 | 实时窗口自检 + `releaseNow()` 无条件清理 |
| 切到音乐 App 后头几下按键没反应 | 让出防抖 2 秒太长 | 350ms；巡检 1 秒 |
| 按键一搭没一搭、反复抢放 | `rootInActiveWindow` 有时返回 `com.android.systemui`，被误判成离开当前应用 | `liveAppPkg()` 过滤系统覆盖层 |
| 戴上耳机不恢复播放 | ①自己的静音音轨把 `isMusicActive()` 顶成 true；②摘下瞬间播放器已自己暂停，被误判「本来就没播」 | 先停音轨再判断；摘下时**停完音轨立刻采样一次** `wasPlaying`；戴上等 900ms + 2.5s 静音期挡固件自动补发的键 |
| 短剧换集后媒体键被抢回去 | 新音轨 `start` 后系统改判给播放器 | `keeper` 定时 `restartSilent()`，按键后 `reclaim()` 再补两次 |

---

## 七、改完怎么验证

1. `./gradlew assembleRelease && adb install -r ...`
2. `adb logcat -c` 清空
3. 让用户按指定步骤操作（说清楚测哪几步）
4. `adb logcat -d -s "PodSkip:*"` 看事件；必要时 `adb shell dumpsys media_session | grep "Media button session"`
5. 用户确认后才提交

`dumpsys media_session` 是排查媒体键问题的关键：
如果 `Media button session is com.zero.podskip/...` 还挂着，说明我们没让出干净。

---

## 八、发版

1. 改 `app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`
2. `./gradlew assembleRelease`
3. `git add -A && git commit && git push origin main`
4. **GitHub Release 和 APK 上传由用户自己在网页操作**，不要代劳

---

## 九、日志

- 按天分文件 `filesDir/logs/podskip-yyyyMMdd.log`，保留 3 天，单文件 512KB 滚动覆盖最旧一半
- 导出只取**最近 3 小时**，导出前有确认弹窗
- 主线程有 3 秒心跳，迟到超过 1.5 秒会记一行 `W/beat`，用来抓「进程被冻结」
