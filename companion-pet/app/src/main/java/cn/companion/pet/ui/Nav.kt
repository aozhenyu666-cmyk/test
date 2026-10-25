package cn.companion.pet.ui

/** 设置页分区（右上角“设置”打开）。 */
const val SETTINGS_PERMISSIONS = 0
const val SETTINGS_MODELS = 1
const val SETTINGS_COMPANION = 2
const val SETTINGS_DEVICE = 3
const val SETTINGS_DIAGNOSTICS = 4
internal val SETTINGS_GROUPS = listOf("权限", "模型与看图", "陪伴", "设备", "诊断")

/** “我的”分区：它自己的东西都放这里。 */
const val MINE_MEMORY = 0
const val MINE_GOALS = 1
const val MINE_SKILLS = 2
const val MINE_PERSONA = 3
const val MINE_STATS = 4
internal val MINE_GROUPS = listOf("记忆", "目标", "技能", "人设", "统计")
