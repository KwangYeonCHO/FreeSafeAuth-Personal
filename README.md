# FreeSafeAuth Personal

FreeSafeAuth Personal is a local, ad-free Android TOTP authenticator for personal use. It generates 2FA verification codes on-device and stores account secrets locally with Android Keystore-backed AES-GCM encryption.

Languages: [English](#english) | [中文](#中文) | [한국어](#한국어)

## English

### Features

- Local TOTP code generation
- Manual account setup with Base32 secret validation
- QR code import for `otpauth://totp/...` links
- 30-second countdown and automatic refresh
- Copy verification codes
- Search accounts by service or account name
- Edit and delete accounts with confirmation
- Local Room database storage
- Android Keystore + AES-GCM encrypted secrets
- Biometric unlock
- Auto-lock after backgrounding
- Optional clipboard clearing 30 seconds after copy
- Encrypted backup export/import
- Light and dark modes
- App language options: follow system, Chinese, English, Japanese, Korean
- No ads, no analytics, no login, no server

### Privacy And Permissions

The app is designed to work offline. It does not request network permissions.

Requested permissions:

- `CAMERA`: only for scanning TOTP QR codes
- `USE_BIOMETRIC`: only for optional biometric unlock
- `READ_EXTERNAL_STORAGE`: only for importing backup files on older Android versions
- `WRITE_EXTERNAL_STORAGE`: only for exporting backup files on older Android versions
- `MANAGE_EXTERNAL_STORAGE`: only for the local backup file picker fallback on Android 11+

The merged debug manifest has been checked to ensure it does not include:

- `INTERNET`
- `ACCESS_NETWORK_STATE`

### Security Notes

- Secret keys are never stored in plain text.
- Database secrets are encrypted using Android Keystore-protected AES-GCM.
- Backup files are encrypted using `PBKDF2WithHmacSHA256 + AES-GCM`.
- Backup export writes an encrypted JSON file to the local Downloads folder.

### Build

Requirements:

- Android SDK
- JDK 17 available from `PATH` or `JAVA_HOME`
- Android Studio or the included Gradle Wrapper

Build a debug APK:

```bash
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Project Status

This is a first public MVP. It is suitable for testing and personal evaluation. For production distribution, configure a proper release signing key and build a signed release APK or AAB.

## 中文

FreeSafeAuth Personal 是一款面向个人使用的 Android 本地 TOTP 验证码工具。它没有广告、没有统计 SDK、无需登录、无需服务器，所有验证码密钥都只保存在本机，并使用 Android Keystore 保护的 AES-GCM 加密保存。

### 功能

- 本地生成 TOTP 动态验证码
- 支持手动输入 Secret Key 添加账号
- 支持扫描 `otpauth://totp/...` 二维码添加账号
- 30 秒倒计时和自动刷新
- 点击复制验证码
- 按服务名称或账号名称搜索
- 编辑账号和删除账号，删除前二次确认
- 使用 Room 本地数据库保存数据
- Secret Key 使用 Android Keystore + AES-GCM 加密保存
- 支持生物识别解锁
- 支持进入后台后自动锁定
- 可选复制后 30 秒自动清空剪贴板
- 支持加密备份导出/导入
- 支持浅色/深色模式
- 支持跟随系统语言，也可选择中文、英文、日文、韩文
- 无广告、无统计、无登录、无服务器

### 隐私与权限

本应用按离线工具设计，不申请网络权限。

当前权限：

- `CAMERA`：仅用于扫描 TOTP 二维码
- `USE_BIOMETRIC`：仅用于可选的生物识别解锁
- `READ_EXTERNAL_STORAGE`：仅用于旧版 Android 导入备份文件
- `WRITE_EXTERNAL_STORAGE`：仅用于旧版 Android 导出备份文件
- `MANAGE_EXTERNAL_STORAGE`：仅用于 Android 11+ 的本地备份文件选择兜底

已检查合并后的 Debug Manifest，确认不包含：

- `INTERNET`
- `ACCESS_NETWORK_STATE`

### 安全说明

- Secret Key 不会明文保存。
- 数据库中的密钥使用 Android Keystore 保护的 AES-GCM 加密。
- 备份文件使用 `PBKDF2WithHmacSHA256 + AES-GCM` 加密。
- 导出备份会将加密 JSON 文件保存到本机下载目录。

### 构建

依赖环境：

- Android SDK
- JDK 17，并已配置到 `PATH` 或 `JAVA_HOME`
- Android Studio 或项目内置 Gradle Wrapper

构建 Debug APK：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 项目状态

当前版本是公开 MVP，适合测试和个人评估。如果要正式分发，请配置正式签名密钥，并构建签名 Release APK 或 AAB。

## 한국어

FreeSafeAuth Personal은 개인 사용을 위한 Android 로컬 TOTP 인증 앱입니다. 광고, 통계 SDK, 로그인, 서버가 없으며 모든 인증 키는 기기에만 저장됩니다. 저장된 Secret Key는 Android Keystore로 보호되는 AES-GCM 방식으로 암호화됩니다.

### 기능

- 로컬 TOTP 인증 코드 생성
- Base32 Secret Key를 직접 입력해 계정 추가
- `otpauth://totp/...` QR 코드 스캔으로 계정 추가
- 30초 카운트다운 및 자동 갱신
- 인증 코드 복사
- 서비스 이름 또는 계정 이름으로 검색
- 계정 편집 및 삭제 확인
- Room 로컬 데이터베이스 저장
- Android Keystore + AES-GCM으로 Secret Key 암호화 저장
- 생체 인식 잠금 해제
- 백그라운드 전환 후 자동 잠금
- 복사 후 30초 뒤 클립보드 자동 삭제 옵션
- 암호화 백업 내보내기/가져오기
- 라이트/다크 모드
- 시스템 언어 따르기 또는 중국어, 영어, 일본어, 한국어 선택
- 광고 없음, 통계 없음, 로그인 없음, 서버 없음

### 개인정보 및 권한

이 앱은 오프라인 도구로 설계되었으며 네트워크 권한을 요청하지 않습니다.

요청 권한:

- `CAMERA`: TOTP QR 코드 스캔에만 사용
- `USE_BIOMETRIC`: 선택적 생체 인식 잠금 해제에만 사용
- `READ_EXTERNAL_STORAGE`: 이전 Android 버전에서 백업 파일 가져오기에만 사용
- `WRITE_EXTERNAL_STORAGE`: 이전 Android 버전에서 백업 파일 내보내기에만 사용
- `MANAGE_EXTERNAL_STORAGE`: Android 11+에서 로컬 백업 파일 선택 대체 기능에만 사용

병합된 Debug Manifest를 확인했으며 다음 권한은 포함되어 있지 않습니다.

- `INTERNET`
- `ACCESS_NETWORK_STATE`

### 보안 안내

- Secret Key는 평문으로 저장되지 않습니다.
- 데이터베이스의 Secret Key는 Android Keystore로 보호되는 AES-GCM으로 암호화됩니다.
- 백업 파일은 `PBKDF2WithHmacSHA256 + AES-GCM`으로 암호화됩니다.
- 백업 내보내기는 암호화된 JSON 파일을 로컬 다운로드 폴더에 저장합니다.

### 빌드

필요 환경:

- Android SDK
- `PATH` 또는 `JAVA_HOME`에 설정된 JDK 17
- Android Studio 또는 포함된 Gradle Wrapper

Debug APK 빌드:

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

APK 출력 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 프로젝트 상태

현재 버전은 공개 MVP이며 테스트와 개인 평가에 적합합니다. 실제 배포를 위해서는 정식 서명 키를 설정하고 서명된 Release APK 또는 AAB를 빌드하세요.

## License

This project is released under the MIT License. See [LICENSE](LICENSE).
