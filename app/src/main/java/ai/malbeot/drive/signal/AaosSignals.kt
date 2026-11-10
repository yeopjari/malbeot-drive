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
            car = Car.createCar(context)
            manager = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            manager!!.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            manager!!.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { onEvent(VehicleProbe.KEY_ERROR, "AAOS Car API 실패: $it") }
    }

    fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
    }

    private fun gearName(g: Int?): String = when (g) {
        1 -> "N"; 2 -> "R"; 4 -> "P"; 8 -> "D"; null -> "-"; else -> "D($g)"
    }

    companion object {
        const val KEY_SPEED = "차속(km/h)"
        const val KEY_GEAR = "기어"
    }
}
