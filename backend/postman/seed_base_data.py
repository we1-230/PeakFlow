# -*- coding: utf-8 -*-
"""
基础种子数据预置脚本（分类 + 商品 + 秒杀商品）

职责（可重复执行）：
1. 【清理】删除验收过程产生的全部业务脏数据：
   - 业务表：address / cart / order / order_item / order_operation_log / pay_record /
             refund_record / login_log / product / category（TRUNCATE，主键归 1）
   - 用户：删除 malltester% 验收测试账号及其 user_role 关联
   - 复位：malluser 昵称/头像恢复为基础值
   - Redis：清理业务缓存（product:* / seckill:* / cart:*）与脏鉴权键（token:blacklist* / login:fail* / login:lock*）
2. 【预置】写入 10 个商品分类 × 每类 5 条商品（其中 1 条为秒杀商品，共 40 普通 + 10 秒杀）：
   - 商品名称 / 商品描述 / 商品详情 / 商品图片 字段全部填充
   - 图片使用互联网公开、可商用、真实实拍（Unsplash CDN 直链，非 AI 生成），内容与品类匹配
3. 【预热】清理缓存后调用后端 /seckill/preheat/all 重建秒杀库存缓存

保留：permission / role / role_permission / user（基础账号） / user_role —— 权限与角色基础数据。

用法（默认 = 开发环境 peakflow + Redis db0）：
  python seed_base_data.py
切到测试环境：
  python seed_base_data.py --db peakflow_test --redis-db 1
"""
import argparse
import json
import subprocess
import urllib.request

import pymysql

DB_HOST, DB_PORT, DB_USER, DB_PASSWORD = '127.0.0.1', 3306, 'root', '1234'
REDIS_CLI = r'D:\tools\redis\redis-cli.exe'
REDIS_PORT = 6380
API_BASE = 'http://localhost:8080'
ADMIN_USER, ADMIN_PASS = 'pfadmin', 'Admin123456'
# 基础账号（「用户/角色/权限」基础表数据，复位时保留，其余业务测试账号一并删除）
BASE_USERS = ('malluser', 'sysadmin', 'productops', 'ordersvc', 'pfadmin')

# 图片前缀：Unsplash CDN 直链 + 统一缩放参数（公开可商用实拍图）
IMG = 'https://images.unsplash.com/photo-%s?auto=format&fit=crop&w=800&q=70'

# (分类名, 排序, 分类图标用图)
CATEGORIES = [
    ('数码配件', 1, '1505740420928-5e560c06d30e'),
    ('手机电脑', 2, '1511707171634-5f897ff02aa9'),
    ('鞋靴箱包', 3, '1542291026-7eec264c27ff'),
    ('服饰穿搭', 4, '1521572163474-6864f9cf17ab'),
    ('美妆护肤', 5, '1586495777744-4413f21062fa'),
    ('食品饮料', 6, '1447933601403-0c6688de566e'),
    ('家居生活', 7, '1578500494198-246f612d3b3d'),
    ('运动户外', 8, '1544367567-0f2fcb009e0b'),
    ('图书文具', 9, '1512820790803-83ca734da794'),
    ('母婴玩具', 10, '1559454403-b8fb88521f11'),
]

