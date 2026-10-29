# 生化通 Web 用户端

在仓库根目录运行开发服务器：

```powershell
pnpm dev:user
```

地址为 `http://localhost:5174`。开发环境默认将 `/v1` 代理到 `http://localhost:8080`；需要使用其他本地 API 时设置 `SHTONG_DEV_API_TARGET`。

如需临时更换端口，可运行：

```powershell
pnpm --filter @shtong/web-user exec nuxt dev . --host localhost --port 5184
```

本地开发代理仅用于开发环境。生产部署应配置正式 API 地址、反向代理和允许来源。
