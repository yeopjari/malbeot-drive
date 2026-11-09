# 말벗 드라이브

졸리기 전에 먼저 말을 걸고, 대화로 깨어 있는지 확인한 뒤, "응" 한마디로 가까운 쉼터를 경유지에 넣어 주는 Pleos Connect 차량용 음성 동승자 앱입니다.

## 구조
| 모듈 | 내용 |
|---|---|
| `engine` | 순수 Kotlin. 휴식 권장도(RiskEngine), 조향 급교정 판정(SteeringCorrectionDetector). PC에서 단위 테스트 |
| `app` | Android(Pleos Connect) 앱. 현재는 엔진 결과를 색으로 보여 주는 골격 |
| `apk/` | 제출용 release APK 위치 (`팀명_앱이름_release.apk`) |

## 테스트 환경
- Android Studio 최신, JDK 17, Gradle 8.10+, AGP 8.8.2, Kotlin 2.0.0
- Pleos Connect Emulator v2.1.0 (Automotive, API 34, Cold boot, RAM 4GB)
- 차량 값 조작: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/ (Virtual Console)

## 실행 방법
```bash
./gradlew :engine:test        # 엔진 단위 테스트
./gradlew :app:installDebug   # 에뮬레이터 실행 중에 설치
```
Gradle wrapper가 없으면 Android Studio로 폴더를 열면 자동 생성됩니다.

## 휴식 권장도 식
`R = min(100, max(25·sT + 15·sC + 10·sM + 20·sS + 10·sV + 20·sD, 바닥점수))`
단계: 0~29 정상, 30~49 주의, 50~69 경계, 70 이상 위험. 상세는 기획 문서 5장.
