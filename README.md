# AIbot - 智能图书馆 AI 助手

基于 Spring AI + Spring AI Alibaba 构建的多模态智能图书馆助手系统，包含 **GraphRAG 知识图谱阅读助手** 和 **图书馆预约助手** 两大模块。

## 功能特性

### 🕸️ GraphRAG 知识图谱阅读助手
- 上传 PDF 文档，自动构建知识图谱并可视化展示
- 基于 LLM 的实体与关系抽取
- 向量检索 + 关键词检索的混合种子实体发现
- 多跳图谱遍历增强 RAG 问答
- 支持流式输出（SSE）

### 📚 图书馆预约助手
- 基于 ReAct Agent 的多步推理与工具编排
- 通过 MCP 协议集成图书查询与预约工具
- 支持 Tavily 网络搜索获取豆瓣评分等外部信息
- 基于 Redis 的 Agent 记忆持久化
- 支持流式输出，展示思维链和工具调用过程

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.5.10 |
| AI 框架 | Spring AI 1.1.2 + Spring AI Alibaba 1.1.2 |
| 大模型 | 阿里云 DashScope (qwen-max) / Ollama (qwen3.5:9b) |
| 向量数据库 | Neo4j Vector Store |
| 知识图谱 | Neo4j |
| 关系数据库 | MySQL (MyBatis-Plus) |
| 缓存/记忆 | Redis (Redisson) |
| 工具协议 | MCP (Model Context Protocol) |
| NLP | HanLP (中文分词) |

## 项目结构

```
AIbot/
├── src/main/java/com/xdu/aibot/
│   ├── config/                    # 配置类
│   │   ├── AibotProperties.java   # 自定义配置属性
│   │   ├── CommonConfiguration.java  # 核心Bean配置
│   │   ├── MvcConfiguration.java  # CORS配置
│   │   └── RedisMemoryConfig.java # Redis配置
│   ├── constant/                  # 常量定义
│   │   ├── ChatType.java          # 会话类型枚举
│   │   └── SystemConstants.java   # 系统提示词
│   ├── controller/                # 控制器（薄层，仅做参数接收）
│   │   ├── ChatHistoryController.java
│   │   ├── CustomerServiceController.java
│   │   ├── GraphController.java
│   │   └── PdfController.java
│   ├── advisor/                   # AI Advisor
│   │   └── GraphRagAdvisor.java   # 图谱增强Advisor
│   ├── service/                   # 业务逻辑层
│   │   ├── CustomerService.java
│   │   ├── GraphService.java
│   │   ├── PdfService.java
│   │   ├── ChatHistoryService.java
│   │   ├── ExtractionResult.java
│   │   ├── LLMEntityExtractor.java
│   │   └── impl/
│   │       ├── ChatHistoryServiceImpl.java
│   │       ├── CustomerServiceImpl.java
│   │       ├── GraphServiceImpl.java
│   │       └── PdfServiceImpl.java
│   ├── repository/                # 数据仓储层
│   │   ├── ChatHistoryRepository.java
│   │   ├── FileRepository.java
│   │   └── Impl/
│   │       └── GraphPdfFileRepository.java
│   ├── mapper/                    # MyBatis-Plus Mapper
│   │   └── ChatHistoryMapper.java
│   └── pojo/                      # 数据模型
│       ├── entity/
│       │   ├── ChatHistory.java
│       │   ├── Entity.java
│       │   └── Relationship.java
│       └── vo/
│           ├── MessageVO.java
│           └── Result.java
├── src/main/resources/
│   ├── application.yaml           # 主配置文件
│   ├── mcp-servers.json           # MCP服务器配置
│   └── static/                    # 前端页面
│       ├── index.html
│       ├── book.html
│       └── graphrag.html
├── BookQueryMcp/                  # MCP图书查询服务（独立子项目）
└── pom.xml
```

## 环境准备

### 必需服务

1. **JDK 17+**
2. **MySQL 8.0+** — 存储会话历史
3. **Redis 6.0+** — 存储Agent记忆和ChatMemory
4. **Neo4j 5.x** — 知识图谱存储和向量检索
5. **Ollama**（可选） — 本地大模型，不使用则用云端API

### 获取 API Key

