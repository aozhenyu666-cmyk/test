// dsh-go-companion · 「墙」面板（第八轮 W2）
//
// 墙是控制塔，跟两姐妹分开：这一页只摆事实和规则，不聊天。
// 两个面板（宿主没有分页组件）：
//   墙     此刻主状态、娱乐锁没锁、锁到几点、原因、今天的局、今日升级、现在生效的阈值
//   审查   第七轮 A3 的审查记录与申诉，句子来自 wall_voice.js 的模板
//
// 视图只认 data.* 绑定，数据由 index.js 推。

const b = (key) => ({ $: `data.${key}` })

const refresh = { label: '刷新', action: 'wall.refresh', icon: 'refresh', style: 'primary' }

/** 墙：此刻。 */
export function wallView(_scope, _caps = {}) {
  return {
    type: 'stack',
    title: '墙',
    children: [
      { type: 'callout', text: b('lock.headline'), tone: b('lock.tone') },
      { type: 'text', text: b('lock.caption'), tone: 'muted' },
      { type: 'text', text: '**此刻**' },
      { type: 'metric', label: '主状态', value: b('state.label'), caption: b('state.agenda') },
      { type: 'list', items: b('layers') },
      { type: 'text', text: b('layersNote'), tone: 'muted' },
      { type: 'text', text: '**今天的局**' },
      { type: 'text', text: b('plan.headline') },
      { type: 'list', items: b('plan.rows') },
      { type: 'text', text: b('plan.note'), tone: 'muted' },
      { type: 'text', text: '**今日升级**' },
      { type: 'list', items: b('escalate.rows') },
      { type: 'text', text: b('escalate.note'), tone: 'muted' },
      { type: 'text', text: '**现在生效的阈值**' },
      { type: 'list', items: b('rules.rows') },
      { type: 'text', text: b('rules.note'), tone: 'muted' },
      { type: 'actions', items: [refresh] },
    ],
  }
}

/** 审查：记录与申诉。 */
export function wallAuditView(_scope, _caps = {}) {
  return {
    type: 'stack',
    title: '审查',
    children: [
      { type: 'text', text: '**审查记录**' },
      { type: 'list', items: b('audit.rows') },
      { type: 'text', text: b('audit.note'), tone: 'muted' },
      // 申诉要指着某一条判定的精确时刻，面板上只有「几点几分」——手指点出来的时间戳对不上账。
      // 所以这里只摆记录；申诉走它自己那个入口（第七轮 A3）。
      { type: 'text', text: '申诉要指着一条具体判定交新的物理证据，那个入口在提交页。', tone: 'muted' },
      { type: 'actions', items: [refresh] },
    ],
  }
}
