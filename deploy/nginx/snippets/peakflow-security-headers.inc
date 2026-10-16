# ============================================================================
# PeakFlow · 安全响应头（可复用片段）
# ----------------------------------------------------------------------------
# 【为什么单独抽一份，还要反复 include】
#   nginx 的 add_header 是「就近覆盖」语义：
#   只要某个 location 里自己设了响应头（无论 add_header 还是 expires），
#   server 级那几条 add_header 在该 location 里就【全部失效】，不会被继承。
#   所以凡是自己设了响应头的 location（比如 /assets/ 设了 expires），
#   必须把本文件再 include 一次，安全头才不会漏。
#
# 【每个头挡的是什么】
#   X-Content-Type-Options: nosniff
#       禁止浏览器"猜"内容类型。否则攻击者上传一个内容是 HTML 的 .jpg，
#       浏览器可能当网页执行 → XSS。接口文档关闭 / 上传目录不可写，
#       这个头是第二道锁。
#   X-Frame-Options: SAMEORIGIN
#       禁止别的站点用 iframe 嵌套本站 → 防「点击劫持」（透明浮层骗点击）。
#       本站在自己的 iframe 里仍然可用。
#   Referrer-Policy: strict-origin-when-cross-origin
#       跨站跳转时不把完整 URL（可能含订单号）带给对方，只给域名。
#   Permissions-Policy
#       明确关掉定位/麦克风/摄像头权限，避免被第三方脚本悄悄申请。
#   always 参数：连 4xx/5xx 错误响应也要带上这些头，
#       否则「错误页」恰好是最容易被利用的那条路径。
# ============================================================================

add_header X-Content-Type-Options  nosniff                            always;
add_header X-Frame-Options         SAMEORIGIN                         always;
add_header Referrer-Policy         strict-origin-when-cross-origin    always;
add_header Permissions-Policy      "geolocation=(), camera=(), microphone=()" always;

# ---------------------------------------------------------------------------
# HSTS —— 为什么放在这里，而不是只写在 HTTPS 那份 server 里
#
# ★ 踩过的坑（真事）：
#   HSTS 原本只写在 deploy/nginx/templates/peakflow-tls.conf.template 的
#   443 server 块里（server 级）。结果线上 curl -I https://… 发现
#   【根本没有 Strict-Transport-Security 响应头】—— 但配置文件里明明有。
#   原因就是本文件开头讲的那条语义：locations.inc 里凡是自己设过 add_header
#   的 location（/assets/、/index.html），会把 server 级的 add_header 全部丢弃；
#   而其余 location 继承的是【本文件】这组头，HSTS 不在其中，就漏了。
#   ⇒ 结论：HSTS 必须和上面 4 个头放在【同一层级】才不会漏。
#
# ★ 放在这儿会不会污染 HTTP（80 端口）的响应？
#   会带上，但【无害】：RFC 6797 §7.2 明确规定，
#   浏览器只在【安全传输（HTTPS）】上收到 HSTS 才生效，
#   通过 http:// 收到的 Strict-Transport-Security 会被直接忽略。
#   所以 80 端口多带这一个头不产生任何行为，而换来的是「一处定义、处处生效」。
#
# ★ 值怎么选：
#   max-age=2592000（30 天）起步。⚠️ 不要急于加 includeSubDomains / preload ——
#   一旦下发，在 max-age 到期前浏览器会强制 https，配置出问题时很难回退
#   （那是"把整个域名锁死"级别的风险）。稳定跑一两个月后再往上调。
# ---------------------------------------------------------------------------
add_header Strict-Transport-Security "max-age=2592000" always;
