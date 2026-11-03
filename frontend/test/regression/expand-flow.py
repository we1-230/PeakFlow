# -*- coding: utf-8 -*-
"""向 test/regression/user-flow.json 追加「参数校验 / 业务规则 / 边缘值」用例。

设计原则：
  - 成功路径（happy path）已在原配置里覆盖；
  - 这里补齐 失败路径（参数非法 / 业务守卫 / 权限）与 边界值；
  - 负向用例尽量断言精确的失败码（expectErrorCode），而不是只看"报错了"。
"""
import json, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'user-flow.json')
d = json.load(open(P, encoding='utf-8'))
steps = d['steps']
existing = {s['id'] for s in steps}

new = []


def add(**kw):
    if kw['id'] not in existing:
        new.append(kw)
        existing.add(kw['id'])


# ========================================================================
# A. 参数校验 · 分页（PageRequest: pageNum @NotNull@Min(1), pageSize @NotNull@Min(1)@Max(100)）
# ========================================================================
add(id='param.page.pageNumZero', desc='参数：页码为 0 应被校验拦下', actor='anon',
    api='Product.page', args=[{'pageNum': 0, 'pageSize': 10}], expectError=True, expectErrorCode=1003)
add(id='param.page.pageSizeTooLarge', desc='参数：每页条数 101 超上限', actor='anon',
    api='Product.page', args=[{'pageNum': 1, 'pageSize': 101}], expectError=True, expectErrorCode=1003)
add(id='param.page.pageSizeZero', desc='参数：每页条数为 0', actor='anon',
    api='Product.page', args=[{'pageNum': 1, 'pageSize': 0}], expectError=True, expectErrorCode=1003)
add(id='param.page.missingFields', desc='参数：分页字段全缺（@NotNull 必填）', actor='anon',
    api='Product.page', args=[{}], expectError=True, expectErrorCode=1003)
add(id='param.page.boundaryMax', desc='边界：每页条数取上限 100 应成功', actor='anon',
    api='Product.page', args=[{'pageNum': 1, 'pageSize': 100}], expectTruthy=['list'])
add(id='param.seckillList.pageInvalid', desc='参数：秒杀列表页码非法', actor='anon',
    api='Seckill.list', args=[{'pageNum': 0, 'pageSize': 10}], expectError=True, expectErrorCode=1003)
add(id='param.adminList.pageInvalid', desc='参数：管理端用户列表每页条数超限', actor='admin',
    api='Admin.list', args=[{'pageNum': 1, 'pageSize': 200}], expectError=True, expectErrorCode=1003)

# ========================================================================
# B. 参数校验 · 认证与注册
# ========================================================================
add(id='param.login.blankAccount', desc='参数：账号为空', actor='anon',
    api='Auth.login', args=['', 'It123456'], expectError=True, expectErrorCode=1003)
add(id='param.login.blankPassword', desc='参数：密码为空', actor='anon',
    api='Auth.login', args=['{{userName}}', ''], expectError=True, expectErrorCode=1003)
add(id='param.login.accountTooShort', desc='参数：账号只有 1 位（USERNAME 要求 2-20）', actor='anon',
    api='Auth.login', args=['a', 'It123456'], expectError=True, expectErrorCode=1003)
add(id='param.login.accountBadChar', desc='参数：账号含非法字符（空格/符号）', actor='anon',
    api='Auth.login', args=['bad account!!', 'It123456'], expectError=True, expectErrorCode=1003)
add(id='param.register.usernameTooShort', desc='参数：注册用户名 1 位', actor='anon',
    api='Auth.register', args=[{'username': 'a', 'password': 'It123456', 'email': 'a@itest.com'}],
    expectError=True, expectErrorCode=1003)
add(id='param.register.usernameTooLong', desc='参数：注册用户名 21 位超长', actor='anon',
    api='Auth.register', args=[{'username': 'a' * 21, 'password': 'It123456', 'email': 'long@itest.com'}],
    expectError=True, expectErrorCode=1003)
