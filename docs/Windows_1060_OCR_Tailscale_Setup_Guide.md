# Windows 1060 OCR + Tailscale 설정 가이드

이 문서는 아무것도 설치되지 않은 Windows 1060 PC를 Calen 가계부 OCR 분석 서버로 준비하는 절차입니다.

목표 구조:

```text
사용자 브라우저
  -> OCI frontend
  -> OCI backend
  -> Tailscale 사설망
  -> Windows 1060 PC OCR 서버:8765
```

중요 원칙:

- 공유기 포트포워딩을 열지 않습니다.
- OCR 서버는 브라우저에 직접 공개하지 않습니다.
- OCI 백엔드만 Windows 1060 PC의 Tailscale IP로 OCR 서버를 호출합니다.
- 영수증 이미지, OCR 로그, API 키, 실제 운영 IP는 Git에 올리지 않습니다.

## 1. 준비물

- Windows 10 22H2 이상 또는 Windows 11
- NVIDIA GTX 1060 6GB
- GitHub 저장소 접근 권한
- Tailscale 계정
- OCI 서버 SSH 접속 권한
- Calen OCR 기능 코드가 GitHub에 push되어 있어야 합니다. 아직 push하지 않았다면 Windows PC에 코드를 받을 수 없습니다.

## 2. Windows 기본 설치

PowerShell을 관리자 권한으로 열고 설치합니다.

`winget`이 있으면 다음 명령을 사용할 수 있습니다.

```powershell
winget install --id Git.Git -e
winget install --id Python.Python.3.11 -e
winget install --id Tailscale.Tailscale -e
winget install --id Ollama.Ollama -e
```

`winget`이 없거나 실패하면 공식 설치 파일로 설치합니다.

- Tailscale Windows: https://tailscale.com/docs/install/windows
- Ollama Windows: https://docs.ollama.com/windows
- Python Windows: https://www.python.org/downloads/windows/
- Git for Windows: https://git-scm.com/download/win
- NVIDIA Driver: https://www.nvidia.com/Download/index.aspx

설치 후 새 PowerShell을 열고 확인합니다.

```powershell
git --version
python --version
tailscale version
ollama --version
nvidia-smi
```

`nvidia-smi`가 안 나오면 NVIDIA 드라이버가 제대로 설치되지 않은 상태입니다. Ollama는 공식 문서 기준 NVIDIA 452.39 이상 드라이버가 필요합니다.

## 3. Tailscale 로그인

Windows에서 Tailscale 앱을 실행하고 로그인합니다.

PowerShell에서 Windows 1060 PC의 Tailscale IP를 확인합니다.

```powershell
tailscale status
tailscale ip -4
```

출력된 `100.x.x.x` 값을 기록합니다.

```text
WINDOWS_1060_TAILSCALE_IP=100.x.x.x
```

## 4. Calen OCR 코드 받기

작업 폴더를 만들고 저장소를 clone합니다.

```powershell
New-Item -ItemType Directory -Force C:\calen
cd C:\calen
git clone https://github.com/chefbeom/TravelLedger.git
cd C:\calen\TravelLedger\PaddleOCR
```

이미 clone한 적이 있으면 최신 코드를 받습니다.

```powershell
cd C:\calen\TravelLedger
git pull origin main
cd C:\calen\TravelLedger\PaddleOCR
```

## 5. Python OCR 환경 만들기

```powershell
cd C:\calen\TravelLedger\PaddleOCR
python -m venv ocr_env
.\ocr_env\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
```

권장값:

- OCR은 우선 CPU로 실행합니다.
- GTX 1060 GPU는 Gemma/Ollama 쪽에 사용하는 것이 더 안정적입니다.
- PaddleOCR GPU 세팅은 CUDA/Paddle 버전 궁합이 까다로우므로 v1에서는 피합니다.

## 6. Ollama + Gemma 준비

Ollama 설치 후 Gemma 2B 모델을 받습니다.

```powershell
ollama pull gemma2:2b
```

동작 확인:

```powershell
ollama run gemma2:2b
```

프롬프트가 뜨면 짧게 입력해 보고 종료합니다.

```text
/bye
```

Ollama API는 기본적으로 Windows PC 내부의 `http://127.0.0.1:11434`에서 동작합니다.

## 7. OCR API 키 만들기

