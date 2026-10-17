#!/usr/bin/env python3
"""存量上传图片压缩（一次性维护脚本，2026-10-17）

背景
----
线上 `backend` 容器 `/app/uploads/` 里躺着早先上传的原图：
    1330273 / 808K / 379K / 377K / 317K 字节
这些是用户头像，服务部署在境外、国内实测吞吐约 60KB/s ⇒ 1.33MB 需要 20 秒+。
本次已给上传接口加了压缩（`ImageCompressor`），但**只对之后上传的生效**，
历史文件必须单独处理，否则老用户头像仍然慢。

做法
----
按「最长边 400px、不带透明转 JPEG(q=0.82)」重编码，原地覆盖同名文件
（文件名是 UUID，数据库里的 URL 不需要改）。**原图先备份**到 `uploads-orig/`。

用法（在服务器上、用后端容器里的 Python 跑最省事）：
    docker exec -i peakflow-backend1 python3 - <<'PY' ... PY
或本地指定目录：
    python3 tools/compress_uploads.py /path/to/uploads

安全
----
· 默认 dry-run，只打印将要做什么；加 `--apply` 才真的写。
· 每个文件先备份副本，失败不影响原图。
· 只处理 jpg/jpeg/png；webp/gif 跳过（动图/格式兼容）。
· 压缩后没变小的，保留原图。
"""
import argparse
import os
import shutil
import sys

MAX_EDGE = 400
JPEG_QUALITY = 82
ALLOWED = {'.jpg', '.jpeg', '.png'}


def human(n):
    return f'{n / 1024:.0f}KB' if n >= 1024 else f'{n}B'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir', nargs='?', default='./uploads')
    ap.add_argument('--apply', action='store_true', help='真正写入（默认只预览）')
    ap.add_argument('--max-edge', type=int, default=MAX_EDGE)
    args = ap.parse_args()

    try:
        from PIL import Image
    except ImportError:
        print('✗ 需要 Pillow：pip install pillow', file=sys.stderr)
        return 2

    if not os.path.isdir(args.dir):
        print(f'✗ 目录不存在：{args.dir}', file=sys.stderr)
        return 2

    backup = args.dir.rstrip('/') + '-orig'
    total_before = total_after = 0
    files = sorted(os.listdir(args.dir))
    mode = 'APPLY' if args.apply else 'DRY-RUN'
    print(f'模式：{mode}   目录：{args.dir}   目标最长边：{args.max_edge}px\n')

    for name in files:
        path = os.path.join(args.dir, name)
        if not os.path.isfile(path):
            continue
        ext = os.path.splitext(name)[1].lower()
        if ext not in ALLOWED:
            continue
        before = os.path.getsize(path)
        if before < 80 * 1024:
            print(f'  - {name:<46} {human(before):>8}  已有足够小，跳过')
            continue

        try:
            with Image.open(path) as im:
                w, h = im.size
                has_alpha = im.mode in ('RGBA', 'LA') or (im.mode == 'P' and 'transparency' in im.info)
                long_edge = max(w, h)
                if long_edge <= args.max_edge and before < 80 * 1024:
                    continue
                ratio = args.max_edge / long_edge
                new_size = (max(1, round(w * ratio)), max(1, round(h * ratio))) if ratio < 1 else (w, h)
                im2 = im.convert('RGBA') if has_alpha else im.convert('RGB')
                if new_size != (w, h):
                    im2 = im2.resize(new_size, Image.LANCZOS)

                out_ext = '.png' if has_alpha else '.jpg'
                out_path = os.path.join(args.dir, os.path.splitext(name)[0] + out_ext)

                if args.apply:
                    os.makedirs(backup, exist_ok=True)
                    shutil.copy2(path, os.path.join(backup, name))

                    if has_alpha:
                        im2.save(out_path, 'PNG', optimize=True)
                    else:
                        im2.save(out_path, 'JPEG', quality=JPEG_QUALITY, optimize=True, progressive=True)

                    after = os.path.getsize(out_path)
                    # 没变小的：恢复原图、删掉新文件
                    if after >= before:
                        print(f'  ! {name:<46} 压缩后反而变大，保留原图')
                        if out_path != path:
                            os.remove(out_path)
                            shutil.copy2(os.path.join(backup, name), path)
                        continue
                    # 扩展名变了（png→jpg）：删掉旧的
                    if out_path != path:
                        os.remove(path)
                else:
                    # dry-run 估算：写到内存看体积
                    import io
                    buf = io.BytesIO()
                    if has_alpha:
                        im2.save(buf, 'PNG', optimize=True)
                    else:
                        im2.save(buf, 'JPEG', quality=JPEG_QUALITY, optimize=True, progressive=True)
                    after = buf.tell()

                total_before += before
                total_after += after
                save_pct = round((1 - after / before) * 100)
                print(f'  ✓ {name:<46} {human(before):>8} → {human(after):>7}  ({w}x{h} → {new_size[0]}x{new_size[1]}, 省 {save_pct}%)')

        except Exception as e:
            print(f'  ✗ {name:<46} 处理失败：{e}', file=sys.stderr)

    print()
    if total_before:
        print(f'合计：{human(total_before)} → {human(total_after)}'
              f'（省 {round((1 - total_after / total_before) * 100)}%）')
        # 按 60KB/s 估算节省的时间
        saved_kb = (total_before - total_after) / 1024
        print(f'按国内 60KB/s 估算，首屏少传约 {saved_kb / 60:.1f} 秒')
    else:
        print('没有需要处理的文件。')

    if not args.apply:
        print('\n（dry-run，未改动任何文件。加 --apply 执行）')
    else:
        print(f'\n原图已备份到：{backup}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
