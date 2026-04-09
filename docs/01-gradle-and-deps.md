# 01 - Gradle 與相依套件（Dependencies）

本文說明如何為 `eap-mcp` 模組準備 Gradle 依賴與常見設定，讓 MCP 工具可以順利編譯、注入以及與其他服務（如 wallet/order）整合。

## 目標

- 確保 Spring Boot、MCP SDK、Feign、Jackson 等套件可用
- 提供 build / bootRun 快速指令

## 重要相依

在 `eap-mcp/build.gradle`（或根目錄 `build.gradle` 的子模組設定）需要包含下列重點：

- Spring Boot 各類依賴（starter-web, starter-actuator, starter-validation）
- Spring Cloud OpenFeign（若使用 Feign 呼叫外部 wallet/order 服務）
- Jackson（已由 Spring Boot 帶入）
- MCP / Model Context Protocol 相關套件（若有 internal SDK）


## 本專案實際的 build.gradle

下面是 `eap-mcp/build.gradle` 的完整內容，我會在之後針對關鍵相依做逐段說明：

```gradle
plugins {
        id 'java'
        id 'org.springframework.boot' version '3.5.3'
        id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.eap'
version = '0.0.1-SNAPSHOT'

java {
        sourceCompatibility = '17'
}

configurations {
        compileOnly {
                extendsFrom annotationProcessor
        }
}

repositories {
        mavenCentral()
        maven { url 'https://repo.spring.io/milestone' }
}

dependencies {
        // EAP Common DTOs
        implementation project(':eap-common')
    
        implementation 'org.springframework.boot:spring-boot-starter-web'
        implementation 'org.springframework.boot:spring-boot-starter-validation'
        implementation 'org.springframework.boot:spring-boot-starter-actuator'
    
        // 正確的 MCP Server WebMVC Starter
        implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc:1.1.0-M1'
    
        // HTTP Client for calling order-service
        implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    
        // JSON processing
        implementation 'com.fasterxml.jackson.core:jackson-databind'
        implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
    
        // Logging and metrics
        implementation 'io.micrometer:micrometer-registry-prometheus'
    
        // Lombok
        compileOnly 'org.projectlombok:lombok'
        annotationProcessor 'org.projectlombok:lombok'
    
        // Testing
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

dependencyManagement {
        imports {
                mavenBom 'org.springframework.cloud:spring-cloud-dependencies:2023.0.0'
                // 使用正確的 Spring AI BOM 版本
                mavenBom "org.springframework.ai:spring-ai-bom:1.1.0-M1"
        }
}

tasks.named('test') {
        useJUnitPlatform()
}
```

## 逐項說明（為什麼需要這些 Spring 套件）

- `org.springframework.boot:spring-boot-starter-web`
    - 提供 Spring MVC、內嵌的 Tomcat/Netty，以及路由/Controller 的基礎。MCP server 與工具管理端通常會以 HTTP endpoint 暴露，因此需要 web starter。

- `org.springframework.boot:spring-boot-starter-validation`
    - 提供 `javax.validation`/Jakarta Bean Validation（例如 `@Valid`、`@NotNull`），方便在工具的方法參數上做入參驗證，避免錯誤的輸入造成系統異常。

- `org.springframework.boot:spring-boot-starter-actuator`
    - 提供健康檢查 (`/actuator/health`)、metrics、和運維端點，好在部署與監控上整合 Prometheus / Grafana。

- `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:1.1.0-M1`
    - 這是專門為 MCP pattern 用的 Spring AI starter（示例名稱），把 MCP tool 掃描、Tool 註冊與 WebMVC 介面整合起來，讓工具可以透過 MCP 協定被呼叫。

- `org.springframework.cloud:spring-cloud-starter-openfeign`
    - 提供 Feign Client 支援，讓你可以用 interface 定義遠端 HTTP 呼叫（例如 `WalletServiceClient`、`OrderServiceClient`），可整合 Ribbon / Resilience4j（視需要）做重試或斷路器。

- `com.fasterxml.jackson.core:jackson-databind` & `jackson-datatype-jsr310`
    - Jackson 是 JSON 序列化/反序列化的核心，JSR310 提供 `java.time` 類型的序列化支援。

- `io.micrometer:micrometer-registry-prometheus`
    - Micrometer 提供應用層 metrics；Prometheus registry 能把 metrics 握手到 Prometheus，配合 Actuator 使用。

- `lombok`（compileOnly + annotationProcessor）
    - 減少樣板程式碼（getter/setter/constructor），但要在 IDE 中啟用 lombok 插件以獲得良好開發體驗。

## 小建議

- 若 MCP server 使用非 WebMVC 的傳輸（例如 gRPC），需要替換相關 starter 與配置。

---