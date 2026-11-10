package ai.malbeot.drive.navi

import android.content.Context
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper

/**
 * Pleos NaviHelper SDK 3.0.5 연동: 목적지 경로 요청, 쉼터 경유지 추가.
 * 호출 방식은 공식 샘플(Pleos-Playground/pleos-sample-navi-app)과 같다.
 *
 * 프로토타입 한계(README 개선 예정 표 참고): 쉼터 위치는 시연용 고정 좌표다.
 * 실제 서비스에서는 공공데이터(휴게소·졸음쉼터 위치)에서 현재 위치 기준 가장 가까운 곳을 고른다.
 */
class NaviBridge(context: Context, private val onLog: (String) -> Unit) {
    private val ctx = context.applicationContext
    private var helper: NaviHelper? = null

    fun start() {
        runCatching { NaviHelper(ctx).also { it.initialize(); helper = it } }
            .onSuccess { onLog("정보: 내비(NaviHelper) 연결됨") }
            .onFailure { onLog("오류: 내비 연결 실패: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
    }

    /** 시연용 목적지로 경로 안내 시작 */
    fun startDemoRoute() = call("목적지 경로 요청(${DEMO_DESTINATION.poiName})") { requestRoute(DEMO_DESTINATION) }

    /** 쉼터를 첫 번째 경유지로 추가 */
    fun addRestStop() = call("쉼터 경유지 추가(${DEMO_REST_STOP.poiName})") { addWaypoint(DEMO_REST_STOP) }

    private fun call(what: String, block: NaviHelper.() -> Unit) {
        val h = helper ?: run { onLog("오류: 내비 미연결 → $what 못 함"); return }
        runCatching { h.block() }
            .onSuccess { onLog("정보: $what 보냄") }
            .onFailure { onLog("오류: $what 실패: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
    }

    fun stop() {
        runCatching { helper?.release() }
        helper = null
    }

    companion object {
        // 공식 샘플 NaviTestData의 좌표·POI(에뮬레이터 내비에서 동작 확인된 값)를 시연용으로 사용
        val DEMO_DESTINATION = RouteInfo(
            latitude = 37.48544722, longitude = 127.03636666,
            poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0",
            address = "서울시 강남구 남부순환로 2621",
        )
        val DEMO_REST_STOP = RequestWaypointInfo(
            latitude = 37.41275833, longitude = 127.09481944,
            waypointIndex = WaypointIndex.FIRST,
            poiId = "36khp2hdj2cfcm5nqhe3", poiName = "소프트웨어드림센터", poiSubId = "0",
            address = "경기도 성남시 수정구 창업로40번길 6",
        )
    }
}
