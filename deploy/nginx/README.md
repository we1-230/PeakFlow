# deploy/nginx —— HTTPS / 证书

nginx 容器同时监听 **80（HTTP）** 和 **443（HTTPS）**。

## 为什么要有 443

Edge / Chrome 现在默认会把 `http://` 自动升级成 `https://`。
如果容器只映射 80、nginx 只 `listen 80`，升级后的请求没有人接，浏览器直接报
**「无法访问此网站 / 连接超时」**——服务器本身其实是好的。

## 目录内容

| 文件 | 是否入库 | 说明 |
| --- | --- | --- |
| `gen-cert.sh` | ✅ 提交 | 一键生成自签证书 |
| `README.md` | ✅ 提交 | 本说明 |
| `certs/peakflow.crt` | ❌ 忽略 | 证书，由脚本生成 |
| `certs/peakflow.key` | ❌ 忽略 | **私钥，绝不能提交** |

`certs/` 整个目录在 `.gitignore` 里，新克隆的仓库没有证书文件，
**部署前必须先跑一次 `gen-cert.sh`**，否则 nginx 会因找不到证书启动失败。

## 首次部署

```bash
cd deploy/nginx
chmod +x gen-cert.sh
./gen-cert.sh 43.111.225.28        # 传你的公网 IP

cd ../..
docker compose up -d nginx
```

## 验证

```bash
curl -sI http://<IP>/            # 期望 200 或 301
curl -skI https://<IP>/          # 期望 200（-k 跳过自签校验）
curl -skI https://<IP>/api/category/list
```

## 自签证书的局限

浏览器默认不信任自签证书，访问 `https://` 会提示「你的连接不是专用连接」，
点 **高级 → 继续前往** 即可。

想彻底消除警告有两条路：

1. **只在自己电脑上临时信任**（Windows，仅当前用户，随时可撤销）

   ```powershell
   certutil -user -addstore -f Root certs\peakflow.crt
   certutil -user -delstore Root <证书指纹>   # 撤销
   ```

2. **正规做法：买一个域名 + Let's Encrypt 证书**（推荐对外使用）

## ⚠️ 同时别忘了 CORS

把站点暴露到 `https://` 之后，后端的 CORS 白名单里必须**同时包含 https 来源**，
否则所有 POST 接口会返回 `403 Invalid CORS request`（页面能打开，但数据全空）。

`.env` 里对应这一行（注意 IP 别打错）：

```ini
CORS_ALLOWED_ORIGINS=http://43.111.225.28,https://43.111.225.28,http://localhost:3000,http://127.0.0.1:3000
```

改完 `.env` 需要重建后端容器才会生效：

```bash
docker compose up -d --force-recreate backend1 backend2
```
