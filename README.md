# flux-panel_rev

`flux-panel_rev` 是一个基于 go-gost 的流量转发面板，仓库内包含面板端和节点端，适合在 VPS 上快速部署、测试和二次开发。

## 项目功能

- 面板端：React/Vite 前端、Spring Boot 后端、MySQL 数据库。
- 节点端：基于 go-gost 改造的节点程序，支持通过 WebSocket 接入面板。
- 节点管理：支持创建节点、查看节点状态、生成节点安装命令。
- 转发管理：支持端口转发、隧道转发、TCP/UDP 转发，以及多入口、多出口转发。
- 负载分摊：新增隧道可选择多个入口节点和多个出口节点，多目标或多出口时自动使用轮询分摊。
- 高吞吐默认：隧道转发默认使用适合公网大流量节点的 `mtls` 复用链路，并自动配置 keepalive 和缓冲参数。
- 用户管理：支持用户、套餐、隧道权限和转发数量控制。
- 流量统计：支持入站/出站流量统计，支持单向或双向计费模式。
- 限速管理：支持按用户、隧道维度配置限速规则。
- Cloudflare DNS/DDNS：支持隧道入口域名绑定、节点公网 IP 自动更新、A/AAAA 差异同步和定时检查。
- 自动部署：支持 VPS 一键安装、更新、卸载、状态查看、日志查看、证书续期和 Docker 缓存清理。
- 运行维护：面板容器后台运行并随 Docker 自动恢复，节点端使用 systemd 开机自启。
- 长期复用：面板前后端从本仓库源码构建，不再依赖第三方面板镜像；节点端 Linux 二进制也直接放在本仓库中。

## 部署教程

### 面板端部署

统一使用下面这一条命令：

```bash
curl -L https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh -o deploy.sh && chmod +x deploy.sh && sudo ./deploy.sh install
```

运行后会进入交互菜单：

```text
1. 安装 / 重装
2. 更新并重新构建
3. 卸载
4. 查看状态
5. 查看日志
6. 续期证书
7. 清理 Docker 缓存
0. 退出
```

安装时按提示输入面板域名。使用 HTTPS 时，请先把域名 A/AAAA 记录解析到当前 VPS，并确保 VPS 安全组或防火墙放行 `80` 和 `443` 端口。脚本会自动检查解析、配置 Nginx、申请 Let's Encrypt 证书并设置自动续期。

默认信息：

- 部署目录：`/opt/flux-panel_rev`
- 默认账号：`admin_user`
- 默认密码：`admin_user`
- 首次登录后请立即修改默认密码。

脚本会自动处理：

- 识别 Linux 发行版、CPU 架构、包管理器、init system 和虚拟化环境。
- 安装 Docker、Docker Compose、Nginx、Certbot、Git 等环境。
- 拉取当前仓库源码并构建前端、后端镜像。
- 前端依赖使用 `pnpm-lock.yaml` 锁定版本，降低后续构建漂移风险。
- 使用 Docker Compose 后台启动 MySQL、后端、前端等服务。
- 配置 Nginx 反向代理和 HTTPS 证书。
- 面板端 Docker 日志循环保存 `50MB`，超过后旧日志自动删除；同时可清理无用构建缓存和旧镜像。

### 面板端更新

仍然运行统一命令，进入菜单后选择 `2. 更新并重新构建`。

```bash
curl -L https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh -o deploy.sh && chmod +x deploy.sh && sudo ./deploy.sh install
```

更新会通过 `git fetch --tags --prune --force` 拉取分支和标签，默认部署 `main`，也支持用 `DEPLOY_REF`/`--ref` 指定分支、标签或 commit。更新时会重新构建镜像、拉取基础镜像并强制重建容器，自动应用最新代码，同时保留数据库数据；更新完成后会清理 Docker 构建缓存、悬空镜像和系统包缓存，默认将构建缓存控制在 `DOCKER_BUILD_CACHE_KEEP=512MB`。

### 面板端卸载

仍然运行统一命令，进入菜单后选择 `3. 卸载`。

```bash
curl -L https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh -o deploy.sh && chmod +x deploy.sh && sudo ./deploy.sh install
```

卸载时会提示是否删除数据库卷和部署目录，确认前不会直接清除数据。

### 节点端部署

推荐在面板中创建节点后，从“节点管理”页面复制自动生成的节点安装命令执行。

节点安装脚本会自动：

- 自动识别 Linux CPU 架构，并从本仓库 `go-gost/releases/` 下载对应节点端 `gost` 程序。
- 写入 `/etc/gost/config.json` 和 `/etc/gost/gost.json`。
- 创建 `gost.service`。
- 设置 systemd 开机自启和后台运行。
- 将节点日志写入 `/var/log/gost/gost.log`。
- 配置 logrotate，节点端日志循环保存 `50MB`，超过后旧日志自动删除。

### 转发使用

