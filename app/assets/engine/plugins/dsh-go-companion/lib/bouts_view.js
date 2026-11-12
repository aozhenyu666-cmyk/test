// dsh-go-companion · 「局」面板（第八轮 W1）
//
// 局有自己的界面，跟两姐妹分开：局进行中她们不在这里说话。
// 四页：
//   现在   这一拍的问题（大字）、剩余时间、作答区、本局进度
//   队列   今天的局：完成 / 进行中 / 待开 / 加局
//   协议库 已装协议、试用中、退役；导入、导出
//   账     精力分布、空局、敷衍、升级、各招式通过率
//
// 视图只认 data.* 绑定，数据由 index.js 推。这里不写任何一句话的措辞——
// 局官说的话来自协议（J2）与局官（J9），面板只负责把它摆出来。

const b = (key) => ({ $: `data.${key}` })

export function boutsView(_scope, caps = {}) {
  const forms = Array.isArray(caps.components) && caps.components.includes('form')
  const children = [
    // ---- 现在 ----
    { type: 'callout', text: b('now.headline'), tone: b('now.tone') },
    { type: 'text', text: b('now.ask'), size: 'large' },
    { type: 'metric', label: '剩余', value: b('now.left'), caption: b('now.progress') },
    { type: 'progress', label: '本局进度', value: b('now.percent'), tone: b('now.tone') },
    { type: 'text', text: b('now.hint'), tone: 'muted' },
  ]
  if (forms) {
    children.push({
      type: 'form',
      label: '答这一拍',
      submit: 'bouts.answer',
      submitLabel: '提交',
      children: [
        { type: 'textField', label: '说的', bind: 'data.form.answer', multiline: true, maxLines: 4, maxLength: 300, required: true },
        { type: 'textField', label: '写/拍的引用（可选）', bind: 'data.form.ref', maxLength: 200 },
      ],
    })
  }
  children.push({ type: 'actions', items: [
    { label: '刷新', action: 'bouts.refresh', icon: 'refresh', style: 'primary' },
    { label: '收局', action: 'bouts.close', icon: 'check' },
  ] })
  children.push({ type: 'divider' })

  // ---- 队列 ----
  children.push({ type: 'text', text: '**今天的局**' })
  children.push({ type: 'list', items: b('queue') })
  children.push({ type: 'text', text: b('queueNote'), tone: 'muted' })
  if (forms) {
    children.push({
      type: 'form',
      label: '加一局',
      submit: 'bouts.open',
      submitLabel: '开局',
      children: [
        {
          type: 'segmented', label: '哪种局', bind: 'data.form.protocolId',
          options: [
            { label: '执行', value: 'execute' },
            { label: '学习', value: 'study' },
            { label: '外出', value: 'outing' },
            { label: '设计', value: 'design' },
            { label: '休息', value: 'rest' },
          ],
        },
      ],
    })
  }
  children.push({ type: 'divider' })

  // ---- 协议库 ----
  children.push({ type: 'text', text: '**协议库**' })
  children.push({ type: 'list', items: b('protocolRows') })
  children.push({ type: 'text', text: b('protocolNote'), tone: 'muted' })
  children.push({ type: 'divider' })

  // ---- 账 ----
  children.push({ type: 'text', text: '**账**' })
  children.push({ type: 'row', children: [
    { type: 'metric', label: '精力分布', value: b('books.minutes'), caption: b('books.minutesCaption') },
    { type: 'metric', label: '空局', value: b('books.empty'), tone: b('books.emptyTone'), caption: b('books.emptyCaption') },
  ] })
  children.push({ type: 'row', children: [
    { type: 'metric', label: '敷衍', value: b('books.slack'), caption: b('books.slackCaption') },
    { type: 'metric', label: '升级', value: b('books.escalations'), caption: b('books.escalationsCaption') },
  ] })
  children.push({ type: 'list', items: b('books.rows') })

  return { type: 'stack', title: '局', children }
}
