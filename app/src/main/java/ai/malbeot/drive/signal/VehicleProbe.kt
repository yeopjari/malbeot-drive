package ai.malbeot.drive.signal

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pleos Vehicle SDK 실시간 값 구독기.
 *
 * Pleos 문서에 메서드 이름(registerSteeringWheelAngle 등)은 있지만 리스너 인터페이스·값 타입이
 * 공개 문서에 나와 있지 않아, 공식 샘플 앱(VehicleApiExplorer)과 같은 방식으로
 * 리플렉션 + Proxy 리스너를 사용한다. 값 형식이 확인되면 타입 안전 코드로 바꾼다.
 */
class VehicleProbe(context: Context, private val onEvent: (key: String, value: Any?) -> Unit) {

    private val vehicleClass = runCatching { Class.forName("ai.pleos.playground.vehicle.Vehicle") }.getOrNull()
    private val vehicle: Any? = vehicleClass?.getConstructor(Context::class.java)?.newInstance(context.applicationContext)
    private val registrations = mutableListOf<Triple<Any, Method, Array<Any?>>>()

    /** 구독할 (도메인 getter, register 메서드, 화면 키) */
    private val targets = listOf(
        Triple("getSteeringWheel", "registerSteeringWheelAngle", KEY_STEERING),
        Triple("getTurnSignal", "registerTurnSignalState", KEY_TURN_SIGNAL),
        Triple("getDrivingMode", "registerDrivingModeState", KEY_DRIVING_MODE),
        Triple("getSeat", "registerSeatOccupancy", KEY_SEAT),
    )

    fun start() {
        val v = vehicle ?: run { onEvent(KEY_ERROR, "Pleos Vehicle SDK 없음"); return }
        runCatching { vehicleClass!!.getMethod("initialize").invoke(v) }
            .onFailure { onEvent(KEY_ERROR, "initialize 실패: ${it.cause ?: it}"); return }
        for ((getter, register, key) in targets) {
            runCatching { subscribe(v, getter, register, key) }
                .onFailure { onEvent(if (key == KEY_SEAT) KEY_INFO else KEY_ERROR, "$register 실패(${if (key == KEY_SEAT) "필수 아님" else "확인 필요"}): ${it.cause ?: it}") }
        }
    }

    fun stop() {
        registrations.forEach { (domain, register, args) ->
            runCatching {
                domain.javaClass.methods.firstOrNull { it.name == "un" + register.name && it.parameterCount == args.size }
                    ?.invoke(domain, *args)
            }
        }
        registrations.clear()
        runCatching { vehicleClass?.getMethod("release")?.invoke(vehicle) }
    }

    private fun subscribe(v: Any, getterName: String, registerName: String, key: String) {
        val domain = vehicleClass!!.getMethod(getterName).invoke(v) ?: error("$getterName null")
        val method = domain.javaClass.methods.firstOrNull { it.name == registerName }
            ?: error("메서드 없음")
        val args = method.parameterTypes.map { type -> argumentFor(type, key) }.toTypedArray()
        method.invoke(domain, *args)
        registrations += Triple(domain, method, args)
        Log.i(TAG, "subscribed $registerName(${method.parameterTypes.joinToString { it.simpleName }})")
        onEvent(KEY_INFO, "$registerName(${method.parameterTypes.joinToString { it.simpleName }}) 구독")
    }

    private fun argumentFor(type: Class<*>, key: String): Any? = when {
        type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, a ->
            when (m.name) {
                "equals" -> proxy === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MalbeotListener(${type.simpleName})"
                else -> {
                    val value = a?.firstOrNull()
                    if (m.name.contains("fail", true)) onEvent(KEY_ERROR, "$key: $value")
                    else onEvent(key, value)
                    Unit
                }
            }
        }
        type.isEnum -> pickEnum(type.enumConstants!!)
        // 좌석처럼 영역(Area)이 필요한 경우: enum 인자로 만들 수 있는 생성자를 찾는다
        else -> buildArea(type) ?: error(
            "${type.simpleName} 생성 방법 미확인. 생성자: " +
                type.constructors.joinToString { c -> "(" + c.parameterTypes.joinToString { it.simpleName } + ")" }
        )
    }

    private fun pickEnum(constants: Array<out Any>): Any =
        constants.firstOrNull { it.toString().contains("PASSENGER") || it.toString().contains("RIGHT") } ?: constants.first()

    private fun buildArea(type: Class<*>): Any? {
        for (c in type.constructors.sortedBy { it.parameterCount }) {
            val args = c.parameterTypes.map { p ->
                when {
                    p.isEnum -> pickEnum(p.enumConstants!!)
                    // SeatArea(SeatValue[]) 형태: enum 배열에 조수석 값 하나를 담는다
                    p.isArray && p.componentType!!.isEnum -> java.lang.reflect.Array.newInstance(p.componentType, 1).also {
                        java.lang.reflect.Array.set(it, 0, pickEnum(p.componentType!!.enumConstants!!))
                    }
                    p == Int::class.javaPrimitiveType || p == Int::class.javaObjectType -> 0
                    p == Boolean::class.javaPrimitiveType -> false
                    else -> return@map null
                }
            }
            if (args.any { it == null } && c.parameterCount > 0) continue
            runCatching { return c.newInstance(*args.toTypedArray()) }
        }
        return null
    }

    companion object {
        const val TAG = "Malbeot"
        const val KEY_STEERING = "조향각"
        const val KEY_TURN_SIGNAL = "방향지시등"
        const val KEY_DRIVING_MODE = "주행모드"
        const val KEY_SEAT = "조수석"
        const val KEY_ERROR = "오류"
        const val KEY_INFO = "정보"

        /** 숫자로 바꿀 수 있으면 바꾼다 (Number, 또는 value/angle 필드를 가진 객체) */
        fun toDouble(value: Any?): Double? = when (value) {
            null -> null
            is Number -> value.toDouble()
            else -> runCatching {
                value.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name in setOf("getValue", "getAngle") }
                    ?.invoke(value)?.let { (it as? Number)?.toDouble() }
            }.getOrNull() ?: value.toString().toDoubleOrNull()
        }
    }
}