PowerShell에서 긴 랜덤 키를 생성합니다.

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

출력된 값을 복사해서 아래 파일에 저장합니다.

```powershell
notepad C:\calen\TravelLedger\PaddleOCR\ocr.env.ps1
```

내용 예시:

```powershell
$env:OCR_API_KEY="여기에_생성한_긴_랜덤키"
$env:OCR_HOST="0.0.0.0"
$env:OCR_PORT="8765"
$env:OCR_DEVICE="cpu"
$env:OCR_CPU_THREADS="4"
$env:OCR_MAX_UPLOAD_BYTES="10485760"

$env:LLM_PROVIDER="ollama"
$env:LLM_BASE_URL="http://127.0.0.1:11434"
$env:LLM_MODEL="gemma2:2b"
$env:LLM_TIMEOUT_SECONDS="45"
```

주의:

- `ocr.env.ps1`은 Git에 올리지 않습니다.
- `OCR_API_KEY`는 OCI 백엔드 `.env.oci.app`에도 같은 값으로 넣어야 합니다.
- i5-4690 환경에서는 `OCR_CPU_THREADS=4`부터 시작하고, 느리면 6 정도까지 올려봅니다.

## 8. OCR 서버 수동 실행

```powershell
cd C:\calen\TravelLedger\PaddleOCR
.\ocr_env\Scripts\Activate.ps1
. .\ocr.env.ps1
python .\ocr_service.py
```

다른 PowerShell 창에서 로컬 상태를 확인합니다.

```powershell
curl.exe http://127.0.0.1:8765/health
```

정상 예시:

```json
{"ok":true,"ocrLoaded":false,"llmProvider":"ollama"}
```

이미지 분석 테스트:

```powershell
curl.exe -X POST http://127.0.0.1:8765/analyze `
  -H "X-OCR-API-Key: 여기에_생성한_긴_랜덤키" `
  -F "file=@C:\path\to\receipt.jpg"
```

처음 분석은 PaddleOCR 모델 로딩 때문에 오래 걸릴 수 있습니다.

## 9. Windows 방화벽 열기

OCI 서버도 Tailscale에 연결한 뒤 OCI의 Tailscale IP를 확인해야 합니다. 아래 명령에서 `OCI_TAILSCALE_IP`를 실제 값으로 바꿉니다.

관리자 PowerShell:

```powershell
New-NetFirewallRule `
  -DisplayName "Calen OCR from OCI Tailscale" `
  -Direction Inbound `
  -Action Allow `
  -Protocol TCP `
  -LocalPort 8765 `
  -RemoteAddress "OCI_TAILSCALE_IP"
```

확인:

```powershell
Get-NetFirewallRule -DisplayName "Calen OCR from OCI Tailscale"
```

공유기 포트포워딩은 설정하지 않습니다.

## 10. OCI 서버에 Tailscale 설치

OCI 서버에서 실행합니다.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

출력되는 로그인 URL로 인증합니다.

OCI Tailscale IP 확인:

```bash
tailscale status
tailscale ip -4
```

이 값을 Windows 방화벽 규칙의 `OCI_TAILSCALE_IP`로 사용합니다.

## 11. OCI에서 Windows OCR 서버 연결 테스트

OCI 서버에서 Windows 1060 PC의 Tailscale IP로 확인합니다.

```bash
curl http://WINDOWS_1060_TAILSCALE_IP:8765/health
```

이미지 분석 테스트:

```bash
curl -X POST http://WINDOWS_1060_TAILSCALE_IP:8765/analyze \
  -H "X-OCR-API-Key: 여기에_생성한_긴_랜덤키" \
  -F "file=@receipt.jpg"
```

여기까지 성공하면 OCI 서버와 Windows OCR 서버 사이의 네트워크는 정상입니다.

## 12. OCI 백엔드 설정

OCI 앱 서버의 `.env.oci.app`에 추가합니다.

```env
LEDGER_OCR_ENABLED=true
LEDGER_OCR_BASE_URL=http://WINDOWS_1060_TAILSCALE_IP:8765
LEDGER_OCR_API_KEY=여기에_생성한_긴_랜덤키
LEDGER_OCR_CONNECT_TIMEOUT=3s
LEDGER_OCR_READ_TIMEOUT=90s
LEDGER_OCR_MAX_FILE_SIZE=10MB
```

