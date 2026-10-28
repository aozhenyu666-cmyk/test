package cn.companion.pet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 文字输入页。
 *
 * 为什么单独开一个 Activity：
 * 悬浮窗（TYPE_APPLICATION_OVERLAY）里的 EditText 没有 Activity 窗口令牌，
 * 部分 ROM 会给它挂一个隔离的输入法实例，表现为“弹出一个跟我平时不一样的百度输入法，
 * 粘贴不了、词库也不通”。在 Activity 里输入就没有这个问题。
 */
class InputActivity : androidx.activity.ComponentActivity() {

    companion object {
        fun intent(context: Context, requestId: Long): Intent = Intent(context, InputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("input_request_id", requestId)
    }

    private var requestId = 0L

    override fun onResume() {
        super.onResume()
        if (!InputBridge.isActive(requestId)) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra("input_request_id", 0L)
        if (!InputBridge.isActive(requestId)) { finish(); return }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(20))
        root.setBackgroundColor(android.graphics.Color.rgb(247, 243, 234))

        val title = TextView(this)
        title.text = "和同桌说"
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTextColor(android.graphics.Color.rgb(28, 26, 22))
        root.addView(title)

        val hintView = TextView(this)
        hintView.text = InputBridge.hint(requestId).ifBlank { "想说什么" }
        hintView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        hintView.setTextColor(android.graphics.Color.rgb(95, 89, 78))
        hintView.setPadding(0, dp(6), 0, dp(10))
        root.addView(hintView)

        val input = EditText(this)
        input.hint = "直接打字，或用输入法上的话筒说话"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEND
        input.maxLines = 4
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        input.setTextColor(android.graphics.Color.rgb(28, 26, 22))
        // 长按菜单里要有“粘贴”，方便把 API Key 之类粘进来。
        input.setCustomSelectionActionModeCallback(null)
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        row.setPadding(0, dp(12), 0, 0)

        val cancel = Button(this)
        cancel.text = "取消"
        cancel.isAllCaps = false
        cancel.setOnClickListener { InputBridge.cancel(requestId); finish() }
        row.addView(cancel)

        val send = Button(this)
        send.text = "发送"
        send.isAllCaps = false
        send.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            hideKeyboard(input)
            if (!InputBridge.submit(requestId, text)) android.widget.Toast.makeText(this, "这次输入已过期，请对当前问题重新打开输入。", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
        row.addView(send)
        root.addView(row)

        setContentView(root)
        // 让 Activity 跟随软键盘上推，输入框不被挡住。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onBackPressed() {
        InputBridge.cancel(requestId)
        super.onBackPressed()
    }

    private fun hideKeyboard(view: android.view.View) {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
