# Argus-backend 引入 Spring Security 详细计划

> **文档状态**：规划草案  
> **目标版本**：v1.1.0  
> **创建日期**：2026-08-18  
> **影响范围**：认证/鉴权架构升级（不改动现有业务源码）

---

## 目录

1. [现状分析](#一现状分析)
2. [引入目标](#二引入目标)
3. [核心概念说明](#三核心概念说明)
4. [实施步骤](#四实施步骤)
5. [代码变更清单](#五代码变更清单)
6. [迁移策略](#六迁移策略)
7. [风险与应对措施](#七风险与应对措施)
8. [测试验证清单](#八测试验证清单)
9. [附录：参考代码](#九附录参考代码)

---

## 一、现状分析

### 1.1 当前安全架构

| 组件 | 现状 | 位置 |
|------|------|------|
| 认证方式 | JWT Token（自定义 Filter） | `JwtAuthenticationFilter.java` |
| 鉴权方式 | Service 层手动调用 | `CurrentUserService.requireXxx()` |
| 密码加密 | BCrypt（已引入 crypto 包） | `PasswordHasher.java` |
| Spring Security 核心 | **未引入** | — |
| 会话管理 | 无状态（ThreadLocal） | `UserContext.java` |

### 1.2 当前存在的问题

1. **无统一的安全配置**：没有 `SecurityFilterChain`，所有请求默认放行，依赖 Service 层手动拦截
2. **鉴权逻辑分散**：每个 Service 都要重复调用 `currentUserService.requireBusinessUser()`
3. **无法使用方法级安全注解**：不能用 `@PreAuthorize("hasRole('ADMIN')")` 声明式鉴权
4. **依赖开发者自觉**：新接口容易遗漏鉴权，存在安全风险
5. **白名单管理分散**：`shouldNotFilter` 和潜在的安全配置可能不一致
6. **异常响应不统一**：未认证/无权限的响应格式由各自 Service 控制

### 1.3 现有相关文件

```
com.argus.rag.auth.security/
├── JwtAccessTokenService.java      # JWT 签发与解析
├── JwtAuthenticationFilter.java    # JWT 认证过滤器（OncePerRequestFilter）
├── AuthCookieSupport.java          # Refresh Token Cookie 操作
└── RefreshTokenService.java        # Refresh Token 业务

com.argus.rag.auth/
├── CurrentUserService.java         # 当前用户获取与鉴权
└── controller/AuthController.java  # 登录/注册/刷新/登出

com.argus.rag.common.security/
├── AuthenticatedUser.java          # 认证用户信息（record）
└── UserContext.java                # ThreadLocal 用户上下文

com.argus.rag.common.exception/
├── GlobalExceptionHandler.java     # 全局异常处理（含 401/403）
├── BusinessException.java
├── ForbiddenException.java
└── UnauthorizedException.java

com.argus.rag.common.enums/
└── SystemRole.java                 # ADMIN / USER
```

---

## 二、引入目标

### 2.1 短期目标（本阶段）

| 目标 | 说明 |
|------|------|
| 统一认证入口 | 将 JWT Filter 整合到 Spring Security Filter Chain |
| 统一路径级鉴权 | 通过 `SecurityFilterChain` 配置公开/受保护接口 |
| 统一异常响应 | 401/403 响应格式与现有 `ApiResponse` 保持一致 |
| 向后兼容 | 保留 `UserContext` 和 `CurrentUserService`，现有代码零改动 |

### 2.2 中期目标（后续迭代）

| 目标 | 说明 |
|------|------|
| 方法级安全 | 启用 `@PreAuthorize`、`@PostAuthorize`、`@Secured` 注解 |
| 简化 Service 层 | 逐步移除 Service 中的手动鉴权，上提到 Controller |
| 角色权限细化 | 支持基于角色的接口访问控制（RBAC） |

### 2.3 长期目标

| 目标 | 说明 |
|------|------|
| 资源级鉴权 | 支持 `@PreAuthorize("@authz.checkOwner(#groupId)")` |
| 审计日志 | 集成 Spring Security 审计事件 |
| 多认证方式 | 支持 OAuth2 / SSO 扩展 |

---

## 三、核心概念说明

### 3.1 认证（Authentication）vs 鉴权（Authorization）

| 维度 | 认证（Authentication） | 鉴权（Authorization） |
|------|----------------------|----------------------|
| 问题 | 你是谁？ | 你能做什么？ |
| 时机 | 请求进入时 | 业务处理时 |
| 依据 | Token 签名、过期时间 | 用户角色、资源归属 |
| 失败响应 | 401 Unauthorized | 403 Forbidden |
| 项目中位置 | `JwtAuthenticationFilter` | `CurrentUserService` / Service 层 |

### 3.2 Spring Security 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Request                              │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  SecurityFilterChain（过滤器链）                              │
│  ├─ CsrfFilter（禁用）                                        │
│  ├─ JwtAuthenticationFilter（自定义）← 当前项目已有            │
│  ├─ UsernamePasswordAuthenticationFilter（标准）               │
│  ├─ AuthorizationFilter（权限判断）                           │
│  └─ ExceptionTranslationFilter（异常转换）                    │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  Method Security（方法级安全，可选）                           │
│  ├─ @PreAuthorize("hasRole('ADMIN')")                       │
│  ├─ @PostAuthorize("returnObject.owner == authentication.name")│
│  └─ @Secured("ROLE_ADMIN")                                  │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  Controller / Service                                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、实施步骤

### 步骤 1：添加 Maven 依赖

**文件**：`pom.xml`

在 `<dependencies>` 节点内添加：

```xml
<!-- Spring Security Starter -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

> **说明**：
> - Spring Boot 3.5.0 默认引入 Spring Security 6.x
> - 与现有 `spring-security-crypto` 兼容（crypto 是 starter 的子依赖）
> - 无需额外配置版本号，由 parent 统一管理

---

### 步骤 2：创建 Spring Security 核心配置类

**新建文件**：`src/main/java/com/argus/rag/auth/config/SecurityConfig.java`

```java
package com.argus.rag.auth.config;

import com.argus.rag.auth.security.CustomAccessDeniedHandler;
import com.argus.rag.auth.security.CustomAuthenticationEntryPoint;
import com.argus.rag.auth.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 核心配置。
 *
 * <p>配置规则：
 * <ul>
 *   <li>无状态会话（JWT 不依赖 HttpSession）</li>
 *   <li>禁用 CSRF（前后端分离，使用 Token 认证）</li>
 *   <li>公开接口白名单（/api/auth/*、Swagger、健康检查）</li>
 *   <li>其他接口默认需要认证</li>
 *   <li>启用方法级安全注解（@PreAuthorize 等）</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomAuthenticationEntryPoint authenticationEntryPoint,
            CustomAccessDeniedHandler accessDeniedHandler
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（前后端分离架构，使用 Bearer Token）
            .csrf(AbstractHttpConfigurer::disable)

            // 无状态会话管理
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // 请求权限配置
            .authorizeHttpRequests(auth -> auth
                // === 公开接口：认证相关 ===
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/register").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()

                // === 公开接口：API 文档（开发环境）===
                .requestMatchers("/doc.html").permitAll()
                .requestMatchers("/webjars/**").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()

                // === 公开接口：健康检查 ===
                .requestMatchers("/actuator/health").permitAll()

                // === 其他所有请求需要认证 ===
                .anyRequest().authenticated()
            )

            // 自定义异常处理（保持与现有 ApiResponse 格式一致）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )

            // 添加 JWT 认证过滤器（在标准认证过滤器之前执行）
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

---

### 步骤 3：改造 JwtAuthenticationFilter

**修改文件**：`src/main/java/com/argus/rag/auth/security/JwtAuthenticationFilter.java`

**变更要点**：
1. 保留 `UserContext` 设置（兼容现有代码）
2. 新增 `SecurityContextHolder` 设置（支持 Spring Security）
3. 将用户角色转换为 `GrantedAuthority`
4. finally 块中清理两个上下文

```java
package com.argus.rag.auth.security;

import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（Spring Security 集成版）。
 *
 * <p>从 Authorization 头提取 Bearer token，解析后：
 * <ol>
 *   <li>设置 {@link UserContext}（兼容现有代码）</li>
 *   <li>设置 Spring Security 的 {@link SecurityContextHolder}（支持标准安全机制）</li>
 * </ol>
 *
 * <p>请求结束时，在 finally 块中清理两个上下文，防止内存泄漏。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtAccessTokenService jwtAccessTokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtAccessTokenService jwtAccessTokenService,
            ObjectMapper objectMapper
    ) {
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (accessToken.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtAccessTokenService.AccessTokenClaims claims = jwtAccessTokenService.parse(accessToken);
            log.debug("JWT 认证成功: userId={}, path={}", claims.userId(), request.getRequestURI());

            // 1. 构建 AuthenticatedUser（兼容现有代码）
            AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                    claims.userId(),
                    claims.userCode(),
                    claims.displayName(),
                    claims.systemRole(),
                    claims.mustChangePassword()
            );

            // 2. 设置 UserContext（现有代码依赖）
            UserContext.set(authenticatedUser);

            // 3. 设置 Spring Security 上下文（新增）
            Authentication authentication = createAuthentication(authenticatedUser);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (Exception exception) {
            log.debug("JWT 认证失败: {} path={}", exception.getMessage(), request.getRequestURI());
            // 认证失败时清理上下文，让 Spring Security 处理未认证情况
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
        } finally {
            // 清理两个上下文，防止内存泄漏和数据污染
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 创建 Spring Security 的 Authentication 对象。
     *
     * <p>将 {@link SystemRole} 转换为 Spring Security 的 {@link GrantedAuthority}，
     * 格式为 {@code ROLE_XXX}（Spring Security 标准前缀）。
     */
    private Authentication createAuthentication(AuthenticatedUser user) {
        String authority = "ROLE_" + user.systemRole().name();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));

        return new UsernamePasswordAuthenticationToken(
                user,           // principal：用户信息
                null,           // credentials：JWT 模式下无需密码
                authorities     // 权限列表
        );
    }
}
```

---

### 步骤 4：创建自定义认证入口点（401 处理）

**新建文件**：`src/main/java/com/argus/rag/auth/security/CustomAuthenticationEntryPoint.java`

```java
package com.argus.rag.auth.security;

import com.argus.rag.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定义认证入口点。
 *
 * <p>当未认证用户访问受保护资源时，Spring Security 会调用此处理器。
 * 返回与现有系统一致的 JSON 格式 401 响应。
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public CustomAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new ApiResponse<>(false, null, "access token 非法或已过期")
        );
    }
}
```

---

### 步骤 5：创建自定义权限拒绝处理器（403 处理）

**新建文件**：`src/main/java/com/argus/rag/auth/security/CustomAccessDeniedHandler.java`

```java
package com.argus.rag.auth.security;

import com.argus.rag.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定义权限拒绝处理器。
 *
 * <p>当已认证用户访问无权限资源时，Spring Security 会调用此处理器。
 * 返回与现有系统一致的 JSON 格式 403 响应。
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new ApiResponse<>(false, null, "无权访问该资源")
        );
    }
}
```

---

### 步骤 6：可选 - 启用方法级安全注解

在 `SecurityConfig.java` 中已经通过 `@EnableMethodSecurity(prePostEnabled = true)` 启用了方法级安全。

**使用示例**（后续迭代中逐步应用）：

```java
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")  // 仅管理员可访问
    public ApiResponse<List<AdminUserItemResponse>> listUsers() {
        return ApiResponse.success(adminUserService.listUsers());
    }
}
```

**获取当前用户**（Spring Security 方式）：

```java
@GetMapping("/me")
public ApiResponse<CurrentUserProfileResponse> currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
    return ApiResponse.success(CurrentUserProfileResponse.from(user));
}
```

---

## 五、代码变更清单

### 5.1 文件变更汇总

| 操作 | 文件路径 | 说明 |
|------|---------|------|
| **修改** | `pom.xml` | 添加 `spring-boot-starter-security` 依赖 |
| **新建** | `com.argus.rag.auth.config.SecurityConfig` | Spring Security 核心配置类 |
| **修改** | `com.argus.rag.auth.security.JwtAuthenticationFilter` | 集成 `SecurityContextHolder` |
| **新建** | `com.argus.rag.auth.security.CustomAuthenticationEntryPoint` | 自定义 401 响应 |
| **新建** | `com.argus.rag.auth.security.CustomAccessDeniedHandler` | 自定义 403 响应 |

### 5.2 变更影响范围

```
┌─────────────────────────────────────────────────────────────┐
│  无改动（保持兼容）                                          │
│  ├── com.argus.rag.auth.CurrentUserService                  │
│  ├── com.argus.rag.auth.controller.AuthController           │
│  ├── com.argus.rag.auth.security.JwtAccessTokenService      │
│  ├── com.argus.rag.common.security.UserContext              │
│  ├── com.argus.rag.common.security.AuthenticatedUser        │
│  ├── com.argus.rag.common.exception.GlobalExceptionHandler  │
│  └── 所有 Controller / Service / Mapper / Entity            │
│                                                             │
│  新增/修改（本次引入）                                       │
│  ├── pom.xml（+1 dependency）                               │
│  ├── SecurityConfig.java（新建）                            │
│  ├── JwtAuthenticationFilter.java（修改）                   │
│  ├── CustomAuthenticationEntryPoint.java（新建）            │
│  └── CustomAccessDeniedHandler.java（新建）                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 六、迁移策略