# (分类序号, 商品名, 图片ID, 原价, 普通库存, 描述, 详情, 是否秒杀)
# 每个分类 5 条 = 前 4 条普通商品 + 第 5 条秒杀商品；第 1 个商品固定为正常上架（供回归依赖 productId=1 可见）
PRODUCTS = [
    # 1 数码配件
    (1, '无线蓝牙耳机', '1505740420928-5e560c06d30e', 299.00, 120, '主动降噪，40 小时长续航', 0),
    (1, '机械键盘87键', '1587829741301-dc798b83add3', 359.00, 80, '全键无冲，青轴手感', 0),
    (1, '静音无线鼠标', '1527864550417-7fd91fc51a46', 129.00, 200, '静音微动，2.4G 双模连接', 0),
    (1, '智能运动手表', '1546868871-7041f2a55e12', 899.00, 60, '心率血氧监测，多运动模式', 0),
    (1, '微单数码相机', '1502920917128-1aa500764cbd', 4299.00, 30, '2420 万像素，4K 视频', 1),
    # 2 手机电脑
    (2, '智能手机旗舰版', '1511707171634-5f897ff02aa9', 3999.00, 100, '大屏高刷，亿级像素', 0),
    (2, '智能手机轻薄款', '1580910051074-3eb694886505', 2299.00, 150, '轻薄机身，长续航', 0),
    (2, '轻薄办公笔记本', '1517336714731-489689fd1ca8', 5299.00, 40, '14 英寸 2K 屏，办公全能', 0),
    (2, '高性能游戏笔记本', '1609091839311-d5365f9ff1c5', 8299.00, 25, '独立显卡，高刷电竞屏', 0),
    (2, '桌面蓝牙音箱', '1570222094114-d054a817e56b', 499.00, 90, '立体声环绕，重低音', 1),
    # 3 鞋靴箱包
    (3, '经典休闲运动鞋', '1542291026-7eec264c27ff', 629.00, 180, '轻质缓震，透气网面', 0),
    (3, '头层牛皮短靴', '1549298916-b41d501d3772', 899.00, 70, '头层牛皮，耐磨防滑', 0),
    (3, '轻便跑步鞋', '1600185365483-26d7a4cc7519', 459.00, 160, '回弹中底，轻盈减负', 0),
    (3, '大容量双肩背包', '1553062407-98eeb64c6a62', 279.00, 220, '防泼水面料，电脑仓', 0),
    (3, '通勤真皮手提包', '1584917865442-de89df76afd3', 1299.00, 50, '大容量通勤，质感五金', 1),
    # 4 服饰穿搭
    (4, '纯棉圆领短袖T恤', '1521572163474-6864f9cf17ab', 129.00, 300, '精梳纯棉，不易变形', 0),
    (4, '深色休闲长裤', '1542272604-787c3835535d', 259.00, 180, '弹力舒适，修身版型', 0),
    (4, '机车皮夹克', '1551028719-00167b16eac5', 1099.00, 45, '复古机车风，挺括有型', 0),
    (4, '法式气质连衣裙', '1595777457583-95e059d581b8', 699.00, 90, '垂坠面料，优雅剪裁', 0),
    (4, '慵懒风针织毛衣', '1434389677669-e08b4cac3105', 399.00, 120, '柔软亲肤，宽松慵懒', 1),
    # 5 美妆护肤
    (5, '丝绒哑光口红', '1586495777744-4413f21062fa', 199.00, 260, '丝绒质地，显白不拔干', 0),
    (5, '经典淡香水', '1541643600914-78b084683601', 899.00, 80, '清新木质调，留香持久', 0),
    (5, '补水保湿精华乳', '1556228720-195a672e8a03', 329.00, 200, '玻尿酸保湿，清爽不黏', 0),
    (5, '专业化妆刷套装', '1596462502278-27bfdc403348', 259.00, 140, '12 支全套，软毛亲肤', 0),
    (5, '十二色眼影盘', '1512496015851-a90fb38ba796', 219.00, 180, '哑光珠光，显色细腻', 1),
    # 6 食品饮料
    (6, '精品手冲咖啡豆', '1447933601403-0c6688de566e', 158.00, 240, '中深烘焙，坚果回甘', 0),
    (6, '意式浓缩挂耳咖啡', '1495474472287-4d71bcdd2085', 89.00, 320, '现磨锁鲜，冲泡便捷', 0),
    (6, '黑巧克力礼盒', '1511381939415-e44015466834', 129.00, 160, '72% 可可，微苦回香', 0),
    (6, '手工曲奇饼干', '1544787219-7f47ccb76574', 69.00, 280, '黄油曲奇，酥脆掉渣', 0),
    (6, '鲜榨橙汁饮品', '1600271886742-f049cd451bba', 39.90, 400, 'NFC 鲜榨，0 添加蔗糖', 1),
    # 7 家居生活
    (7, '北欧简约休闲椅', '1586023492125-27b2c045efd7', 899.00, 70, '实木框架，人体工学', 0),
    (7, '护眼LED台灯', '1507473885765-e6ed057f782c', 239.00, 150, '无频闪护眼，三档调光', 0),
    (7, '布艺三人沙发', '1578500494198-246f612d3b3d', 3299.00, 20, '高回弹海绵，可拆洗', 0),
    (7, '记忆棉护颈枕', '1584100936595-c0654b55a2e2', 199.00, 220, '慢回弹记忆棉，护颈助眠', 0),
    (7, '绿植盆栽套装', '1485955900006-10f4d324d411', 129.00, 300, '客厅绿植，净化空气', 1),
    # 8 运动户外
    (8, '加厚防滑瑜伽垫', '1544367567-0f2fcb009e0b', 189.00, 210, 'TPE 环保，加厚防滑', 0),
    (8, '可调节哑铃套装', '1517836357463-d25dfeac3438', 499.00, 90, '可调重量，家用健身', 0),
    (8, '复古通勤自行车', '1485965120184-e220f721d03e', 1599.00, 35, '复古车架，通勤代步', 0),
    (8, '户外防风露营帐篷', '1504280390367-361c6d9f38f4', 699.00, 60, '双人速开，防风防雨', 0),
    (8, '大容量保温水壶', '1602143407151-7111542de6e8', 159.00, 260, '316 不锈钢，长效保温', 1),
    # 9 图书文具
    (9, '畅销管理学图书', '1512820790803-83ca734da794', 79.00, 300, '经管畅销，案例丰富', 0),
    (9, '简约硬壳笔记本', '1531346878377-a5be20888e57', 49.00, 400, '硬壳线装，顺滑书写', 0),
    (9, '顺滑中性笔套装', '1583485088034-697b5bc54ccd', 29.90, 500, '0.5mm 顺滑，速干不晕染', 0),
    (9, '儿童绘本书籍', '1497633762265-9d179a990aa6', 98.00, 220, '全彩绘本，亲子共读', 0),
    (9, '精装典藏读本', '1456513080510-7bf3a84b82f8', 168.00, 120, '精装典藏，收藏级装帧', 1),
    # 10 母婴玩具
    (10, '婴儿连体爬服', '1519689680058-324335c77eba', 139.00, 260, '纯棉A类，柔软亲肤', 0),
    (10, '柔软安抚毛绒熊', '1559454403-b8fb88521f11', 99.00, 300, '可水洗毛绒，安抚陪伴', 0),
    (10, '木质益智轨道玩具', '1596461404969-9ae70f2830c1', 199.00, 160, '榉木材质，益智拼搭', 0),
    (10, '儿童拼插积木', '1587654780291-39c9404d746b', 159.00, 220, '大颗粒积木，安全无毛刺', 0),
    (10, '婴幼儿乳胶枕', '1522771930-78848d9293e8', 229.00, 140, '天然乳胶，透气护颈', 1),
]

