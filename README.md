# 作业呢 · zuoyene

把散在几十门课里的学习通（超星）作业和考试，拉成一张能一眼看完的清单；点一条，直接进到学习通里那份作业的详情页。

Android 6.0+ / 单 APK / 无第三方依赖 / 无 Gradle。

**[⬇ 下载 zuoyene-v13.apk](https://github.com/lmy18837613075/zuoyene/releases/download/v13/zuoyene-v13.apk)**（132.9 KB） · [Releases](https://github.com/lmy18837613075/zuoyene/releases)

```
release/zuoyene-v13.apk   132.9 KB
```
包名 `com.dsh.cxtodo`，minSdk 23，targetSdk 34。

---

## 它做什么

| 能力 | 说明 |
| --- | --- |
| 全量抓取 | 课程 → 班级 → 作业/考试，解析标题、开始时间、截止时间、作业状态 |
| 四分区折叠 | **待完成 / 已交待批阅 / 往期学期 / 已完成**，默认只露最近一项待完成 |
| 缓存优先 | 打开秒显上次结果，同时后台刷新，顶部标「缓存于 HH:mm」 |
| 自定义作业 | hero 里「＋ 手动」加一条：标题必填，课程/截止选填 |
| 一键进入 | 点卡片 → 拉起学习通 → 无障碍服务自动导航到那份作业（实测 **8.6 秒**） |
| 应用内浏览 | 长按卡片在 app 内 WebView 打开，不想跳 app 时用 |
| 诊断 | 扫描细节全屏等宽字体展示，可一键复制 |

界面是浅饱和极光背景 + 半透明白玻璃卡片 + 深紫墨字，圆角 22–30dp，API 28+ 用紫色调投影（`setOutlineSpotShadowColor`）。

---

## 数据链路

学习通的作业接口要现场签名的 `enc`，直接拼 `courseId/classId` 拿不到数据。实际可用链路：

```
GET https://mooc1.chaoxing.com/visit/courses                       # 课程列表（含 courseid/clazzid/cpi）
GET .../mycourse/backclazzdata?view=json&rss=1                     # 开课时间，用于学期判定
GET .../visit/stucoursemiddle?courseid=&clazzid=&vc=1&cpi=         # 302 → 带 enc 签名的 /work/getAllWork
GET .../mooc-ans{path}&start=0&size=50                             # 作业/考试列表本体
```

解析要点：`titTxt` 分块、`inspectTask`（待做）、`selectWorkQuestionYiPiYue`（已批阅），配合 `开始时间：` / `截止时间：` / `作业状态：` 三个标签定状态；没有任何时间标签也没有 `titTxt` 的空块直接跳过。

状态机四种：`finished`、`submitted`（先判定）、`todo`、`unknown`。

---

## 自动跳转是怎么做的（以及为什么只能这么做）

点击一条作业要落到学习通里那份作业，试过并**排除**的三条路：

1. **深链 `cxstudy://`** — `web / webview / h5 / browser / open / url / jump / link / page / html / site / internal`，加 `type=` / `newtype=` 变体，全部落到 `.main.ui.MainTabActivity`（首页），data 被静默丢弃。日志能看到 `START dat=cxstudy://... cmp=.activity.JumpActivity` → 700ms 后 `START cmp=.main.ui.MainTabActivity with LAUNCH_SINGLE_TASK`。也没有 http/https 的 VIEW 过滤器，`intent://` 无法解析。
2. **直连原生页面** — 从 APK manifest 里拉出 **868 个 Activity**，`StudentCourseActivity`（学生课程页）、`HomeworkListActivity`、`ClassTaskActivity`、`MyCourseActivity` 等全部非导出：`am start` 直接 `SecurityException: Permission Denial: not exported from uid 10385`。学习通对外只暴露 `JumpActivity`。
3. **静态逆向协议** — 本体是梆梆加固，`classes.dex` 114.8 MB 只是壳（`dexdump` 只出 3761 行就断在 `com/secneo/apkwrapper/a`），路由表拿不到。

于是走 **无障碍服务 + UI 导航**，链路：

```
拉起学习通
└ 首页 → 命中课程名（快捷卡片 / 最近使用）
       └ 没命中 → 点「我的课程」→ 课程列表命中 → 点课程
课程页 → 点「作业/考试」入口行          ← 它不是顶部 Tab，是内容流里的一行
作业列表 → 命中作业标题 → 点击
落点 com.chaoxing.mobile.webapp.ui.WebAppCommonViewer
```

实现上踩平过的坑，都写在 [apk/README-v13.md](apk/README-v13.md)：

- `getRootInActiveWindow()` 在学习通身上只返回 **1 个空壳子节点** → 改用 `getWindows()` 遍历所有窗口，取节点最多的那个学习通窗口
- 「作业」用精确匹配永远匹配不到，实际文本是 **「作业/考试」** → 包含匹配 + 「更多」兜底
- `getLaunchIntentForPackage()` 因 Android 11+ 包可见性返回 null（跳转根本没发生）→ 改显式 `ComponentName` 启 `SplashActivity`，并补 `<queries>`
- 节点大多不可点，可点的是父容器 → 向上爬最多 8 层找 `clickable`，仍不行用手势 `dispatchGesture` 点中心
- MIUI 把 `am start` 到已有任务当成「从最近任务启动」，不投递新 Intent → 调试改用广播

---

## 构建

不用 Gradle，`aapt2` + `javac` + `d8` + `zipalign` + `apksigner` 手搓（见 `apk/build.ps1`）：

```powershell
$env:JAVA_HOME='D:\tools\jdk17\jdk17.0.21_0'; $env:ANDROID_HOME='D:\android-sdk'
powershell -ExecutionPolicy Bypass -File apk\build.ps1
# 构建完成：apk\cx-todo.apk
```

约束：Java 8 源码级、**不用 lambda**（匿名内部类）、不用 androidx、不引任何第三方库。`aapt2 compile --dir` 是递归的，`res/xml/` 会被自动带上。

---

## 安装与开启跳转

1. 装 `release/zuoyene-v13.apk`
2. **设置 → 无障碍 → 已下载的应用 → 作业呢 → 作业跳转 → 开启**
3. app 内首次点卡片若服务没开，会弹三选一：`去开启` / `应用内打开` / `取消`

HyperOS / MIUI 上要注意：

- 系统不给 shell `pm grant WRITE_SECURE_SETTINGS`（`Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS`），`settings put secure` 同样被拒 —— 所以包里的「一键自开」在这些机型上不可用，**必须手动开一次**
- **重装 APK 或强停进程后，系统约 30 秒才重绑无障碍服务**，这段时间点卡片会提示「服务未就绪」
- 建议把 app 的**省电策略设为「无限制」**并允许**自启动**，否则后台一清跳转就失效

### 调试通道（留在包里，不影响日常使用）

```bash
adb shell am broadcast -a com.dsh.cxtodo.AUTOTEST -p com.dsh.cxtodo --ei idx 0
adb shell am broadcast -a com.dsh.cxtodo.AUTOTEST -p com.dsh.cxtodo --es title "期末课程设计"
adb shell am broadcast -a com.dsh.cxtodo.AUTOTEST -p com.dsh.cxtodo --es addcustom "马原课程论文" --es coursename "马克思主义基本原理" --es deadline 11-25
adb logcat -s CxJump     # 每步 tick step=N + 当前页面文本快照
```

`--es` 的值带空格会被 am 当成 URI 解析（`dat=23:`），时间用 `11-25` 这种无空格写法。

---

## 已知限制

- 学习通 App 改了页面结构就要跟着调选择器；UI 导航本质上是脆的
- 只有 Android 端，没有 iOS
- 只验证过本机 HyperOS（25019PNF3C）与 Android 14 模拟器，其它 ROM 未测
- 抓取依赖登录 Cookie，登录态过期需要重新输账号

---

## 致谢：很多硅基朋友

这个项目从头到尾是在**许多硅基朋友的帮助下完成的** —— 从需求拆解、UI 重做、协议勘察、真机调试到逐条修 bug，整个协作过程本身就是这件事的一部分，值得写在这里：

- **架构与迭代**：v1 到 v13 的每一轮，折叠分区、缓存优先渲染、下拉刷新、玻璃拟态视觉重做，都是在对话里反复推翻重来的结果
- **协议勘察**：从 868 个 Activity 的 manifest 里筛候选、穷举 `cxstudy://` 的十几种变体、判定梆梆加固（`dexdump` 断在 `com/secneo/apkwrapper/a`）—— 每一步都是先取证再下结论，不是猜的
- **无障碍状态机**：`getRootInActiveWindow()` 空壳、精确匹配失配、「作业/考试」实测、「包可见性」返回 null、MIUI 吞掉 `onNewIntent` —— 四个关键 bug 都是靠 `adb logcat -s CxJump` 的逐步日志定位的
- **真机调试**：`uiautomator dump` 看节点树、`dumpsys window` 看落点 Activity、广播注入绕开 MIUI 的 am 限制

## License

MIT