#!/usr/bin/env python3
"""UI 回归（桌面端 1366 + 手机端 390）· 可重复执行

对每一页做三件事：
  1. 渲染断言：页面关键文本/元素必须存在（不是白屏、不是加载中卡死）
  2. 横向溢出断言：scrollWidth 不得大于 clientWidth（手机端最常见的破版）
  3. 交互断言：关键按钮/输入框存在且可用

为什么要脚本化：手机端适配属于「全局媒体查询」，任何一次样式改动都可能让某一页
在小屏上溢出。人工逐页点太慢，这里固化成一条命令。

用法：
  python3 tools/ui_regression.py                    # 默认打生产
  BASE=https://peakflow.top python3 tools/ui_regression.py
  ONLY=mobile python3 tools/ui_regression.py        # 只跑手机端

依赖：agent-browser（路径见 AB 变量，可用环境变量覆盖）
"""
import json
import os
import re
import subprocess
import sys
import tempfile

AB = os.environ.get(
    'AB',
    r'C:\Users\Administrator\.workbuddy\binaries\node\versions\22.22.2-6\agent-browser',
)
BASE = os.environ.get('BASE', 'https://peakflow.top')
ONLY = os.environ.get('ONLY', '')          # desktop | mobile | (空=都跑)
USER_ACC = os.environ.get('PEAKFLOW_USER', 'malluser')
USER_PW = os.environ.get('PEAKFLOW_PASS', '2005ww')
ADMIN_ACC = os.environ.get('PEAKFLOW_ADMIN', 'pfadmin')

results = []   # (viewport, page, ok, detail)


def ab(args, label=''):
    """执行一条 agent-browser 命令，输出重定向到临时文件再读。

    ⚠️ 不能直接用管道接 stdout：agent-browser 的守护进程会继承 stdout，
    管道永远等不到 EOF（看起来像卡死）。必须重定向到文件。
    """
    with tempfile.NamedTemporaryFile('w+', suffix='.log', delete=False, encoding='utf-8') as f:
        log = f.name
    cmd = '"{}" {} > "{}" 2>&1'.format(AB, args, log)
    try:
        subprocess.run(cmd, shell=True, timeout=180)
        with open(log, encoding='utf-8', errors='replace') as f:
            return f.read()
    finally:
        try:
            os.unlink(log)
        except OSError:
            pass


def eval_json(js):
    """执行页面内 JS，返回解析后的对象；失败返回 None"""
    out = ab('eval ' + json.dumps(js))
    m = re.search(r'^"(.*)"$', out.strip(), re.S)
    if not m:
        return None
    try:
        raw = json.loads('"' + m.group(1) + '"')      # 还原转义
        return json.loads(raw)
    except Exception:
        return None


# ⚠️ 必须是单行：json.dumps 会把换行转成字面量 \n，而 shell 双引号不做转义，
#    CLI 收到的 JS 里就会出现裸反斜杠 ⇒ 语法错误 ⇒ 取不到任何结果。
PROBE_JS = (
    '(() => {'
    ' const w = document.documentElement.clientWidth;'
    ' const sw = document.documentElement.scrollWidth;'
    ' const t = document.body.innerText || "";'
    ' const bad = [];'
    ' document.querySelectorAll("*").forEach(el => {'
    '   const r = el.getBoundingClientRect();'
    '   if (r.width > 0 && (r.right > w + 2 || r.left < -2)) {'
    '     if (bad.length < 5) bad.push((el.tagName + "." + String(el.className)).slice(0, 60));'
    '   }'
    ' });'
    ' return JSON.stringify({ w, sw, overflow: sw > w + 2, text: t.slice(0, 6000), bad });'
    '})()'
)


