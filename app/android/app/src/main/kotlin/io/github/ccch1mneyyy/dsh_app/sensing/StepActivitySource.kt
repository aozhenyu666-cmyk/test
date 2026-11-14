package io.github.ccch1mneyyy.dsh_app.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 计步源：只读 TYPE_STEP_COUNTER（开机以来的累计步数），每 [windowMs] 算一次活动。
 *
 * Android 10 起读这个传感器要 ACTIVITY_RECOGNITION 运行时权限（清单里已声明，授权在能力自检里做）。
 * 没授权就收不到事件，[current] 一直是「不确定」——不确定不等于「静止」，别把它当成他没动。
 * 没有计步器的手机会让 [available] 是 false，那台机上这两个能力就是没有，不假装有。
 */
class StepActivitySource(context: Context, private val windowMs: Long = 30_000L) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var baseSteps: Float? = null
    private var baseAt = 0L

    @Volatile
    private var kind = ActivityKind.UNKNOWN

    val available: Boolean get() = sensor != null

    fun current(): ActivityKind = kind

    /** 返回 false 表示这台机没有计步器或注册失败，调用方照实报「没有」，别重试到天荒地老。 */
    fun start(): Boolean {
        val m = manager ?: return false
        val s = sensor ?: return false
        return m.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val base = baseSteps
        if (base == null) {
            // 第一个事件只记基线：累计值是开机以来的，不减去基线会把一辈子走的路都算进这一窗口。
            baseSteps = event.values[0]
            baseAt = now
            return
        }
        if (now - baseAt < windowMs) return
        kind = ActivityClassifier.classify(
            steps = (event.values[0] - base).toInt(),
            windowMs = now - baseAt,
        )
        baseSteps = event.values[0]
        baseAt = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