add(id='param.register.passwordTooShort', desc='参数：注册密码 1 位（PASSWORD 要求 2-20）', actor='anon',
    api='Auth.register', args=[{'username': 'pws' + '{{suffix}}', 'password': '1', 'email': 'pw1@itest.com'}],
    expectError=True, expectErrorCode=1003)
add(id='param.register.phoneBadFormat', desc='参数：手机号格式不正确', actor='anon',
    api='Auth.register', args=[{'username': 'phs' + '{{suffix}}', 'password': 'It123456', 'email': 'ph@itest.com', 'phone': '12345'}],
    expectError=True, expectErrorCode=1003)
add(id='param.register.emailBlank', desc='参数：邮箱为空（@NotBlank）', actor='anon',
    api='Auth.register', args=[{'username': 'emb' + '{{suffix}}', 'password': 'It123456', 'email': ''}],
    expectError=True, expectErrorCode=1003)
add(id='biz.register.duplicateUsername', desc='业务：用户名已存在', actor='anon',
    api='Auth.register', args=[{'username': '{{userName}}', 'password': 'It123456', 'email': 'dup@itest.com'}],
    expectError=True, expectErrorCode=2004)
add(id='biz.register.duplicateEmail', desc='业务：邮箱已存在', actor='anon',
    api='Auth.register', args=[{'username': 'dupem' + '{{suffix}}', 'password': 'It123456', 'email': '{{userName}}@itest.com'}],
    expectError=True, expectErrorCode=2005)
add(id='biz.login.wrongPassword', desc='业务：密码错误', actor='anon',
    api='Auth.login', args=['{{userName}}', 'DefinitelyWrong123'], expectError=True)

# ========================================================================
# C. 参数校验 · 昵称 / 分类 / 商品 / 地址
# ========================================================================
add(id='param.nickname.tooShort', desc='参数：昵称 1 位（NICKNAME 要求 2-10）', actor='user',
    api='User.changeNickname', args=['一'], expectError=True, expectErrorCode=3005)
add(id='param.nickname.tooLong', desc='参数：昵称 11 位超长', actor='user',
    api='User.changeNickname', args=['abcdefghijk'], expectError=True, expectErrorCode=3005)
add(id='param.nickname.illegalChar', desc='参数：昵称含非法符号', actor='user',
    api='User.changeNickname', args=['bad@name!'], expectError=True, expectErrorCode=3005)

add(id='param.category.nameTooLong', desc='参数：分类名 21 字超长（CATEGORY_NAME 2-20）', actor='admin',
    api='Category.add', args=[{'name': 'IT' + 'x' * 19, 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 1}],
    expectError=True, expectErrorCode=1003)
add(id='param.category.iconNotUrl', desc='参数：分类图标不是 http/https 链接', actor='admin',
    api='Category.add', args=[{'name': 'IT参数类目' + '{{suffix}}', 'icon': 'not-a-url', 'sort': 1, 'status': 1}],
    expectError=True, expectErrorCode=1003)
add(id='param.category.statusMissing', desc='参数：分类状态缺失（@NotNull）', actor='admin',
    api='Category.add', args=[{'name': 'IT参数类目2' + '{{suffix}}', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1}],
    expectError=True, expectErrorCode=1003)
add(id='param.category.statusOutOfRange', desc='参数：分类状态非 0/1', actor='admin',
    api='Category.add', args=[{'name': 'IT参数类目3' + '{{suffix}}', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 9}],
    expectError=True, expectErrorCode=1003)
add(id='biz.category.duplicateName', desc='业务：分类名称重复', actor='admin',
    api='Category.add', args=[{'name': '回归类目{{suffix}}改', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 1}],
    expectError=True)

add(id='param.product.imageNotUrl', desc='参数：商品图片不是合法链接', actor='admin',
    api='Product.add', args=[{'name': 'IT参数商品' + '{{suffix}}', 'image': 'abc.jpg', 'price': 10, 'stock': 5, 'categoryId': '{{seckillCategoryId}}'}],
    expectError=True, expectErrorCode=1003)