- 新增隧道时只需要选择入口节点和出口节点，协议会自动使用优化后的高吞吐默认值。
- 一个隧道可以选择多个入口节点，新增转发后会同时下发到所有入口节点。
- 一个隧道可以选择多个出口节点，多出口时转发会自动轮询分摊。
- 一个转发可以填写多个目标地址，多目标时也会自动轮询分摊。
- TCP 和 UDP 服务会同时创建，使用 UDP 场景时请确认 VPS 防火墙和安全组已放行对应 UDP 端口。

## 更新日志

### 2026-07-23

- 新增多入口、多出口隧道支持，保留旧单节点隧道数据兼容。
- 新增数据库启动迁移，自动补齐 `in_node_ids`、`out_node_ids` 等多节点字段。
- 新增转发创建、更新、删除、暂停、恢复等流程的多节点下发逻辑。
- 隧道转发支持多入口对多出口，入口链路可同时挂载多个出口节点。
- 前端隧道页面改为入口节点、出口节点多选。
- 前端转发页面支持展示多目标或多出口负载策略。
- 新建隧道默认使用 `mtls` 复用链路，自动配置 keepalive 和大流量缓冲参数。
- 多目标或多出口转发默认使用 `round` 轮询，避免流量长期压在第一个节点上。
- 隐藏协议类型和负载策略等非必要配置项，减少误配置。
- 已有隧道支持修改入口/出口节点列表，更新后会自动重建转发配置。
- 节点 IP 变更后会自动刷新相关隧道和转发配置，避免旧节点残留。
- 流量统计按单向仅上传、双向上传+下载的统一口径重新计算。
- 新增 Cloudflare DNS/DDNS 管理功能，支持为隧道绑定入口域名并自动同步 A/AAAA 记录。
- DNS 绑定默认跟随隧道入口节点，已有隧道增加或移除入口节点后会自动同步解析。
- 节点上线时可自动识别公网 IP 并更新节点服务器 IP，适配动态 IP 节点。
- 节点端上线前会通过 ip.sb、ifconfig、icanhazip、Cloudflare trace 等公网 IP 接口辅助判断真实出口 IP，后端优先采用节点上报 IP，失败时回退 WebSocket 来源 IP。
- 节点端启动日志不再输出带 `secret` 的 WebSocket 完整 URL，并重新构建 Linux `amd64`、`arm64`、`armv7`、`armv6` 二进制。
- 面板更新后自动清理 Docker 构建缓存、悬空镜像和系统包缓存，减少 VPS 磁盘空间持续增长。
- 新增 `DOCKER_BUILD_CACHE_KEEP` 部署变量，默认仅保留 `512MB` Docker build cache。
- Cloudflare DNS 同步采用差异更新，只管理本项目写入的记录，失败时保留旧记录，避免误删用户已有解析。
- Cloudflare DNS 定时同步默认 120 秒，最低 60 秒，同时支持手动全量同步和单个域名同步。
- Cloudflare 配置强制 DNS-only，不开启代理；API Token 不会在接口返回值和请求日志中暴露。

### 2026-07-22

- 项目重命名为 `flux-panel_rev`，并切换到 `w243420707/flux-panel_rev` 仓库。
- 新增 VPS 一键部署脚本 `deploy.sh`。
- 新增源码构建部署编排 `docker-compose.deploy.yml`。
- 统一部署入口，运行后进入安装、更新、卸载等交互菜单。
- 修复前端 Docker 构建时 npm peer dependency 解析失败的问题。
- 简化面板端日志策略，Docker 日志循环保存 `50MB`，超过后旧日志自动删除。
- 增加菜单项 `清理 Docker 缓存`。
- 简化节点端日志策略，节点日志循环保存 `50MB`，超过后旧日志自动删除。
- 移除旧面板安装链路对第三方面板镜像的依赖，旧入口统一转发到 `deploy.sh`。
- 节点安装脚本改为自动识别 Linux 架构，并从本仓库 `go-gost/releases/` 拉取 `gost-linux-*` 二进制。
- 节点安装脚本补充 `armv8l`、`armhf`、`armel` 等常见 ARM 别名识别。
- 面板更新脚本支持识别 Git branch、tag、commit，并在更新时强制重建容器应用最新代码。
- 面板更新后自动清理 Docker 构建缓存、悬空镜像和系统包缓存，默认将构建缓存控制在 `DOCKER_BUILD_CACHE_KEEP=512MB`。
- 前端 Docker 构建移除 Corepack 下载链路，改为 npm 安装 pnpm，并支持 npm registry fallback。
- 前端 Docker 构建镜像升级到 Node `22.13.1`，匹配 `pnpm@11.7.0` 的运行要求。
- 前端 Docker 构建改用 `pnpm ci` 和 pnpm 配置项，移除无效的 `network-timeout` 命令行参数。
- 前端默认关闭 legacy 构建并提升 Node heap，降低 VPS 上的打包内存峰值。
- 修复关闭 legacy 构建后 `toFile.mjs` 误删现代浏览器入口脚本导致页面黑屏的问题。
- 新增并提交节点端 Linux `amd64`、`arm64`、`armv7`、`armv6` 二进制。
- 移除旧的 macOS 节点二进制，避免 Linux VPS 误下载后无法运行。
- 前端 Docker 构建切换到 `pnpm-lock.yaml` 锁定依赖版本。