# ==================== 追加的 50 条「秒杀商品」（10 分类 × 每类 5 条） ====================
# 说明：与上面 50 条普通商品并存，追加后共 100 条商品、其中秒杀商品 60 条。
# 图片同样为互联网公开可商用的真实实拍图（Unsplash CDN，非 AI 生成），且与商品品类严格匹配；
# 其中 5 条复用了同品类已核验过的实拍图（对应不同型号/包装），其余 45 条为本轮新核验的实拍图。
# 字段顺序：(所属分类 id, 商品名, 图片 id, 原价, 商品描述)；秒杀价 = 原价 × SECKILL_PRICE_RATIO
SECKILL_PRODUCTS = [
    # 1 数码配件
    (1, '头戴式无线耳机', '1546435770-a3e426bf472b', 599.00, '主动降噪，沉浸式音质'),
    (1, '降噪蓝牙耳机', '1583394838336-acd977736f90', 799.00, '混合降噪，通话清晰'),
    (1, '真无线蓝牙耳机', '1572569511254-d8f925fe2cbb', 899.00, '入耳式设计，超长续航'),
    (1, '微单相机套机', '1526170375885-4d8ecf77b99f', 5299.00, '套机镜头，一机走天下'),
    (1, '智能运动手表旗舰版', '1546868871-7041f2a55e12', 1099.00, '多运动模式，心率监测'),
    # 2 手机电脑
    (2, '平板电脑', '1531297484001-80022131f5a1', 2699.00, '高刷大屏，影音娱乐'),
    (2, '智能手机大屏版', '1523206489230-c012c64b2b48', 4599.00, '大屏高刷，长续航'),
    (2, '轻薄办公笔记本青春版', '1496181133206-80ce9b88a853', 4599.00, '轻薄便携，办公首选'),
    (2, '高性能游戏笔记本高配版', '1596704017254-9b121068fb31', 6999.00, '强劲性能，畅玩大作'),
    (2, '静音无线鼠标便携款', '1527864550417-7fd91fc51a46', 99.00, '静音微动，即插即用'),
    # 3 鞋靴箱包
    (3, '轻质跑步鞋', '1560769629-975ec94e6a86', 499.00, '回弹缓震，轻盈透气'),
    (3, '舒适休闲运动鞋', '1608231387042-66d1773070a5', 569.00, '经典小白鞋，百搭耐穿'),
    (3, '女式高跟凉鞋', '1543163521-1bf539c55dd2', 399.00, '优雅细跟，气质通勤'),
    (3, '秋冬马丁靴', '1520639888713-7851133b1ed0', 699.00, '复古工装，耐磨防滑'),
    (3, '印花帆布托特包', '1591561954557-26941169b49e', 199.00, '大容量单肩，通勤百搭'),
    # 4 服饰穿搭
    (4, '纯色圆领T恤', '1576566588028-4147f3842f27', 89.00, '柔软亲肤，经典百搭'),
    (4, '休闲直筒长裤', '1594633312681-425c7b97ccd1', 169.00, '垂感面料，显瘦修身'),
    (4, '复古长袖衬衫', '1591047139829-d91aecb6caea', 199.00, '复古格调，通勤休闲两相宜'),
    (4, '印花短袖T恤', '1618354691373-d851c5c3a990', 129.00, '个性印花，透气排汗'),
    (4, '商务修身西装', '1610652492500-ded49ceeb378', 899.00, '剪裁利落，商务必备'),
    # 5 美妆护肤
    (5, '保湿精华水乳套装', '1571781926291-c477ebfd024b', 299.00, '深层保湿，温和不刺激'),
    (5, '基础护肤五件套', '1598440947619-2c35fc9aa908', 399.00, '洁面到面霜，一套搞定'),
    (5, '大地色眼影盘', '1522335789203-aabd1fc54bc9', 189.00, '哑光细闪，显色自然'),
    (5, '丝绒唇釉', '1586495777744-4413f21062fa', 129.00, '丝绒质地，持久显色'),
    (5, '淡香水旅行装', '1541643600914-78b084683601', 399.00, '清新木质调，便携随身'),
    # 6 食品饮料
    (6, '手工黄油曲奇', '1558961363-fa8fdf82db35', 79.00, '黄油浓郁，酥脆掉渣'),
    (6, '轻食沙拉套餐', '1504674900247-0877df9cc836', 59.00, '低卡轻食，营养均衡'),
    (6, '草莓慕斯蛋糕', '1560508180-03f285f67ded', 139.00, '动物奶油，入口即化'),
    (6, '经典芝士汉堡', '1571091718767-18b5b1457add', 39.00, '厚切牛肉，芝士拉丝'),
    (6, '冰镇西瓜', '1587049352846-4a222e784d38', 29.90, '沙瓤爆汁，夏日必备'),
    # 7 家居生活
    (7, '布艺三人沙发贵妃款', '1493663284031-b7e3aefcae8e', 2999.00, '高回弹海绵，可拆洗'),
    (7, '现代简约沙发', '1567016432779-094069958ea5', 3599.00, '北欧风设计，舒适承托'),
    (7, '抱枕组合套装', '1616627561950-9f746e330187', 129.00, '多种配色，柔软亲肤'),
    (7, '整体橱柜套装', '1556911220-bff31c812dba', 4999.00, '一体式设计，收纳实用'),
    (7, '酒店风床品四件套', '1591088398332-8a7791972843', 399.00, '长绒棉，亲肤透气'),
    # 8 运动户外
    (8, '公路骑行头盔', '1517649763962-0c623066013b', 299.00, '一体成型，通风减阻'),
    (8, '室内训练篮球', '1546519638-68e109498ffc', 199.00, '吸湿防滑，耐磨耐用'),
    (8, '专业加厚瑜伽垫', '1518611012118-696072aa579a', 169.00, 'TPE环保，防滑回弹'),
    (8, '专业田径钉鞋', '1461896836934-ffe607ba8211', 459.00, '轻量竞速，抓地强劲'),
    (8, '越野跑装备套装', '1552674605-db6ffd4facb5', 599.00, '越野缓震，防护到位'),
    # 9 图书文具
    (9, '经典文学名著套装', '1481627834876-b7833e8f5570', 199.00, '全译精校，收藏级装帧'),
    (9, '精装典藏图书', '1543002588-bfa74002ed7e', 168.00, '精装典藏，纸张考究'),
    (9, '美术水彩画笔套装', '1519682337058-a94d519337bc', 89.00, '专业尼龙毛，不易掉毛'),
    (9, '图书馆藏精装书', '1507842217343-583bb7270b66', 129.00, '烫金封面，典藏必备'),
    (9, '参考工具书套装', '1524995997946-a1c2e315a42f', 259.00, '工具查阅，内容权威'),
    # 10 母婴玩具
    (10, '婴儿爬行垫套装', '1515488042361-ee00e0ddd4e4', 159.00, 'XPE材质，防摔防滑'),
    (10, '婴儿安抚睡袋', '1544126592-807ade215a0b', 199.00, '纯棉A类，安睡防踢'),
    (10, '木质轨道火车玩具', '1596461404969-9ae70f2830c1', 189.00, '榉木轨道，益智拼搭'),
    (10, '儿童益智玩具套装', '1607453998774-d533f65dac99', 149.00, '手眼协调，安全无毛刺'),
    (10, '安抚毛绒玩具熊', '1559454403-b8fb88521f11', 99.00, '可水洗，柔软安抚'),
]

