/**
 * 七维情绪 —— **唯一一份真相**
 *
 * 为什么单独一个文件：
 *   面板（views.mjs）、模型上下文（mood.mjs）、工具（tools.mjs）三处都必须用同一份
 *   维度表。上一轮 views.mjs 里另抄了一份 DIM_LABEL，mood.mjs 里又抄了一份 ——
 *   两边一起改还行，只改一处就会出现「面板显示心疼、上下文里没有心疼」这种错位。
 *   现在只在这里定义一次，其余地方 import + 原样再导出。
 *
 * 维度名是**上游 dsh-feiyu-mood 发的真字段**（实测 0.8.1）：
 *   happy / calm / sweet / heartache / worry / sad / jealous
 *   以前这里写成 steady / tender 是错的 —— 上游根本不发那两个键，
 *   于是「心疼」永远显示 0（真机上就是「明明心疼 7，一根柱子都没有」）。
 */

/** 上游真正的七个维度，顺序也照它。 */
export const DIMS = Object.freeze(['happy', 'calm', 'sweet', 'heartache', 'worry', 'sad', 'jealous'])

export const DIM_LABEL = Object.freeze({
  happy: '开心',
  calm: '安稳',
  sweet: '甜蜜',
  heartache: '心疼',
  worry: '担心',
  sad: '难过',
  jealous: '吃醋',
})

/**
 * 一根情绪「还剩着」的门槛（0–100）。
 * 为什么不是 >0：真机上的 dims 常年是 1.7e-7 这种衰减残渣 ——
 * 用 >0 判断，面板会画出七根全为 0 的柱子，人看了会以为「心情是死的」。
 */
export const DIM_VISIBLE_MIN = 1

/**
 * 七维当前值（上游存的是 0–100 的强度，不是 0–1）。
 * 永远返回七条，顺序固定；缺的字段是 0 且 labeled —— 缺了也要能看出来「上游没发」。
 * @param {{dims?: object}} mood state.json 里的一份 mood
 */
export function dimsOf(mood) {
  const d = mood && mood.dims && typeof mood.dims === 'object' ? mood.dims : {}
  return DIMS.map((k) => {
    const raw = Number(d[k])
    const value = Number.isFinite(raw) ? Math.round(raw * 100) / 100 : 0
    return { key: k, label: DIM_LABEL[k], value, present: Number.isFinite(raw) }
  })
}

/** 只留看得见的那几根（面板和上下文都用这一条门槛）。 */
export function visibleDims(rows) {
  return (rows || []).filter((d) => Number(d.value) >= DIM_VISIBLE_MIN)
}
