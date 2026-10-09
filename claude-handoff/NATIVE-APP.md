# 浮光掠影 · 原生 App 交接（2026-10-08）

新窗口先读这份。HANDOFF.md 讲网站，这份讲 App 和内容发布。

## 现在的状态

- 原生 Android App 已完成，交互照网站：视频背景、玻璃卡片、显示设置、手记、掠影阅读器、留言悬浮卡片、日夜主题扩散切换。
- 一套代码适配小米 17、Fold8 外屏（窄屏单栏）和 Fold8 内屏（宽屏双栏）。宽度 < 750dp 走手机布局，阅读器 < 720dp 时目录和正文切换显示。
- **还没在真机上测过**，只确认能编译。用户会在 Fold8 和小米 17 上装，等截图反馈。
- 最新 APK：仓库 `fuguang-lueying` 的 Releases，`app-build-9`（之后有改动会自动出新编号）。

## 两个仓库

| 仓库 | 可见性 | 用途 |
| --- | --- | --- |
| `dhhd1426-ops/fuguang-lueying` | 私有 | 网站源码（`main`），App 源码在 **`native-app` 分支**的 `android/` |
| `dhhd1426-ops/fuguanglueying` | 公开 | App 读取的内容：`content/luoying.json`、`content/letters.json` |

`main` 分支没有动过，所有 App 相关改动都在 `native-app`。

## 内容怎么发布（不用重新打包）

1. 改公开仓库 `fuguanglueying` 的 `content/luoying.json`（掠影）或 `content/letters.json`（留言），推到 `main`。
2. 那边的 Actions「刷新 CDN 缓存」会检查 JSON 格式并清 jsDelivr 缓存。
3. App 每次打开 / 回到前台会用 ETag 检查 `cdn.jsdelivr.net`、`fastly.jsdelivr.net`、`gcore.jsdelivr.net`，有新章节或新留言时导航的「掠影」「留言」右上角亮小点。
4. 同时把同一份文件复制到私有仓库 `native-app` 分支的 `public/content/`，作为 APK 自带的兜底内容。提交信息带 `[skip ci]` 可以不触发打包。

章节格式：`id`（如 `claude-149`，不可重复）、`sourceIndex`（排序用，最新是 147）、`title`、`group`（写作方法 / 场景练习 / 连续故事）、`text`（空行分段，以【开头的段落显示为时间地点条）、`characters`（= 正文长度）。

## 掠影收录规则（用户定的）

- 只收剧情和散文讨论，不收无关对话。
- 已略去：问人物年龄、「查户口」、Claude 跳出故事的问候及用户对此的回复、索要 txt 的几条，以及「Ok, continuing the scene」「好，之后不跳出来。接着写」这类过渡语。
- 现有 73 篇；原始对话 141 条。用户直接贴来的新章节（如 145、147）不进原始对话。
- 正文不改字，标题由 Claude 起，用户可改。

## 留言

- 18 封：原来 6 封 + 姐姐、林小满、妹妹、苏老师各 3 封（按掠影剧情写）。
- 之前写过但**没加**的两封：老张（男）和署名「掠影」的那封，用户只要女性角色的。

## 打包

- 推送 `native-app` 分支里 `android/**` 或 `public/**` 的改动会触发 `.github/workflows/android.yml`，产出 `app-build-<编号>` Release。
- 编译错误会写成 Actions 注解，用 `api.github.com/repos/.../check-runs/<job id>/annotations` 读（这边的网络直接下载日志会被拦）。
- 签名：`android/app/fuguang.keystore`，口令写在 `android/app/build.gradle.kts`。每版同一签名，可覆盖安装。
- 依赖要点：AGP 9.3.2、Kotlin 2.3.0、Compose 1.12、Media3 1.9.2、Haze 1.7.3（玻璃模糊，要求 compileSdk 37）。
- 这台云端环境连不上 Google Maven，不能本地编译，只能靠 CI。

## 代码地图（`android/app/src/main/java/com/fuguang/lueying/`）

- `App.kt`：首页、导航（含小红点）、底部卡片、纯净模式、全屏、返回键逐层关闭
- `Background.kt`：视频背景（ExoPlayer，cover 裁切、后台暂停）和渐暗遮罩
- `Overlays.kt`：弹层外壳、显示设置 / 手记 / 场景 / 关于、主题扩散动画、主题开关
- `Reader.kt`：掠影阅读器（分类、搜索、收藏、字号、进度记忆、原始对话、全屏）
- `Letters.kt`：留言列表和悬浮卡片（左右滑动切换）
- `ContentStore.kt`：在线内容、缓存、兜底、小红点
- `Theme.kt`：颜色全部取自网站 CSS，深浅两套
- `Prefs.kt`：本机存储，键名和网站 localStorage 一致

## 待办 / 用户提过但没做

- 真机反馈后修界面问题。
- 「散文」分类：从用户历史对话里挑十篇左右（白玫瑰、江南细雨、东京入夜、薰衣草、大年初七大雪等），用户还没定篇目。
- 网站（Sites 上线版）的掠影还是旧的 31 篇，没有同步；预览版在 claude.ai artifact `XrEguvhukwP6tjRuB5a4ky`。
- 迁到大陆服务器后，可以把网站地址加进 App 的内容备用地址。
- Fold8 半折「静置屏」模式（上半屏视频和时钟，下半屏控制）只是建议，没做。
- 方案图在 claude.ai artifact `Wcinmq93kV7dxUtVqv2JDT`（Design 画布）。

## 本地克隆注意

本地克隆只跟踪 `main`，需要：

```sh
git config --add remote.origin.fetch '+refs/heads/native-app:refs/remotes/origin/native-app'
git fetch origin && git checkout native-app
```
