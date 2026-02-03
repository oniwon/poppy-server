# Poppy Server

## 프로젝트 소개

Poppy 서버는 팝업 스토어 정보 제공 및 예약/대기 서비스를 위한 백엔드 API 서버입니다. Spring Boot를 기반으로 개발되었으며, 사용자 인증, 팝업 스토어 정보 관리, 예약, 대기, 리뷰 등 다양한 기능을 제공합니다.

## 주요 기능

- **사용자 관리**: OAuth2 (Naver)를 이용한 소셜 로그인
- **팝업 스토어**: 팝업 스토어 정보 조회, 검색, 스크랩 기능
- **예약 및 대기**: 팝업 스토어 방문 예약 및 실시간 대기 시스템
- **리뷰**: 사용자들이 작성한 리뷰 조회 및 '좋아요' 기능
- **결제**: 결제 관련 기능 (Toss Payments 연동 등)
- **알림**: FCM을 이용한 푸시 알림 및 WebSocket을 통한 실시간 알림
- **관리자**: 관리자 전용 기능 제공

## 기술 스택

### Backend

- **Framework**: Spring Boot 3.3.5
- **Language**: Java 17
- **Database**: MySQL
- **ORM**: Spring Data JPA, QueryDSL
- **Authentication**: Spring Security, JWT, OAuth2 Client
- **Cache**: Spring Data Redis
- **Distributed Lock**: Redisson
- **API Documentation**: Swagger (SpringDoc OpenAPI)
- **Cloud Services**: AWS S3 for Image Storage
- **Real-time Communication**: Spring WebSocket, Firebase Cloud Messaging (FCM)
- **Build Tool**: Gradle

### DevOps

- Docker
- GitHub Actions

## API 명세

API 문서는 Swagger를 통해 제공됩니다.

- `http://localhost:8080/swagger-ui/index.html`

## 시작하기

`application.yml` 또는 환경 변수를 통해 아래 항목들을 설정해야 합니다.

- **Database**
  - `spring.datasource.url`
  - `spring.datasource.username`
  - `spring.datasource.password`
- **JWT**
  - `jwt.secret-key`
- **OAuth2 Client**
  - Google, Kakao, Naver 등 각 Provider의 Client ID 및 Secret
- **AWS S3**
  - `cloud.aws.credentials.access-key`
  - `cloud.aws.credentials.secret-key`
  - `cloud.aws.s3.bucket`
- **FCM**
  - Firebase Admin SDK 설정 파일 경로

## 아키텍처 구조

<img width="826" height="387" alt="architecture" src="https://github.com/user-attachments/assets/359eb703-ad85-4f97-a07e-c87b479f9931" />

