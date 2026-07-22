# flux-panel_rev

`flux-panel_rev` 是一个基于 go-gost 的流量转发面板，仓库内包含面板端和节点端，适合在 VPS 上快速部署、测试和二次开发。

## 项目功能

- 面板端：React/Vite 前端、Spring Boot 后端、MySQL 数据库。
- 节点端：基于 go-gost 改造的节点程序，支持通过 WebSocket 接入面板。
- 节点管理：支持创建节点、查看节点状态、生成节点安装命令。
- 转发管理：支持端口转发、隧道转发，以及 TCP/UDP 等转发场景。
- 用户管理：支持用户、套餐、隧道权限和转发数量控制。
- 流量统计：支持入站/出站流量统计，支持单向或双向计费模式。
- 限速管理：支持按用户、隧道维度配置限速规则。
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

更新会通过 `git fetch --tags --prune --force` 拉取分支和标签，默认部署 `main`，也支持用 `DEPLOY_REF`/`--ref` 指定分支、标签或 commit。更新时会重新构建镜像、拉取基础镜像并强制重建容器，自动应用最新代码，同时保留数据库数据。

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

## 更新日志

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
- 前端 Docker 构建移除 Corepack 下载链路，改为 npm 安装 pnpm，并支持 npm registry fallback。
- 新增并提交节点端 Linux `amd64`、`arm64`、`armv7`、`armv6` 二进制。
- 移除旧的 macOS 节点二进制，避免 Linux VPS 误下载后无法运行。
- 前端 Docker 构建切换到 `pnpm-lock.yaml` 锁定依赖版本。
