# 耳机切剧 · 产品需求与技术方案

| 项目 | 内容 |
| --- | --- |
| 产品名（暂定） | 耳机切剧 |
| 文档版本 | v2.1 |
| 日期 | 2026-10-19 |
| 平台 | Android 12 ~ 15（minSdk 31，targetSdk 35） |
| 技术路线 | AccessibilityService（无障碍服务）+ 手势注入 |
| 目标场景 | 红果短剧 / 番茄短剧等竖屏视频流 App |
| 文档状态 | 待 M0 真机验证（见 §7） |

> **版本变更**
> v2.0 去掉广告识别，只做「按键 → 上滑」。
> v2.1 加入单击/双击/三击的多击识别（§5.3），并说明它对响应延迟的影响。

---

## 1. 一句话定位

耳机上按一下暂停，按两下下一个视频，按三下上一个视频。不用碰手机。

## 2. 背景

免费短剧 App 的广告插在剧集流中间，跳过方式是**上滑一屏**；正片之间切换也是上滑一屏。两者对用户来说是同一个动作。

耳机线控的媒体键本质是通用按键，系统把它分发给当前媒体会话，短剧 App 基本不响应。

**要做的就是把这些按键翻译成手势。**

## 3. 按键映射

| 物理操作 | 动作 | 实现方式 |
| --- | --- | --- |
| 单击 | 暂停 / 继续 | 点击画面中央（竖屏视频流通用交互） |
| 双击 | 下一个视频 | 上滑手势 |
| 三击 | 上一个视频 | 下滑手势 |

### 3.1 一个必须先说清的前提

耳机分两类，行为完全不同：

| 类型 | 系统收到的 keyCode | 我们需要做的 |
| --- | --- | --- |
| **A. 固件区分型**（多数品牌蓝牙耳机、AirPods 类） | 双击 → `MEDIA_NEXT`，三击 → `MEDIA_PREVIOUS`，单击 → `PLAY_PAUSE` | 直接映射，**零延迟**，不做计数 |
| **B. 单键型**（廉价蓝牙耳机、有线线控） | 无论按几下都是 `HEADSETHOOK` | 必须自己做计数 + 超时判定，**有延迟** |

判断不了也没关系——产品做**自动识别**（§5.6）：观察到 `MEDIA_NEXT`/`MEDIA_PREVIOUS` 事件就判定为 A 型，走直通；只观察到 `HEADSETHOOK`/`PLAY_PAUSE` 连续重复就判定为 B 型，启计数。

## 4. 功能需求

| 编号 | 功能 | 优先级 | 说明 |
| --- | --- | --- | --- |
| F1 | 无障碍服务开启引导 | P0 | 分机型图文指导（小米/华为/OPPO/vivo/三星） |
| F2 | 按键捕获与映射 | P0 | `NEXT`→上滑、`PREVIOUS`→下滑、`HEADSETHOOK`→计数 |
| F3 | 多击识别 | P0 | 单击/双击/三击，350ms 判定窗口（§5.3） |
| F4 | 手势注入 | P0 | 上滑/下滑 78%↔22%、260ms；点击中央 60ms |
| F5 | 应用白名单 | P0 | 仅在名单内 App 接管，默认预置短剧 App |
| F6 | 去抖 / 长按忽略 | P0 | `repeatCount > 0` 直接丢弃 |
| F7 | 按键模式 | P0 | 自动 / 单键计数 / 固件直通（默认自动） |
| F8 | 音量键备用触发 | P1 | 媒体键被系统抢占时的兜底 |
| F9 | 轻反馈 | P1 | 小 Toast「↑ 下一个」+ 震动 30ms，可关 |
| F10 | 参数调节 | P1 | 判定窗口 200–600ms、手势时长、滑动距离 |

**明确不做**：广告识别、内容判断、自动运行、去广告。

## 5. 技术方案

### 5.1 权限与配置

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />

