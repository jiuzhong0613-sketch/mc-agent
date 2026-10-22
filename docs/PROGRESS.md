# MC Agent 开发进度文档

> 最后更新：2026-10-22 11:10（UTC+8）
> 仓库：https://github.com/jiuzhong0613-sketch/mc-agent
> 远端 main：`c21502a`（三系统 CI 通过）
> 版本：`0.1.0`，协议 `mc-agent/1`
> 状态：**可编译、未实机验证**

---

## 1. 一句话定位

给 AI 一个 Minecraft 身体：客户端模组把游戏状态输出成结构化 JSON，把语义动作作为输入，内置 agent 通过**托管 LLM API** 决策。没有截图，没有视觉模型，没有本地模型。

---

## 2. 目标与硬约束

| 项 | 决定 |
|---|---|
| 形态 | 纯客户端 Forge 模组，不装服务端 |
| 使用者 | 玩家自己（单人、多人服都可以） |
| 输入 | 观测 = 结构化状态；动作 = 游戏语义动作 |
| 模型 | **只调用托管 API Key**，明确不支持本地模型 |
| 平台 | Windows / macOS / Linux（Java 版） |
| 版本 | 首发 Minecraft 1.20.1 + Forge 47.x |
| 路线 | 永远只保留 mod 适配器，不做协议级 headless bot |
| 许可 | MIT，public |

---

## 3. 里程碑与提交记录

| 时间 | 提交 | 内容 | CI |
|---|---|---|---|
| 10:50 | `fb7532a` | 初始骨架：桥、观测、动作队列、文档、CI | 失败（2 处编译错误） |
| 10:52 | `81d1a60` | 修 `inventory` 返回类型、聊天改走 `connection.sendChat` | 通过 |
| 10:54 | `d0796d1` | CI 扩展为 Linux/Windows/macOS 矩阵 | 三系统通过 |
| 10:59 | `0c9e4a3` | 加内置 agent、API 网关、Key 存储、LLM 客户端 | 失败（配置行损坏） |
| 11:06 | `55b0b42` | 去掉 TOML Key 字段，改为 env/文件；修复编译 | 三系统通过 |

---

## 4. 仓库结构

```
mcagent/
├─ build.gradle / gradle.properties / settings.gradle
├─ gradle/wrapper/ + gradlew(.bat)
├─ src/main/java/dev/mcagent/
│  ├─ McAgentMod.java          入口、事件、/agent 命令
│  ├─ AgentConfig.java         ForgeConfigSpec
│  ├─ AgentRuntime.java        生命周期、事件环、桥管理
│  ├─ ClientThread.java        客户端线程调度（超时）
│  ├─ act/  Action, ActionQueue, Actions(13 个动作)
│  ├─ bridge/ BridgeServer, Json
│  ├─ obs/  Observation
│  └─ llm/  AgentLoop, LlmClient, ApiGate, SecretStore, StateSummary, Tools
├─ src/main/resources/META-INF/mods.toml, pack.mcmeta
├─ docs/ ARCHITECTURE, PROTOCOL, PORTING, LLM, PROGRESS
└─ .github/workflows/build.yml
```

规模：16 个 Java 文件，约 2100 行；文档 5 篇。

---

## 5. 架构与数据流

```
client tick ──> AgentRuntime.tick()
                  ├─ ActionQueue.tick()  (一 tick 一个动作)
                  └─ BridgeServer (可选，默认关闭)

/agent start ──> AgentLoop (独立线程)
                  ├─ StateSummary.build()  ── ClientThread ──> Observation
                  ├─ LlmClient.chat()      ── HTTPS ──> 托管 API
                  └─ tool_call ──> AgentRuntime.submit ──> ActionQueue
                                        └─ awaitResult(id) ──> 回填 tool 消息
```

关键设计：

- **线程**：只有客户端线程碰游戏对象；HTTP 与 agent 线程通过 `ClientThread.call` 跳转并带超时。
- **输入注入**：不模拟操作系统按键，直接改 `KeyMapping.setDown(...)`，让原版 `KeyboardInput` 在下一 tick 读取，物理与碰撞保持原版。
- **动作串行**：同一时间只允许一个动作，避免按键与挖掘进度互相打架。
- **取消安全**：动作完成、超时、取消、异常都会调用 `releaseKeys`；挖掘会 `stopDestroyBlock`。

---

## 6. 已实现功能

### 6.1 状态观测（`/state`）

