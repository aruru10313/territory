# Territory (NeoForge 1.21.1)

마인크래프트 1.21.1 (NeoForge 21.1.249) 환경을 위한 **청크 단위 영토 관리 및 BlueMap 3D 연동 모드**입니다.

---

## 🌟 주요 기능

### 1. 청크 단위 영토 점유 및 확장
- 플레이어 또는 관리자가 원하는 영토 이름을 지정하여 현재 청크를 점유(`claim`)할 수 있습니다.
- 인접 청크를 구매하여 자신의 영토를 지속적으로 확장할 수 있습니다.
- 청크 삭제 요청 시 오작동 방지를 위한 확인 절차를 지원합니다.

### 2. BlueMap 실시간 3D 영토 경계 렌더링
- **BlueMap API 연동**: 서버에 BlueMap이 설치되어 있을 경우 자동으로 `territories` ("영토") 마커 세트를 생성합니다.
- **3D 입체 영역 (`ExtrudeMarker`)**:
  - 각 청크의 4개 꼭짓점(`(x*16, z*16) ~ ((x+1)*16, (z+1)*16)`)을 기반으로 높이 `Y=64 ~ 256`에 이르는 반투명 3D 영역을 생성합니다.
  - 영토 이름의 해시를 기반으로 고유한 색상(채움 투명도 0.35, 테두리 투명도 0.9)을 자동 부여합니다.
  - 마커 클릭 시 영토 이름, 소유자, 청크 좌표를 상세 팝업으로 표시합니다.
- **월드 차원 매핑**:
  - 기본 저장 폴더명 `world`를 `minecraft:overworld`로 자동 매핑하여 오버월드 지형 위에 완벽하게 표시됩니다.
  - 네더(`minecraft:the_nether`), 엔드(`minecraft:the_end`) 차원도 지원합니다.

### 3. 인게임 파티클 경계 표시
- `/trm` 또는 `/trm show`를 통해 자신이 속한 영토의 경계선에 파티클을 켜고 끌 수 있습니다.

### 4. 클라우드 데이터베이스 및 비동기 동기화 지원
- **비동기 백그라운드 동기화 (서버 최적화)**:
  - 영토 구매/삭제 시 로컬 SQLite에 0.1ms 만에 즉시 기록하고, 원격 클라우드 DB 연동은 별도의 백그라운드 스레드에서 비동기 처리하여 **마인크래프트 서버 틱(TPS) 지연이 전혀 없습니다.**
- **Supabase (클라우드 PostgreSQL)**:
  - Supabase 연결 문자열(`jdbc:postgresql://db.<REF>.supabase.co:5432/postgres?sslmode=require`) 또는 Pooler(6543 포트) 지원
  - SSL 자동 활성화(`sslmode=require`)
- **MongoDB Atlas (클라우드 NoSQL)**:
  - MongoDB Atlas Data API(REST)를 통한 클라우드 컬렉션 동기화 지원
  - `endpoint`, `api-key`, `cluster`, `database`, `collection` 설정
- **기본 로컬 SQLite & MySQL & PostgreSQL** 지원

---

## 📜 명령어 안내

| 명령어 | 권한 | 설명 |
| :--- | :--- | :--- |
| `/trm` | 모든 유저 | 현재 청크의 영토 경계 파티클 토글 (ON/OFF) |
| `/trm show` | 모든 유저 | 현재 영토 정보, 총 청크 수 확인 및 파티클 토글 |
| `/trm list` | 모든 유저 | 서버에 등록된 전체 영토 목록 확인 |
| `/trm buy <영토이름>` | 관리자/유저 | 현재 서 있는 청크를 해당 영토로 구매/점유 |
| `/trm remove` | 소유자/관리자 | 현재 서 있는 청크 점유 해제 요청 |
| `/trm test <영토이름>` | OP / 관리자 | **[테스트 전용]** `(0, 0)` 주변 2x2 청크(32x32 블록)를 즉시 영토로 등록하고 BlueMap에 3D 마커를 생성 |
| `/trm admin claim <소유자> <영토이름> <청크X> <청크Z>` | OP / 관리자 | 특정 좌표의 청크를 지정된 소유자의 영토로 강제 등록 |
| `/trm reload` | OP / 관리자 | `config/territory.json` 설정 및 BlueMap 마커 전체 리로드 |

---

## ⚙️ 설정 파일 (`config/territory.json`)

```json
{
  "database": {
    "type": "SQLITE",
    "sqlite": {
      "path": "config/territory/territory.db"
    },
    "mysql": {
      "host": "localhost",
      "port": 3306,
      "database": "minecraft",
      "user": "root",
      "password": ""
    }
  },
  "bluemap": {
    "enabled": true,
    "markerSetLabel": "영토",
    "minY": 64,
    "maxY": 256,
    "fillOpacity": 0.35,
    "borderOpacity": 0.9
  }
}
```

---

## 🛠️ 빌드 방법

### 1. 요구사항
- JDK 21 (Java 21)
- Gradle 8.8 (gradlew 래퍼 포함)

### 2. 컴파일
```bash
./gradlew build
```

빌드 완료 시 `build/libs/territory-neoforge-1.0.0.jar`가 생성됩니다.

---

## 🔗 호환성
- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.249+
- **BlueMap**: 5.x+ (NeoForge 1.21.1)