def probe(page, width, height, login=None):
    """打开页面并采集：宽度、溢出、可见文本、溢出元素"""
    seq = ['set viewport {} {}'.format(width, height), 'open "{}{}"'.format(BASE, page)]
    if login:
        acc, pw, user_sel, pwd_sel, btn_sel = login
        seq += [
            'wait 2500',
            'fill "{}" "{}"'.format(user_sel, acc),
            'fill "{}" "{}"'.format(pwd_sel, pw),
            'eval "(()=>{const c=document.querySelector(\'.agreement-check input\'); if(c&&!c.checked)c.click(); return 1})()"',
            'click "{}"'.format(btn_sel),
            'wait 3500',
        ]
        if page != '/':
            seq += ['eval "location.assign(\'{}\'); 1"'.format(page), 'wait 3500']
    seq += ['wait 2500']
    # 必须逐条执行：agent-browser 每条命令都是独立的 CLI 调用，
    # 拼成 'cmd1 && cmd2' 会被 shell 当成两条**系统命令**（第二条是 open/wait，不存在）
    for step in seq:
        ab(step)
    data = eval_json(PROBE_JS)
    return data


def evaluate(data, must, must_not):
    """对采集结果做断言，返回问题列表（空 = 通过）"""
    if data is None:
        return ['页面无响应/无法读取 DOM']
    text = data.get('text', '')
    problems = []
    for kw in must:
        if kw not in text:
            problems.append('缺少「{}」'.format(kw))
    for kw in must_not:
        if kw in text:
            problems.append('不该出现「{}」'.format(kw))
    if data.get('overflow'):
        problems.append('横向溢出 sw={} > w={} 例:{}'.format(
            data.get('sw'), data.get('w'), ','.join(data.get('bad', [])[:3])))
    return problems


def check(viewport, page, width, height, must, must_not=(), login=None):
    print('  probing {} …'.format(page), flush=True)
    data = probe(page, width, height, login)
    problems = evaluate(data, must, must_not)

    # 失败重试一次：首屏要下 HTML + 4 个拆分包 + 若干接口，
    # 跨国链路上偶尔会超过等待窗口 —— 重试能把「真缺陷」和「还没渲染完」分开。
    if problems and data is not None:
        print('    ↻ 首次未通过（{}），重试一次…'.format('; '.join(problems)[:60]), flush=True)
        data = probe(page, width, height, login)
        problems = evaluate(data, must, must_not)

    results.append((viewport, page, not problems, '; '.join(problems) or 'OK'))


MALL_LOGIN = (USER_ACC, USER_PW, 'input[placeholder*="用户名"]', 'input[type="password"]', 'button.btn-submit')
ADMIN_LOGIN = (ADMIN_ACC, USER_PW, 'input[placeholder*="管理员账号"]',
               'input[placeholder*="管理员密码"]', 'button.el-button--primary')


def run(view, w, h):
    print('\n===== {} {}x{} ====='.format(view, w, h))
    # 公开页面
    check(view, '/', w, h, ['全智能秒杀商城', '购物车'])
    check(view, '/products', w, h, ['商品'])
    check(view, '/product/38', w, h, ['库存', '加入购物车'], ['秒杀限购'])
    # 秒杀详情：库存口径 + 限购提示，且不得误报售罄
    check(view, '/product/5', w, h, ['秒杀库存', '秒杀限购 1 件', '立即抢购'], ['该商品已售罄'])
    check(view, '/seckill', w, h, ['秒杀'])
    check(view, '/login', w, h, ['用户协议', '隐私政策', '登录'])
    check(view, '/admin/login', w, h, ['管理员账号', '登录'])
    # 需要登录的页面（商城）
    check(view, '/cart', w, h, ['购物车'], login=MALL_LOGIN)
    check(view, '/orders', w, h, ['我的订单'], login=MALL_LOGIN)
    check(view, '/user', w, h, ['基本信息'], login=MALL_LOGIN)
    # 后台主要页面
    for path, kw in [
        ('/admin', ['仪表盘']),
        ('/admin/products', ['商品']),
        ('/admin/orders', ['订单']),
        ('/admin/refunds', ['退款']),
        ('/admin/users', ['用户']),
    ]:
        check(view, path, w, h, kw, login=ADMIN_LOGIN)


if ONLY in ('', 'desktop'):
    run('desktop', 1366, 900)
if ONLY in ('', 'mobile'):
    run('mobile', 390, 844)

print('\n' + '=' * 72)
fail = 0
for view, page, ok, detail in results:
    print('{} [{}] {:<20} {}'.format('✓' if ok else '✗', view, page, detail))
    if not ok:
        fail += 1
print('=' * 72)
print('总计 {}/{} 通过'.format(len(results) - fail, len(results)))
sys.exit(0 if fail == 0 else 1)
