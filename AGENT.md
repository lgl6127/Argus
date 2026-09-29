# AGENT.md — Argus RAG 智能知识平台

本文件面向在 Argus 仓库内工作的所有开发/协作智能体，定义项目上下文、架构约定与行为约束。执行任何代码修改前必须先阅读并遵守本文件。若本文件与通用假设冲突，以本文件与项目实际代码为准。

## 1. 项目定位

Argus 是一个企业级 RAG（检索增强生成）智能知识平台，核心能力：

- **知识库管理**：以“群组（Group）”为知识库单元，支持成员、邀请、加入申请
- **文档摄入（Ingestion）**：上传（MinIO + 分片上传会话）→ 解析（PDF/Office/Markdown）→ 切片（token 级分块与重叠）→ 向量化（Embedding）→ 双写索引（PGvector + Elasticsearch）
- **混合检索 QA**：查询规划/改写 → 向量 + 关键词混合召回 → 证据融合 → LLM 流式回答（SSE）→ 引用溯源
- **AI 助手（Agent）**：基于 Spring AI Alibaba Agent Framework 的多轮会话助手，含短期记忆（会话上下文）与工具调用
- **运营指标**：LLM 调用统计、Token 成本核算（metrics 模块）
- **认证授权**：JWT 双 Token（Access + Refresh）体系、系统角色 + 群组角色两级权限

## 2. 技术栈

### 后端（`Argus-backend/`）

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 21（record、虚拟线程）、Spring Boot 3.5.0、Spring MVC（禁用 WebFlux） |
| ORM | MyBatis-Plus 3.5.15（BaseMapper + LambdaQueryWrapper + XML） |
| 数据库 | PostgreSQL（含 pgvector 扩展） |
| 向量存储 | Spring AI `PgVectorStore`（HNSW 索引、cosine 距离、512 维） |
| 全文检索 | Elasticsearch（索引 `dd_rag_document_chunks`） |
| 对象存储 | MinIO 8.5.17（bucket `argus-rag-documents`） |
| AI | Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.0（DashScope 对话模型、OpenAI 兼容 Embedding）、Agent Framework |
| 认证 | JJWT 0.12.6（Access Token）、Spring Security Crypto（BCrypt）、Refresh Token 存库 + httpOnly Cookie |
| 文档解析 | Apache PDFBox 2.0.31、POI-OOXML 5.2.5 |
| API 文档 | Knife4j 4.5.0 + springdoc-openapi |
| 其他 | Lombok、Spring AOP（操作日志）、Actuator、Validation |
| 构建 | Maven Wrapper（`mvnw`） |

### 前端（`Argus-frontend/`）

Vue 3.5（Composition API + `<script setup>`）、TypeScript、Vite、Pinia 3、Vue Router、Element Plus 2.14、Axios、marked（Markdown 渲染）。Lint：oxlint + ESLint + Prettier。Node ≥ 20.19 / 22.12。

### 运行时环境

- 后端默认 profile 为 `dev`，服务端口 `10001`；配置前缀：`rag.auth.*`、`storage.minio.*`、`elasticsearch.*`、`ingestion.*`、`spring.ai.*`
- 前端 axios `baseURL` 为 `/api`（Vite 代理到后端），`withCredentials: true`（Refresh Token Cookie）
- 数据库 DDL 单一来源：`sql/schema.sql`

## 3. 后端分层架构

包根：`com.argus.rag`，按业务模块组织，每个模块内统一分层：

```
com.argus.rag.{module}
├── controller/    # HTTP 入口，只做的参数接收/校验、调用 Service、返回 ApiResponse<T>
├── service/       # 业务编排与规则，事务边界
├── mapper/        # MyBatis-Plus BaseMapper 接口（XML 放 resources/mappers/{module}/*.xml）
├── model/
│   ├── entity/    # 数据库实体（@TableName + @TableId(type = IdType.AUTO)，getter/setter 手写）
│   ├── dto/       # 请求入参（Java record）
│   ├── vo/        # 响应出参（Java record）
│   └── enums/     # 模块内枚举（公共枚举放 common/enums）
├── config/        # 模块级 Spring 配置
└── support/       # 模块内辅助工具（解析器、装配器等）
```

### 核心模块职责

