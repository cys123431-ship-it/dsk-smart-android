# DSK 스마트 (DSKapp)

ChatGPT 스타일의 범용 AI 채팅 앱. 특정 기능 전용 앱이 아니라 **채팅으로 모든 것을 요청**하는 구조입니다.

- 문서 작성·편집, 번역, 사진 속 영어 해석, 이미지 글자 추출, 코딩까지 모두 채팅 안에서 처리
- 결과를 **docx / xlsx / pptx / pdf / md / txt** 로 내보내기 (전부 기기에서 생성)
- 스마트폰 + 태블릿 대응 (좁은 화면은 드로어, 840dp 이상은 좌우 2단)

---

## 1. AI 연결: 내 Base URL + API Key

앱은 **OpenAI 호환 API**를 사용합니다. 기본값은 River AI이며, 설정에서 언제든 바꿀 수 있습니다.

| 항목 | 기본값 |
|---|---|
| 서버 주소 (Base URL) | `https://api.river.ai/v1` |
| 인증 | `Authorization: Bearer <API 키>` |
| 엔드포인트 | `POST /chat/completions`, `GET /models` |
| 기본 모델 | `deepseek-ai/DeepSeek-V4.1-Flash` |

River AI 문서에서 확인한 모델 ID:

```
deepseek-ai/DeepSeek-V4.1-Flash
deepseek-ai/DeepSeek-V4.1-Flash-1017
moonshotai/Kimi-K3
zai-org/GLM-5.3-Flash
GLM-5.3
Qwen/Qwen3.8-27B-FP8
nvidia/NVIDIA-Nemotron-3.5-Super-VL-120B-A12B-NVFP4   (이미지 입력)
```

### 사용 방법
1. 앱 실행 → 우측 상단에서 드로어 열기 → **설정**
2. 서버 주소 / API 키 / 모델 입력
3. **모델 목록** 버튼으로 실제 사용 가능한 모델 확인 가능
4. **연결 테스트**로 URL·키·모델이 함께 맞는지 즉시 검증
5. **저장**

> 다른 OpenAI 호환 서버(vLLM, Ollama, OpenRouter 등)도 Base URL만 바꾸면 그대로 동작합니다.

---

## 2. 보안

- API 키는 **Android Keystore 의 AES-256-GCM 키로 암호화**해 저장 (`KeystoreCipher`). 키 자체는 Keystore 밖으로 나오지 않음
- 서버 주소·모델은 비밀이 아니므로 일반 SharedPreferences (`dsk_settings`) 에 저장
- 지원 중단된 EncryptedSharedPreferences(Tink 포함)를 쓰지 않아 앱이 가볍고 시작이 빠름
- 1.0.0 에서 올리면 이전 저장 방식이 정리되므로 **API 키를 한 번 다시 입력**해야 함
- `data_extraction_rules.xml` 에서 백업·기기이전 **제외**
- 키는 AI 서버 외 어디로도 전송하지 않음
- 키를 코드에 하드코딩하지 않음 (`ServiceLocator` → `SettingsRepository.providerConfig()`)

---

## 3. 주요 기능