add(id='param.product.priceZero', desc='参数：商品价格必须大于 0', actor='admin',
    api='Product.add', args=[{'name': 'IT参数商品2' + '{{suffix}}', 'image': 'https://it-test.peakflow.local/p.png', 'price': 0, 'stock': 5, 'categoryId': '{{seckillCategoryId}}'}],
    expectError=True, expectErrorCode=1003)
add(id='param.product.stockNegative', desc='参数：商品库存不能为负', actor='admin',
    api='Product.add', args=[{'name': 'IT参数商品3' + '{{suffix}}', 'image': 'https://it-test.peakflow.local/p.png', 'price': 10, 'stock': -1, 'categoryId': '{{seckillCategoryId}}'}],
    expectError=True, expectErrorCode=1003)
add(id='param.product.nameTooLong', desc='参数：商品名称超长（PRODUCT_NAME 2-30）', actor='admin',
    api='Product.add', args=[{'name': 'IT' + 'x' * 30, 'image': 'https://it-test.peakflow.local/p.png', 'price': 10, 'stock': 5, 'categoryId': '{{seckillCategoryId}}'}],
    expectError=True, expectErrorCode=1003)

add(id='param.address.phoneBadFormat', desc='参数：地址手机号格式不正确', actor='user',
    api='Address.add', args=[{'receiverName': '参数', 'receiverPhone': '123', 'province': '江苏省', 'city': '南京市', 'district': '鼓楼区', 'detailAddress': '参数路1号'}],
    expectError=True, expectErrorCode=1003)
add(id='param.address.receiverBlank', desc='参数：收货人为空（@NotBlank）', actor='user',
    api='Address.add', args=[{'receiverName': '', 'receiverPhone': '13800000000', 'province': '江苏省', 'city': '南京市', 'district': '鼓楼区', 'detailAddress': '参数路2号'}],
    expectError=True, expectErrorCode=1003)
add(id='param.address.updateIdMissing', desc='参数：更新地址缺少 id（@NotNull）', actor='user',
    api='Address.update', args=[{'receiverName': '改', 'receiverPhone': '13800000000', 'province': '江苏省', 'city': '南京市', 'district': '鼓楼区', 'detailAddress': '参数路3号'}],
    expectError=True, expectErrorCode=1003)

# ========================================================================
# D. 参数校验 · 订单
# ========================================================================
add(id='param.order.noItems', desc='参数：订单商品清单为空', actor='user',
    api='Order.create', args=[{'addressId': '{{addressId}}', 'items': []}], expectError=True)
add(id='param.order.quantityTooLarge', desc='参数：单个商品数量超过 10 上限', actor='user',
    api='Order.create', args=[{'addressId': '{{addressId}}', 'items': [{'productId': 1, 'quantity': 11}]}], expectError=True)
add(id='param.order.boundaryQuantity10', desc='边界：单个商品数量取上限 10 应成功', actor='user',
    api='Order.create', args=[{'addressId': '{{addressId}}', 'items': [{'productId': 1, 'quantity': 10}]}],
    save={'boundaryOrderId': 'id'}, expectTruthy=['id'])
add(id='param.order.addressNotFound', desc='参数：收货地址不存在', actor='user',
    api='Order.create', args=[{'addressId': 999999999, 'items': [{'productId': 1, 'quantity': 1}]}], expectError=True)
add(id='biz.order.otherUserAddress', desc='业务：使用他人地址下单应被拒', actor='admin',
    api='Order.create', args=[{'addressId': '{{addressId}}', 'items': [{'productId': 1, 'quantity': 1}]}], expectError=True)

# ========================================================================
# E. 业务规则 · 认证与权限
# ========================================================================
add(id='biz.auth.noToken', desc='业务：未登录访问受保护接口', actor='anon',
    api='User.info', expectError=True, expectErrorCode=401)
