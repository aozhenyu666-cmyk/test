package cn.companion.pet.core.text

/**
 * 一次读屏得到的文字候选。
 *
 * [text] 是节点上的原始文字；[inScrollableContainer] 表示该文字是否来自可滚动容器
 * （通常意味着它是正文段落而不是按钮、标签之类的短词）。
 *
 * 这个类型只承载数据，不做任何判断，也不接触 AccessibilityNodeInfo 或 Context。
 */
data class TextCandidate(
    val text: String,
    val inScrollableContainer: Boolean
)