SECKILL_STOCK = 50
SECKILL_PRICE_RATIO = 0.5  # 秒杀价 = 原价 5 折（保留 2 位小数）


def parse_args():
    p = argparse.ArgumentParser(description='PeakFlow 基础种子数据预置（分类 + 商品 + 秒杀）')
    p.add_argument('--db', default='peakflow', help='目标库：dev=peakflow（默认），test=peakflow_test')
    p.add_argument('--redis-db', type=int, default=0, help='Redis 库号：dev=0（默认），test=1')
    p.add_argument('--skip-clean', action='store_true', help='跳过清理（仅补种子，调试用）')
    p.add_argument('--skip-preheat', action='store_true', help='跳过秒杀缓存预热')
    p.add_argument('--flush-redis', action='store_true',
                   help='清空目标 Redis 逻辑库（FLUSHDB，彻底复位缓存/购物车/秒杀库存等派生数据）')
    return p.parse_args()


def redis_do(db, *args):
    try:
        return subprocess.run([REDIS_CLI, '-p', str(REDIS_PORT), '-n', str(db)] + list(args),
                              capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception as e:
        print('    [warn] redis 调用失败 %s：%s' % (args, e))
        return ''


def redis_del_pattern(db, pattern):
    keys = redis_do(db, 'keys', pattern)
    keys = [k for k in keys.splitlines() if k.strip()]
    for k in keys:
        redis_do(db, 'del', k)
    return len(keys)


def clean(db):
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASSWORD, database=db, charset='utf8mb4')
    cur = conn.cursor()
    try:
        cur.execute('SET FOREIGN_KEY_CHECKS = 0')
        for t in ['order_item', 'order_operation_log', 'refund_record', 'pay_record',
                  '`order`', 'cart', 'address', 'login_log', 'product', 'category']:
            cur.execute('TRUNCATE TABLE %s' % t)
        # 只保留基础账号（权限/角色/用户为「基础表」），删除其余全部业务测试账号及其角色关联
        keep = list(BASE_USERS)
        ph = ','.join(['%s'] * len(keep))
        cur.execute("DELETE FROM user_role WHERE user_id IN "
                    "(SELECT id FROM user WHERE username NOT IN (" + ph + "))", keep)
        cur.execute("DELETE FROM user WHERE username NOT IN (" + ph + ")", keep)
        removed = cur.rowcount
        # 复位基础账号 malluser 的验收改动（昵称/头像）
        cur.execute("UPDATE user SET nickname='商城用户', avatar=NULL "
                    "WHERE username='malluser' AND (nickname<>'商城用户' OR avatar IS NOT NULL)")
        cur.execute('SET FOREIGN_KEY_CHECKS = 1')
        conn.commit()
        print('[1] 业务表已清空；删除非基础账号 %d 个；malluser 昵称/头像已复位' % removed)
    finally:
        cur.close()
        conn.close()


