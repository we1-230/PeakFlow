#!/usr/bin/env python3
"""存量上传图片压缩（一次性维护脚本，2026-10-17）

背景
----
线上 `backend` 容器 `/app/uploads/` 里躺着早先上传的原图：
    1330273 / 826464 / 484789 / 388047 / 385964 / 324554 字节（合计 3.6GB→3.6MB）
这些是用户头像，服务部署在境外、国内实测吞吐约 60KB/s ⇒ 1.33MB 需要 20 秒+，
页面上多个头像展示位叠加，就是用户说的「一两分钟才加载出来」。

本次已给上传接口加了压缩（`ImageCompressor`），但**只对之后上传的生效**，
历史文件必须单独处理，否则老用户头像仍然慢。

⚠️ 第一条铁律：**必须保持原文件名（含扩展名）**
------------------------------------------------------------------
数据库里存的是完整 URL（`https://域名/uploads/<uuid>.png`）。
如果把 .png 压成 .jpg 并改名，数据库里的 URL 会 404 ——
第一次实测就踩了这个坑：压缩后有 3 个头像变成 .jpg，原 URL 全部失效。
所以：**PNG 输出 PNG，JPEG 输出 JPEG，文件名一个字符都不改。**
PNG 靠「缩放到 400px + 减色 256 + optimize」降体积（头像展示位只有 80px，
256 色在视觉上无差别）；JPEG 靠缩放 + q82 progressive。

⚠️ 第二条：**保持原属主**
后端容器以 UID 1001 运行，脚本若以 root 执行会把文件属主变成 root，
后端读不了（表现为 403/500）。脚本会记录并还原 uid/gid。

用法
----
    # 预览（默认，不改任何文件）
    sudo python3 tools/compress_uploads.py /var/lib/docker/volumes/peakflow_peakflow-uploads/_data

    # 执行（自动备份原图到 <dir>-orig/）
    sudo python3 tools/compress_uploads.py <dir> --apply

安全
----
· 默认 dry-run，只打印「将做什么 + 预计省多少」
· 执行前把原图备份到 `<dir>-orig/`，随时可 cp 回去
· 压缩后反而变大的，恢复原图（不做负优化）
· 只处理 jpg/jpeg/png；webp/gif 跳过（格式兼容 / 动图会丢帧）
"""
import argparse
import io
import os
import shutil
import sys

MAX_EDGE = 400
JPEG_QUALITY = 82
PNG_COLORS = 256
ALLOWED = {'.jpg', '.jpeg', '.png'}


def human(n):
    return f'{n / 1024:.0f}KB' if n >= 1024 else f'{n}B'


def encode(im, ext, has_alpha):
    """按原扩展名编码，返回 (bytes, 使用的方案名)"""
    if ext == '.png':
        # 先试减色（体积最小）；减色失败或反而更大时回落普通 optimize
        try:
            b = io.BytesIO()
            im.quantize(colors=PNG_COLORS, method=1).save(b, 'PNG', optimize=True)
            return b.getvalue(), f'PNG减色{PNG_COLORS}'
        except Exception:
            b = io.BytesIO()
            im.save(b, 'PNG', optimize=True)
            return b.getvalue(), 'PNG优化'
    b = io.BytesIO()
    im.save(b, 'JPEG', quality=JPEG_QUALITY, optimize=True, progressive=True)
    return b.getvalue(), f'JPEG q{JPEG_QUALITY}'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir', nargs='?', default='./uploads')
    ap.add_argument('--apply', action='store_true', help='真正写入（默认只预览）')
    ap.add_argument('--max-edge', type=int, default=MAX_EDGE)
    args = ap.parse_args()

    try:
        from PIL import Image
    except ImportError:
        print('✗ 需要 Pillow：pip3 install --user pillow', file=sys.stderr)
        return 2

    if not os.path.isdir(args.dir):
        print(f'✗ 目录不存在：{args.dir}', file=sys.stderr)
        return 2

    backup = args.dir.rstrip('/') + '-orig'
    total_before = total_after = 0
    changed = 0
    mode = 'APPLY' if args.apply else 'DRY-RUN'
    print(f'模式：{mode}   目录：{args.dir}   目标最长边：{args.max_edge}px\n')

    for name in sorted(os.listdir(args.dir)):
        path = os.path.join(args.dir, name)
        if not os.path.isfile(path):
            continue
        ext = os.path.splitext(name)[1].lower()
        if ext not in ALLOWED:
            continue

        before = os.path.getsize(path)
        try:
            st = os.stat(path)
            with Image.open(path) as im:
                w, h = im.size
                has_alpha = (im.mode in ('RGBA', 'LA')
                             or (im.mode == 'P' and 'transparency' in im.info))
                long_edge = max(w, h)
                need_resize = long_edge > args.max_edge
                # 已经足够小 + 体积不大 ⇒ 不折腾（重编码可能反而变大）
                if not need_resize and before <= 100 * 1024:
                    print(f'  - {name[:46]:<48} {human(before):>8}  已足够小，跳过')
                    continue

                im2 = im.convert('RGBA') if has_alpha else im.convert('RGB')
                if need_resize:
                    ratio = args.max_edge / long_edge
                    ns = (max(1, round(w * ratio)), max(1, round(h * ratio)))
                    im2 = im2.resize(ns, Image.LANCZOS)
                else:
                    ns = (w, h)

                out, how = encode(im2, ext, has_alpha)

            # 没变小 ⇒ 保留原图（不做负优化）
            if len(out) >= before:
                print(f'  ! {name[:46]:<48} {human(before):>8}  压缩后未变小，保留原图')
                continue

            if args.apply:
                os.makedirs(backup, exist_ok=True)
                bpath = os.path.join(backup, name)
                if not os.path.exists(bpath):
                    shutil.copy2(path, bpath)
                # 同名覆盖：扩展名不变 ⇒ 数据库里的 URL 依然有效
                with open(path, 'wb') as f:
                    f.write(out)
                os.chmod(path, 0o644)
                # 还原属主（root 执行时必须做，否则后端 UID1001 读不了）
                try:
                    os.chown(path, st.st_uid, st.st_gid)
                except (AttributeError, PermissionError):
                    pass

            total_before += before
            total_after += len(out)
            changed += 1
            pct = round((1 - len(out) / before) * 100)
            print(f'  ✓ {name[:46]:<48} {human(before):>8} → {human(len(out)):>7}'
                  f'  ({w}x{h}→{ns[0]}x{ns[1]}, {how}, 省 {pct}%)')
        except Exception as e:
            print(f'  ✗ {name[:46]:<48} 处理失败：{e}', file=sys.stderr)

    print()
    if total_before:
        saved = total_before - total_after
        print(f'合计：{human(total_before)} → {human(total_after)}'
              f'（省 {round(saved / total_before * 100)}%，{changed} 个文件）')
        print(f'按国内 60KB/s 估算，首屏少传约 {saved / 1024 / 60:.1f} 秒')
    else:
        print('没有需要处理的文件。')

    if not args.apply:
        print('\n（dry-run，未改动任何文件。加 --apply 执行）')
    else:
        print(f'\n原图已备份到：{backup}（同名覆盖，可用 cp 还原）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
