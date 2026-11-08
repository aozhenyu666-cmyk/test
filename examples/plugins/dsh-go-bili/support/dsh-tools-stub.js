// 找不到真的 @deepseek-ai/dsh-tools 时的替身：把定义原样返回。
// 注意：它不校验 output.schema，所以「schema 写得对不对」这一项在有真 DSH 的机器上才算真跑过。
export const defineTool = (options) => options