### 6.1 三阶段迁移路线图

```
┌─────────────────────────────────────────────────────────────┐
│  阶段 1：引入框架（预计 1-2 天）                              │
│  ├── 添加 Maven 依赖                                         │
│  ├── 创建 SecurityConfig                                     │
│  ├── 改造 JwtAuthenticationFilter                            │
│  ├── 创建 CustomAuthenticationEntryPoint                     │
│  ├── 创建 CustomAccessDeniedHandler                          │
│  └── 全面回归测试                                            │
├─────────────────────────────────────────────────────────────┤
│  阶段 2：并行运行（预计 1-2 周）                              │
│  ├── Spring Security 负责路径级拦截（SecurityFilterChain）    │
│  ├── CurrentUserService 继续负责业务级鉴权                     │
│  ├── 观察日志，确认无异常                                    │
│  └── 收集问题，修复边界情况                                  │
├─────────────────────────────────────────────────────────────┤
│  阶段 3：逐步迁移（后续迭代）                                 │
│  ├── 新接口优先使用 @PreAuthorize 注解                       │
│  ├── 逐步替换 CurrentUserService.requireXxx() 调用           │
│  ├── Controller 层负责鉴权，Service 层专注业务                 │
│  └── 最终移除 Service 层的手动鉴权（可选）                    │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 阶段 1 详细任务清单

| 序号 | 任务 | 负责人 | 预计时间 |
|------|------|--------|---------|
| 1 | 添加 Maven 依赖 | 后端开发 | 10 分钟 |
| 2 | 创建 SecurityConfig | 后端开发 | 30 分钟 |
| 3 | 改造 JwtAuthenticationFilter | 后端开发 | 30 分钟 |
| 4 | 创建 CustomAuthenticationEntryPoint | 后端开发 | 20 分钟 |
| 5 | 创建 CustomAccessDeniedHandler | 后端开发 | 20 分钟 |
| 6 | 本地启动测试 | 后端开发 | 30 分钟 |
| 7 | 接口回归测试（所有 Controller） | 测试/后端 | 2-4 小时 |
| 8 | 代码评审 | 团队 | 1 小时 |

---

## 七、风险与应对措施

| 风险等级 | 风险描述 | 影响 | 应对措施 |
|---------|---------|------|---------|
| 🔴 **高** | 引入后所有非白名单接口默认需要认证 | 现有前端调用可能失败 | 白名单配置完整，包含 `/api/auth/*`、Swagger、健康检查 |
| 🔴 **高** | `UserContext` 与 `SecurityContextHolder` 数据不一致 | 业务逻辑异常 | 两个上下文同时设置、同时清理，保持同步 |
| 🟡 **中** | Spring Security 6.x 配置语法与 5.x 不同 | 配置不生效 | 使用 Lambda DSL 配置，参考官方文档 |
| 🟡 **中** | 现有测试用例可能失败 | CI/CD 阻塞 | 更新测试配置，添加 `@WithMockUser` 等注解 |
| 🟢 **低** | 性能影响（额外 Filter） | 请求延迟增加 | Spring Security Filter 开销极小，可忽略 |
| 🟢 **低** | 开发者学习成本 | 开发效率暂时下降 | 提供培训文档和代码示例 |

---

## 八、测试验证清单

### 8.1 认证相关测试

| 场景 | 请求 | 预期结果 |
|------|------|---------|
| 公开接口 - 登录 | `POST /api/auth/login`（无 Token） | ✅ 200 |
| 公开接口 - 注册 | `POST /api/auth/register`（无 Token） | ✅ 200 |
| 公开接口 - 刷新 | `POST /api/auth/refresh`（无 Token） | ✅ 200 |
| 公开接口 - Swagger | `GET /doc.html`（无 Token） | ✅ 200 |
| 受保护接口 - 无 Token | `GET /api/assistant/sessions`（无 Token） | ❌ 401 |
| 受保护接口 - 无效 Token | `GET /api/assistant/sessions`（Token=invalid） | ❌ 401 |
| 受保护接口 - 过期 Token | `GET /api/assistant/sessions`（Token=expired） | ❌ 401 |
| 受保护接口 - 有效 Token | `GET /api/assistant/sessions`（有效 Token） | ✅ 200 |

### 8.2 鉴权相关测试

| 场景 | 请求 | 预期结果 |
|------|------|---------|
| 普通用户访问业务接口 | `POST /api/assistant/chat`（USER Token） | ✅ 200 |
| 普通用户访问管理接口 | `GET /api/admin/users`（USER Token） | ❌ 403 |
| 管理员访问管理接口 | `GET /api/admin/users`（ADMIN Token） | ✅ 200 |
| 管理员访问业务接口 | `POST /api/assistant/chat`（ADMIN Token） | ❌ 403（requireBusinessUser） |

### 8.3 兼容性测试

| 场景 | 验证点 |
|------|--------|
| UserContext 正常工作 | Service 层通过 `UserContext.get()` 能获取当前用户 |
| CurrentUserService 正常工作 | `getRequiredCurrentUser()` 返回正确用户信息 |
| 异常响应格式一致 | 401/403 响应格式为 `ApiResponse<>(false, null, "message")` |
| 并发请求无数据污染 | 多线程环境下用户上下文不串号 |

---

## 九、附录：参考代码

### 9.1 现有异常类（保持不变）

```java
// com.argus.rag.common.exception.UnauthorizedException
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}

// com.argus.rag.common.exception.ForbiddenException
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
```

### 9.2 现有全局异常处理（保持不变）

```java
// com.argus.rag.common.exception.GlobalExceptionHandler
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorizedException(UnauthorizedException exception) {
        return new ApiResponse<>(false, null, exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleForbiddenException(ForbiddenException exception) {
        return new ApiResponse<>(false, null, exception.getMessage());
    }
}
```

### 9.3 现有 SystemRole 枚举（保持不变）

```java
// com.argus.rag.common.enums.SystemRole
public enum SystemRole {
    ADMIN,   // 管理员
    USER     // 普通用户
}
```

### 9.4 推荐的测试用例模板

```java
package com.argus.rag.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicEndpoint_shouldAllowWithoutToken() throws Exception {
        mockMvc.perform(post("/api/auth/login"))
            .andExpect(status().isBadRequest())  // 参数校验失败，但不是 401
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void protectedEndpoint_shouldRejectWithoutToken() throws Exception {
        mockMvc.perform(get("/api/assistant/sessions"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("access token 非法或已过期"));
    }

    @Test
    void adminEndpoint_shouldRejectUserRole() throws Exception {
        // 使用普通用户 Token
        String userToken = "Bearer <USER_TOKEN>";
        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
    }
}
```

---

## 文档修订记录

| 版本 | 日期 | 修订内容 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-08-18 | 初始版本 | — |

---

> **注意**：本文档为规划性文档，不涉及任何源码修改。实际实施时，请按照"实施步骤"章节逐步执行，并在每个步骤完成后进行测试验证。