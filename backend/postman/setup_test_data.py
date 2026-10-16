# -*- coding: utf-8 -*-
"""
夹具准备脚本：跑 Postman / 前端回归用例前执行一次，把目标库准备到「可重复执行」状态。

职责：
1. 重置管理员 pfadmin 密码为已知值 Admin123456（bcrypt 哈希，管理端链路登录用）
2. 确保「基线商品」上架且未删除 —— 前端回归流程硬编码依赖 `productId=1` 可见
3. 把秒杀测试商品补足库存 + 拉长活动窗口，保证秒杀链路（含一人一单）可反复跑

⚠️ 基础商品目录（分类 + 商品 + 秒杀商品）由 `seed_base_data.py` 预置：
   它会清空业务脏数据并写入 10 个分类 × 5 条商品（其中每类 1 条秒杀商品，
   秒杀商品 id = 5/10/15/.../50，基线商品 id = 1）。本脚本的默认值即与服务该目录对齐。

用法（默认 = 测试环境 peakflow_test + Redis db1）：
  python seed_base_data.py            # 先预置基础目录（--db peakflow_test --redis-db 1）
  python setup_test_data.py

切到开发环境（dev profile 用 peakflow 库 + Redis db0）：
  python seed_base_data.py --db peakflow --redis-db 0
  python setup_test_data.py --db peakflow --redis-db 0 --seckill-ids 5,10 --base-product-id 1
"""
import argparse
import os
import subprocess
from datetime import datetime

import bcrypt
import pymysql

DB_HOST = '127.0.0.1'
# 与 docker-compose 对齐：容器 MySQL 发布到 3307（3306 是系统 MySQL80）
DB_PORT = int(os.getenv('DB_PORT', '3307'))
DB_USER = 'root'
DB_PASSWORD = '1234'

# Redis（test 环境 6380 / db1；dev 环境 6380 / db0）
REDIS_CLI = r'D:\tools\redis\redis-cli.exe'
REDIS_PORT = 6380

# 管理端账号（与 postman 环境变量 adminUser/adminPass 保持一致）
ADMIN_USER = 'pfadmin'
ADMIN_PASS = 'Admin123456'

SECKILL_STOCK = 100
BASE_PRODUCT_STOCK = 100


def parse_args():
    p = argparse.ArgumentParser(description='PeakFlow 测试/开发夹具准备')
    p.add_argument('--db', default='peakflow_test',
                   help='目标数据库：test 环境=peakflow_test（默认），dev 环境=peakflow_dev')
    p.add_argument('--redis-db', type=int, default=1,
                   help='Redis 库号：test 环境=1（默认），dev 环境=0')
    p.add_argument('--seckill-ids', default='5,10',
                   help='要补库存/拉窗口的秒杀商品 id，逗号分隔（默认取种子目录里的秒杀商品 5,10）')
    p.add_argument('--base-product-id', type=int, default=1,
                   help='需确保「上架且未删除」的基线商品 id（0=跳过；种子目录里 id=1 为普通商品）')
    return p.parse_args()


def bcrypt_hash(password: str) -> str:
    return bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt(rounds=10)).decode('utf-8')


def redis_del(key: str, redis_db: int):
    """删除指定 Redis 库里的 key。"""
    try:
        subprocess.run([REDIS_CLI, '-p', str(REDIS_PORT), '-n', str(redis_db), 'del', key],
                       capture_output=True, text=True, timeout=5)
    except Exception as e:
        print('    [warn] 清理 Redis key 失败：%s（%s）' % (key, e))


def main():
    args = parse_args()
    seckill_ids = [int(x) for x in args.seckill_ids.split(',') if x.strip()]
    print('目标库：%s（Redis 6380 / db%s）' % (args.db, args.redis_db))

    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASSWORD, database=args.db, charset='utf8mb4')
    cur = conn.cursor()
    try:
        # 1. 重置管理员密码
        cur.execute('SELECT password FROM `user` WHERE username=%s', (ADMIN_USER,))
        row = cur.fetchone()
        if row:
            hashed = bcrypt_hash(ADMIN_PASS)
            cur.execute('UPDATE `user` SET password=%s WHERE username=%s', (hashed, ADMIN_USER))
            print('[1] 管理员 %s 密码已重置为 %s（原哈希前缀: %s）'
                  % (ADMIN_USER, ADMIN_PASS, (row[0] or '')[:7]))
        else:
            print('[1] 未找到管理员 %s —— 该库需先建此账号（否则管理端链路无法登录）' % ADMIN_USER)

        # 2. （可选）基线商品：前端回归流程硬编码依赖 productId 可见
        if args.base_product_id:
            cur.execute('UPDATE product SET status=1, deleted=0, stock=%s WHERE id=%s',
                        (BASE_PRODUCT_STOCK, args.base_product_id))
            print('[2] 基线商品 id=%s 已确保上架可见、库存 %s（rows=%s）'
                  % (args.base_product_id, BASE_PRODUCT_STOCK, cur.rowcount))

        # 3. 秒杀商品夹具：补库存 + 拉窗口到今天 23:59:59（保证「活动进行中」）
        end_time = datetime.now().strftime('%Y-%m-%d 23:59:59')
        for pid in seckill_ids:
            cur.execute(
                'UPDATE product SET is_seckill=1, status=1, deleted=0, seckill_stock=%s, '
                'seckill_start_time=NOW() - INTERVAL 1 HOUR, seckill_end_time=%s, sold_count=0 '
                'WHERE id=%s',
                (SECKILL_STOCK, end_time, pid))
            print('[3] 秒杀商品 id=%s 库存已补到 %s，活动截止 %s（rows=%s）'
                  % (pid, SECKILL_STOCK, end_time, cur.rowcount))
            # 预热用的是 setIfAbsent（不覆盖已存在 key），不删掉的话多轮测试后 Redis 会残留旧库存
            redis_del('seckill:stock:%s' % pid, args.redis_db)

        conn.commit()

        # 4. 打印关键数据（便于对照）
        print('\n===== 夹具后快照 =====')
        cur.execute('SELECT id, username, LEFT(password,7) FROM `user`')
        for r in cur.fetchall():
            print('  user: id=%s username=%s pwd=%s...' % r)
        if seckill_ids:
            cur.execute('SELECT id, status, deleted, seckill_stock, sold_count, '
                        'seckill_start_time, seckill_end_time FROM product '
                        'WHERE id IN (%s) ORDER BY id' % ','.join(map(str, seckill_ids)))
            for r in cur.fetchall():
                print('  seckill product: id=%s status=%s deleted=%s stock=%s sold=%s window=%s ~ %s' % r)
    finally:
        cur.close()
        conn.close()


if __name__ == '__main__':
    main()