- **阿里云 DashScope API Key**：前往 [阿里云百炼平台](https://bailian.console.aliyun.com/) 申请
- **Tavily API Key**（可选）：前往 [Tavily](https://tavily.com/) 申请，用于网络搜索

## 配置说明

### 1. 创建数据库

```sql
CREATE DATABASE aibot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后执行项目根目录下的 `schema.sql` 创建表结构。

### 2. 修改配置文件

编辑 `src/main/resources/application.yaml`，修改以下配置：

```yaml
# MySQL 配置
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/aibot?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root        # 改为你的MySQL用户名
    password: 1234        # 改为你的MySQL密码

  # Redis 配置
  data:
    redis:
      host: 127.0.0.1    # Redis地址
      port: 6379          # Redis端口
      password: your_password  # Redis密码

  # Neo4j 配置
  neo4j:
    uri: neo4j://localhost:7687
    authentication:
      username: neo4j
      password: 12345678  # 改为你的Neo4j密码

  # AI 模型配置
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}  # 设置环境变量或直接填入
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/
      api-key: ${DASHSCOPE_API_KEY}   # 与dashscope相同
```

### 3. 模型选择

通过 `aibot.chat-model-type` 配置选择模型：

```yaml
aibot:
  chat-model-type: cloud   # cloud=阿里云DashScope, ollama=本地Ollama
```

使用 Ollama 模式需要先拉取模型：

```bash
ollama pull qwen3.5:9b
```

### 4. 环境变量

建议通过环境变量设置敏感信息：

```bash
# Linux/macOS
export DASHSCOPE_API_KEY=sk-xxxxxxxx

# Windows PowerShell
$env:DASHSCOPE_API_KEY = "sk-xxxxxxxx"
```

### 5. MCP 服务配置（图书馆预约助手）

图书馆预约助手依赖独立的 MCP 服务 `BookQueryMcp`，需要先构建并启动：

```bash
# 构建 BookQueryMcp
cd BookQueryMcp
../mvnw.cmd clean package -DskipTests

# 启动 MCP 服务（在另一个终端）
java -jar target/BookQueryMcp-0.0.1-SNAPSHOT.jar
```

MCP 服务默认运行在 `8848` 端口，配置在 `src/main/resources/mcp-servers.json` 中。

### 6. Tavily 搜索配置（可选）

如需网络搜索能力，在 `application.yaml` 中配置 Tavily：

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            tavily:
              command: cmd.exe   # Windows用cmd.exe，macOS/Linux用npx
              args: ["/c", "npx", "-y", "tavily-mcp@latest"]
              env:
                TAVILY_API_KEY: your_tavily_api_key
```

## 启动

```bash
# 构建项目
./mvnw.cmd clean package -DskipTests

# 启动主应用
java -jar target/AIbot-0.0.1-SNAPSHOT.jar
```

或开发模式：

```bash
./mvnw.cmd spring-boot:run
```

访问 http://localhost:8080 即可看到首页。

## API 接口

### PDF 知识图谱助手

| 接口 | 方法 | 说明 |
|------|------|------|
| `/ai/pdf/upload/{chatId}` | POST | 上传PDF文件 |
| `/ai/pdf/chat` | POST | 同步问答 |
| `/ai/pdf/chat/stream` | GET | 流式问答（SSE） |
| `/ai/pdf/file/{chatId}` | GET | 下载PDF文件 |
| `/ai/pdf/chat/{chatId}` | DELETE | 删除会话 |
| `/ai/graph/{chatId}` | GET | 获取知识图谱数据 |

### 图书馆预约助手

| 接口 | 方法 | 说明 |
|------|------|------|
| `/ai/service` | POST | 同步问答 |
| `/ai/service/stream` | GET | 流式问答（SSE） |
| `/ai/service/chat/{chatId}` | DELETE | 删除会话 |

### 会话历史

| 接口 | 方法 | 说明 |
|------|------|------|
| `/ai/history/{type}` | GET | 获取指定类型的会话ID列表 |
| `/ai/history/{type}/{chatId}` | GET | 获取指定会话的消息记录 |

### ChatId 格式

ChatId 采用 `{模块编号}_{时间戳}_{随机串}` 格式：
- PDF模块：`1_1713456789_abc123`
- 客服模块：`2_1713456789_abc123`

## 常见问题

**Q: 启动报 Neo4j 连接失败？**
A: 确认 Neo4j 服务已启动，检查端口 7687 是否开放，用户名密码是否正确。

**Q: 上传PDF后知识图谱构建很慢？**
A: LLM 实体抽取是逐页调用的，页数较多时耗时较长。可在 `application.yaml` 中调整 `aibot.graph.traversal-limit`。

**Q: 如何切换到本地模型？**
A: 设置 `aibot.chat-model-type: ollama`，并确保 Ollama 已安装并拉取了 `qwen3.5:9b`或其他兼容模型。

**Q: Tavily 搜索不生效？**
A: 检查 `TAVILY_API_KEY` 环境变量是否设置，以及 `npx` 命令是否可用。
