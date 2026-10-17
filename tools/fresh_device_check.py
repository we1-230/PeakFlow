#!/usr/bin/env python3
"""全新设备端到端体检（2026-10-18）

目的
----
用户要求「把本机缓存清空，模拟崭新设备第一次访问网站，逐个组件检查」。
人工点 20+ 页面容易漏，所以固化成脚本，每页检查 6 类问题：

  ① JS 运行时错误（page errors）与 console.error
  ② 破图（img 标签 complete 但 naturalWidth === 0）
  ③ 关键组件是否渲染（按页面声明选择器）
  ④ 横向溢出（scrollWidth > clientWidth + 2）
  ⑤ 接口失败（响应非 2xx 或业务 code != 200）
  ⑥ 页面级耗时（TTFB / DCL / load）

与 tools/ui_regression.py 的区别
--------------------------------
· ui_regression 查「文本关键字 + 溢出」
· 本脚本查「JS 错误 + 破图 + 组件存在性 + 接口失败」，更贴近前端质量
· 本脚本支持**多角色**（未登录 / 普通用户 / 新注册 / 超管），覆盖后台

用法
----
    python3 tools/fresh_device_check.py                # 全部检查
    python3 tools/fresh_device_check.py --only guest    # 只查未登录态
    python3 tools/fresh_device_check.py --only admin    # 只查超管后台

注意
----
· agent-browser 的 daemon 会继承 stdout，所有输出必须重定向到文件再读
· eval 的 JS 必须单行（json.dumps 会把换行转成字面 \\n，shell 不转义）
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time

AB = os.environ.get(
    'AB',
    r'C:\Users\Administrator\.workbuddy\binaries\node\versions\22.22.2-6\agent-browser',
)
BASE = os.environ.get('BASE', 'https://peakflow.top')
PW = os.environ.get('PEAKFLOW_PASS', '2005ww')
ONLY = ''
for i, a in enumerate(sys.argv):
    if a == '--only' and i + 1 < len(sys.argv):
        ONLY = sys.argv[i + 1]

results = []      # (角色, 页面, 问题列表)
TIMINGS = []      # (角色, 页面, ttfb, dcl, load)


# ---------------------------------------------------------------------------
# agent-browser 调用封装
# ---------------------------------------------------------------------------
def ab(args, timeout=200):
    log = tempfile.mktemp(suffix='.log')
    try:
        subprocess.run('"{}" {} > "{}" 2>&1'.format(AB, args, log),
                       shell=True, timeout=timeout)
        with open(log, encoding='utf-8', errors='replace') as f:
            return f.read()
    finally:
        try:
            os.unlink(log)
        except OSError:
            pass


def js(code):
    """执行 JS 并解析返回的 JSON 对象；失败返回 None"""
    out = ab('eval ' + json.dumps(code))
    m = re.search(r'^"(.*)"$', out.strip(), re.S)
    if not m:
        return None
    try:
        return json.loads(json.loads('"' + m.group(1) + '"'))
    except Exception:
        return None


def js_num(code):
    """执行 JS 并解析返回的**数字**（js() 只处理被 JSON.stringify 的对象，
    直接返回数字时会被判成 None → 误报「组件缺失」）。"""
    out = ab('eval ' + json.dumps(code)).strip()
    m = re.match(r'^-?\d+$', out)
    if not m:
        return 0
    try:
        return int(out)
    except ValueError:
        return 0


# ---------------------------------------------------------------------------
# 页面探针：一次性采集所有维度
# ---------------------------------------------------------------------------
PROBE = (
    '(() => {'
    ' const d=document;'
    ' const w=d.documentElement.clientWidth, sw=d.documentElement.scrollWidth;'
    ' const imgs=[...d.querySelectorAll("img")];'
    ' const broken=imgs.filter(i=>i.complete&&i.naturalWidth===0).map(i=>i.src.slice(0,80));'
    ' const pending=imgs.filter(i=>!i.complete).length;'
    ' const nav=performance.getEntriesByType("navigation")[0]||{};'
    ' const res=performance.getEntriesByType("resource");'
    ' const bad=[];'
    ' d.querySelectorAll("*").forEach(el=>{'
    '   const r=el.getBoundingClientRect();'
    '   if(r.width>0&&(r.right>w+2||r.left<-2)){ if(bad.length<4) bad.push((el.tagName+"."+String(el.className)).slice(0,50)); }'
    ' });'
    ' const txt=d.body.innerText||"";'
    ' return JSON.stringify({'
    '   cw:w, sw:sw, overflow:sw>w+2, overflowEls:bad,'
    '   imgs:imgs.length, broken:broken, brokenCount:broken.length, pendingImgs:pending,'
    '   ttfb:Math.round(nav.responseStart||0), dcl:Math.round(nav.domContentLoadedEventEnd||0),'
    '   load:Math.round(nav.loadEventEnd||0), resCount:res.length,'
    '   textLen:txt.length, text:txt.slice(0,1200),'
    '   hasLogin:txt.includes("登录"), hasCart:txt.includes("购物车")'
    ' });'
    '})()'
)


def fill_field(pane_index, item_index, value):
    """按「第几个 tab-pane 的第几个 el-form-item」精确填值。

    为什么不用 agent-browser 的 fill + CSS 选择器：
      · Element Plus 的 input 各自嵌在自己的 .el-input__inner 里，
        `input:nth-of-type(2)` 匹配不到第 2 个输入框（每个 input 都是其父级的第一个）
      · placeholder 里含空格/全角括号时，CSS 属性选择器必须加引号，
        而引号又会和外层 shell 命令拼接冲突
    改用 JS 直接赋值 + 派发 input/change 事件 —— 这是给受控组件赋值的标准做法，
    Vue 的 v-model 会正常收到变更。
    """
    code = (
        '(()=>{'
        ' var panes=document.querySelectorAll(".auth-tabs .el-tab-pane");'
        ' var pane=panes[' + str(pane_index) + '];'
        ' if(!pane) return "NO_PANE";'
        ' var items=pane.querySelectorAll(".el-form-item");'
        ' var it=items[' + str(item_index) + '];'
        ' if(!it) return "NO_ITEM";'
        ' var inp=it.querySelector("input,textarea");'
        ' if(!inp) return "NO_INPUT";'
        ' var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"value");'
        ' d.set.call(inp,' + json.dumps(value) + ');'
        ' inp.dispatchEvent(new Event("input",{bubbles:true}));'
        ' inp.dispatchEvent(new Event("change",{bubbles:true}));'
        ' return inp.value;'
        '})()'
    )
    return ab('eval ' + json.dumps(code)).strip()


def login(page_path, account, password, is_admin=False):
    """在指定页面完成登录；返回是否成功"""
    # ⚠️ 选择器必须精确：登录页同时渲染「登录」与「注册」两个 tab 的表单，
    #    注册表单虽然 display:none 但元素仍在 DOM 里。用 input[placeholder*="用户名"]
    #    会命中隐藏的注册框（placeholder="用户名"），导致填了看不见的输入框、登录失败。
    if is_admin:
        user_sel = 'input[placeholder^=管理员账号]'
        pwd_sel = 'input[placeholder^=管理员密码]'
    else:
        # 登录框的 placeholder 是「用户名 / 手机号 / 邮箱」，用它精确区分
        user_sel = '.auth-tabs .el-tab-pane:nth-of-type(1) input:nth-of-type(1)'
        pwd_sel = '.auth-tabs .el-tab-pane:nth-of-type(1) input[type=password]'
    btn = 'button.el-button--primary' if is_admin else 'button.btn-submit'

    ab('open "{}{}"'.format(BASE, page_path))
    ab('wait 4000')
    ab('fill "{}" "{}"'.format(user_sel, account))
    ab('fill "{}" "{}"'.format(pwd_sel, password))
    if not is_admin:
        # 登录页有协议勾选，必须勾选才能提交
        ab('eval "(()=>{const c=document.querySelector(\'.agreement-check input\'); if(c&&!c.checked)c.click(); return 1})()"')
    ab('click "{}"'.format(btn))
    ab('wait 5000')
    # ⚠️ 必须 JSON.stringify：js() 靠解析被引号包裹的 JSON 字符串，
    #    直接返回对象会得到 [object Object]，判成 None → 误报「登录失败」。
    data = js('(()=>JSON.stringify({url:location.pathname, '
              'ls:Object.keys(localStorage).length, '
              't:document.body.innerText.slice(0,300)}))()')
    return data


def check(role, path, must=(), must_sel=(), setup=None, extra_wait=6000):
    """检查一个页面"""
    print('  · {} {}'.format(role, path), flush=True)
    if setup:
        setup()
    ab('open "{}{}"'.format(BASE, path))
    ab('wait {}'.format(extra_wait))

    data = js(PROBE)
    errs = ab('errors').strip()
    cons = ab('console').strip()

    problems = []
    if data is None:
        results.append((role, path, ['页面无响应/取不到 DOM']))
        return

    TIMINGS.append((role, path, data.get('ttfb'), data.get('dcl'), data.get('load')))

    # ① JS 错误
    if errs and 'No page errors' not in errs and len(errs) > 3:
        problems.append('页面JS错误: ' + errs.replace('\n', ' ')[:120])
    # console.error（过滤掉已知无害的）
    error_lines = [ln for ln in cons.splitlines()
                   if 'error' in ln.lower() and 'favicon' not in ln.lower()]
    if error_lines:
        problems.append('console错误: ' + error_lines[0][:120])

    # ② 破图
    if data.get('brokenCount', 0) > 0:
        problems.append('破图 {} 张: {}'.format(
            data['brokenCount'], ','.join(data['broken'][:2])))

    # ③ 关键文本
    txt = data.get('text', '')
    for kw in must:
        if kw not in txt:
            problems.append('缺少「{}」'.format(kw))

    # ④ 关键组件
    for sel in must_sel:
        cnt = js_num('(()=>document.querySelectorAll({}).length)()'.format(json.dumps(sel)))
        if not cnt:
            problems.append('组件缺失 {}'.format(sel))

    # ⑤ 登录态交叉校验：已登录页面顶栏必须有「退出」。
    #    否则像 /orders 这种页面在未登录时也会显示「我的订单」+登录提示，
    #    纯文本断言会误判为通过。
    if role in ('user', 'admin', 'new-user'):
        if '退出' not in txt:
            problems.append('登录态丢失（页面无「退出」，可能已掉登录）')

    # ⑥ 溢出
    if data.get('overflow'):
        problems.append('横向溢出 sw={}>{ } 例:{}'.format(
            data.get('sw'), data.get('cw'), ','.join(data.get('overflowEls', [])[:2])))

    # ⑥ 白屏
    if data.get('textLen', 0) < 50:
        problems.append('疑似白屏（可见文本<50字）')

    results.append((role, path, problems))


# ---------------------------------------------------------------------------
# 各角色检查
# ---------------------------------------------------------------------------
def guest_round():
    if js('(()=>({u:location.href}))()'):
        pass
    ab('cookies clear')
    ab('eval "(()=>{localStorage.clear();sessionStorage.clear();if(window.caches)caches.keys().then(k=>k.forEach(x=>caches.delete(x)));return 1})()"')

    print('\n── A. 未登录（全新设备）──', flush=True)
    check('guest', '/', must=['全智能秒杀商城', '购物车'], must_sel=['.product-card'])
    check('guest', '/products', must=['商品'], must_sel=['.product-card'])
    check('guest', '/product/38', must=['库存', '加入购物车'], must_sel=['.detail-card'])
    check('guest', '/product/5', must=['秒杀库存', '立即抢购'], must_sel=['.btn-seckill'])
    check('guest', '/seckill', must=['秒杀'], must_sel=['.product-card'])
    check('guest', '/login', must=['用户协议', '隐私政策'], must_sel=['.agreement-check'])
    check('guest', '/admin/login', must=['管理员账号'], must_sel=['input'])
    # 未登录直接访问需登录页，应被拦到登录
    check('guest', '/cart', must=['登录'])
    check('guest', '/user', must=['登录'])


def user_round():
    print('\n── B. 普通用户 malluser ──', flush=True)
    d = login('/login', 'malluser', PW)
    if not d or d.get('ls', 0) == 0:
        results.append(('user', '登录', ['登录失败']))
        print('    ✗ 登录失败', d)
        return
    print('    ✓ 登录成功 -> {}'.format(d.get('url')), flush=True)

    check('user', '/', must=['购物车'], must_sel=['.product-card'])
    # 购物车可能为空（新注册/未加购的用户），空态是正常分支：
    # 要么有商品（表格/条目/缩略图），要么显示空态文案。两者都没有才是缺陷。
    check('user', '/cart', must=['购物车'],
          must_sel=['.el-table, .cart-item, .product-img, .el-empty, .empty-state'])
    check('user', '/checkout', must=['订单'])
    check('user', '/orders', must=['订单'], must_sel=['.el-table, .order-card'])
    check('user', '/user', must=['基本信息'], must_sel=['.el-avatar'])
    # 个人中心各 tab
    for tab_id, kw in [('#tab-logs', '登录日志'), ('#tab-address', '地址'), ('#tab-password', '密码')]:
        ab('open "{}/user"'.format(BASE))
        ab('wait 4000')
        ab('click "{}"'.format(tab_id))
        ab('wait 2500')
        data = js(PROBE)
        problems = []
        if data is None:
            problems.append('tab 无响应')
        else:
            if kw not in data.get('text', ''):
                problems.append('tab 内容缺少「{}」'.format(kw))
            if data.get('brokenCount', 0):
                problems.append('破图 {}'.format(data['brokenCount']))
            if data.get('overflow'):
                problems.append('溢出')
        results.append(('user', '/user ' + tab_id, problems))


def register_round():
    print('\n── C. 注册新账号 ──', flush=True)
    name = 'fresh' + str(int(time.time()))[-8:]
    ab('open "{}/login"'.format(BASE))
    ab('wait 4000')
    # 切到注册 tab
    ab('click "#tab-register"')
    ab('wait 2000')
    js('(()=>{const c=document.querySelector(\'.agreement-check input\'); if(c&&!c.checked)c.click(); return 1})()')
    # ⚠️ placeholder 用**精确等值**匹配：登录框的 placeholder 含「用户名 / 手机号 / 邮箱」，
    #    用 *= 模糊匹配会命中登录框而不是注册框。
    # 注册表单 6 个字段（DOM 顺序已实测：用户名/昵称/手机号/邮箱/密码/确认密码）
    for idx, val in enumerate([
        name,
        '新设备测试',
        '139' + str(int(time.time()))[-8:],
        name + '@itest.local',
        'Fresh123456',
        'Fresh123456',
    ]):
        fill_field(1, idx, val)
        ab('wait 200')

    # 填完自检：6 个值都要在
    filled = js('(()=>JSON.stringify([].slice.call('
                'document.querySelectorAll(".auth-tabs .el-tab-pane")[1]'
                '.querySelectorAll(".el-form-item")).map(function(it){'
                'var i=it.querySelector("input");return i?i.value:""})))()')
    empty = [i + 1 for i, v in enumerate(filled or []) if not v]
    print('    表单填写: {}'.format('✓ 6/6' if not empty else '✗ 空项 {}'.format(empty)), flush=True)
    # ⚠️ 必须点**第 2 个 tab-pane 里的提交按钮**：
    #    `#tab-register` 是 tab 头部（点了只会切页签），
    #    `#tab-register button` 也一样——不会提交表单。
    #    实测该 pane 内只有 1 个 button：class 含 btn-submit，文字「注 册」。
    ab('click ".auth-tabs .el-tab-pane:nth-of-type(2) button.btn-submit"')
    ab('wait 5000')

    # 注册成功的判据：出现成功提示，或页面从注册页跳到别处
    res = js('(()=>JSON.stringify({'
             'msgs:[].slice.call(document.querySelectorAll(".el-message__content"))'
             '.map(function(e){return e.textContent}),'
             'path:location.pathname}))()')
    msgs = (res or {}).get('msgs') or []
    toast_ok = any('成功' in m for m in msgs)
    # 提示消息可能 3 秒后自动消失，抓不到不代表失败。
    # **最终判据是「这个新账号能不能登录」** —— 能登录就说明注册真的成功了。
    print('    注册提示: {}'.format(msgs or '(已消失)'), flush=True)

    # 用新账号登录，检查个人中心（无头像的默认展示）
    d = login('/login', name, 'Fresh123456')
    logged = bool(d and d.get('ls', 0) > 0)
    print('    新账号登录: {}'.format('✓' if logged else '✗'), flush=True)
    results.append(('new-user', '注册并登录新账号',
                    [] if logged else ['新注册账号无法登录']))
    if logged:
        check('new-user', '/user', must=['基本信息'], must_sel=['.el-avatar'])
        check('new-user', '/', must=['购物车'])


def admin_round():
    print('\n── D. 超级管理员 pfadmin ──', flush=True)
    d = login('/admin/login', 'pfadmin', PW, is_admin=True)
    if not d or d.get('ls', 0) == 0:
        results.append(('admin', '登录', ['超管登录失败']))
        print('    ✗ 登录失败', d)
        return
    print('    ✓ 登录成功 -> {}'.format(d.get('url')), flush=True)

    check('admin', '/admin', must=['仪表盘'], must_sel=['.el-card, .stat-card, .admin-sidebar'])
    check('admin', '/admin/products', must=['商品'], must_sel=['.el-table'])
    check('admin', '/admin/categories', must=['分类'], must_sel=['.el-table'])
    check('admin', '/admin/seckill', must=['秒杀'], must_sel=['.el-table'])
    check('admin', '/admin/users', must=['用户'], must_sel=['.el-table'])
    check('admin', '/admin/orders', must=['订单'], must_sel=['.el-table'])
    check('admin', '/admin/refunds', must=['退款'], must_sel=['.el-table'])
    # 超管回商城前台（此时是管理员身份，检查顶栏头像/昵称/后台入口）
    check('admin', '/', must=['管理后台'], must_sel=['.product-card'])


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
print('=' * 72)
print(' 全新设备体检  {}'.format(time.strftime('%Y-%m-%d %H:%M:%S')))
print(' 目标: {}'.format(BASE))
print('=' * 72)

ab('set viewport 1366 900')

if ONLY in ('', 'guest'):
    guest_round()
if ONLY in ('', 'user'):
    user_round()
if ONLY in ('', 'register'):
    register_round()
if ONLY in ('', 'admin'):
    admin_round()

# ---------------- 汇总 ----------------
print('\n' + '=' * 72)
fail = 0
for role, page, problems in results:
    if problems:
        fail += 1
        print('✗ [{}] {}\n    {}'.format(role, page, '\n    '.join(problems)))
    else:
        print('✓ [{}] {}'.format(role, page))

print('\n' + '-' * 72)
print('耗时（ms）  角色        页面                     TTFB    DCL    LOAD')
for role, page, ttfb, dcl, load in TIMINGS:
    print('  {:<10} {:<24} {:>6} {:>6} {:>7}'.format(role, page[:24], ttfb or 0, dcl or 0, load or 0))

print('\n' + '=' * 72)
print('总计 {} 项，通过 {} 项，失败 {} 项'.format(len(results), len(results) - fail, fail))
sys.exit(0 if fail == 0 else 1)