add(id='biz.auth.noToken.orderList', desc='业务：未登录查看订单列表', actor='anon',
    api='Order.list', args=[{'pageNum': 1, 'pageSize': 10}], expectError=True, expectErrorCode=401)
add(id='biz.auth.forgedToken', desc='业务：伪造 token 访问', actor='forged',
    api='User.info', expectError=True, expectErrorCode=401)
add(id='biz.admin.productForbiddenForUser', desc='权限：普通用户新增商品应 403', actor='user',
    api='Product.add', args=[{'name': 'IT越权商品' + '{{suffix}}', 'image': 'https://it-test.peakflow.local/p.png', 'price': 10, 'stock': 5, 'categoryId': '{{seckillCategoryId}}'}],
    expectError=True, expectErrorCode=403)
add(id='biz.admin.categoryForbiddenForUser', desc='权限：普通用户新增分类应 403', actor='user',
    api='Category.add', args=[{'name': 'IT越权类目' + '{{suffix}}', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 1}],
    expectError=True, expectErrorCode=403)
add(id='biz.admin.statsForbiddenForUser', desc='权限：普通用户看仪表盘应 403', actor='user',
    api='Admin.stats', expectError=True, expectErrorCode=403)
add(id='biz.admin.resetPwdForbiddenForUser', desc='权限：普通用户重置他人密码应 403', actor='user',
    api='Admin.resetPassword', args=['{{banUserId}}'], expectError=True, expectErrorCode=403)
add(id='biz.admin.uploadForbiddenForUser', desc='权限：普通用户无 file:upload，上传图片应 403', actor='user',
    transport='multipart', path='/upload/image', field='file', fileName='r.png',
    api='Upload.image', expectError=True, expectErrorCode=403)

# 封禁 → 无法登录 → 解封恢复
add(id='biz.ban.prepare', desc='准备：注册待封禁用户', actor='anon',
    api='Auth.register', args=[{'username': '{{ban2Name}}', 'password': 'It123456', 'email': '{{ban2Name}}@itest.com', 'nickname': '待封禁2'}],
    save={'ban2UserId': 'user.id'}, expectTruthy=['user.id'])
add(id='biz.ban.doBan', desc='业务：管理员封号', actor='admin', api='Admin.ban', args=['{{ban2UserId}}'], expectTruthy=[])
add(id='biz.ban.bannedCannotLogin', desc='业务：被封禁账号登录应被拒', actor='anon',
    api='Auth.login', args=['{{ban2Name}}', 'It123456'], expectError=True)
add(id='biz.ban.doUnban', desc='业务：管理员解封', actor='admin', api='Admin.unban', args=['{{ban2UserId}}'], expectTruthy=[])
add(id='biz.ban.canLoginAfterUnban', desc='业务：解封后可正常登录', actor='anon',
    api='Auth.login', args=['{{ban2Name}}', 'It123456'], expectTruthy=['token'])

# ========================================================================
# F. 业务规则 · 订单状态机
# ========================================================================
add(id='biz.order.state.prepare', desc='准备：下一笔待付款订单', actor='user',
    api='Order.create', args=[{'addressId': '{{addressId}}', 'items': [{'productId': 1, 'quantity': 1}]}],
    save={'stateOrderId': 'id'}, expectTruthy=['id'])
add(id='biz.order.confirmNotShipped', desc='业务：未发货订单不能确认收货', actor='user',
    api='Order.confirm', args=['{{stateOrderId}}'], expectError=True)
add(id='biz.order.refundNotPaid', desc='业务：未付款订单不能申请退款', actor='user',
    api='Order.refund', args=['{{stateOrderId}}', {'reason': '未付款申请退款'}], expectError=True)
add(id='biz.order.cancelWaitPay', desc='业务：待付款订单可取消', actor='user',
    api='Order.cancel', args=['{{stateOrderId}}'], expectTruthy=[])
add(id='biz.order.cancelTwice', desc='业务：已取消订单不能再次取消', actor='user',
    api='Order.cancel', args=['{{stateOrderId}}'], expectError=True)