<service
    android:name=".core.SwipeKeyService"
    android:exported="true"
    android:label="@string/a11y_label"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/a11y_config" />
</service>
```

```xml
<!-- res/xml/a11y_config.xml -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
```

- **不声明 `canRetrieveWindowContent`**：不读屏幕内容，前台包名从 `event.packageName` 取就够。权限越小，隐私解释越简单。
- `canRequestFilterKeyEvents="true"` 必须在 XML 里，只在代码设 flag 无效。
- `canPerformGestures="true"` 是 `dispatchGesture` 的前提，缺了抛 `SecurityException`。

### 5.2 服务主干

```kotlin
class SwipeKeyService : AccessibilityService() {

    @Volatile private var fgPkg = ""
    private var lastNextPrevAt = 0L          // 用于自动识别 A/B 型耳机

    private val systemOverlays = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in systemOverlays) return
        fgPkg = pkg
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (!Settings.enabled) return false
        if (fgPkg !in Settings.whitelist) return false      // 白名单外一律透传

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                lastNextPrevAt = SystemClock.uptimeMillis()
                if (Settings.mode == Mode.COUNT) false else { swipe(up = true); true }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                lastNextPrevAt = SystemClock.uptimeMillis()
                if (Settings.mode == Mode.COUNT) false else { swipe(up = false); true }
            }
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> onHookKey()
            else -> false
        }
    }

    private fun onHookKey(): Boolean {
        // 固件直通：单击交给系统自己暂停，零延迟
        if (Settings.mode == Mode.PASSTHROUGH) return false

        MultiTap.feed { tap ->
            when (tap) {
                Tap.SINGLE -> tapCenter()      // 点画面 = 暂停/继续
                Tap.DOUBLE -> swipe(up = true)
                Tap.TRIPLE -> swipe(up = false)
            }
            Feedback.flash(tap)
        }
        return true                             // 计数期间吞掉，避免系统同时暂停
    }

    private fun swipe(up: Boolean) {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val x = b.exactCenterX()
        val fromY = b.height() * if (up) 0.78f else 0.22f
        val toY   = b.height() * if (up) 0.22f else 0.78f
        val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, Settings.swipeDurationMs))
                .build(), null, null
        )
    }

    private fun tapCenter() {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build(), null, null
        )
    }

    override fun onInterrupt() {}
}
```

### 5.3 多击识别（本版新增，也是唯一有延迟的部分）

#### 状态机

```
IDLE ──按下──► 计数=1，启动 350ms 定时器
                  │
      350ms 内再按 ├──► 计数=2，重置定时器
                  │       │
                  │  350ms 内再按 ──► 计数=3 ──► 立即执行「三击」，计数清零
                  │
            定时器到期 ──► 计数=1 执行「单击」/ 计数=2 执行「双击」，计数清零
```

```kotlin
object MultiTap {
    private const val WINDOW_MS = 350L
    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private var lastAt = 0L
    private var pending: Runnable? = null

    fun feed(onResolved: (Tap) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt > WINDOW_MS) count = 0
        count++
        lastAt = now
        pending?.let(handler::removeCallbacks)