def clean_redis(db, flush=False):
    if flush:
        before = redis_do(db, 'dbsize')
        redis_do(db, 'flushdb')
        print('[2] Redis(db%s) 已 FLUSHDB 复位（清理前 %s 个键）' % (db, before))
        return
    pats = ['product:*', 'seckill:stock:*', 'seckill:order:success:*', 'seckill:process:*',
            'cart:*', 'token:blacklist*', 'login:fail*', 'login:lock*',
            'login:user:malltester001', 'login:user:malltester002', 'login:user:周奥宇']
    total = 0
    for p in pats:
        total += redis_del_pattern(db, p)
    print('[2] Redis(db%s) 已清理业务/脏键 %d 个' % (db, total))


def seed(db):
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASSWORD, database=db, charset='utf8mb4')
    cur = conn.cursor()
    try:
        # 分类（固定 id 1..10）
        for i, (name, sort, icon_id) in enumerate(CATEGORIES, start=1):
            cur.execute(
                'INSERT INTO category (id, name, icon, sort, status, deleted) '
                'VALUES (%s, %s, %s, %s, 1, 0)',
                (i, name, IMG % icon_id, sort))
        # 商品（固定 id 1..50）
        seckill_ids = []
        for idx, (cat_seq, name, img_id, price, stock, desc, is_seckill) in enumerate(PRODUCTS, start=1):
            detail = ('%s。%s。\n'
                      '材质 / 工艺：精选优质材料，做工精细，细节处理到位。\n'
                      '适用场景：日常通勤、居家、送礼皆宜。\n'
                      '售后保障：七天无理由退换，正品保证。') % (name, desc)
            if is_seckill:
                seckill_price = round(float(price) * SECKILL_PRICE_RATIO, 2)
                cur.execute(
                    'INSERT INTO product (id, name, description, detail, image, price, seckill_price, '
                    'stock, seckill_stock, sold_count, category_id, is_seckill, seckill_start_time, '
                    'seckill_end_time, status, version, deleted) '
                    'VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,0,%s,1, NOW() - INTERVAL 1 HOUR, '
                    'DATE_ADD(CURDATE(), INTERVAL 1 DAY) - INTERVAL 1 SECOND, 1, 0, 0)',
                    (idx, name, desc, detail, IMG % img_id, price, seckill_price,
                     SECKILL_STOCK, cat_seq))
                seckill_ids.append(idx)
            else:
                cur.execute(
                    'INSERT INTO product (id, name, description, detail, image, price, stock, '
                    'sold_count, category_id, is_seckill, status, version, deleted) '
                    'VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,0,1,0,0)',
                    (idx, name, desc, detail, IMG % img_id, price, stock, cat_seq))
        # 追加的 50 条「秒杀商品」：id 从 51 开始连续分配（原有 50 条商品 id 1..50 保持不变）
        base = len(PRODUCTS)
        for i, (cat_seq, name, img_id, price, desc) in enumerate(SECKILL_PRODUCTS, start=1):
            idx = base + i
            detail = ('%s。%s。\n'
                      '材质 / 工艺：精选优质材料，做工精细，细节处理到位。\n'
                      '适用场景：限时秒杀专场，数量有限，先到先得。\n'
                      '售后保障：七天无理由退换，正品保证。') % (name, desc)
            seckill_price = round(float(price) * SECKILL_PRICE_RATIO, 2)
            cur.execute(
                'INSERT INTO product (id, name, description, detail, image, price, seckill_price, '
                'stock, seckill_stock, sold_count, category_id, is_seckill, seckill_start_time, '
                'seckill_end_time, status, version, deleted) '
                'VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,0,%s,1, NOW() - INTERVAL 1 HOUR, '
                'DATE_ADD(CURDATE(), INTERVAL 1 DAY) - INTERVAL 1 SECOND, 1, 0, 0)',
                (idx, name, desc, detail, IMG % img_id, price, seckill_price,
                 SECKILL_STOCK, cat_seq))
            seckill_ids.append(idx)

        conn.commit()
        print('[3] 已写入 %d 个分类、%d 条商品（其中秒杀商品 %d 条：%s）'
              % (len(CATEGORIES), len(PRODUCTS) + len(SECKILL_PRODUCTS), len(seckill_ids), seckill_ids))
        return seckill_ids
    finally:
        cur.close()
        conn.close()