add(id='biz.order.detailNotFound', desc='业务：订单不存在', actor='user',
    api='Order.detail', args=[999999999], expectError=True)
add(id='biz.order.detailOtherUser', desc='业务：查看他人订单应被拒', actor='admin',
    api='Order.detail', args=['{{payOrderId}}'], expectError=True, expectErrorCode=3007)

# ========================================================================
# G. 业务规则 · 退款状态机与退货信息
# ========================================================================
add(id='biz.refund.returnInfoMissing', desc='业务：售后退货退款必须填写退货物流', actor='user',
    api='Order.refund', args=['{{shipOrderId}}', {'reason': '缺退货物流'}], expectError=True)
add(id='biz.refund.cancelTwice', desc='业务：退款单撤销后不能重复撤销', actor='user',
    api='Refund.cancel', args=['{{refundId}}'], expectError=True)
add(id='biz.refund.detailNotFound', desc='业务：退款单不存在', actor='user',
    api='Refund.detail', args=[999999999], expectError=True)
add(id='biz.refund.adminConfirmReturnInvalid', desc='业务：非「待用户退货」状态不能确认收货', actor='admin',
    api='Refund.adminConfirmReturn', args=['{{refundShippedId}}'], expectError=True)

# ========================================================================
# H. 业务规则 · 商品与分类约束
# ========================================================================
add(id='biz.category.prepare', desc='准备：新建一个分类用于「有商品不可删」验证', actor='admin',
    api='Category.add', args=[{'name': 'IT约束类目' + '{{suffix}}', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 1}],
    expectTruthy=[])
add(id='biz.category.prepare.find', desc='准备：回填该分类 id', actor='anon',
    api='Category.list', saveFind={'constraintCategoryId': {'list': '', 'by': 'name', 'equals': 'IT约束类目{{suffix}}', 'value': 'id'}},
    expectTruthy=[])
add(id='biz.category.prepare.product', desc='准备：在分类下建商品（默认上架）', actor='admin',
    api='Product.add', args=[{'name': 'IT约束商品' + '{{suffix}}', 'image': 'https://it-test.peakflow.local/p.png', 'price': 10, 'stock': 5, 'categoryId': '{{constraintCategoryId}}'}],
    expectTruthy=[])
add(id='biz.category.prepare.product.find', desc='准备：回填商品 id', actor='admin',
    api='Product.page', args=[{'pageNum': 1, 'pageSize': 10, 'keyword': 'IT约束商品{{suffix}}'}],
    saveFind={'constraintProductId': {'list': 'list', 'by': 'name', 'equals': 'IT约束商品{{suffix}}', 'value': 'id'}},
    expectTruthy=['list'])
add(id='biz.category.deleteWithProduct', desc='业务：分类下有商品不允许删除', actor='admin',
    api='Category.remove', args=['{{constraintCategoryId}}'], expectError=True, expectErrorCode=3017)
add(id='biz.product.deleteOnShelf', desc='业务：已上架商品不允许删除', actor='admin',
    api='Product.remove', args=['{{constraintProductId}}'], expectError=True)
add(id='biz.product.updateNotExist', desc='业务：更新不存在的商品', actor='admin',
    api='Product.update', args=[{'id': 999999999, 'name': 'IT幽灵商品', 'image': 'https://it-test.peakflow.local/p.png', 'price': 10, 'stock': 1, 'categoryId': '{{constraintCategoryId}}', 'status': 1}],
    expectError=True)
add(id='biz.product.statusInvalidValue', desc='业务：商品状态值非法（只允许 0/1）', actor='admin',
    api='Product.status', args=['{{constraintProductId}}', 2], expectError=True)
add(id='biz.product.detailNotExist', desc='业务：商品详情不存在', actor='anon',
    api='Product.detail', args=[999999999], expectTruthy=[])
# 清理：下架 → 删商品 → 删分类
add(id='biz.constraint.cleanupProductOff', desc='清理：下架约束商品', actor='admin',
    api='Product.status', args=['{{constraintProductId}}', 0], expectTruthy=[])
