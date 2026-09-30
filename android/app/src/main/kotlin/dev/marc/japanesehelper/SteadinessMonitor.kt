package dev.marc.japanesehelper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Immobilité du téléphone d'après le gyroscope : [steadiness] monte de 0 à 1 quand la
 * vitesse de rotation reste sous [threshold] pendant [holdMs], et retombe à 0 au moindre
 * mouvement. Sert au déclenchement automatique d'une photo nette.
 */
class SteadinessMonitor(
    context: Context,
    private val threshold: Float = 0.08f, // rad/s, tremblement normal d'une main : ~0,02-0,1
    private val holdMs: Long = 600,
) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val available get() = gyroscope != null

    private val _steadiness = MutableStateFlow(0f)
    val steadiness: StateFlow<Float> = _steadiness.asStateFlow()

    private var smoothed = 0f
    private var steadySince = 0L

    fun start() {
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors.unregisterListener(this)
        reset()
    }

    /** Repart de zéro (après une photo, par exemple). */
    fun reset() {
        steadySince = 0L
        _steadiness.value = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        // Lissage exponentiel : ignore les pics isolés du capteur
        smoothed = 0.7f * smoothed + 0.3f * sqrt(x * x + y * y + z * z)
        val now = event.timestamp / 1_000_000
        if (smoothed > threshold) {
            steadySince = 0L
            _steadiness.value = 0f
            return
        }
        if (steadySince == 0L) steadySince = now
        _steadiness.value = ((now - steadySince).toFloat() / holdMs).coerceAtMost(1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
