#!/usr/bin/env python3
"""把境外图床上的商品图/分类图标下载到本机，并按展示场景生成两档尺寸。

## 为什么需要它

线上商品图 100 条、分类图标 10 条全部指向 `images.unsplash.com`（境外 CDN），
前端轮播还有 2 张硬编码的 unsplash 地址。带来三个问题：

  ① 跨国链路要额外做 DNS + TCP + TLS 三次握手（≈1 秒/域名，且首访无缓存）
  ② 图片尺寸不受控：统一 w=800，而列表卡片实际只展示 200~260px
  ③ 无法利用本机的长缓存与 HTTP/2 同域复用

本地化后：图从 `https://域名/uploads/products/xxx-800.jpg` 出，
走已有的 `/uploads/` location（30 天缓存）、同域 HTTP/2、且列表页只取 400px 档。

## 两档尺寸

    列表 / 卡片 / 缩略  →  xxx-400.jpg   （q82）
    详情页主图 / 轮播   →  xxx-800.jpg   （q82）

数据库里存 **-800** 那一档（详情用），前端 `resolveImage()` 按场景把
`-800` 换成 `-400`。这样同一个商品在不同页面拿不同尺寸，不用后端做动态裁剪
（本机 nginx 未编译 image_filter 模块）。

## 用法

    # 1) 预览：只统计，不下载、不改库
    python3 tools/localize_images.py --dry-run

    # 2) 下载：写入 uploads/products/，并生成 SQL 映射文件（不执行）
    python3 tools/localize_images.py --download --sql-out /tmp/localize.sql

    # 3) 执行 SQL（改数据库）
    mysql < /tmp/localize.sql

    # 4) 校验：逐条检查新 URL 是否都能取到图
    python3 tools/localize_images.py --verify

    # 5) 轮播图（前端硬编码，需按 1000x400 裁剪）
    python3 tools/localize_images.py --banners

## 设计取舍

· **下载成功才写 SQL**：任何一张失败都不生成它的 UPDATE，绝不出现「库里有 URL、盘上没文件」
· **幂等**：文件已存在且大小正常则跳过下载；SQL 用固定规则生成，重复执行结果一致
· **不改原图比例**：只按宽度等比缩放，不裁剪（避免商品被切掉）
· **保留 JPEG 质量 82**：肉眼几乎无损，体积约为 q95 的三分之一
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# 本机有系统代理，不绕过会把外网请求打回 502
urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({})))

DEST_DIR = os.environ.get('DEST_DIR', '/var/lib/docker/volumes/peakflow_peakflow-uploads/_data/products')
PUBLIC_BASE = os.environ.get('PUBLIC_BASE', 'https://peakflow.top/uploads/products')
SIZES = (400, 800)
QUALITY = 82
# 轮播是 1200 宽的容器，单独一档
BANNER_SIZE = 1000

# 前端 Home.vue 里硬编码的轮播图（原先直接指向 unsplash）。
# 轮播容器是「固定高 400 + 居中裁剪」，所以不能只等比缩放，必须裁成 1000x400。
BANNERS = {
    'banner-seckill': 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da',
    'banner-mall': 'https://images.unsplash.com/photo-1556742502-ec7c0e9f34b1',
}
BANNER_W, BANNER_H = 1000, 400

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0 Safari/537.36')


def mysql(sql):
    """在容器里执行 SQL，返回按行分割的结果（制表符分隔）"""
    pw = subprocess.run(
        "grep '^DB_PASSWORD=' /opt/PeakFlow/.env | head -1 | cut -d= -f2",
        shell=True, capture_output=True, text=True).stdout.strip()
    cmd = ("docker exec -i -e MYSQL_PWD='{}' peakflow-mysql "
           "mysql -uroot peakflow_prod -N -e {}".format(pw, shq(sql)))
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        print('SQL 失败:', r.stderr[:300], file=sys.stderr)
        return []
    return [ln for ln in r.stdout.splitlines() if ln.strip()]


def shq(s):
    """单引号包裹，供 shell 使用"""
    return "'" + s.replace("'", "'\\''") + "'"


def slug_from_url(url):
    """从 unsplash URL 里取出稳定的图片标识作为文件名。

    unsplash 的路径形如 /photo-1505740420928-5e560c06d30e，
    我们去掉 `photo-` 前缀，只保留可读的 id 部分。
    非 unsplash 的 URL 用短哈希兜底，保证任何输入都能得到合法文件名。
    """
    m = re.search(r'/(photo-[A-Za-z0-9_-]+)', url)
    if m:
        return m.group(1).replace('photo-', '')
    import hashlib
    return 'img' + hashlib.md5(url.encode()).hexdigest()[:12]


def fetch(url, retries=3, timeout=60):
    """下载图片字节，带重试。unsplash 偶尔会 503。"""
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:      # noqa: BLE001 网络异常种类多，统一重试
            last = e
            time.sleep(1.5 * (i + 1))
    print('   ✗ 下载失败 {}: {}'.format(url[:80], last), file=sys.stderr)
    return None


def ensure_variants(raw, slug):
    """把一张原图写成 -400 / -800（以及轮播用的 -1000）多档 JPEG。

    返回实际生成的宽度列表，供日志与校验使用。
    """
    from PIL import Image
    import io

    os.makedirs(DEST_DIR, exist_ok=True)
    made = []
    with Image.open(io.BytesIO(raw)) as im:
        # 统一成 RGB：商品图不需要透明；带 alpha 直接转 RGB 会变黑底，所以铺白底
        if im.mode in ('RGBA', 'LA', 'P'):
            im = im.convert('RGBA')
            bg = Image.new('RGB', im.size, (255, 255, 255))
            bg.paste(im, mask=im.split()[-1])
            im = bg
        else:
            im = im.convert('RGB')

        w, h = im.size
        for size in SIZES + (BANNER_SIZE,):
            out = os.path.join(DEST_DIR, '{}-{}.jpg'.format(slug, size))
            # 只缩不放：原图比目标窄就按原宽输出（避免放大糊掉）
            if w > size:
                nh = max(1, round(h * size / w))
                cur = im.resize((size, nh), Image.LANCZOS)
            else:
                cur = im
            cur.save(out, 'JPEG', quality=QUALITY, optimize=True, progressive=True)
            os.chmod(out, 0o644)
            made.append((size, os.path.getsize(out)))

    # 关键：容器内后端以 UID 1001 运行，root 写的文件它读不了
    try:
        os.chown(DEST_DIR, 1001, 1001)
        for f in os.listdir(DEST_DIR):
            os.chown(os.path.join(DEST_DIR, f), 1001, 1001)
    except (AttributeError, PermissionError):
        subprocess.run('chown -R 1001:1001 {} 2>/dev/null'.format(shq(DEST_DIR)),
                       shell=True)
    return made


def collect():
    """收集需要本地化的条目：(表, 主键, 字段, 当前URL)"""
    rows = []
    for line in mysql("SELECT id, image FROM product WHERE image LIKE '%unsplash%';"):
        pid, url = line.split('\t', 1)
        rows.append(('product', pid, 'image', url))
    for line in mysql("SELECT id, icon FROM category WHERE icon LIKE '%unsplash%';"):
        cid, url = line.split('\t', 1)
        rows.append(('category', cid, 'icon', url))
    for line in mysql("SELECT id, image FROM product WHERE image LIKE '%favicon.ico%';"):
        pid, url = line.split('\t', 1)
        rows.append(('product', pid, 'image', url))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true', help='只统计，不下载')
    ap.add_argument('--download', action='store_true', help='下载并生成两档图')
    ap.add_argument('--sql-out', default='', help='把 UPDATE 语句写到该文件')
    ap.add_argument('--verify', action='store_true', help='校验库里 URL 是否都可访问')
    ap.add_argument('--banners', action='store_true',
                    help='只处理前端硬编码的轮播图（1000x400 裁剪）')
    args = ap.parse_args()

    if args.verify:
        return do_verify()
    if args.banners:
        return make_banners()

    rows = collect()
    print('待处理条目: {} 条'.format(len(rows)))
    by_slug = {}
    for table, pk, col, url in rows:
        by_slug.setdefault(slug_from_url(url), []).append((table, pk, col, url))
    print('去重后的图片: {} 张\n'.format(len(by_slug)))

    if args.dry_run:
        for slug, items in list(by_slug.items())[:8]:
            print('  {:<40s} 被 {} 条记录引用  例: {}'.format(slug, len(items), items[0][3][:70]))
        print('  …')
        print('\n预计生成 {} 个文件（每张 {} 档）'.format(len(by_slug) * 3, len(SIZES) + 1))
        return 0

    if not args.download:
        ap.print_help()
        return 0

    sql_lines = ['-- 由 tools/localize_images.py 生成',
                 '-- 把境外图床地址换成本机 /uploads/products/ 下的静态文件',
                 'START TRANSACTION;']
    ok = fail = skipped = 0
    total_before = total_after = 0

    for i, (slug, items) in enumerate(sorted(by_slug.items()), 1):
        target800 = os.path.join(DEST_DIR, '{}-800.jpg'.format(slug))
        # 幂等：已有且大小合理就跳过下载
        if os.path.exists(target800) and os.path.getsize(target800) > 1024:
            skipped += 1
            ok += 1
        else:
            url = items[0][3]
            # 用 unsplash 的 fit=max 拿原图，避免二次放大
            fetch_url = url
            if 'unsplash' in url:
                base = url.split('?')[0]
                fetch_url = base + '?auto=format&fit=max&q=90&w=1600'
            raw = fetch(fetch_url)
            if raw is None:
                fail += 1
                continue
            try:
                made = ensure_variants(raw, slug)
                total_before += len(raw)
                total_after += sum(s for size, s in made if size == 800)
                ok += 1
            except Exception as e:                       # noqa: BLE001
                print('   ✗ 处理失败 {}: {}'.format(slug, e), file=sys.stderr)
                fail += 1
                continue

        new_url = '{}/{}-800.jpg'.format(PUBLIC_BASE, slug)
        for table, pk, col, _old in items:
            sql_lines.append(
                "UPDATE {} SET {} = '{}' WHERE id = {};".format(table, col, new_url, pk))

        if i % 10 == 0 or i == len(by_slug):
            print('  进度 {}/{}  ✓{} ✗{} 跳过{}'.format(i, len(by_slug), ok, fail, skipped),
                  flush=True)

    sql_lines.append('COMMIT;')

    print('\n下载完成: 成功 {} 张（其中跳过 {}），失败 {}'.format(ok, skipped, fail))
    if total_before:
        print('原图合计 {:.1f}MB → 800 档合计 {:.1f}MB（省 {:.0f}%）'.format(
            total_before / 1048576, total_after / 1048576,
            (1 - total_after / total_before) * 100))

    if args.sql_out:
        with open(args.sql_out, 'w', encoding='utf-8') as f:
            f.write('\n'.join(sql_lines) + '\n')
        print('SQL 已写入 {}（{} 条 UPDATE）'.format(
            args.sql_out, len([x for x in sql_lines if x.startswith('UPDATE')])))
    else:
        print('（未指定 --sql-out，仅下载文件，数据库未改）')

    print('\n文件目录: {}'.format(DEST_DIR))
    return 0 if fail == 0 else 1


def make_banners():
    """下载前端硬编码的轮播图，居中裁剪成 1000x400。

    为什么单独处理：轮播是「固定高 400px + object-fit: cover」，
    若只按宽等比缩放，图会被拉变形。必须按 1000:400 的比例先裁剪再缩放。
    """
    from PIL import Image

    os.makedirs(DEST_DIR, exist_ok=True)
    for name, url in BANNERS.items():
        raw = fetch(url + '?auto=format&fit=max&q=90&w=1600')
        if raw is None:
            print('   ✗ 轮播图下载失败:', name, file=sys.stderr)
            continue
        with Image.open(__import__('io').BytesIO(raw)) as im:
            im = im.convert('RGB')
            sw, sh = im.size
            target = BANNER_W / BANNER_H
            if sw / sh > target:                 # 太宽：裁左右
                nw = int(sh * target)
                left = (sw - nw) // 2
                im = im.crop((left, 0, left + nw, sh))
            else:                                # 太高：裁上下
                nh = int(sw / target)
                top = (sh - nh) // 2
                im = im.crop((0, top, sw, top + nh))
            im = im.resize((BANNER_W, BANNER_H), Image.LANCZOS)
            out = os.path.join(DEST_DIR, '{}-1000.jpg'.format(name))
            im.save(out, 'JPEG', quality=QUALITY, optimize=True, progressive=True)
            os.chmod(out, 0o644)
            print('  ✓ {} {:.0f}KB {}x{}'.format(
                os.path.basename(out), os.path.getsize(out) / 1024, BANNER_W, BANNER_H))

    try:
        os.chown(DEST_DIR, 1001, 1001)
        for f in os.listdir(DEST_DIR):
            os.chown(os.path.join(DEST_DIR, f), 1001, 1001)
    except (AttributeError, PermissionError):
        pass
    return 0


def do_verify():
    """逐条校验数据库里的 /uploads/ 图片是否真的能访问"""
    rows = mysql("SELECT id, image FROM product WHERE image LIKE '%/uploads/%';")
    cats = mysql("SELECT id, icon FROM category WHERE icon LIKE '%/uploads/%';")
    bad = []
    total = 0
    for line in rows + cats:
        if '\t' not in line:
            continue
        pk, url = line.split('\t', 1)
        if not url.startswith('http'):
            continue
        total += 1
        try:
            req = urllib.request.Request(url, headers={
                'User-Agent': UA, 'Host': 'peakflow.top'})
            # 走本机 nginx 校验（跳过 DNS 与证书）
            u2 = url.replace('https://peakflow.top', 'https://127.0.0.1')
            ctx_req = urllib.request.Request(u2, headers={
                'User-Agent': UA, 'Host': 'peakflow.top'})
            import ssl
            ctx = ssl._create_unverified_context()      # noqa: SLF001 本机自签校验
            with urllib.request.urlopen(ctx_req, timeout=20, context=ctx) as r:
                ct = r.headers.get('Content-Type', '')
                size = len(r.read())
                if r.status != 200 or 'image' not in ct or size < 500:
                    bad.append((pk, url, r.status, ct, size))
        except Exception as e:                          # noqa: BLE001
            bad.append((pk, url, 'ERR', str(e)[:60], 0))
    print('校验 {} 条本机图片，异常 {} 条'.format(total, len(bad)))
    for b in bad[:15]:
        print('  ✗ id={} {} {} {} {}'.format(*b))
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
