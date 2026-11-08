# DSK Remote — PC 쪽 goose 서버 실행기

스마트폰 앱(**DSK 스마트**)에서 이 PC의 goose 를 그대로 쓰기 위한 실행기입니다.
goose 데스크톱 앱이 꺼져도 서버는 계속 살아 있게 만드는 것이 목적입니다.

> 이 폴더는 앱 리포지터리의 `server/` 입니다. 앱과 별개로 이 PC 에서만 실행됩니다.

## 파일

| 파일 | 역할 |
|---|---|
| `DSK-Remote.bat` | 실행기. 더블클릭 → 주소/Secret 표시 → 서버 시작 |
| `proxy.ps1` | **폰이 접속하는 문.** `0.0.0.0:3284` 로 받아 goose(`127.0.0.1:3285`)로 넘깁니다 |
| `start-hidden.vbs` | **창 없이** 서버만 켜기. 로그인 자동 시작이 이 파일을 씁니다 |
| `install-autostart.ps1` | 시작프로그램에 창 없는 자동 시작을 등록(다시 실행해도 안전) |
| `Stop-DSK-Remote.bat` | 서버 끄기 |
| `settings.txt.example` | 설정 템플릿. 복사해서 `settings.txt` 로 쓰세요 |
| `list-addrs.ps1` | PC 의 LAN 주소를 모으는 보조 스크립트 (자동 호출) |
| `get-tailscale-ip.ps1` | Tailscale 주소를 읽는 보조 스크립트 (자동 호출) |
| `workspace` | goose 가 작업하는 폴더 (자동 생성) |

## 처음 준비

1. 이 폴더에서 `settings.txt.example` 을 `settings.txt` 로 복사합니다.
2. 세 줄을 채웁니다.

   ```
   SECRET=아무도-모를-길고-무작위인-문자열
   PORT=3284
   WORKDIR=
   GOOSE=C:\...\goose.exe
   ```

   - `SECRET` — 폰 앱에 입력할 비밀번호. 길고 무작위로 만들수록 좋습니다.
   - `WORKDIR` — 비워두면 이 폴더 안의 `workspace` 를 씁니다.
   - `GOOSE` — goose 실행 파일의 전체 경로.

3. `goose.exe serve` 가 동작하는 goose(v1.57 이상)가 필요합니다.

> `settings.txt` 는 `.gitignore` 에 들어 있어 리포지터리에 올라가지 않습니다.
> **Secret Key 는 절대 공개하지 마세요.** 폰과 PC 만 알면 됩니다.

## 사용법

1. `DSK-Remote.bat` 을 더블클릭합니다.
2. 화면에 나오는 **주소**와 **Secret Key** 를 폰 앱에 입력합니다.
   (앱 → 설정 → **PC goose 원격**)
   앱이 `PC 자동 찾기` 를 지원하므로, 같은 Wi-Fi 나 같은 Tailscale 계정이면
   주소를 직접 넣지 않아도 됩니다. Secret Key 만 입력하면 됩니다.
3. 시작 방식을 고릅니다.
   - `1` **백그라운드** — 이 창을 닫아도, goose 데스크톱 앱을 꺼도 서버는 계속 돕니다. **평소엔 이걸 쓰세요.**
   - `2` 이 창에서 실행 — 창을 닫으면 서버도 꺼집니다.
   - `3` 서버 끄기.
4. 끌 때는 `Stop-DSK-Remote.bat` 을 더블클릭합니다.

## PC 켤 때 자동 시작 (창 없이)

Windows 에 로그인하면 서버가 **아무 창 없이** 저절로 켜지게 하려면:

```
powershell -ExecutionPolicy Bypass -File install-autostart.ps1
```

시작프로그램 폴더에 `DSK Remote server.lnk` 가 생기고, 이 바로가기는
`wscript.exe start-hidden.vbs` 를 실행합니다. `wscript.exe` 는 콘솔 창을 만들지
않는 프로그램이라 **검은 창이 뜨지 않습니다.**
(예전 방식은 `.bat` 을 실행해서, 숨김 옵션을 줘도 검은 창이 잠깐 떴습니다.)

이미 서버가 떠 있으면 아무 것도 하지 않으므로 두 번 실행해도 안전합니다.
끄려면 `Stop-DSK-Remote.bat`, 창 없이 다시 켜려면 `start-hidden.vbs` 더블클릭.

## 폰에 입력할 주소

실행하면 이렇게 보입니다. (주소는 실제 값이 표시됩니다)

```
    Wi-Fi     : 192.168.0.12:3284
    Tailscale : 100.x.y.z:3284
    Emulator  : 127.0.0.1:3284
```

| 상황 | 입력할 주소 |
|---|---|
| 집 안, 같은 Wi-Fi | `Wi-Fi` 줄의 주소 |
| 집 밖 (데이터/LTE) | `Tailscale` 줄의 주소 |
| PC 안의 에뮬레이터 | `Emulator` 주소 |

