package ai.malbeot.drive.signal

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/** 차속·기어: Pleos 정책상 표준 데이터는 AAOS Car API로 읽는다. */
class AaosSignals(private val context: Context, private val onEvent: (key: String, value: Any?) -> Unit) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> onEvent(KEY_SPEED, (value.value as? Float)?.times(3.6f)) // m/s → km/h
                VehiclePropertyIds.GEAR_SELECTION -> onEvent(KEY_GEAR, gearName(value.value as? Int))
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = onEvent(VehicleProbe.KEY_ERROR, "AAOS 속성 오류 $propId")
    }

    fun start() {
        runCatching {
            stop() // 권한 허용 뒤 다시 불릴 수 있으므로 중복 구독 방지
            car = Car.createCar(context)
            manager = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            for ((id, name) in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "차속", VehiclePropertyIds.GEAR_SELECTION to "기어")) {
                val ok = runCatching { manager!!.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                    .getOrElse { e -> onEvent(VehicleProbe.KEY_ERROR, "AAOS $name 구독 실패: $e"); false }
                onEvent(VehicleProbe.KEY_INFO, "AAOS $name 구독 ${if (ok) "성공" else "실패"}")
            }
        }.onFailure { onEvent(VehicleProbe.KEY_ERROR, "AAOS Car API 실패: $it") }
    }

    fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null; car = null
    }

    private fun gearName(g: Int?): String = when (g) {
        1 -> "N"; 2 -> "R"; 4 -> "P"; 8 -> "D"; null -> "-"; else -> "D($g)"
    }

    companion object {
        const val KEY_SPEED = "차속(km/h)"
        const val KEY_GEAR = "기어"
    }
}