| 字段 | 内容 | 上限 / 说明 |
|---|---|---|
| `protocol / tick` | 协议版本、客户端 tick | |
| `inGame / paused / screen` | 是否在游戏、是否暂停、当前界面 | |
| `dimension` | 维度 id | |
| `player` | 名字、UUID、位置、速度、朝向、在地面、潜行、疾跑、血量、饥饿、饱和、经验、氧气、死亡、游戏模式 | |
| `inventory` | 选中槽位 + 非空槽（物品 id、数量、耐久） | 容器全部槽位 |
| `lookingAt` | 准星命中的方块（id/面/坐标）或实体 | |
| `blocks` | 周围非空气方块 | 半径 5，最多 800，按距离排序 |
| `entities` | 实体 id、UUID、类型、名字、坐标、距离、血量 | 半径 32，最多 48 |
| `menu` | 打开的容器：id + 非空槽 | 只读，最多 90 槽 |
| `events` | 聊天等事件环形缓冲 | 最近 40 条 |
| `capabilities` | 能力清单 | 由桥附加 |

### 6.2 动作（`/act` 或 agent tool_call）

| type | 参数 | 行为 | 限制 |
|---|---|---|---|
| `look` | yaw, pitch | 立即转向 | 受 `allowMove` 控制（语义待修） |
| `look_at` | x,y,z | 看向坐标 | 同上 |
| `move` | forward, strafe, jump, sneak, sprint, ticks | 按住输入 N tick | ticks 1..400 |
| `walk_to` | x,y,z,tolerance=0.8,sprint=true | 面向目标直走，碰墙跳 | **直线，不是寻路**；600 tick 超时；80 tick 无进展判卡住 |
| `mine` | x,y,z | 持续挖掘直到空气 | 4.5 格内；600 tick 超时 |
| `place` | x,y,z,face=up | 对相邻面使用手持物品 | 4.5 格内 |
| `use` | hand=main/offhand | 右键一次 | |
| `attack` | entity | 攻击实体一次 | 默认关闭；4.5 格内 |
| `select_slot` | slot 0..8 | 切换快捷栏 | |
| `chat` | text | 发聊天 | 默认关闭；最多 256 字符 |
| `drop` | all | 丢弃手持 | |
| `respawn` | — | 死亡后重生 | |
| `wait` | ticks | 空转 | 1..400 |
| `stop` | — | 取消当前并清空队列 | |

所有动作返回 `{id, type, ok, ticks, data?, error?}`，失败原因包括 `out of reach`、`timeout`、`stuck at distance x`、`... disabled by config`。

### 6.3 内置 Agent（`/agent start <目标>`）

1. 取压缩状态（`StateSummary`）：位置/维度/血量/饥饿/手持/背包聚合/准星/方块类型计数与最近坐标/实体/事件。
2. 调托管 API（OpenAI 兼容 `/chat/completions`），带 11 个 tool 定义。
3. 模型返回 tool_call → 入队执行 → 等待结果（45s）→ 作为 `role:tool` 回填。
4. 循环直到 `done`、无 tool_call、步数上限、错误或 `/agent stop`。

护栏：`maxSteps=20`、`maxTokens=700`、请求超时 60s、步间 250ms、429/5xx 重试一次（4s）、401/403 立即停止。聊天栏只输出开始、结束、错误。

### 6.4 API Key 管理

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | 环境变量 `MCAGENT_API_KEY` | 推荐，启动器 profile 里设置 |
| 2 | `config/mcagent/secret.txt` | POSIX 写入时 0600 |
| — | TOML | **故意不支持**，避免随整合包/截图泄露 |

- 每次请求现读，不落日志、不进 prompt、不进 `/state`、不进桥响应。
- 没有聊天栏输入 Key 的命令（命令历史会被记录）。
- `/agent key` 只返回 `set` / `missing` 和读取位置。

### 6.5 托管 API 强制

`ApiGate` 每次请求前校验：

- 必须 `https`；
- 主机不得为 `localhost`、`127/8`、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`::1`、`.local`；
- 命中直接报 `local endpoints are not supported`，无降级分支。

支持任何 OpenAI 兼容托管服务：OpenAI、DeepSeek、Moonshot、智谱、OpenRouter、硅基流动等，示例见 `docs/LLM.md`。

### 6.6 HTTP 桥（可选，默认关闭）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/health` | 状态、MC 版本、capabilities |
| GET | `/state` | 观测 + lastResult |
| GET | `/result` | 最近一次动作结果 |
| POST | `/act` | 入队一个动作（202/409/400） |
| POST | `/cancel` | 取消当前并清空队列 |

