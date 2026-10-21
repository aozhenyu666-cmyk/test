// Runs the app inside a claude.ai artifact viewer.
// There the page cannot reach outside APIs, but it can ask Claude on the viewer's own
// account through the `sample` capability, and offer files through `downloads`.

export function inClaudeViewer() {
  return typeof window !== 'undefined' && typeof window.claude?.use === 'function';
}

const cache = {};
function use(name) {
  if (!inClaudeViewer()) return Promise.resolve(null);
  cache[name] ||= window.claude.use(name).catch(() => null);
  return cache[name];
}

export const getSample = () => use('sample');
export const getDownloads = () => use('downloads');

const SAMPLE_ERRORS = {
  not_granted: '你还没有允许这个页面使用 Claude。重新打开页面后，在弹出的提示里选择允许。',
  sampling_disabled: '这个账号暂时不能在页面里使用 Claude。',
  rate_limited: '用得太频繁或者达到了 Claude 的用量上限，过一会儿再试。',
  session_expired: '登录已过期，请重新登录 claude.ai。',
  refused: 'Claude 拒绝回答这个请求，可以换一种描述方式再试。',
  invalid_json: '这次的回答格式不对，请再点一次。',
  prompt_too_large: '内容太长了，删掉一些再试。',
  empty_completion: '这次没有得到回答，请把情况写得更具体一些再试。',
};

export function sampleErrorMessage(e) {
  return SAMPLE_ERRORS[e?.code] || `Claude 暂时不可用（${e?.code || '未知错误'}），请稍后再试。`;
}
