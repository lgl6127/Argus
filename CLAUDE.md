# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Set JDK 21 (required — record syntax, virtual threads)
export JAVA_HOME="D:\Develop\DevelopTool\StudyEnvironment\PhpWebStudy-Data\app\openjdk-21.0.9"
 
# Compile
cd Argus-backend && ./mvnw clean compile

# Run tests
./mvnw test

# Run single test
./mvnw test -Dtest=ClassName#methodName

# Package
./mvnw clean package -DskipTests

# Run app (dev profile by default, port 10001)
./mvnw spring-boot:run
```

## Tech Stack

- **Java 21** with Spring Boot 3.5.0, Spring MVC (no WebFlux)
- **MyBatis-Plus 3.5.15** (not JdbcTemplate) for all DB access
- **PostgreSQL** as the database, with **pgvector** (Spring AI PgVectorStore, 512-dim, HNSW/cosine) as vector store
- **Elasticsearch** for full-text chunk indexing (`dd_rag_document_chunks`); **MinIO** for document object storage
- **Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.0**: DashScope chat models (thinking models require `enable-thinking: true`), OpenAI-compatible embeddings, Agent Framework
- Document parsing: Apache PDFBox, POI-OOXML
- **Maven** with wrapper (`mvnw`)
- **Lombok 1.18.34** (available but currently unused — entity getters/setters are hand-written)
- **JJWT 0.12.6** for JWT access tokens
- **Spring Security Crypto** (BCrypt) for password hashing
- **Knife4j 4.5.0** for API documentation
- **Frontend**: Vue 3 + TypeScript + Vite + Pinia + Element Plus (see `Argus-frontend/`)

## Architecture

Full module details and conventions live in [AGENT.md](AGENT.md). Package root `com.argus.rag`, organized by business module, each with `controller / service / mapper / model(entity|dto|vo) / support` layering:

```
com.argus.rag
├── ArgusBackendApplication        # @SpringBootApplication entry point
├── common/                        # ApiResponse<T>, enums, exception hierarchy + GlobalExceptionHandler,
│                                  # @OperationLog + LogAop, UserContext/AuthenticatedUser
├── auth/                          # JWT dual-token auth: /api/auth/*, JwtAuthenticationFilter,
│                                  # JwtAccessTokenService, RefreshTokenService (user_refresh_tokens + httpOnly cookie),
│                                  # CurrentUserService, AuthProperties (prefix: rag.auth), DevAdminInitializer (@Profile("dev"))
├── user/                          # Account profile / change-password, admin user management (/api/admin/users)
├── group/                         # Knowledge-base groups: members, invitations, join requests, group roles
├── document/                      # Document CRUD, chunked upload sessions, MinIO presigned upload, preview
├── ingestion/                     # Async ingest pipeline (reader→parser→transformer→vector), ingestion_jobs scheduling,
│                                  # document_chunks persistence
├── engine/                        # Infra adapters: elasticsearch (chunk index), pgvector (retrieval), storage (MinIO ObjectStorageService)
├── qa/                            # Query planning/rewrite, hybrid retrieval (vector + ES), evidence assembly,
│                                  # CitationAssembler, SSE streaming answer (/api/qa/*)
├── assistant/                     # AI assistant: sessions/messages, Spring AI Alibaba agent framework, conversation memory
└── metrics/                       # LLM usage collection, token cost accounting, admin metrics API
```

## Key Conventions

### MyBatis-Plus Pattern
- All DB access uses `BaseMapper` + `LambdaQueryWrapper`, never `JdbcTemplate`
- Entities extend nothing but use `@TableName` / `@TableId(type = IdType.AUTO)`
- Complex queries with `FOR UPDATE` row locking go in `src/main/resources/mappers/*.xml`
- Enums use `default-enum-type-handler: org.apache.ibatis.type.EnumTypeHandler` (stored as `name()` strings)

### API Response Pattern
- Every controller method returns `ApiResponse<T>` — a `record` with `success()`, `data()`, `message()`
- Never throw raw exceptions; throw `BusinessException`/`ForbiddenException`/`UnauthorizedException` which `GlobalExceptionHandler` maps to HTTP status codes

### Auth Flow
- `JwtAuthenticationFilter` extracts Bearer token → sets `AuthenticatedUser` as request attribute
- `CurrentUserService` reads that attribute to provide `CurrentUser` record
- Controllers call `currentUserService.getRequiredCurrentUser(request)` or `requireSystemAdmin(request)`
- Refresh tokens stored in `user_refresh_tokens` table, sent as httpOnly cookie

## Configuration

- **Active profile**: `dev` (default in `application.yml`), config in `application-dev.yml`
- **Server port**: `10001` (frontend Vite proxies `/api` to it)
- **Auth properties prefix**: `rag.auth` (maps to `AuthProperties` class; JWT secret, token expiry, refresh cookie)
- **Other prefixes**: `storage.minio.*`, `elasticsearch.*`, `ingestion.*` (chunking/vector batch), `spring.ai.*` (DashScope chat + OpenAI-compatible embedding, 512 dims)
- **MyBatis-Plus XML mappers**: `classpath*:/mappers/**/*.xml`
- **Enum handling**: Stored as VARCHAR using enum `.name()` values
- **DDL single source**: `sql/schema.sql`