add(id='biz.constraint.cleanupProduct', desc='清理：删除约束商品', actor='admin',
    api='Product.remove', args=['{{constraintProductId}}'], expectTruthy=[])
add(id='biz.constraint.cleanupCategory', desc='清理：删除约束分类', actor='admin',
    api='Category.remove', args=['{{constraintCategoryId}}'], expectTruthy=[])

# ========================================================================
# I. 业务规则 · 秒杀与支付幂等
# ========================================================================
add(id='biz.seckill.repeatOrder', desc='业务：同一商品重复秒杀（一人一单）', actor='user',
    api='Seckill.order', args=['{{seckillProductId}}', '{{addressId}}'], expectError=True)
add(id='biz.seckill.detailNotExist', desc='业务：秒杀商品不存在', actor='anon',
    api='Seckill.detail', args=[999999999], expectError=True)
add(id='biz.pay.prepayIdempotent', desc='业务：同一订单重复发起支付应复用同一支付单', actor='user',
    api='Pay.prepay', args=['{{payOrderId}}'], expectTruthy=['outTradeNo'])
add(id='biz.pay.queryNotExist', desc='业务：查询不存在的支付单', actor='user',
    api='Pay.query', args=['NOT-EXIST-' + '{{suffix}}'], expectError=True)

# ========================================================================
# J. 边缘与清理
# ========================================================================
add(id='edge.category.nameBoundaryMin', desc='边界：分类名 2 字（下限）应成功', actor='admin',
    api='Category.add', args=[{'name': 'IT', 'icon': 'https://it-test.peakflow.local/i.png', 'sort': 1, 'status': 1}], expectTruthy=[])
add(id='edge.category.boundary.find', desc='边界：回填该边界分类 id', actor='anon',
    api='Category.list', saveFind={'edgeCategoryId': {'list': '', 'by': 'name', 'equals': 'IT', 'value': 'id'}},
    expectTruthy=[])
add(id='edge.category.cleanupBoundary', desc='清理：删除边界分类', actor='admin',
    api='Category.remove', args=['{{edgeCategoryId}}'], expectTruthy=[])
add(id='edge.product.priceMin', desc='边界：商品价格 0.01（下限）应成功', actor='admin',
    api='Product.add', args=[{'name': 'IT边界商品' + '{{suffix}}', 'image': 'https://it-test.peakflow.local/p.png', 'price': 0.01, 'stock': 0, 'categoryId': '{{seckillCategoryId}}'}],
    expectTruthy=[])
add(id='edge.product.boundary.find', desc='边界：回填该边界商品 id', actor='admin',
    api='Product.page', args=[{'pageNum': 1, 'pageSize': 10, 'keyword': 'IT边界商品{{suffix}}'}],
    saveFind={'edgeProductId': {'list': 'list', 'by': 'name', 'equals': 'IT边界商品{{suffix}}', 'value': 'id'}},
    expectTruthy=['list'])
add(id='edge.product.cleanupBoundaryOff', desc='清理：下架边界商品', actor='admin',
    api='Product.status', args=['{{edgeProductId}}', 0], expectTruthy=[])
add(id='edge.product.cleanupBoundary', desc='清理：删除边界商品', actor='admin',
    api='Product.remove', args=['{{edgeProductId}}'], expectTruthy=[])
add(id='edge.order.cleanupBoundary', desc='清理：取消边界数量订单', actor='user',
    api='Order.cancel', args=['{{boundaryOrderId}}'], expectTruthy=[])

# 过滤掉引用了「未定义变量」的步骤由运行期暴露；这里只做去重追加
steps.extend(new)
d['steps'] = steps
json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)

print('新增步骤数:', len(new))
print('步骤总数:', len(steps))
neg = [s for s in steps if s.get('expectError')]
print('负向用例数:', len(neg))
pinned = [s for s in neg if s.get('expectErrorCode') is not None]
print('其中断言了精确失败码:', len(pinned))
