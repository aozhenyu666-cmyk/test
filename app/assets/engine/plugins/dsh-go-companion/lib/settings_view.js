// dsh-go-companion · 「设定」面板（第八轮 W4）
//
// 两个面板（宿主没有分页组件）：作息与预算、阈值与声音。表单字段和 settings_panel.js 的
// parseDay / parseLimits 一一对应；改紧立即生效、改松挂一天冷却期由 settings.js 判，这一页
// 只负责把字交上去。
//
// 表单里预填的是现在的值（data.form.* 的 value 绑定），不是空白框——改一个数不该从零开始填。

const b = (key) => ({ $: `data.${key}` })
const v = (key) => ({ $: `data.form.${key}` })

const f = (label, key, hint) => ({
  type: 'textField',
  label,
  bind: 'data.form.' + key,
  value: v(key),
  ...(hint ? { placeholder: hint } : {}),
})

/** 作息与预算。 */
export function settingsDayView(_scope, caps = {}) {
  const children = [
    { type: 'list', items: b('rows') },
    { type: 'text', text: b('rowsNote'), tone: 'muted' },
    { type: 'list', items: b('budgets') },
    { type: 'text', text: b('budgetsNote'), tone: 'muted' },
  ]
  if (Array.isArray(caps.components) && caps.components.includes('form')) {
    children.push({
      type: 'form',
      label: '改作息与预算',
      submit: 'settings.saveDay',
      submitLabel: '保存',
      children: [
        f('工作日时段', 'workday', '9:00-12:00，14:00-18:00'),
        f('休息日时段', 'restday', '10:00-12:00，空着表示没有'),
        f('外出日时段', 'outing', '空着表示没有'),
        f('每天最少局数', 'minBouts', '4'),
        f('最少思维局', 'minThinking', '1'),
        f('学习局预算', 'budget.study', '分钟'),
        f('执行局预算', 'budget.execute', '分钟'),
        f('设计局预算', 'budget.design', '分钟'),
        f('外出局预算', 'budget.outing', '分钟'),
        f('休息局预算', 'budget.rest', '分钟'),
        f('思维局预算', 'budget.thinking', '分钟'),
      ],
    })
  }
  children.push({ type: 'text', text: b('pending.text'), tone: 'muted' })
  return { type: 'stack', title: '作息与预算', children }
}

/** 阈值与声音。 */
export function settingsLimitsView(_scope, caps = {}) {
  const children = [
    { type: 'list', items: b('limits') },
    { type: 'text', text: b('limitsNote'), tone: 'muted' },
    { type: 'metric', label: '念出来', value: b('voice.row') },
    { type: 'metric', label: '局官模型', value: b('voice.model') },
  ]
  if (Array.isArray(caps.components) && caps.components.includes('form')) {
    children.push({
      type: 'form',
      label: '改阈值',
      submit: 'settings.saveLimits',
      submitLabel: '保存',
      children: [
        f('E1 敷衍次数', 'E1Count', '3'),
        f('E1 时间窗（分钟）', 'E1WindowMin', '30'),
        f('审讯舱起步（分钟）', 'cellMin', '10'),
        f('L1 工作时段连续娱乐（分钟）', 'L1WorkMin', '15'),
        f('L1 非工作时段连续娱乐（分钟）', 'L1RestMin', '30'),
        f('L2 在局里用没声明的应用（分钟）', 'L2Min', '20'),
        f('L3 每天娱乐累计（分钟）', 'L3Min', '60'),
      ],
    })
  }
  children.push({ type: 'text', text: b('pending.text'), tone: 'muted' })
  return { type: 'stack', title: '阈值', children }
}