| 模块 | 职责 |
|---|---|
| `common` | `ApiResponse<T>`（record: success/data/message）、公共枚举、`BusinessException(400)/ForbiddenException(403)/UnauthorizedException(401)` + `GlobalExceptionHandler`、`@OperationLog` 注解与 `LogAop`、`UserContext/AuthenticatedUser` |
| `auth` | 登录/注册/刷新/登出（`/api/auth/*`）、JWT 签发解析（`JwtAccessTokenService`）、Refresh Token 管理（`RefreshTokenService` + `user_refresh_tokens` 表 + `AuthCookieSupport`）、`JwtAuthenticationFilter`、`CurrentUserService`、dev 环境管理员初始化 |
| `user` | 账户资料、改密、管理员用户管理（`/api/admin/users`） |
| `group` | 知识库（群组）CRUD、成员/邀请/加入申请，群组角色权限判定 |
| `document` | 文档列表/详情/删除、分片上传会话、预签名直传、文档预览 |
| `ingestion` | 摄入管线（reader → parser → transformer → vector），异步 Worker 调度（`ingestion_jobs` 表），切片落库（`document_chunks`） |
| `engine` | 底层引擎适配：`elasticsearch`（切片索引）、`pgvector`（向量检索适配）、`storage`（MinIO `ObjectStorageService` 抽象） |
| `qa` | 查询规划（`QueryPlanningService`）、问题改写、混合检索（`HybridChunkRetrievalService`）、证据装配、`CitationAssembler`（引用）、SSE 流式问答 |
| `assistant` | AI 助手会话/消息、Agent（spring-ai-alibaba agent-framework）、对话记忆（memory + `assistant_session_contexts`）、聊天 DTO/VO |
| `metrics` | LLM 调用统计收集（collector）、成本核算（cost）、管理端指标查询 |

跨模块调用规则：只能调用目标模块的对外 Service 接口或经由公共模型，禁止绕层访问他模块 Mapper/内部类。

## 4. 前端目录结构

```
src/
├── api/           # HTTP 接口封装，每模块一个 .ts（auth/document/group/qa/assistant/metrics/admin-user）
│   └── http.ts    # 全局 axios 实例 + ApiResponse<T> 类型 + extractApiError()
├── views/         # 页面级组件，按模块分子目录（含 components/、composables/）
├── components/    # 全局共享组件（EmptyState、LoginModal、PageHeaderHero 等）
├── layouts/       # DefaultLayout（导航 + 登录态）
├── stores/        # Pinia：useAuthStore（登录态/当前用户）、useAppStore
├── router/        # 路由 + 全局守卫（登录态、角色）
├── types/         # 跨页面公共类型（如 assistant.ts）
└── assets/        # 全局样式
```

## 5. 编码规范

### 后端

1. **API 响应**：Controller 一律返回 `ApiResponse<T>`（`ApiResponse.success(data)` / `ApiResponse.error(message)`）
2. **异常**：只抛 `BusinessException` / `ForbiddenException` / `UnauthorizedException`，由 `GlobalExceptionHandler` 统一映射 HTTP 状态码；禁止裸抛或吞异常返回 null
3. **依赖注入**：构造器注入（final 字段 + 显式构造器或 `@RequiredArgsConstructor`），禁止 `@Autowired` 字段注入
4. **DTO/VO**：使用 Java record；入参放 `model/dto`、出参放 `model/vo`，禁止把 Entity 直接返回给前端
5. **实体**：`@TableName("snake_case表名")` + `@TableId(type = IdType.AUTO)`；getter/setter 手写（项目实体风格），注释与日志统一中文
6. **枚举**：存库使用 `name()` 字符串（`default-enum-type-handler: EnumTypeHandler`），状态枚举集中在 `common/enums`
7. **校验**：请求 DTO 使用 Jakarta Validation 注解 + `@Valid`
8. **SSE**：流式接口（qa/assistant 聊天）使用 `SseEmitter`，注意编码 UTF-8 与完成/超时回调
9. **文档注释**：对外 Controller 接口加 Knife4j/OpenAPI 注解（`@Tag`/`@Operation`）

### 前端

1. 组件 `<script setup lang="ts">`；props/emits 用泛型 `defineProps<{...}>()` / `defineEmits<{...}>()`
2. 所有 HTTP 调用必须走 `src/api/` 封装层（`import http from './http'`），显式标注返回类型 `Promise<ApiResponse<T>>` 中的 `T`；禁止组件内直接创建 axios 实例
3. 错误处理统一 `extractApiError(error, '兜底文案')`；异步操作必须有 loading 状态
4. 命名：组件 PascalCase、composable `useXxx`、CSS BEM（`block__element--modifier`）
5. Element Plus 按需引入（`ElMessage`、`ElMessageBox`）
6. 与后端契约对齐：前端 TS 类型字段名与后端 VO record 字段一致（camelCase）

## 6. 接口契约

- REST 前缀统一 `/api`；认证接口 `/api/auth/{login,register,refresh,logout,me}`；管理端 `/api/admin/**`（仅 SYSTEM_ADMIN）
- 响应体统一 `{ success, data, message }`；HTTP 状态码由异常类型驱动（400/401/403）
- 认证：`Authorization: Bearer <access_token>`（有效期 30 分钟）；refresh token 经 httpOnly Cookie（`ARGUS_DD_RAG_REFRESH_TOKEN`，14 天）静默续期，前端 401 时通过 `/api/auth/refresh` 重试
- 流式问答：QA 与助手聊天走 SSE（`text/event-stream`），事件负载为 JSON 分片；前端使用 fetch/EventSource 消费，不套 ApiResponse
- 分页/列表类返回 VO record，字段命名与前端 TS 类型保持同步；新增/变更接口必须同时更新 `src/api/` 封装

## 7. 数据库访问