绑定 `127.0.0.1`，可配 `X-McAgent-Token`，请求体上限 64KB，4 个守护线程。capabilities 当前为：`obs.player/inventory/blocks/entities.v1`、`act.look/move/walk_to/mine/place/use/attack/chat/select_slot/drop.v1`。

### 6.7 命令

| 命令 | 作用 |
|---|---|
| `/agent` | 显示状态 |
| `/agent start <目标>` | 启动内置 agent |
| `/agent stop` | 中断 |
| `/agent status` | 运行状态、步数、模型、Key 是否存在 |
| `/agent key` | Key 读取位置 |

### 6.8 配置项

| 段 | 键 | 默认 | 说明 |
|---|---|---|---|
| bridge | enabled | **false** | 外部程序驱动时才开 |
| bridge | port | 25580 | 仅回环 |
| bridge | token | 空 | 可选共享令牌 |
| bridge | maxQueue | 32 | 队列上限 |
| observation | radius | 5 | 方块扫描半径 |
| llm | baseUrl | https://api.openai.com/v1 | 必须 https 且非本地 |
| llm | model | gpt-4o-mini | 需支持 tool calling |
| llm | maxSteps | 20 | 单次运行模型调用上限 |
| llm | maxTokens | 700 | 单次响应上限 |
| llm | temperature | 0.2 | |
| llm | timeoutSeconds | 60 | |
| permissions | reach | 4.5 | 交互距离 |
| permissions | allowMove | true | |
| permissions | allowBreak | true | |
| permissions | allowPlace | true | |
| permissions | allowAttack | **false** | |
| permissions | allowChat | **false** | |

### 6.9 主动感知与记忆（新增，未实机验证）

- 状态自带 `coverage`，明确“只看到半径 5、仅已加载区块”；
- 9 个只读查询：`query_block/scan/entities/inventory/raycast/can_stand/surface/route/env`；
- 查询有 tick 缓存与每步预算（默认 6 次）；
- 动作失败自动补探测（`autoProbe`）：walk_to→route、mine/place→block、attack→entities；
- 每世界记忆文件：facts / lessons / skills / episodes，原子写、上限、按关键词+置信度+新近度检索；
- `remember` / `recall` 工具，启动时自动注入最多 900 字记忆摘要；
- `done` 可带 `lesson`，下一次运行生效；
- 拟人化：转向速度 18°/tick、步间 250ms，`look` / `look_at` 需要多 tick 完成。

### 6.10 构建与 CI

- JDK 17（Temurin），Gradle Wrapper 8.8，ForgeGradle 6。
- GitHub Actions 矩阵：Ubuntu / Windows / macOS；`./gradlew build`；产物上传为 artifact。
- 最新成功运行：39714892226（三系统全绿）。

---

## 7. 质量与验证状态

| 项 | 状态 |
|---|---|
| 三系统编译 | 通过 |
| 单元测试 | 无 |
| 实机启动 | 未验证 |
| agent 端到端 | 未验证（需要真实 Key 和客户端） |
| 主动感知 / 记忆 / 拟人转向 | 未验证（本轮新增） |
| 多人服测试 | 未做 |
| 性能/内存 | 未测 |
| 发布包（Modrinth/CurseForge） | 未做 |

---

## 8. 已知缺陷（按优先级）

### P0（影响可用性）

1. **未实机跑过**：编译通过不等于能玩，模型循环、按键注入、准星命中都未在客户端验证。
2. **`walk_to` 不是寻路**：直线走向目标，遇水、悬崖、围墙不会绕；80 tick 无进展直接失败。
3. **单人暂停仍消耗 token**：游戏暂停（Esc）时 tick 停止，但 agent 线程不会感知 `paused`，会继续请求。
4. **无全局取消热键**：只能 `/agent stop` 或 `/cancel`。
5. **死亡/维度切换/断线未处理**：动作会一直超时，agent 可能连续失败。

### P1（一致性与体验）

6. `capabilities` 不完整：缺 `respawn`、`wait`、`obs.menu`。
7. `look` / `look_at` 被 `allowMove` 限制，语义不对。
8. `use` 没有距离校验（`mine` / `place` / `attack` 有）。
9. 队列满判断只看排队数，没算正在执行的动作。
10. 容器只能读槽位，不能点击/合成/存取。
11. 无 token 用量与费用统计。
12. 无配置界面，改配置需要关游戏改 TOML。
13. 无 i18n，只有英文。
14. 无单元测试；`ActionQueue`、`ApiGate`、`StateSummary` 都可以脱离游戏测试。

