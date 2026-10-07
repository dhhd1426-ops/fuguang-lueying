# 浮光掠影：Claude 开发入口

本仓库 main 分支保存已发布网站第 11 版的完整网页源码，供复刻与原生 App 开发。先读 HANDOFF.md，再读 claude-handoff/NATIVE-APP.md。

## 实际页面与素材

- public/index.html：真实网站主页；app/page.tsx 为框架模板，不能拿来代替网站页面。
- public/letters.js、letters.css：纵向留言与展开悬浮卡片。
- public/theme.js、theme.css：留言右上角唯一开关、半月与太阳变化、主题扩散动画。
- public/reader.js、reader.css、public/content/luoying.json：阅读界面与完整内容。
- public/assets/scene.mp4、poster.jpg：原始动态背景与静帧，保持素材、画质和比例。
- build/password-gate.mjs：网页密码验证。

复刻时以这些源文件的布局、颜色、尺寸、字体栈、动效参数和交互状态为依据；先展示实现并按相同屏幕尺寸逐页对照，再确认完成。原生控件的字体渲染可能不同，不能仅凭有源码就宣称像素一致。

## 完整素材备份

GitHub Releases 中的 fuguang-latest-native-mainland.zip 保存原始导出包，包含约 76 MiB 的 original-wallpaper/2886288494.mpkg。原始壁纸包不放在普通网页上传目录；网页播放所需的视频已直接存于仓库。恢复包解压后的 fuguang-latest/ 即完整源目录。SHA-256 清单见 SOURCE-CHECKSUMS.json。

本次上传后从 GitHub 下载 main.zip 验证，141 个展开文件与原始项目导出逐字节一致。

## 开发与部署

网页预览及独立服务器部署方法见 deploy-mainland/README.md。原生开发规范见 claude-handoff/NATIVE-APP.md。Linux 上通过网页上传恢复的 shell 脚本若缺少执行权限，先运行 chmod +x scripts/*.sh；或通过 bash 调用脚本。

此文档描述网页源码基线，仓库其他分支或 Release 中的 App 构建须另外核对；上传源码不会自动完成大陆托管迁移。私有仓库须授权给 Claude 才能读取。
