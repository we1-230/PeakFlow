# ============================================================================
# PeakFlow 秒杀商城 · Nginx 站点公共段（被 HTTP / HTTPS 两个 server 块 include）
# ----------------------------------------------------------------------------
# 【为什么单独抽一份】
#   80 与 443 两个 server 块，"怎么托管前端产物、怎么反代 /api"完全一样，
#   只有端口和证书不同。抽成一份 ⇒ 改一处两边同时生效，
#   不会出现「HTTPS 上某个 location 忘了同步」的经典事故。
#
# 【注意】本文件不是 *.conf 结尾，nginx 不会自动加载它（只自动加载 conf.d/*.conf），
#   只能靠显式 include 生效 —— 这是故意的，否则它会被当成一个 server 级文件加载。
#
# include 的位置是 server 块【内部】，所以这里写的都是 server 级指令。
# ============================================================================

root  /usr/share/nginx/html;
index index.html;

charset utf-8;
# 与后端 spring.servlet.multipart.max-file-size 对齐（头像 / 商品图上传）
client_max_body_size 10m;

# ---------------------------------------------------------------------------
# 安全响应头
#   ⚠️ 下面所有 location 都刻意避开 add_header：缓存用 expires 表达、
#      /healthz 的 Content-Type 用 default_type 表达。
#      原因见 peakflow-security-headers.inc 的注释（add_header 就近覆盖）。
#      凡是自己设了响应头的 location，都要把这份 include 再写一遍。
# ---------------------------------------------------------------------------
include /etc/nginx/snippets/peakflow-security-headers.inc;

# ---------------------------------------------------------------------------
# 反向代理公共参数（写在 server 级，下面几条 /api 规则共用，不必各写一遍）
#   proxy_set_header / proxy_*_timeout / proxy_http_version 都允许写在 server 级。
#
# 与 frontend/vite.config.js 的 dev proxy 保持同一套前缀重写规则：
#     浏览器 /api/xxx  →  后端收到 /xxx
# 前端代码统一写 /api 前缀，开发与线上行为一致，不用分环境改代码。
# ---------------------------------------------------------------------------
proxy_http_version 1.1;
proxy_set_header   Host              $host;
proxy_set_header   X-Real-IP         $remote_addr;
proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header   X-Forwarded-Proto $scheme;
# 必须置空：否则 nginx 对每条请求都发 Connection: close，
# upstream 里配的 keepalive 32 就完全白配了
proxy_set_header   Connection        "";
# 秒杀接口最坏情况要等 MQ 降级同步落库，给足超时
proxy_connect_timeout 5s;
proxy_send_timeout    30s;
proxy_read_timeout    30s;

# ---------------------------------------------------------------------------
# gzip：JS / CSS / JSON 收益最大
# ---------------------------------------------------------------------------
gzip              on;
gzip_vary         on;
gzip_min_length   1024;
gzip_comp_level   6;
gzip_types        text/plain text/css application/json application/javascript
                  application/x-javascript text/xml application/xml
                  application/xml+rss image/svg+xml;

# ---------------------------------------------------------------------------
# Let's Encrypt 的 ACME 挑战
#   certbot 把校验文件写进宿主机 /var/www/certbot（只读挂进容器），
#   Let's Encrypt 从公网访问 http://域名/.well-known/acme-challenge/xxx 来核对。
#   ^~ 前缀匹配优先级高于普通前缀，保证它不会被别的规则抢走；
#   access_log off 是因为续期时会周期性地来，不需要留痕。
# ---------------------------------------------------------------------------
location ^~ /.well-known/acme-challenge/ {
    root         /var/www/certbot;
    default_type text/plain;
    access_log   off;
}

# ---------------------------------------------------------------------------
# 静态资源缓存
#   Vite 产物形如 assets/index-4f3a1c.js：内容一变文件名就变，
#   所以可以放心长缓存；index.html 必须不缓存，
#   否则发版后用户拿到的还是旧壳子（引用的 JS 已不存在 → 白屏）。
# ---------------------------------------------------------------------------
location /assets/ {
    include     /etc/nginx/snippets/peakflow-security-headers.inc;
    expires     1y;      # ⇒ Cache-Control: max-age=31536000
    access_log  off;
}

location = /index.html {
    include /etc/nginx/snippets/peakflow-security-headers.inc;
    expires -1;          # ⇒ Cache-Control: no-cache
}

# ---------------------------------------------------------------------------
# 接口限流
#   nginx 的 location 匹配优先级：= 精确 > ^~ 前缀 > 正则 > 普通前缀。
#   下面四条从具体到宽泛排列，最后一条 /api/ 才是兜底。
# ---------------------------------------------------------------------------

# 1) 登录：最严的一档，防撞库。
#    这里用 location = （精确匹配）而不是正则，是因为
#    proxy_pass 带 URI 时不允许出现在正则 location 里。
#    精确匹配下把 /api 前缀显式替换掉，效果与 /api/ → / 的规则一致。
location = /api/auth/login {
    limit_req  zone=pf_login burst=5 nodelay;
    limit_conn pf_conn 10;
    proxy_pass http://peakflow_backend/auth/login;
}

# 2) 注册：同样严格（防批量注册刷号）
location = /api/auth/register {
    limit_req  zone=pf_login burst=5 nodelay;
    limit_conn pf_conn 10;
    proxy_pass http://peakflow_backend/auth/register;
}

# 3) 秒杀下单：全站最热的一条路径，单独一个更紧的闸门
location = /api/seckill/order {
    limit_req  zone=pf_order burst=10 nodelay;
    limit_conn pf_conn 20;
    proxy_pass http://peakflow_backend/seckill/order;
}

# 4) 其余全部 /api：/api/xxx → 后端 /xxx
#    proxy_pass 结尾带 / ⇒ 把匹配到的 /api/ 前缀替换掉
location /api/ {
    limit_req  zone=pf_api burst=100 nodelay;
    limit_conn pf_conn 50;
    proxy_pass http://peakflow_backend/;
}

# ---------------------------------------------------------------------------
# SPA history 路由回退（必须放在最后）
#   /orders/123 这类前端路由在服务器上没有对应文件，
#   返回 index.html 交给 Vue Router 处理，否则用户一刷新就 404。
# ---------------------------------------------------------------------------
location / {
    limit_conn pf_conn 50;
    try_files $uri $uri/ /index.html;
}

# 隐藏文件与工程文件不给外网（.well-known 例外，ACME 要用）
location ~ /\.(?!well-known) {
    deny all;
}

# 健康检查：给探活 / 监控用（负载均衡器、uptime 监控都靠它），不写访问日志
location = /healthz {
    default_type text/plain;
    access_log   off;
    return 200 "ok\n";
}
