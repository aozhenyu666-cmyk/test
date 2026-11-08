// 大肥鱼本地记忆 · 原生面板视图
//
// 视图只认 data.* 绑定，不做 IO；所有状态由 lib/index.js 的 data 回调推过来。
// 面板是无状态的：布局固定，点开哪条由 data.detail 决定，避免视图在两种结构间切换。

const b = (key) => ({ $: `data.${key}` })

const IMPORTANCE_OPTIONS = (t) => [
  { label: t('普通', 'Normal'), value: '1' },
  { label: t('重要', 'High'), value: '2' },
  { label: t('琐事', 'Low'), value: '0' },
]

export function memoryView(_scope, caps = {}) {
  const en = String(caps.locale || '').startsWith('en')
  const t = (zh, english) => (en ? english : zh)
  const forms = Array.isArray(caps.components) && caps.components.includes('form')

  const children = [
    { type: 'text', text: b('summary') },
    {
      type: 'keyValue',
      items: [
        [t('有效记忆', 'Active'), b('counts.facts')],
        [t('钉住', 'Pinned'), b('counts.pinned')],
        [t('未完成话题', 'Open topics'), b('counts.topics')],
        [t('今天', 'Today'), b('counts.today')],
      ],
    },
  ]

  if (forms) {
    children.push({
      type: 'form',
      label: t('搜索本地记忆', 'Search local memory'),
      submit: 'search',
      submitLabel: t('搜索', 'Search'),
      children: [
        {
          type: 'textField',
          label: t('关键词（留空看最近）', 'Keywords (empty = recent)'),
          bind: 'data.form.search',
          maxLength: 120,
          placeholder: t('比如：睡觉、面试、约定', 'e.g. sleep, interview, promise'),
        },
      ],
    })
  }

  children.push({ type: 'text', text: b('listTitle'), tone: 'muted' })
  children.push({ type: 'list', items: { $: 'data.items' } })

  children.push({ type: 'divider' })
  children.push({
    type: 'stack',
    style: { card: 'outlined', padding: 'M' },
    children: [
      { type: 'text', text: b('detail.text') },
      {
        type: 'keyValue',
        items: [
          [t('记于', 'Recorded'), b('detail.created')],
          [t('重要度', 'Importance'), b('detail.importance')],
          [t('范围', 'Scope'), b('detail.scope')],
          [t('清晰度', 'Clarity'), b('detail.clarity')],
        ],
      },
      {
        type: 'actions',
        items: [
          { label: t('钉住 / 取消', 'Pin / unpin'), action: 'memory.pin', icon: 'pin' },
          { label: t('忘掉这条', 'Forget'), action: 'memory.forget', icon: 'delete' },
          { label: t('返回列表', 'Back'), action: 'memory.back', icon: 'list' },
        ],
      },
    ],
  })

  if (forms) {
    children.push({
      type: 'form',
      label: t('纠正上面这条', 'Correct the selected entry'),
      submit: 'memory.correct',
      submitLabel: t('保存纠正', 'Save correction'),
      children: [
        {
          type: 'textField',
          label: t('纠正后的内容', 'Corrected text'),
          bind: 'data.form.correct',
          multiline: true,
          maxLines: 4,
          maxLength: 500,
        },
        { type: 'segmented', label: t('重要度', 'Importance'), bind: 'data.form.correctImportance', options: IMPORTANCE_OPTIONS(t) },
      ],
    })
  }

  children.push({ type: 'divider' })
  children.push({ type: 'text', text: t('**未完成的话题**', '**Open topics**') })
  children.push({ type: 'list', items: { $: 'data.topics' } })
  children.push({
    type: 'actions',
    items: [
      { label: t('完成', 'Done'), action: 'topic.done', icon: 'check' },
      { label: t('先搁着', 'Defer'), action: 'topic.defer', icon: 'clock' },
      { label: t('放掉', 'Drop'), action: 'topic.drop', icon: 'delete' },
    ],
  })

  if (forms) {
    children.push({
      type: 'form',
      label: t('记一条新的', 'Remember something'),
      submit: 'remember',
      submitLabel: t('记下', 'Remember'),
      children: [
        {
          type: 'textField',
          label: t('内容（一件事一条）', 'One fact per entry'),
          bind: 'data.form.text',
          multiline: true,
          maxLines: 3,
          maxLength: 500,
          required: true,
        },
        { type: 'segmented', label: t('重要度', 'Importance'), bind: 'data.form.importance', options: IMPORTANCE_OPTIONS(t) },
        { type: 'toggle', label: t('钉住（永不淡忘）', 'Pin (never fades)'), bind: 'data.form.pinned' },
        { type: 'toggle', label: t('所有会话都能看到', 'Share with all sessions'), bind: 'data.form.share' },
      ],
    })
    children.push({
      type: 'form',
      label: t('记一个还没聊完的话题', 'Track an open topic'),
      submit: 'topic.open',
      submitLabel: t('加入话题', 'Add topic'),
      children: [
        {
          type: 'textField',
          label: t('话题', 'Topic'),
          bind: 'data.form.topic',
          maxLength: 120,
          required: true,
        },
      ],
    })
  }

  children.push({
    type: 'actions',
    items: [
      { label: t('刷新', 'Refresh'), action: 'memory.refresh', icon: 'refresh' },
      { label: t('导出 Markdown', 'Export Markdown'), action: 'memory.export', icon: 'download' },
    ],
  })
  children.push({ type: 'callout', tone: 'info', text: b('backendNote') })

  return {
    v: 2,
    type: 'stack',
    title: t('本地记忆', 'Local memory'),
    style: { gap: 'M' },
    children,
  }
}