### 채팅
- 스트리밍 응답(SSE `data:` 라인, `[DONE]` 종료) → 타이핑 효과
- 응답 중 **중지** 버튼 → HTTP 연결을 실제로 끊고, 그때까지 받은 글만 저장
- 대화를 보내는 중 다른 대화로 옮겨도 받은 부분까지는 저장됨
- 사진·파일은 **최근 4개 메시지에서만 다시 전송** (그 이전 것은 파일 이름만 알려 줌) → 데이터·토큰 절약
- 오류 안내 문구는 AI 에게 보내는 대화 기록과 내보내기에서 제외
- 첫 메시지로 대화 제목 자동 생성
- 대화 목록: 고정(pin), 삭제, 재선택
- 마크다운 렌더링: 제목/목록/인용/표/**코드블록 + 복사 버튼**
- **답변 다시 생성**: 마지막 답변 아래 ↻ 버튼 (오류 답변도 다시 시도 가능)
- **내 메시지 수정**: 메시지 아래 연필 버튼 → 고친 내용으로 그 뒤 대화를 지우고 새로 답변
- 메시지·답변 **복사** 버튼
- **대화별 모델**: 상단 제목 아래 모델 이름을 눌러 이 대화만 다른 모델로 (기본값은 설정의 모델)
- **맞춤 지시문**: 설정에 적은 요청("항상 존댓말로" 등)을 모든 대화에 적용
- **긴 대화 자동 요약**: 요약되지 않은 메시지가 30개를 넘으면 오래된 부분을 한 번 요약해 두고,
  이후에는 요약 + 최근 10개만 보냄 → 맥락은 유지하면서 토큰 절약 (요약 실패 시 최근 30개만 전송)

### 링크 읽기
- 보낸 메시지에 웹 주소가 있으면 앱이 페이지를 직접 가져와 본문만 뽑아 함께 전송 (최대 3개, 페이지당 2MB·2만 자)
- 첨부 칩에 "🔗 페이지 제목"으로 표시, 설정에서 끌 수 있음. EUC-KR 등 옛 한글 페이지도 자동 판별

### 웹 검색
- 최신 정보(뉴스·가격·일정·출시·환율 등)가 필요한 질문이면 AI 가 먼저 검색어를 정하고, 앱이 **DuckDuckGo** 로 검색 (API 키 불필요)
- 상위 결과 8개의 요약 + 앞쪽 3개 페이지 본문(각 3천 자)을 AI 에 넘겨 출처 링크와 함께 답변. 한 답변에 검색 최대 2번
- 답변 아래 "🔍 검색어" 칩을 누르면 AI 가 본 검색 결과를 확인. 다음 질문에서도 그 결과를 이어서 사용
- 예약 작업에도 적용 ("매일 아침 8시 IT 뉴스 요약" 같은 요청)
- 설정 → 링크 읽기 · 웹 검색에서 끌 수 있음. 검색한 질문은 토큰이 수천~2만 개 더 듦
- 동작 방식: 도구 호출(function calling) 대신 답변 속 `<search>검색어</search>` 표시를 앱이 알아보는 방식 → 어떤 OpenAI 호환 서버·모델에서도 동작, 스트리밍 유지
- 검색 엔진 추가(Brave·Tavily 등): `data/search/` 에 `WebSearchProvider` 구현 1개 + `SearchProviderType` 항목 + `ServiceLocator.webSearch` 분기 1줄

### 나만의 명령어
- 입력창 위 버튼("영어로 번역", "3줄 요약", "맞춤법 교정"…)을 누르면 요청 문구가 입력창에 들어감
- 설정에서 추가·수정·삭제·순서 변경. 명령 안에 `{입력}`을 넣으면 그 자리에 입력한 글이 들어감

### 답변 읽어주기
- 답변 아래 🔊 버튼 → 폰 내장 음성(TTS)으로 읽기, 다시 누르면 멈춤 (코드 블록은 건너뜀)

### 앱 아이콘 바로가기
- 아이콘 길게 누르기 → **새 채팅 / 사진으로 질문(카메라 바로 열림) / 음성으로 질문(받아쓰기 바로 시작)**

### 코드 색상 · 생각 과정 · 즐겨찾기
- 코드 블록에 언어별 색 (Kotlin·Java·JS/TS·Python·C/C++·C#·Go·Rust·Swift·SQL·Bash·JSON·XML 등, 다크 모드 색 별도)
- 추론 모델의 **생각 과정**은 답변 위 "💭 생각 과정 (N자)"로 접혀 표시 (`reasoning_content` / `<think>` 모두 지원, AI 에게 다시 보내지 않음)
- 답변 아래 ★ → 드로어 **즐겨찾기**에서 모아 보기, 누르면 그 대화로 이동

### 예약 작업
- 드로어 또는 설정 → **예약 작업**: 이름, 요청, 시각(한국 시간), 요일(매일/평일/주말/직접), 모델 선택
- 정한 시각에 AI 에 요청 → 결과를 **알림**으로 받고 "⏰ 이름" 대화에 저장 (요청에 링크가 있으면 읽어서 함께 보냄)
- 정확한 알람(`USE_EXACT_ALARM`) + 백그라운드 작업(JobScheduler, 추가 라이브러리 없음), 재부팅 후 자동 재등록
- 삼성 폰은 배터리 설정에서 DSK 스마트를 "제한 없음"으로 두면 시각이 정확함

### 앱 잠금
- 설정 → 앱 잠금: 앱을 열 때 지문·얼굴·화면 잠금으로 확인 (1분 넘게 다른 앱에 있다가 돌아오면 다시 확인)
- 켜 두면 최근 앱 화면 미리보기에도 대화가 보이지 않음 (Android 13+)

### 폰 조작 (스마트폰 유즈)
데스크톱의 컴퓨터 유즈처럼, AI 가 폰 화면을 읽고 다른 앱을 대신 조작합니다. Android 접근성 서비스 사용(추가 SDK 없음).

- **켜기**: 설정 → 폰 조작 → "접근성 설정 열기" → 'DSK 스마트 폰 조작' 켜기
  (Android 13+ 에서 막히면 앱 정보 → ⋮ → **제한된 설정 허용** 후 다시)
- **사용**: 채팅 입력창 + → **📱 폰 조작 모드** → "카카오톡 열어서 ○○ 대화 마지막 메시지 읽어 줘" 처럼 목표를 보냄
- 동작: 화면 읽기(글자·버튼 목록) → AI 가 행동 1개 결정(JSON) → 실행 → 반복. 결과와 진행 기록은 그 대화에 저장
- 단계별 허용 수준: **읽기만 / 이동까지(앱 열기·스크롤·뒤로) / 조작까지(누르기·입력)**, 최대 단계 수 설정
- **접근성 버튼**: 어느 화면에서든 누르면 지금 화면을 요약 (읽기 전용)
- 예약 작업에서 "📱 폰 조작으로 실행"을 켜면 무인 실행 (이동까지만, 화면이 켜져 있을 때)
- 안전장치
  - 화면 아래 떠 있는 **중지** 버튼, 단계마다 무엇을 하는지 표시
  - 보내기·결제·삭제·구매·설치·확인 같은 버튼, AI 가 위험하다고 표시한 동작은 **실행 전 확인** (무인 실행은 그 자리에서 멈춤)
  - 비밀번호 칸은 읽지도 입력하지도 않음, 은행·결제·증권·코인·인증 앱은 기본 차단 (허용 목록으로 예외)
  - 화면 속 글은 데이터로만 취급 (화면에 적힌 지시를 따르지 않도록 프롬프트에 명시)
  - DSK 앱 자신의 화면은 읽지 않음

### 오류 기록
- 앱이 죽거나 AI·첨부·링크 오류가 나면 기기에 기록 (API 키는 자동으로 가림, 최대 20만 자)
- 설정 → 오류 기록에서 보기·복사·보내기·지우기

### 토큰 사용량
- 답변마다 **입력·출력 토큰 수** 표시 (서버가 사용량을 알려 줄 때. `stream_options.include_usage` 요청,
  서버가 이 옵션을 거부하면 자동으로 빼고 다시 요청)
- 설정 → 사용량: **이번 달 누적 토큰** (제목 생성·요약 호출 포함), 단가를 입력하면 **예상 비용(USD)**

### 다른 앱에서 공유받기
- 브라우저·갤러리·파일 앱 등에서 **공유 → "DSK 스마트에 묻기"**
- 글(제목+링크 포함)은 입력창에, 사진·문서는 첨부로 들어간 **새 대화**가 열림 (사진은 한 번에 최대 6장)

### 대화 백업 · 복원
- 설정 → 백업: 대화 전체와 첨부 파일을 **.zip 하나**로 저장 (원하는 폴더·드라이브에 저장)
- 불러오기는 기존 대화를 지우지 않고 **추가**. API 키는 백업에 포함되지 않음

### 화면
- 테마: 시스템 / 밝게 / 어둡게 (상태 표시줄 아이콘 색도 함께 전환)
- 글자 크기: 85% ~ 140% (앱 전체 글자에 적용)

### 사진 · 첨부
- 카메라 촬영 또는 갤러리 선택
- **EXIF 회전 보정** (옆으로 누운 사진의 글자를 제대로 읽도록)
- 긴 변 1568px 로 축소 후 JPEG 82 품질 → 토큰·전송량 절감
- 텍스트 파일 첨부 (최대 12만 자). 필요한 만큼만 읽고, UTF-8 / UTF-16 / CP949(EUC-KR) 자동 판별
- **Word(.docx)·PowerPoint(.pptx)** 첨부: 기기에서 글자만 뽑아 전송 (최대 25MB, 표는 탭으로 구분)
- **PDF** 첨부: Android 15+ 는 PDF 글자를 직접 읽어 전송. 스캔 PDF 이거나 Android 14 이하면
  앞 4쪽을 이미지로 바꿔 전송 (이미지를 읽을 수 있는 모델 필요)
- 옛 형식(.doc/.ppt/.hwp)은 지원하지 않음
- 첨부 파일은 앱 전용 저장소에 복사되어 재시작 후에도 유지, **대화를 지우면 함께 삭제**
- 카메라 원본은 축소본을 만든 직후 삭제, 내보낸 파일 캐시는 앱 시작 시 1시간 지난 것 정리

### 음성 입력
입력창 오른쪽 마이크 버튼으로 말한 내용을 바로 글로 입력합니다.

- Android 기본 `SpeechRecognizer` 사용 (별도 SDK·과금 없음)
- **한국어(ko-KR)** 기본, 말하는 동안 중간 결과가 실시간으로 입력창에 반영
- 마이크 권한은 처음 누를 때만 요청 (거부 시 안내 후 계속 타이핑 가능)
- 화면을 벗어나면 인식기가 자동 정리됨
- 오류 메시지는 원인별로 구분 (권한·네트워크·무음성·인식 실패)
- 삼성·구글 음성 서비스가 있는 기기에서 동작하며, 없으면 안내만 표시

### 대화 검색
상단바 돋보기 또는 드로어의 **검색**으로 전체 기록을 찾습니다.

- **제목 + 메시지 본문 동시 검색** (LIKE 기반, 스키마 변경 없음, `%`·`_` 도 글자 그대로 검색)
- 입력 후 250ms 디바운스 → 타이핑 중 DB 부하 없음
- 결과를 누르면 해당 대화로 바로 이동
- 제목/내용 둘 다 일치해도 중복 없이 1건으로 표시

### 내보내기
마지막 AI 답변을 아래 형식으로 변환 후 공유 시트로 전달합니다. **전부 오프라인 생성**이며 외부 라이브러리를 쓰지 않습니다.

| 형식 | 구현 |
|---|---|
| `.md` | 원문 마크다운 |
| `.txt` | 서식 제거한 텍스트 |
| `.docx` | WordprocessingML 직접 생성 (제목 스타일, 목록, 표, 코드) |
| `.xlsx` | 표를 시트별로 분리, `내용` 시트에 본문 |
| `.pptx` | 제목마다 슬라이드 1장, 불릿 자동 배치 |
| `.pdf` | Android `PdfDocument` + `StaticLayout` (한글 줄바꿈 정확) |

---

## 4. 프로젝트 구조

```
app/src/main/java/com/dskapp/
├─ DskApp.kt                     Application (첫 실행 시 대화 1개 생성)
├─ MainActivity.kt               edge-to-edge + IME inset 처리
├─ di/ServiceLocator.kt          수동 DI (AI 클라이언트 교체 지점)
├─ agent/                       폰 조작: 접근성 서비스, 화면 읽기, 실행기, 안전 규칙, 판단 루프, 화면 위 제어판
├─ data/
│  ├─ ai/
│  │  ├─ AiClient.kt             ← 인터페이스 (교체 지점)
│  │  ├─ OpenAiCompatibleClient.kt  실제 구현 (OkHttp, SSE 스트리밍)
│  │  ├─ ChatWireModels.kt       요청/응답 직렬화 모델
│  │  ├─ AiModels.kt             AiMessage, AiError, ProviderConfig
│  │  └─ Prompts.kt              시스템 프롬프트 / 추천 질문
│  ├─ chat/                      Room v3: 대화(모델·요약) + 메시지(토큰·생각 과정·즐겨찾기), 마이그레이션 1→2→3
│  ├─ attachment/                AttachmentStore, TextDecoding, DocumentImporter(docx/pptx/pdf)
│  ├─ backup/                    BackupService (.zip 백업·복원), BackupCodec
│  ├─ schedule/                  예약 작업: 저장·시각 계산·알람·JobService·알림·실행기
│  ├─ search/                    웹 검색: 검색 요청 규칙(SearchProtocol), 엔진 인터페이스, DuckDuckGo, 결과 수집기
│  ├─ image/ImageProcessor.kt    축소 + EXIF 회전 + base64
│  ├─ export/                    MarkdownParser, Docx/Xlsx/Pptx/Pdf Writer, ExportService
│  └─ settings/                  SettingsRepository, KeystoreCipher (API 키 암호화)
└─ ui/
   ├─ DskAppNavHost.kt           Chat ↔ Settings ↔ Search, 열린 대화의 ViewModel 만 유지
   ├─ chat/                      ChatScreen(폰 드로어 / 넓은 화면 2단 공용), ChatViewModel, Drawer, ExportSheet
   ├─ components/                MessageBubble, MarkdownText, ChatComposer
   ├─ settings/                  SettingsScreen/ViewModel (연결·지시문·화면·사용량·백업)
   ├─ share/ShareInbox.kt        다른 앱의 공유 인텐트 → 새 대화로 전달
   └─ theme/                     Material 3 (동적 색상, 수동 다크 모드, 글자 크기)
```

### 나중에 중계 서버로 전환하려면
`AiClient` 구현체만 추가하고 `ServiceLocator.aiClient()` 의 바인딩을 바꾸면 됩니다.
ViewModel·UI 코드는 변경이 필요 없습니다.

---

## 5. 빌드

요구 사항: **JDK 17+**, Android SDK **Platform 36**, **Build-Tools 36.0.0**

```bash
# 폰에 설치할 릴리스 빌드 (R8 축소·최적화, 약 1.8MB)
gradlew.bat assembleRelease

# 단위 테스트
gradlew.bat testDebugUnitTest
```

결과물: `app/build/outputs/apk/release/app-release.apk`

디버그 빌드는 최적화가 꺼져 있어 Compose 화면이 버벅이고 메모리도 더 씁니다. 실제로 쓸 때는 릴리스 빌드를 설치하세요.

### 릴리스 서명
프로젝트 루트의 `keystore.properties` (git 제외) 를 읽습니다.

```properties
storeFile=C:/Users/user/.secrets/dskapp-release.jks
storePassword=...
keyAlias=dskapp
keyPassword=...
```

파일이 없으면 서명되지 않은 릴리스 APK 가 만들어집니다. **키스토어를 잃어버리면 같은 앱으로 업데이트할 수 없으니** 백업해 두세요.

### Android Studio
1. 이 폴더를 프로젝트로 열기
2. `local.properties` 의 `sdk.dir` 을 본인 SDK 경로로 수정
3. Gradle Sync 후 Run

### 버전
| | |
|---|---|
| AGP | 9.5.0 (내장 Kotlin 사용 — `kotlin.android` 플러그인 불필요) |
| Gradle | 9.8.2 |
| Kotlin | 2.4.21 |
| Compose BOM | 2025.09.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |

> 최신 AndroidX(2026.x)는 compileSdk 37을 요구하지만 SDK 37이 아직 공개 저장소에 없어,
> compileSdk 36과 호환되는 안정 버전으로 고정했습니다.

---

## 6. 1차 범위에 포함되지 않은 것

- **기존 파일 편집** (업로드한 docx/xlsx/pdf의 레이아웃을 유지하며 수정): 내용을 읽어 새 파일로 생성하는 방식만 지원

## 7. 설계 메모

### 중계 서버를 쓰지 않는 이유와 대가
이 앱은 API 키를 기기에 암호화 저장하고 AI 서버로 직접 호출합니다. 서버 운영 비용과
지연이 없고, 키가 제3자를 거치지 않는다는 장점이 있습니다.

키는 APK 안에 들어 있지 않고 각 사용자가 설정에서 직접 입력합니다. 따라서 APK 를
공유해도 내 키가 함께 나가지는 않습니다. 다만 여러 사람에게 **내 키로** 서비스하려면
기기마다 키를 나눠 줘야 하므로, 그때는
`data/ai/AiClient.kt` 인터페이스의 구현체를 중계 서버용으로 하나 추가하고
`di/ServiceLocator.kt` 의 `aiClient(context)` 반환값만 바꾸면 됩니다.
ViewModel과 UI는 손대지 않아도 됩니다.

### 검색을 FTS 대신 LIKE로 한 이유
개인 대화 기록은 수천 건 수준이고, FTS는 별도 가상 테이블과 데이터 재빌드·마이그레이션이
필요합니다. `title`/`content` LIKE 조회로 충분한 속도를 얻으면서 스키마를 그대로 유지했습니다.