앱 입력란에는 `192.168.0.12:3284` 처럼 **`:3284` 까지** 넣으면 됩니다.
(`ws://` 는 앱이 자동으로 붙입니다.)

## Tailscale (집 밖에서 쓸 때만)

집 밖에서 접속하려면 폰과 PC 가 같은 Tailscale 계정으로 로그인되어 있어야 합니다.

- PC: Tailscale 설치 후 로그인 (이미 되어 있으면 `Tailscale : 100.x.y.z:3284` 가 표시됩니다)
- 폰: Play 스토어에서 Tailscale 설치 후 **같은 계정**으로 로그인
- 미로그인 상태면 실행기에 `Tailscale : installed but not logged in` 이 뜹니다.

Tailscale 이 없어도 집 안 Wi-Fi 에서는 그대로 쓸 수 있습니다.

## 동작 방식 (참고)

서버는 **두 개의 프로세스**로 뜹니다. 실행기 창과 무관하게 둘 다 살아 있습니다.

| 프로세스 | 듣는 주소 | 역할 |
|---|---|---|
| `goose serve` | `127.0.0.1:3285` (이 PC 안에서만) | 실제 대화·파일 작업을 하는 본체 |
| `proxy.ps1` | `0.0.0.0:3284` (밖에서도 접속 가능) | 들어온 접속을 위의 goose 로 그대로 넘김 |

폰은 **3284** 로 접속하고, 3285 는 밖에 열려 있지 않습니다.

> **왜 이렇게 했나:** goose 의 `serve` 는 127.0.0.1(자기 PC) 말고 다른 곳에서 오는
> WebSocket 연결을 **403 Forbidden** 으로 거부합니다. 그래서 goose 는 루프백에만
> 두고, 앞에 TCP 프록시를 세워 폰의 접속을 그대로 안쪽으로 흘려보냅니다.
> 프록시는 데이터를 그대로 복사만 하므로 Secret Key 검사도 goose 가 그대로 합니다.

> **Origin 도 열어 둡니다 (1.9.3).** goose 는 Secret Key 를 보기 **전에** `Origin` 헤더를
> 검사합니다. 폰은 `http://<PC 주소>:3284` 를 Origin 으로 보내므로, 그 주소를 모르면
> 키가 맞아도 403 이 납니다. `DSK-Remote.bat` 이 이 PC 의 모든 IPv4 주소(Wi-Fi·이더넷·
> Tailscale)를 찾아 `--allowed-origin` 으로 넘깁니다. IP 가 바뀌어도 실행할 때마다
> 다시 읽으므로 따로 고칠 것은 없습니다.
>
> 참고로 `--allowed-origin` 을 주면 goose 의 기본 루프백 Origin 목록이 **대체**됩니다.
> 그래서 스크립트가 `127.0.0.1` 과 `localhost` 도 함께 넣습니다. 빼먹으면 PC 자신의
> 브라우저에서만 403 이 납니다.

- 폰과는 `ws://주소:3284/acp` WebSocket(ACP, JSON-RPC 2.0) 로 통신합니다.
- `X-Secret-Key` 헤더(앱 기준)가 맞지 않으면 거부됩니다.
- 설정은 `settings.txt` 에 저장되므로 두 번째 실행부터는 묻지 않습니다.

## 문제가 생기면

- **포트를 이미 쓰고 있다고 나올 때** — 이미 서버가 떠 있다는 뜻입니다. 폰에서 바로 접속해도 됩니다. 끄려면 `Stop-DSK-Remote.bat`.
- **Wi-Fi 주소가 `not found`** — 네트워크(이더넷/Wi-Fi) 연결을 확인하세요.
- **폰에서 연결이 안 될 때**
  1. `Stop-DSK-Remote.bat` → `DSK-Remote.bat` 다시 실행
  2. Secret Key 를 폰에 다시 입력
  3. 집 밖이면 PC·폰 모두 Tailscale 이 켜져 있는지 확인
  4. Windows 방화벽이 3284 포트를 막고 있지 않은지 확인
  5. `server.log` 를 열어 `front door on 0.0.0.0:3284 -> 127.0.0.1:3285` 가 있는지 확인
     (이 줄이 없으면 프록시가 안 뜬 것이므로 서버를 다시 켜세요)
- **403 Forbidden 이 보일 때**
  1. 폰은 반드시 3284(프록시) 로 접속해야 합니다. 3285 를 입력하면 안 됩니다.
  2. 그래도 403 이면 대개 **Origin** 문제입니다. `DSK-Remote.bat` 이 예전 것(1.9.3 이전)이면
     새 것으로 바꾼 뒤 `Stop-DSK-Remote.bat` → `DSK-Remote.bat` 순서로 다시 켜세요.
- **폰에서 PC 를 못 찾을 때** — 폰의 Tailscale 앱에서 **MagicDNS** 가 켜져 있는지 보세요
  (Tailscale 앱 → Settings). 켜져 있으면 앱이 PC 이름(`내PC.xxxx.ts.net`)을 곧바로 물어봅니다.
