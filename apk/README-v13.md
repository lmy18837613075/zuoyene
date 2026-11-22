# 作业呢 v13 · 学习通作业收集

APK：`作业呢-v13.apk`（132.9 KB，minSdk 23 / Android 6.0+，targetSdk 34，应用名「作业呢」）

## v13：手动作业 + 点卡片自动进入学习通对应作业

### 1. 自定义作业

hero 行新增「＋ 手动」药丸（刷新 / 切换账号 之后）：

- 弹窗三项：**标题（必填）** / 课程（选填）/ 截止（选填，支持 `11-25` 或 `11-25 23:59`，年份按当前学期自动补，`mm<=7` 视为次年）
- 手动条目 `kind=custom`，卡片带 ✎ 与「手动」标签，课程空时显示「未写课程」
- **点卡片 = 编辑**（同弹窗，多出「删除」）；长按复制一行文本
- 与扫描结果同存本地（sp 键 `customs`，8 字段 `\t` 编码），刷新/换账号不丢

### 2. 点作业卡片 → 自动落到学习通里那份作业

点非手动卡片：拉起学习通 → 无障碍服务接管导航 → 停在作业详情页。

实测链路（真机 25019PNF3C / HyperOS，冷启动到作业页 **8.6 秒**）：

```
首页 → 命中课程名（首页快捷/最近使用）
     └ 没命中 → 点「我的课程」→ 课程列表命中 → 点课程
课程页 → 点「作业/考试」入口行
作业列表 → 命中作业标题 → 点击
落点 com.chaoxing.mobile.webapp.ui.WebAppCommonViewer（作业详情）
```

已被实测排除的两条路（写下来免得再试）：

- **深链 `cxstudy://`**：`cxstudy://web?url= / webview / h5 / browser / open / url / jump / link / page / html / site / internal`，以及带 `type=newtype=` 的变体，全部落到 `.main.ui.MainTabActivity`（首页），数据被丢弃
- **直连原生页**：`StudentCourseActivity` / `HomeworkListActivity` / `ClassTaskActivity` 等 868 个 Activity 里没有一个导出，`am start` 直接 `SecurityException: not exported from uid 10385`；学习通对外只暴露 `JumpActivity`
- 静态逆向也不通：学习通本体是梆梆加固，`classes.dex` 114.8 MB 只是壳（`dexdump` 只出 3761 行就断在 `com/secneo/apkwrapper/a`）

### 3. 开启「作业跳转」服务

**设置 → 无障碍 → 已下载的应用 → 作业呢 → 作业跳转 → 开启**（可能弹系统提示，点允许）。

app 内首次点卡片若服务没开，会弹三选一：`去开启` / `应用内打开` / `取消`。选「去开启」直接跳到无障碍设置页。

### 4. HyperOS / MIUI 注意

- 系统**不给 shell `pm grant WRITE_SECURE_SETTINGS`**（`SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS`），`settings put secure` 同样被拒 —— 所以「一键自开」在这台机器上不可用，**必须手动开一次**
- **重装 APK 或强停进程后，系统约 30 秒才重绑无障碍服务**；这期间点卡片会提示「服务未就绪」
- 建议同时设：设置 → 应用设置 → 作业呢 → **省电策略「无限制」**、允许**自启动**，否则服务会被后台清理，跳转失效
- MIUI 把 `am start` 到已有任务当作「从最近任务启动」，不投递新 Intent（`onNewIntent` 不触发），调试要用广播（见下）

### 5. 调试开关（保留在包里，不影响正常使用）

```bash
adb broadcast -a com.dsh.cxtodo.AUTOTEST -p com.dsh.cxtodo --ei idx 0        # 按列表序号
adb broadcast -a com.dsh.cxtodo.AUTOTEST -p com.dsh.cxtodo --es title "期末课程设计"   # 按标题片段
adb logcat -s CxJump          # 每步：tick step=N + 当前页面文本快照
```

`startNow()` 在拉起学习通时立即起 tick（1.5 s 一次），不等窗口事件；多窗口 App 用 `getWindows()` 遍历取节点最多的学习通根节点（`getRootInActiveWindow()` 在它身上只给到 1 个空壳子节点）。

## v12：视觉重做

真机反馈「排版/渲染/美观不咋地」后的整轮修正：

- **极光背景加饱和**：六团径向渐变透明度翻倍、白雾减半——玻璃终于采得到颜色，不再是灰泥
- **玻璃减白增透**：hero/卡片/折叠行白色涂层降到 35~40%，亮边加到 60~70%，高光加强——透出背景色
- **投影换紫色调**：API 28+ 用 `setOutlineSpotShadowColor` 紫影替代默认灰黑影，高度整体降档
- **药丸按钮白玻璃化**：刷新/切换账号/诊断/学期胶囊/截止徽章改半透明白 + 白描边，去掉灰紫泥块
- **状态栏/导航栏融入背景**：启动时从极光上下沿取同色设给两条系统栏，背景通到屏幕边
- **标记加粗**：圆圈里的 ! / 批 / ✓ / ? 换黑体加大；「已交待批阅」标记从「…」改「批」
- **列表收尾**：末尾加「— 本学期就这些 —」，短列表不再是一片突兀空白
- **折叠**（v11 末加）：四分区全可折叠，默认只露最近一项待完成

## v11：缓存 + 下拉刷新 + 应用内浏览 + 进度可见 + 状态细分

针对真机反馈的 8 个问题一次修完：