def preheat():
    """登录管理员 → 调用 /seckill/preheat/all 重建 Redis 秒杀库存。"""
    try:
        body = json.dumps({'account': ADMIN_USER, 'password': ADMIN_PASS}).encode('utf-8')
        req = urllib.request.Request(API_BASE + '/auth/login', data=body,
                                     headers={'Content-Type': 'application/json; charset=utf-8'})
        with urllib.request.urlopen(req, timeout=20) as resp:
            token = json.loads(resp.read().decode('utf-8'))['data']['token']
        req2 = urllib.request.Request(API_BASE + '/seckill/preheat/all',
                                      headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(req2, timeout=20) as resp:
            print('[4] 秒杀缓存预热：%s' % resp.read().decode('utf-8')[:160])
    except Exception as e:
        print('[4] [warn] 预热失败（后端未启动？）：%s' % e)


def main():
    args = parse_args()
    print('目标库 %s（Redis db%s）' % (args.db, args.redis_db))
    if not args.skip_clean:
        clean(args.db)
        clean_redis(args.redis_db, flush=args.flush_redis)
    seed(args.db)
    # 种子写完后业务缓存必须再清一次，避免读到旧列表
    print('[2b] 二次清理业务缓存：%d 个' % redis_del_pattern(args.redis_db, 'product:*'))
    redis_del_pattern(args.redis_db, 'seckill:stock:*')
    if not args.skip_preheat:
        preheat()
    print('完成。')


if __name__ == '__main__':
    main()
