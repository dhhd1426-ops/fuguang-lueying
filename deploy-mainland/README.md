# 独立服务器部署

状态：部署文件已准备；Node HTTP 服务已本地验证；尚未在用户云服务器上线。生产 HTTPS、Docker/Caddy、云端网络与大陆访问速度尚未实测。

## 选择节点

如果已有备案域名，可部署到阿里云或腾讯云中国内地服务器。中国内地网站域名需完成ICP备案后上线；备案与其他适用要求请在选定服务商核实。

如果没有ICP备案，可考虑中国香港服务器。香港节点不要求同样的ICP备案，但不是大陆境内节点；大陆访问速度和稳定性需要三网实测，不能保证。其他适用备案要求请遵循服务商指引。

官方参考：
- https://www.alibabacloud.com/help/en/icp-filing/basic-icp-service/product-overview/icp-filing-requirements-for-a-regular-website
- https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/for-the-record-process-faq

## 所需资源

Linux 服务器、Docker Engine/Compose、自有域名及 DNS 管理权限。准备 HTTPS 域名；保持 80/443 可访问，DNS A/AAAA 指向实际服务器。如果没有 IPv6 服务，不配置不可达的 AAAA 记录。实例购买、域名、带宽和证书配置属于用户云账户操作。

## 启动

将完整项目目录上传到服务器，例如 `/opt/fuguang`。在项目根目录执行：

```bash
node deploy-mainland/init-config.mjs your-domain.example
cd deploy-mainland
docker compose up -d --build
```

若主机未安装 Node 22，可先由管理员安装 Node，再运行初始化；或自行在服务器安全环境中生成相同 .env 变量。初始化脚本仅接受完整真实域名，不接受网址路径，且拒绝覆盖已有 .env。

Caddy 为真实解析到当前服务器的域名申请 HTTPS。请等待证书成功签发后访问 `https://你的域名/`。外部用户无需登录 ChatGPT，输入原来的访问密码进入。

## 更新与备份

网页素材在 `public/`，服务器仅开放 public 下的文件，并使用原密码验证逻辑。更新此目录或部署服务后执行 `docker compose up -d --build`。备份 .env、证书数据卷和项目文件。不要把 .env 提交 Git 或放进共享项目包。

## 密钥

- `PASSWORD_HASH`：当前访问密码的 SHA-256。
- `SESSION_SECRET`：新环境随机签名密钥。
- `PUBLIC_ORIGIN`：完整 HTTPS origin，必须与访客域名相同。
- .env 由初始化脚本创建，权限 600。包内 .env.example 仅为说明，不能直接用于生产。
- 原有生产密钥未导出；新服务器生成的新 cookie 不兼容原 Sites cookie。

## 上传范围与视频

Docker 镜像仅包含 public、服务器和密码模块。原始 .mpkg 留在项目包中，不进入网站镜像、不提供下载。

视频支持 HTTP Range 206 与 HEAD，避免每次拖动播放位置都下载整文件。密码验证覆盖视频/JSON/JS/CSS，不能仅依赖前端遮罩。Caddy 反向代理到 app:3000，不把 Node 服务端口直接暴露到公网。

## 实测与未实测

已测：密码页、错误密码、正确登录、签名 cookie、未登录素材拦截、主页、JSON、视频 Range/HEAD、非法 Range、路径越界、请求大小、Origin 校验。
未测：真实服务器 Docker 构建、Caddy 证书签发、DNS、防火墙、三网访问速度、手机端视频首帧和动画流畅度。
