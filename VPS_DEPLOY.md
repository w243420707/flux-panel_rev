# VPS 一键部署

```bash
curl -L https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh -o deploy.sh && chmod +x deploy.sh && sudo ./deploy.sh install
```

脚本会自动做这些事：

1. 识别系统、架构、包管理器和 `systemd`
2. 安装 Docker、Docker Compose、Nginx、Certbot、Git、DNS 工具
3. 拉取仓库源码并用 Docker 构建前后端
4. 自动配置 Nginx 反向代理
5. 如果填写了域名，会自动检查解析、申请证书、配置续期
6. 容器全部设置为后台运行，并随机器开机自动恢复

常用命令：

```bash
sudo ./deploy.sh update
sudo ./deploy.sh status
sudo ./deploy.sh logs
sudo ./deploy.sh renew-cert
sudo ./deploy.sh uninstall
```