백엔드/프론트 재배포:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml config --quiet
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml up -d --build backend frontend
```

백엔드 컨테이너에서 OCR 서버가 보이는지 확인합니다.

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml exec backend \
  curl -fsS http://WINDOWS_1060_TAILSCALE_IP:8765/health
```

이 명령이 성공하면 Calen 백엔드도 OCR 서버에 접근할 수 있습니다.

## 13. Windows OCR 서버 자동 실행

간단한 방식은 Windows 로그인 시 PowerShell 스크립트를 실행하는 것입니다.

```powershell
notepad C:\calen\start-calen-ocr.ps1
```

내용:

```powershell
Set-Location C:\calen\TravelLedger\PaddleOCR
. .\ocr.env.ps1
.\ocr_env\Scripts\python.exe .\ocr_service.py
```

작업 스케줄러 등록:

```powershell
$action = New-ScheduledTaskAction `
  -Execute "powershell.exe" `
  -Argument "-ExecutionPolicy Bypass -File C:\calen\start-calen-ocr.ps1"

$trigger = New-ScheduledTaskTrigger -AtLogOn

Register-ScheduledTask `
  -TaskName "Calen OCR Service" `
  -Action $action `
  -Trigger $trigger `
  -Description "Start Calen OCR analysis service at logon"
```

수동 실행 테스트:

```powershell
Start-ScheduledTask -TaskName "Calen OCR Service"
```

중단:

```powershell
Stop-ScheduledTask -TaskName "Calen OCR Service"
```

항상 무인 실행이 필요하면 NSSM 같은 Windows 서비스 래퍼로 `start-calen-ocr.ps1` 또는 Python 실행 파일을 서비스화합니다.

## 14. 장애 확인표

### OCI에서 `/health` 연결 실패

확인할 것:

- Windows OCR 서버가 켜져 있는지
- Windows OCR 서버 실행 시 `OCR_HOST=0.0.0.0`인지
- Windows와 OCI가 같은 Tailscale 계정에 로그인되어 있는지
- `tailscale status`에서 서로 보이는지
- Windows 방화벽의 `RemoteAddress`가 OCI Tailscale IP인지

### `/analyze`가 401

확인할 것:

- Windows `ocr.env.ps1`의 `OCR_API_KEY`
- OCI `.env.oci.app`의 `LEDGER_OCR_API_KEY`
- 요청 헤더 `X-OCR-API-Key`

세 값이 완전히 같아야 합니다.

### `/analyze`가 413

이미지 크기가 `OCR_MAX_UPLOAD_BYTES` 또는 `LEDGER_OCR_MAX_FILE_SIZE`보다 큽니다. 우선 이미지를 줄이는 것이 좋습니다.

### OCR은 되는데 분류/결제수단이 안 맞음

백엔드는 사용자의 기존 결제수단/카테고리 이름과 OCR/Gemma 결과가 매칭될 때만 ID를 채웁니다. 매칭이 안 되면 프론트에서 직접 선택해야 합니다.

### Gemma 응답이 느림

- `gemma2:2b`가 1060 6GB에서 느릴 수 있습니다.
- OCR은 CPU, Gemma는 Ollama GPU 사용을 기본으로 둡니다.
- `LEDGER_OCR_READ_TIMEOUT`과 `LLM_TIMEOUT_SECONDS`를 90초 정도로 늘릴 수 있습니다.

## 15. 운영 체크리스트

- [ ] Windows 1060 PC Tailscale 로그인 완료
- [ ] OCI 서버 Tailscale 로그인 완료
- [ ] Windows Tailscale IP 기록
- [ ] OCI Tailscale IP 기록
- [ ] Windows 방화벽에서 OCI Tailscale IP만 8765 허용
- [ ] 공유기 포트포워딩 미사용
- [ ] OCR API 키 생성 및 Windows/OCI 양쪽에 동일하게 설정
- [ ] `curl http://127.0.0.1:8765/health` 성공
- [ ] OCI에서 `curl http://WINDOWS_1060_TAILSCALE_IP:8765/health` 성공
- [ ] 백엔드 컨테이너에서 OCR `/health` 성공
- [ ] Calen 가계부 빠른 입력에서 영수증 자동입력 테스트 성공