        if (count >= 3) {
            count = 0
            onResolved(Tap.TRIPLE)
            return
        }
        pending = Runnable {
            val tap = if (count == 1) Tap.SINGLE else Tap.DOUBLE
            count = 0
            onResolved(tap)
        }.also { handler.postDelayed(it, WINDOW_MS) }
    }
}
```

#### 延迟代价（必须让用户知道）

| 操作 | 触发时刻 | 实际执行 | 感知延迟 |
| --- | --- | --- | --- |
| 三击 | 第三下 | 立即 | 0ms |
| 双击 | 第二下 | 第二下 + 350ms | 约 0.35s |
| 单击（暂停） | 第一下 | 第一下 + 350ms | 约 0.35s |

单击暂停有 350ms 延迟，因为系统必须等你「不再按第二下」才能确定。这是多击识别的物理限制，无法消除，只能调小窗口（最小 200ms，再小容易把慢速双击拆成两次单击）。

> 若耳机是 A 型（固件区分），走直通模式，**三个动作全部零延迟**。优先引导用户使用这个模式。

### 5.4 为什么单击要「点画面」而不是「发暂停键」

判定为单击时，按键已经被我们吞掉了，系统收不到播放/暂停。而普通 App **无法把媒体键重新投递给系统**（`AudioManager.dispatchMediaKeyEvent` 需要 `MEDIA_CONTENT_CONTROL` 系统权限）。

所以单击改为注入一次画面中央的点击手势——竖屏视频流 App（含短剧类）都是「点一下暂停/继续」，效果一致，且不依赖任何特权。

设置里保留「单击不接管」选项：勾选后单击直接透传，交给系统自己暂停，这种情况零延迟（但双击/三击失效，因为第一击已经发给系统）。

#### 5.4.1 横屏：单击改为「双击屏幕」

短剧 App 进全屏播放（横屏）后，交互普遍变成：

| 操作 | 竖屏信息流 | 横屏播放器 |
| --- | --- | --- |
| 单击画面 | 暂停/继续 | **只显示/隐藏控制栏** |
| 双击画面 | 点赞 | **暂停/继续** |

因此横屏时注入一次单击，用户只会看到进度条闪一下，观感是「没反应」。处理方式：判定为单击后先判断屏幕方向，横屏则改为注入**两次间隔 120ms 的点击**（同一个 `GestureDescription` 里放两个 stroke），播放器的 `GestureDetector` 会识别成标准双击，直接暂停。

方向判定用两个信号取或，覆盖机型差异：

```kotlin
private fun isLandscape(): Boolean {
    val rot = displayManager.getDisplay(Display.DEFAULT_DISPLAY)!!.rotation
    val byRotation = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
    val byConfig = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return byRotation || byConfig
}
```

- 主判据是 display 旋转角：播放器进全屏会请求 `screenOrientation`，系统随之旋转 display，这是最贴近「用户眼睛看到的」信号。
- `Configuration.orientation` 兜底「应用声明了自己画横屏但 display 未旋转」的机型。
- 双击坐标仍取 `currentWindowMetrics` 的几何中心，横竖屏自动适配，不写死像素。
- 设置项 `landscape_double_tap`（默认开）可关：个别播放器横屏后单击就是暂停，关掉避免变成「点两下」。

延迟代价：横屏单击 = 多击窗口（400ms）+ 双击间隔（120ms）≈ 0.5s 后才暂停，比竖屏多 0.1s。这是模拟双击的固有成本，无法再压缩。

### 5.5 两个容易踩的坑

**① 坐标必须用 `currentWindowMetrics`**

无障碍服务里 `resources.displayMetrics` 常不含状态栏/导航栏，上滑终点偏下，表现为「滑一半弹回来」。`currentWindowMetrics.bounds`（API 30+，minSdk 31 完全可用）才是真实尺寸。

**② `return true` 是故意吞键**

若目标 App 自己也响应 `MEDIA_NEXT`，不吞就会连跳两格。副作用：白名单内耳机键不再控制音乐，所以白名单要精确到具体 App，别图省事全选。

### 5.6 自动识别 A/B 型耳机

```kotlin
// 简单启发式：10 秒内出现过 NEXT/PREVIOUS → 该耳机固件区分了多击
fun detectMode(): Mode {
    val recent = SystemClock.uptimeMillis() - lastNextPrevAt < 10_000
    return when {
        Settings.mode != Mode.AUTO -> Settings.mode
        recent -> Mode.PASSTHROUGH
        else -> Mode.COUNT
    }
}
```

更稳的做法是在引导页提供「按两下耳机键」的测试步骤：

- 捕获到 `NEXT` → 提示「你的耳机支持直接切视频，已开启零延迟模式」。
- 只捕获到 `HEADSETHOOK` → 提示「将使用多击识别，暂停有约 0.35 秒延迟」。

用户看得懂，也解释了延迟来源。

#### 换耳机：独立「按键测试」页

换耳机是这个产品最容易翻车的地方——同一副手机，换副耳机，按键发出的键值和连按节奏都可能完全不同，用户第一反应是「坏了」。

所以把识别做成**手机端自助**，不依赖电脑、不依赖重装：

页面做的事情（`KeyTestActivity`）：

1. **实时显示按键码**：每按一次，列出 keyCode 与距上一次的间隔（毫秒），最多保留 8 条。这是判断「耳机到底发了什么」的直接证据。
2. **判定耳机类型**：出现 `MEDIA_NEXT(87)` / `MEDIA_PREVIOUS(88)` → A 型，双击零延迟；只有 `HEADSETHOOK(79)` → B 型，走多击计数。
3. **算建议判定窗口**：取连按间隔，下限 400ms、上限 600ms，对齐到 50ms。
4. **一键采用**：直接写 `SharedPreferences`，服务每帧读取，无需重启。

键值对照：

| keyCode | 含义 | 对应耳机 |
| --- | --- | --- |
| 79 | `HEADSETHOOK` | 单键线控、多数廉价蓝牙；所有按法都发这一个 |
| 87 | `MEDIA_NEXT` | 蓝牙 AVRCP 双击、独立切歌键 |
| 88 | `MEDIA_PREVIOUS` | 蓝牙 AVRCP 三击、独立切歌键 |
| 85 | `MEDIA_PLAY_PAUSE` | 部分转接头只上报这个，不区分按键 |

**关于 3.5mm 转 USB-C 的情况**：声音走 USB 数字音频，按键由转接头里的 DAC 芯片编码成 HID 报告上报。廉价纯转接线只做解码不做按键检测，按键根本不会到达系统；多数芯片会上报，但可能只发 `PLAY_PAUSE` 而不区分上一首/下一首。这类耳机在测试页里会明确显示成 85，一眼可辨。

### 5.7 前台应用追踪

用 `TYPE_WINDOW_STATE_CHANGED` 自己记，不申请 `PACKAGE_USAGE_STATS`（敏感权限，还要用户单独授权）。

- 事件里的 `packageName` 就是新窗口所属包。
- 过滤 `com.android.systemui`、输入法、权限弹窗，否则会覆盖真实前台。
- 10 秒有效，超时视为无前台（用户已锁屏），按键直接透传。

### 5.8 保活：按需挂前台，不做无条件常驻

#### 问题

MIUI 会在应用后台空闲约 50 秒后主动解绑无障碍服务，logcat 原文：

```
SmartPower: com.podskip: background->idle(53683ms)
            R(service unbind com.podskip/.core.SwipeKeyService) adj=200
