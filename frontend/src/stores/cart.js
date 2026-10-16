import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 购物车勾选态（前端本地维护）
//
// 背景：后端 /cart/list 会回传每个条目的 selected（服务端 Redis 用「排除法」存选中态），
// 但 CartController 并没有暴露修改勾选的接口（旧的 /cart/select、/cart/selected 在后端已不存在，会 404）。
// 所以勾选结果只能由前端持有，放在 store 里让「购物车页」与「结算页」共享，
// 避免从购物车跳到结算页时勾选丢失。
export const useCartStore = defineStore('cart', () => {
  // 已勾选的商品 id（数组存储，模板里可直接 includes 判断）
  const selectedIds = ref([])
  // 是否已用后端返回的 selected 初始化过（只在首次加载购物车时同步一次）
  const initialized = ref(false)

  const selectedCount = computed(() => selectedIds.value.length)

  function isSelected(productId) {
    return selectedIds.value.includes(productId)
  }

  // 首次加载购物车时，用后端回传的 selected 初始化本地勾选态
  function initFromList(list) {
    if (initialized.value) return
    selectedIds.value = (list || [])
      .filter(i => Number(i.selected) === 1)
      .map(i => i.productId)
    initialized.value = true
  }

  function setOne(productId, selected) {
    const has = isSelected(productId)
    if (selected && !has) selectedIds.value = [...selectedIds.value, productId]
    if (!selected && has) selectedIds.value = selectedIds.value.filter(id => id !== productId)
  }

  function setAll(productIds, selected) {
    if (selected) {
      selectedIds.value = Array.from(new Set([...selectedIds.value, ...productIds]))
    } else {
      selectedIds.value = selectedIds.value.filter(id => !productIds.includes(id))
    }
  }

  function remove(productId) {
    setOne(productId, false)
  }

  function clear() {
    selectedIds.value = []
  }

  return {
    selectedIds, initialized, selectedCount,
    isSelected, initFromList, setOne, setAll, remove, clear,
  }
})
