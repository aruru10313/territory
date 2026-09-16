# Territory NeoForge Mod

Minecraft 1.21.1 / NeoForge 21.1.249 영토 청크 관리 모드입니다.

## 기능

- 관리자 전용 영토 구매 및 인접 청크 확장
- `/territory` 및 `/trm` 명령어
- 영토 삭제 수락/거절 확인 버튼
- 영토 경계 파티클 토글
- SQLite/MySQL/PostgreSQL 저장소
- BlueMap 영토 마커 연동

## 명령어

- `/trm` - 영토 경계 파티클 켜기/끄기
- `/trm buy <이름>` - 현재 청크 구매
- `/trm remove` - 현재 청크 삭제 요청
- `/trm show` - 영토 청크 수와 파티클 토글
- `/trm list` - 영토 목록

## 빌드

이 모듈은 BlueMap 저장소의 API 모듈과 함께 빌드하도록 구성되어 있습니다. `build.gradle.kts`의 BlueMap API JAR 경로를 환경에 맞게 지정한 뒤 Java 21로 빌드하세요.