### P2（架构）

15. **PORTING.md 声称的 SPI 尚不存在**：目前 core 与 Forge 仍是文件级耦合，换加载器要改 import，不是改接口实现。
16. `SecretStore` 残留未使用的 `AgentConfig` import。
17. 无版本矩阵机制（Stonecutter），1.21 需要复制代码。
18. 无日志分级与调试开关。

---

## 9. 安全与合规风险

| 风险 | 说明 | 当前缓解 |
|---|---|---|
| Key 泄露 | 客户端模组无法防其他模组、崩溃报告、内存 dump | 独立文件 0600、不进日志、不进 prompt、无聊天输入 |
| 费用失控 | 模型循环可能反复重试 | maxSteps、maxTokens、单次重试、401 停止 |
| 服务器规则 | 公共服可能禁止自动化 | README 明示；攻击/聊天默认关闭 |
| 反作弊 | 客户端行为可能被判异常 | 未做拟人化；不承诺绕过 |
| 模型不稳定 | 工具调用格式错误、幻觉坐标 | 参数校验、动作失败回填、终止工具 |

---

## 10. 下一步计划

### P0 实机验证（立即做）

在单人创造模式跑 5 个场景并记录结果：

1. `/agent start "走到 10 格外"` → 位置误差不超过 1 格；
2. 挖一个方块 → 方块变空气且掉落物出现；
3. 放置一个方块 → 目标位置出现手持方块；
4. 攻击一只动物 → 血量下降；
5. 失败注入（不可达坐标）→ 80 tick 内报 stuck，不无限重试。

**验收**：5 项至少 4 项通过；失败项写进 ISSUES。

### P1 修缺陷

- 暂停检测：`mc.isPaused()` 时挂起循环（不计步、不发请求）；
- 补全 capabilities；
- `look` 改由独立 `allowLook`（默认 true）；
- `use` 加距离校验；
- 队列满计入 current；
- 加 `K~ 键紧急停止；
- 死亡/断线自动停。

### P2 测试体系

- 纯 JVM 单测：`ApiGate`（12 个主机样例）、`ActionQueue`（超时/取消/通知）、`StateSummary`（固定 JSON → 快照）；
- Forge GameTest：mine/place 最小闭环；
- CI 增加 test 任务。

### P3 第二个平台

- Fabric 1.20.1，先抽 Platform 接口（tick、命令、配置、聊天事件）；
- 验收：只改入口与 3 个 helper，core 不动。

### P4 发布

- 版本 0.2.0，CHANGELOG；
- Modrinth/CurseForge 页面、截图、安装说明；
- Release 附 jar。

### P5 版本矩阵

- 引入 Stonecutter，目标 1.20.1 + 1.21.x（Fabric/NeoForge）；
- 适配层限定在 Actions / Observation / 入口。

---

## 11. 运行手册（当前可用）

### 构建

```bash
./gradlew build          # JDK 17
# 产物：build/libs/mcagent-0.1.0.jar
```

### 安装

1. Minecraft 1.20.1 + Forge 47.x；
2. jar 放入 `.minecraft/mods`；
3. 启动游戏，进入世界；
4. 设置 Key（二选一）：
   - 启动器环境变量 `MCAGENT_API_KEY`；
   - 写入 `config/mcagent/secret.txt`。
5. `/agent start "你的目标"`。

### 外部程序驱动（可选）

```toml
[bridge]
enabled = true
```

```bash
curl -s http://127.0.0.1:25580/health
curl -s http://127.0.0.1:25580/state
curl -s -X POST http://127.0.0.1:25580/act -H 'Content-Type: application/json' \
  -d '{"type":"walk_to","x":120,"y":68,"z":-34}'
```

### 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即崩，Mixin 69 | 用了 Java 21/25 | 换 Java 17 |
| `/agent start` 提示 no API key | 环境变量/文件都没读到 | 检查路径与权限 |
| `local endpoints are not supported` | baseUrl 是本地/私网 | 换托管地址 |
| 动作一直 timeout | 目标不可达/被方块挡住 | 先 `walk_to` 靠近 |
| 401/403 | Key 无效或余额不足 | 换 Key |
| 429 | 供应商限流 | 等一会，或换模型 |

---

## 12. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-22 | 初版：骨架、观测、动作、桥、内置 agent、三系统 CI |