- 一律 MyBatis-Plus：简单查询 `BaseMapper` + `LambdaQueryWrapper`；复杂 SQL、`FOR UPDATE` 行锁、批量操作放 `src/main/resources/mappers/**/*.xml`
- 禁止 `JdbcTemplate`、禁止拼接字符串 SQL
- 表/字段命名 snake_case，Java 侧 camelCase，依赖 `map-underscore-to-camel-case` 自动映射
- 所有 DDL 变更必须同步回写 `sql/schema.sql`（含中文表/列注释），新增表需带 `created_at/updated_at`
- pgvector 表 `vector_store` 由 Spring AI PgVectorStore 管理（512 维、HNSW、cosine），修改维度需同步 embedding `dimensions` 配置
- PostgreSQL `NOT NULL DEFAULT` 列：插入时避免显式传 NULL 覆盖默认值

## 8. 认证与授权

1. `JwtAuthenticationFilter`（OncePerRequestFilter）解析 Bearer Token → 将 `AuthenticatedUser` 写入 request attribute
2. Controller 通过 `CurrentUserService.getRequiredCurrentUser(request)` 获取当前用户；管理端用 `requireSystemAdmin(request)`
3. 权限两级：系统角色 `SystemRole`（USER / SYSTEM_ADMIN）+ 群组角色 `GroupRole`（OWNER / ADMIN / MEMBER），资源级鉴权在 Service 层判定并抛 `ForbiddenException`
4. 密码使用 BCrypt（`PasswordHasher` bean）；JWT 密钥、Token 有效期由 `rag.auth.*` 配置（`AuthProperties`）
5. dev profile 下 `DevAdminInitializer` 初始化管理员账号；生产环境必须显式配置安全密钥
6. 新增需要登录的接口无需改过滤器链白名单；公开接口才加入 permit 列表（auth 配置中集中管理）

## 9. 日志与操作审计

- 日志框架 Slf4j + `logback-spring.xml`，滚动日志输出到 `logs/`（`argus.log` / `argus-error.log`）
- 级别约定：业务分支用 `info`/`debug`，可恢复异常用 `warn`，未预期失败用 `error`（带堆栈）；日志内容中文
- 操作审计：写操作 Controller 所在类加 `@OperationLog` 注解，由 `LogAop` 统一记录操作人、动作、参数与结果；查询类接口不加
- 禁止 `System.out.println`；禁止在日志中输出密码、token、API key 等敏感信息

## 10. 测试规范

- 框架：JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）+ AssertJ
- Controller 测试：`@WebMvcTest` + `@MockitoBean`（注意 Spring Boot 3.5 用 `@MockitoBean` 而非已废弃的 `@MockBean`）
- Service 测试：纯 Mockito 单元测试，`@Mock` 依赖 + `@InjectMocks` 被测类，不连数据库
- 场景覆盖：正常路径 + 异常路径（`assertThatThrownBy`）+ 边界条件；Given-When-Then 结构
- 命名：`@DisplayName` 中文描述场景，方法名英文表达意图；用 `@Nested` 按场景分组
- 测试放置于 `src/test/java` 对应包路径；存量示例：`GroupManagementServiceTest`、`QaControllerTest`

## 11. 常用命令

```bash
# ── 后端（PowerShell 用 ; 分隔命令，不支持 &&）──
cd Argus-backend
./mvnw clean compile            # 编译
./mvnw test                     # 全部测试
./mvnw test -Dtest=ClassName#methodName   # 单个测试
./mvnw clean package -DskipTests        # 打包
./mvnw spring-boot:run          # 启动（默认 dev profile，端口 10001）

# ── 前端 ──
cd Argus-frontend
npm run dev                     # 开发服务器
npm run build                   # 类型检查 + 构建
npm run type-check              # vue-tsc 类型检查
npm run lint                    # oxlint + eslint --fix
npm run format                  # Prettier
```

API 文档：启动后端后访问 Knife4j（`/doc.html`）。

## 12. 约束与禁止事项

**必须做：**

- 新代码风格与同模块现有代码保持一致；中文注释/日志风格与存量一致
- 前后端接口契约同步修改（后端 VO ↔ 前端 TS 类型 ↔ `api/` 封装）
- 数据库结构变更同时更新 `sql/schema.sql`
- 关键改动给出验证命令（编译/测试/类型检查）
- 思考模型（DashScope glm 系列）必须显式 `enable-thinking: true`，否则被服务端拒绝

**禁止做：**

- 禁止 Controller 直接操作数据库或编写业务规则
- 禁止使用 `JdbcTemplate`，数据访问统一 MyBatis-Plus
- 禁止 `@Autowired` 字段注入
- 禁止前端绕过 `api/` 层直接使用 axios
- 禁止跨模块引用内部 Service / Mapper（只走模块公开入口）
- 禁止引入 WebFlux 或在 MVC Controller 中混用响应式类型
- 禁止在代码或配置中新增真实密钥/密码明文；新增配置一律走 `${ENV_VAR:默认值}` 占位
- 禁止未经需求确认删除已有功能或重写其接口契约
