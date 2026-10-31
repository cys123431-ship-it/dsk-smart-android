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
├─ data/
│  ├─ ai/
│  │  ├─ AiClient.kt             ← 인터페이스 (교체 지점)
│  │  ├─ OpenAiCompatibleClient.kt  실제 구현 (OkHttp, SSE 스트리밍)
│  │  ├─ ChatWireModels.kt       요청/응답 직렬화 모델
│  │  ├─ AiModels.kt             AiMessage, AiError, ProviderConfig
│  │  └─ Prompts.kt              시스템 프롬프트 / 추천 질문
│  ├─ chat/                      Room v2: 대화(모델·요약) + 메시지(토큰), 첨부 JSON, v1→v2 마이그레이션
│  ├─ attachment/                AttachmentStore, TextDecoding, DocumentImporter(docx/pptx/pdf)
│  ├─ backup/                    BackupService (.zip 백업·복원), BackupCodec
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