| 问题 | 修法 |
| --- | --- |
| 数据更新不及时，每次干等 | 扫描结果缓存本地：**打开秒显上次数据，同时后台刷新**，顶部显示「缓存于 HH:mm」 |
| 没有下拉刷新 | 列表顶部下拉（超过 64dp 松手）触发刷新，头部显示「下拉刷新 / 松手刷新 / 正在刷新…」 |
| 点卡片跳外部浏览器 | 改**应用内浏览层**：顶栏返回 + 标题 + 「↗ 浏览器」出口（真想用系统浏览器时）；返回键 = 返回列表 |
| 扫描过程看不见 | 扫描时 dock 上方浮**进度卡**：`读取 12/18 · 数据结构` + 进度条 + **取消**按钮 |
| 诊断入口太隐蔽 | hero 里常驻**「诊断」胶囊**，点开全屏等宽字体的诊断页，一键复制 |
| 状态分得太粗 | 新增 **已交待批阅** 分区：`已完成待批阅 / 已提交` 的作业单独列出（紫色「…」标记 + 「已交」徽章），不再和待做、已完成混在一起 |
| 往期学期判断有误 | 课程学期判定加入**自学习**：没有开课时间、名字也没有学期标记的课，扫完用**作业时间多数派**推断学期并缓存；下次扫描这类课直接跳过（诊断里 `learn 电子技术 25-26-2` 一行可见） |
| 考试条目质量差 | 只保留门户页里**文字像考试名**（考试/测验/期中/期末/测试）的 exam 链接；test 工具页之类全部丢弃 |
| 数字对不上 | hero meta 写明口径：`待完成 A · 待批 B · 往期 C · 已完成 D`，状态行显示 `N 门课，跳过 M 门往期 · K 条 · HH:mm` |

## 界面结构

```
┌ hero：账号 · 刷新 · 切换账号 ────────────┐
│  4 项待完成                                │
│  待完成 4 · 待批 1 · 往期 1 · 已完成 2     │
│  学期 2026-2027 秋 · 2025 级 · 第 3 学期   │
│  18 门课，跳过 18 门往期 · 44 条 · 23:41  [诊断] │
└───────────────────────────────────────────┘
待完成 · 4   展开需          │ 折叠行 + 默认只露最近一项
!  第三章 …  1 天            │ 当前最近截止的作业/考试
… 已交待批阅 · 1  展开
!  往期学期 · 11   展开       │ 非本学期未完成
✓  已完成 · 90    展开
[dock 刷新]  [扫描时：进度卡 读取 i/n · 课程名 ▓▓▓░ 取消]
```

- 四个分区都是折叠行：**默认只显示最近一项待完成**，点行展开/收起
- 下拉列表 → 刷新；点卡片 → 应用内做作业；长按卡片 → 复制那一条
- 点 hero 的学期胶囊 → 填入学年份（存本地，算「第几学期」）

## 数据链路

```
打开 App → 读本地缓存（秒显）→ 后台自动扫描
  GET /visit/courses                     → 36 门课
  GET mooc1-api/backclazzdata            → 每门课 beginDate（判学期）
  课程学期 = beginDate → 上次自学习缓存 → 课程名标记 → 照扫
  往期课不发请求；照扫的课扫完按作业时间多数派回填学期缓存
  每门本学期课：
    GET /visit/stucoursemiddle → 302 → studentcourse，正则 /work/getAllWork?...
    GET /mooc-ans{路径}&start=0&size=50 → <div class="titTxt"> 分块解析
      inspectTask(未完成) / selectWorkQuestionYiPiYue(已完成)
      开始时间 / 截止时间 / 作业状态(待做|已完成|已批阅|已完成待批阅|已过期…)
扫描结果写回缓存 + 更新时间
```

状态映射：`已批阅/已完成 → 已完成`；`已完成待批阅/已提交/已上交 → 已交待批阅`；
`待做/未交/已过期/未开始 → 待完成`。

## 诊断（点 hero 右侧「诊断」）

```
course=36 items=44
courses=36 html=…
bc=12
skip 往期 写作与沟通 25-26-2
skip-past=18 scan=18
learn 电子技术 25-26-2          ← 自学习：作业都在旧学期，记下这门课的学期
works 视觉感知与应用=4
```

| 行 | 含义 |
| --- | --- |
| `bc=0` | 拿不到开课时间（退化为名字+自学习判断） |
| `skip 往期 A B` | 课程判为 B 学期，跳过 |
| `learn A B` | 无学期信号的课，由作业时间推断为 B 学期，下次自动跳过 |
| `NEED-LOGIN` / `courses=0` | 登录失效，点「切换账号」 |
| `works 课名=0` | 列表页拿到了但没解析出条目（结构变了，把日志发我） |

## 源码

```
app/src/com/dsh/cxtodo/
  MainActivity.java   hero/四分区/下拉刷新/进度卡/浏览层/诊断页/缓存
  CxClient.java       课程学期过滤(含自学习) + 作业抓取 + 状态细分
  Term.java           学期模型：ofTime / ofName / ofShort / parse
  GlassView.java      液态玻璃 + box blur
  AuroraView.java     极光背景
```

零第三方依赖，不依赖 Gradle / androidx。

## 构建

```powershell
$env:JAVA_HOME = "D:\tools\jdk17\jdk17.0.21_0"
$env:ANDROID_HOME = "D:\android-sdk"
powershell -ExecutionPolicy Bypass -File D:\dsh\cx-homework\apk\build.ps1
```

演示：`adb shell am start -n com.dsh.cxtodo/.MainActivity --ez demo true`

## 已知边界

- 自学习只对「无任何学期信号」的课生效；判错了删应用数据即可重来
- 缓存展示的是上次扫描口径；学期切换（如寒假后）首次扫描会重新自学习
- 考试条目仍只有标题（学习通门户页不给考试截止时间）
- 登录过期不自动续；点「切换账号」重登
- 仅本地自用：debug 签名、无混淆、无埋点，请求只发往 `chaoxing.com`