unbindService is called for service : com.podskip/.core.SwipeKeyService
```

服务一旦被解绑，`onKeyEvent` 再也不会被调用，单击/双击/三击**全部失效**，没有任何报错。

**排查时的一个陷阱**：被解绑后 `dumpsys accessibility` 仍把这服务列在 `Bound services` 里，状态是陈旧的。判断服务是否真的活着，要看 `ps -A | grep podskip` 和 logcat 里的 `unbind` 记录，不要信 dumpsys。

#### 方案

只在白名单应用处于前台时挂前台通知，离开即撤：

| 时机 | 行为 |
| --- | --- |
| `TYPE_WINDOW_STATE_CHANGED` 进入白名单 App | `startForeground`，通知文案「正在接管：<App 名>」 |
| 离开白名单 App | 延迟 60 秒后 `stopForeground(true)`，回到普通优先级 |
| 长时间不看剧 | 不占前台，允许系统正常降级回收 |

通知渠道用 `IMPORTANCE_MIN`：静默、不响不亮、不进锁屏，只在通知栏占一行。

延迟 60 秒而不是立即撤，是为了覆盖「短剧 App 内部跳 WebView / 弹权限框」这类短暂离开——否则一进一出就反复挂撤，通知栏闪。

#### 为什么不做无条件常驻

这个工具的真实使用特征是**间歇性**的：用户一天看剧可能只有一两个小时，其余时间挂着前台服务纯属浪费。用户明确表示「长时间不用被系统杀掉属于正常情况，大不了手动再开」。

所以不提供「无条件保活」作为默认行为。主页面留了一个「始终后台保活」开关，默认关闭，给「一天看剧八小时」的重度用户备用。

也不做「心跳自检 + 打开 App 时红条提示」——用户接受手动重开的成本，加这套自检只是在为一个小概率事件增加复杂度。

#### 必须补的权限

`startForeground` 需要 `FOREGROUND_SERVICE` 和对应类型权限。只声明 `<service android:foregroundServiceType="specialUse">` 而不申请 `FOREGROUND_SERVICE_SPECIAL_USE`，会直接抛：

```
Permission Denial: startForeground requires android.permission.FOREGROUND_SERVICE
```

三个权限缺一不可：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（Android 13+，需运行时申请）。

### 5.9 佩戴检测：摘下自动暂停、戴上自动恢复

**先说清楚能力边界**：Android 公共 API 没有「入耳状态」，拿不到耳机的真实佩戴传感器数据。能做到的最接近信号是**音频输出设备的去留**：

| 信号 | 来源 | 覆盖场景 |
| --- | --- | --- |
| 输出设备全部消失 | `AudioDeviceCallback.onAudioDevicesRemoved` | 有线拔出、蓝牙断开、摘下即断开的 TWS |
| `ACTION_AUDIO_BECOMING_NOISY` 广播 | 系统在拔出瞬间发 | 同上，比回调更及时 |
| 输出设备重新出现 | `onAudioDevicesAdded` | 重新戴上/重连 |

「只摘一只但蓝牙还连着」的耳机（多数 TWS 的默认行为）检测不到——这是系统层面的限制，不是实现缺陷。页面上已向用户说明。

**实现**（`WearDetector`）：

- 判定耳机类设备：`WIRED_HEADSET/WIRED_HEADPHONES/BLUETOOTH_A2DP/BLUETOOTH_SCO/USB_HEADSET/HEARING_AID/BLE_HEADSET`。
- **去抖 500ms**：设备移除后延迟确认，避开 A2DP ↔ SCO 通话路由切换时的瞬时抖动，否则接电话、切歌会误暂停。
- **只在之前确实接着耳机时才报「摘下」**：没戴耳机时收到的 `BECOMING_NOISY`（比如手动切扬声器）不算摘下，防止凭空暂停。
- 动作复用现有的注入手势：`singleTapGesture()`（竖屏单击 / 横屏双击）。不新增手势路径，行为与手动单击完全一致。

**误触发防护（四层）**：

1. 只在「总开关开 + 白名单应用在前台」时动作（`canAct()`）；
2. 暂停前查 `AudioManager.isMusicActive()`——本来就没在播（已暂停/播完）就不点，防止反向误触发；
3. 恢复前也查 `isMusicActive()`——耳机固件自己已经恢复播放（部分 TWS 摘下/戴上有自己的播放控制）就不再补一刀，避免点一下又暂停；
4. 用户手动按过耳机键就撤销「自动暂停」标记，之后戴上不再自动播放。

**自动恢复时限**（默认 5 分钟，可选 1/5/15）：摘下超过时限再戴上不恢复，防止晚上摘下、第二天早上戴上突然外放。

**权限**：无新增。`AudioDeviceCallback`、`BECOMING_NOISY`、`isMusicActive()` 都不需要运行时权限，也不需要 `BLUETOOTH_CONNECT`（只判断设备类型，不读设备名）。动态注册广播在 Android 14 用 `RECEIVER_NOT_EXPORTED` 即可收到系统广播。

**延迟**：摘下到暂停 ≈ 耳机断开耗时（有线几乎即时、蓝牙 1–2s）+ 500ms 去抖。蓝牙场景整体 1.5–2.5s，属可感知但可接受；这是「等系统确认设备真的没了」的必要成本。

## 6. 设置项

页面归属：**手势参数页只放滑动几何与时序（滑块），开关类选项全部集中在「高级设置」页**。

| 设置项 | 页面 | 类型 | 默认值 |
| --- | --- | --- | --- |
| 总开关 | 主页 | Switch | 关 |
| 生效应用 | 白名单页 | 列表 | 空（引导第 5 步勾选） |
| 上滑起点 / 终点 | 手势参数 | 滑块 | 屏高 78% / 22% |
| 上滑时长 | 手势参数 | 滑块 | 260ms |
| 判定窗口 | 手势参数 | 滑块 | 400ms（200–600） |
| 按键模式 | 高级设置 | 单选 | 自动 |
| 单击接管 | 高级设置 | Switch | 开（关则失去双击/三击） |
| 横屏时单击＝双击屏幕 | 高级设置 | Switch | 开 |
| 长按不触发语音助手 | 高级设置 | Switch | 开 |
| 耳机摘下自动暂停 | 高级设置 | Switch | 开 |
| 重新戴上自动恢复 | 高级设置 | Switch | 开（依赖上一项，关闭时置灰） |
| 自动恢复时限 | 高级设置 | 单选 | 5 分钟（1/5/15） |
| 始终后台保活 | 主页 | Switch | 关 |

**迁移方式**：SharedPreferences 键名全部不变，只挪页面——升级后用户的既有配置原样保留；首次安装按上表默认值。手势参数页留一行说明和「打开高级设置」跳转按钮，两个页面的「恢复默认」各自只重置自己管辖的范围（`resetSwipe()` / `resetAdvanced()`），互不越界。

判定窗口从 350ms 提到 400ms 的依据：实测同一副耳机连按时，双击间隔在 288ms 上下浮动；350ms 只剩 60ms 余量，手速稍慢一次双击就会退化成两次单击（表现为「按两下只暂停了两次」）。

**测试页给出的建议值必须设下限**。曾经用「单次连按的间隔 × 1.35」推窗口，结果给出 300ms，比默认值还小，直接把用户的双击拆成了两次单击。正确做法是：下限锁死 400ms，只有观察到更慢的连按才往上加，最高 600ms——建议值只能往「更宽容」方向调，不能反向。

## 7. 风险与待验证

### 7.1 M0 必须真机验证

| 编号 | 验证内容 | 方法 | 影响 |
| --- | --- | --- | --- |
| T1 | 媒体键能否进入 `onKeyEvent` | 各 ROM 实机打日志 | **方案成立与否** |
| T2 | 耳机按下时发出哪些 keyCode | 日志打印 keyCode + 时间戳 | 决定走 A 型还是 B 型 |
| T3 | 上滑/下滑手势能否触发翻页 | 注入一次手势看效果 | 动作层是否可用 |
| T4 | 「点画面中央」是否等于暂停 | 实机点一下 | 单击方案是否成立 |
| T5 | 目标 App 真实包名 | `adb shell dumpsys window \| grep mCurrentFocus` | 白名单配置 |

**2026-10-19 实测结果（Xiaomi 14 / Android 16 / HyperOS V816，3.5mm 线控 + USB-C 转接头）**

| 编号 | 结论 |
| --- | --- |
| T1 | **通过**。按键经无障碍输入过滤器进入 `onKeyEvent`，`capabilities=40` 生效。注意：重装 APK 后服务重新绑定期间按键会被系统吞掉，需等绑定完成或在系统设置里关开一次服务 |
| T2 | **B 型**。单键耳机，任何按法都只发 `KEYCODE_HEADSETHOOK(79)`，`deviceId=8`（`Button Jack`）。系统同时把该键送给 MediaSession（`MediaSessionService` 日志可见），所以原生的「一下暂停 / 两下切歌」是系统层翻译的，不是耳机固件 |
| T3 | **通过**。上滑 `(600, 2056) → (600, 587)`、下滑镜像，260ms，`dispatchGesture` 返回 true 且 `onCompleted` 回调正常，短剧 App 正常翻页 |
| T4 | **通过**。点 `(600, 1335)` 画面中央可暂停 |
| T5 | `com.kylin.read`（番茄短剧，`ShortSeriesActivity`）；另有 `com.dragon.read`（番茄小说） |

实测多击间隔：双击约 288ms、三击约 198–259ms。原默认窗口 350ms 只剩 60ms 余量，双击容易退化成两次单击，现已把默认值改为 400ms，并做了一次性配置迁移（低于 400ms 的旧值自动抬到 400）。

**另记一条实测结论**：重装 APK 后无障碍服务会重新绑定，这几秒内的按键被系统吞掉，日志里一行 `onKeyEvent` 都没有。这是「装完新版就没反应」的最常见原因，不是代码问题。判断方法与恢复方式见 §5.8。

### 7.2 风险

| 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- |
| 媒体键在 `PhoneWindowManager` 阶段被系统抢先路由给 MediaSession，无障碍收不到 | 中高 | 主卖点失效 | 小米 14 实测**不存在**（系统虽同时投给 MediaSession，但无障碍过滤器先拦到）；其他 ROM 仍需验证，保留音量键兜底（F8） |
| 单击判定延迟被感知为「卡」 | 高 | 体验差 | A 型耳机走直通零延迟；B 型必然有约等于判定窗口的延迟，引导页如实说明；窗口可调至 200ms（代价是双击误判） |
| 部分 App 点画面不暂停（如全屏广告） | 中 | 单击失效 | 设置项「单击不接管」改为透传 |
| 国产 ROM 杀无障碍服务 | 高 | 用户以为坏了 | 白名单 App 在前台时挂前台通知（§5.8）；`dumpsys accessibility` 会显示陈旧状态，排查必须看 logcat 的 `unbind` 记录 |
| 建议值把判定窗口算小，双击被拆成两次单击 | 中（已发生） | 用户以为功能彻底失效 | 建议值下限锁死 400ms，只允许往上调 |
| 重装后服务重新绑定期间按键被吞 | 高 | 表现为「装完就没反应」 | 装完在系统设置里把服务关开一次；长按说明写进引导页 |
| 白名单配错，听歌时双击变成上滑 | 中 | 很烦 | 默认仅预置短剧 App，不全选 |
| 商店因无障碍 API 用途拒审 | 高 | 阻断上架 | 定位自用/侧载 |

### 7.3 合规

仅响应用户主动按键，不后台自动操作、不读屏幕内容、不联网、不修改目标 App 数据。

## 8. 里程碑

| 阶段 | 内容 | 工作量参考 | 状态 |
| --- | --- | --- | --- |
| M0 | 真机验证 T1–T5（重点 T2：耳机实际发什么键） | 1 人日 | 已完成 |
| M1 | 服务 + 按键映射 + 手势打通（先不做多击） | 2 人日 | 已完成 |
| M2 | 多击状态机 + 自动识别 + 引导页 | 2 人日 | 已完成 |
| M3 | 设置、反馈、保活、多机型适配 | 2 人日 | 已完成（保活改为按需模式，见 §5.8） |

**M0 是决策点**：T1 不通过，改成音量键触发方案（音量+ 上滑、音量- 下滑），代码 80% 复用。本次实测 T1 通过，主方案成立。

已交付但未做的事：release 签名与体积优化（debug 包约 12MB，release 预计 3MB 左右）、引导页的分机型自启动说明（产品改为按需保活后优先级降低）。

## 9. 验收标准

- 双击耳机键，1 秒内翻到下一个视频；三击回到上一个。
- 单击后在 0.5 秒内暂停（B 型耳机）。
- 非白名单 App（如网易云音乐）中行为与未安装本应用时完全一致。
- 连续快按 10 次不卡顿、不连跳、不重复触发。
