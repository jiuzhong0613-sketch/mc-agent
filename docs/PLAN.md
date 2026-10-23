# MC Agent 计划与执行约定

> 最后更新：2026-10-23 21:00（UTC+8）
> 当前版本：0.3.0（M1 寻路内核已实现，待实机验证）
> 本文件是唯一计划来源。**任何任务开始前先读本文件；每次里程碑完成后更新本文件。**

---

## 0. 工作约定

1. 开工前先读本文件，确认当前阶段、边界和验收标准。
2. 规划变更先改本文件，再改代码。
3. 每个里程碑结束必须更新：状态、提交号、CI 结果、遗留问题。
4. 不把“编译通过”写成“已完成”；实机未验证的必须标注。
5. 验收任务使用服主自己的世界（单人存档），不另建测试世界。

---

## 1. 产品目标

玩家说一句话，agent 在 Minecraft 里把事做完。例如：

- “帮我造个刷怪塔”
- “帮我挖一组钻石”

### 非目标（当前阶段）

- 不做视觉输入；
- 不支持本地大模型，只走托管 API；
- 不做服务端组件；
- 不承诺公共服务器可用（反作弊/规则风险自担）。

---

## 2. 里程碑总览

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | A* 寻路 + 避水/避崖 | **实现完成，待实机验证（0.3.0）** |
| M2 | GUI 交互 + 合成/箱子/熔炉 | 未开始 |
| M3 | 分层规划器 + 确定性验证器 | **已完成（编译/单测通过，待实机验证）** |
| M4 | 建造宏/蓝图 | 未开始 |
| M5 | 断点续跑 + 成本预算 + 长任务恢复 | 未开始 |

依赖关系：M3 的验收任务必须落在 M1/M2 能力范围内，否则失败原因不在 M3。

---

## 3. M3：分层规划器 + 验证器

### 3.1 目标形态

```
一句话
  → Planner（1 次 LLM + 1 次修复）→ TaskPlan JSON
  → PlanExecutor（确定性）→ 按拓扑执行节点
      → Action / Query / Skill / Macro
      → Verifier 立即验证
  → 失败 → 节点重试 1 次 → 仍失败 → Replanner（≤3 次）
  → 顶层 success 谓词全部为真 → 完成
```

原则：**LLM 只负责规划和重规划，执行与判定不经过模型。**

### 3.2 TaskPlan 契约

```json
{
  "version": 1,
  "goal": "挖 8 个原木",
  "success": [
    {"type": "inventory_count", "item": "minecraft:oak_log", "min": 8}
  ],
  "budget": {"llmCalls": 6, "actions": 400, "seconds": 600},
  "tasks": [
    {
      "id": "t1",
      "title": "扫描附近原木",
      "type": "query",
      "tool": "query_scan",
      "args": {"radius": 8},
      "verify": {"type": "query_true", "path": "types", "contains": "minecraft:oak_log"}
    },
    {
      "id": "t2",
      "title": "挖原木",
      "type": "macro",
      "macro": "mine_n",
      "args": {"block": "minecraft:oak_log", "count": 8, "radius": 8},
      "dependsOn": ["t1"],
      "retries": 1,
      "verify": {"type": "inventory_count", "item": "minecraft:oak_log", "min": 8}
    }
  ]
}
```

任务类型：`action`、`query`、`skill`、`macro`、`assert`。

验证谓词：

| 谓词 | 判定 |
|---|---|
| `inventory_count` | 物品数量 ≥ min |
| `block_at` | 坐标方块 id 匹配 |
| `blocks_count` | 半径内某方块 ≥ min |
| `position_near` | 距离 ≤ tolerance |
| `entity_near` | 半径内实体 ≥ min |
| `query_true` | 查询结果 JSON 路径包含/等于 |
| `action_ok` | 节点动作返回 ok |
| `all` / `any` / `not` | 组合 |

### 3.3 代码结构

```
dev.mcagent.plan/
├─ TaskPlan.java        解析、校验 DAG、序列化
├─ TaskNode.java        id/type/args/dependsOn/verify/retries/status
├─ VerifySpec.java      谓词模型
├─ Verifier.java        谓词求值（依赖 FactSource）
├─ FactSource.java      只读事实接口（背包/方块/位置/实体）
├─ McFactSource.java    Minecraft 实现
├─ ActionPort.java      执行端口（action/query/skill）
├─ McActionPort.java    Minecraft 实现
├─ PlanExecutor.java    拓扑执行、重试、预算、结果
├─ Budget.java          LLM/动作/墙钟计数
├─ PlanStore.java       checkpoint 持久化
└─ PlanResult.java      结果与证据

dev.mcagent.skill.macro/
├─ MineNMacro.java
└─ GotoMacro.java
```

### 3.4 执行语义

1. 校验 id 唯一、依赖存在、无环、success 非空。
2. 拓扑取 ready 节点，执行前重新拉状态，防坐标过期。
3. 执行后立即验证；失败重试 `retries`（默认 1）。
4. 依赖失败的节点跳过；整计划标记失败。
5. 中止条件：预算耗尽、连续 3 次重规划失败、玩家连续死亡 2 次、`/agent stop`。
6. 每节点完成写 checkpoint，支持重启恢复。
7. 顶层 success 全真才算完成。

### 3.5 预算

| 项 | 默认 |
|---|---|
| 规划 LLM | 1 次 + 1 次修复 |
| 重规划 | ≤3 次 |
| 总 LLM | 8 次 |
| 动作数 | 400 |
| 墙钟 | 600 秒 |

### 3.6 验收（使用服主自己的世界）

| # | 目标 | 验证 |
|---|---|---|
| 1 | 走到 20 格内坐标 | position_near |
| 2 | 挖 5 个附近同类方块 | inventory_count |
| 3 | 放 3 个方块成直线 | block_at ×3 |
| 4 | 走到坐标再返回 | position_near |
| 5 | 靠近最近动物到 5 格 | entity_near |
| 6 | 收集 4 个掉落物 | inventory_count |
| 7 | 扫描并报告区域方块数 | query_true |
| 8 | 在指定点搭 2 格柱 | block_at ×2 |
| 9 | 挖 8 个原木（树在附近） | inventory_count |
| 10 | 走到水边但不进入 | position_near + 背包无水桶 |

通过标准：≥7/10；无死循环；平均重规划 ≤3；单任务不超预算；checkpoint 可恢复。

### 3.7 两周排期

| 天 | 交付 |
|---|---|
| 1–2 | TaskPlan/TaskNode/校验 + 单测 |
| 3–4 | Verifier + FactSource + 单测 |
| 5–6 | PlanExecutor + Budget + PlanStore + `/agent plan` |
| 7–8 | Planner prompt + JSON 修复 + dry-run |
| 9–10 | MineN/Goto 宏 |
| 11–12 | 10 个验收任务 + 调参 + 报告 |
| 13–14 | 文档 + 0.2.0 打包 |

---

## 4. M1：A* 寻路（目标版本 0.3.0）

### 4.1 范围

- 纯客户端，只在已加载区块内规划；
- 点到点可靠移动：绕墙、上台阶、跳小空隙、避水、避崖；
- **不做**：挖方块开路、搭桥/垫脚、开门、爬梯子、游泳、船/矿车、跨维度；
- 目标不是“最短”，而是“能走通且不受伤”。

### 4.2 世界模型

- 2.5D 网格：X/Z 平面 + 每格允许的站立高度（当前高度 ±3）；
- 采样半径 ≤ 64 格；未加载区块标记为不可达；
- 每格标记：solid、fluid（水/岩浆）、hazard（火、仙人掌、甜浆果）、stepUp、drop、climbable（暂不支持）；
- 快照在客户端线程完成，A* 在 worker 线程跑，避免卡渲染。

### 4.3 移动原语（图的边）

| 原语 | 条件 | 代价 |
|---|---|---|
| 平走 1 格 | 目标脚/头为空，脚下实心 | 1.0 |
| 斜走 1 格 | 两个正交邻格都可走（防切角） | 1.414 |
| 上 1 格台阶 | 目标高 1，头顶空 | 1.3 |
| 下落 ≤3 格 | 落点空、脚下实心 | 1.0 + 0.5/格 |
| 跳 1 格空隙 | 同高、对岸可站 | 1.3 |
| 疾跑跳 2 格 | 直线、两侧无阻挡 | 1.8 |
| 进入水/岩浆/火/仙人掌 | — | 禁止 |
| 关闭的门/栅栏/墙 | — | 禁止 |
| 未知区块 | — | 禁止 |

### 4.4 代价与启发式

- 基础代价 + 危险邻接 +2（贴岩浆、悬崖边）；
- 启发式：octile 距离；
- 不可达时返回“到最近可达点”的部分路径 + 原因，不返回空。

### 4.5 算法与预算

- A* + 二叉堆 + closed set（long hash）；
- 上限：展开 50,000 节点、80ms、路径 256 步；
- 超出预算 → 返回部分路径 + budget 原因；
- 每次规划前检查起点/终点是否可站，失败快速返回。

### 4.6 执行（PathFollower）

- 新动作 path_to {x,y,z,tolerance}；
- 每 tick：看向下一节点 → 按住前进 → 需要时跳；
- 转向复用 18°/tick，不瞬移；
- 卡住 20 tick、进入水/岩浆、掉落 >3 格 → 中止并返回证据；
- 到达容差 0.6 格；
- 执行中每 10 tick 检查是否偏离路径，偏离 >1.5 格则重新规划。

### 4.7 接口

- 查询 query_path {x,y,z} → {reachable, length, partial, reason, sample}；
- 动作 path_to；
- goto 宏改为调用 path_to；
- mine_n 接近目标改用 path_to；
- Planner prompt 更新：允许 path_to，仍禁止挖/放开路。

### 4.8 代码结构

```
dev.mcagent.path/
├─ BlockGrid.java        网格与标记
├─ GridSnapshot.java     客户端线程采样
├─ MoveRules.java        可走性/代价/边生成
├─ AStar.java            纯算法，无 MC 依赖
├─ Path.java             路径与结果
├─ PathService.java      快照缓存、worker 线程
└─ PathFollower.java     动作实现

src/test/java/dev/mcagent/path/
├─ MoveRulesTest.java
├─ AStarTest.java        平地/墙/空隙/水/悬崖/不可达
└─ PathFollowerTest.java 假端口
```

### 4.9 验收（服主自己的世界）

| # | 场景 | 标准 |
|---|---|---|
| 1 | 40 格内 20 个随机目标 | ≥18 到达 |
| 2 | 有墙/房子的路线 | 绕行，不撞墙 |
| 3 | 水边目标 | 不进入水 |
| 4 | 3 格高差 | 不摔伤（>2 心算失败） |
| 5 | 目标不可达 | 10 秒内返回 partial + 原因 |
| 6 | 执行中放置障碍 | 10 tick 内重规划 |
| 7 | 搜索性能 | p95 < 100ms（64×64×5） |
| 8 | 连续 10 次规划 | 无内存增长、无卡顿 |

### 4.10 排期（两周）

| 天 | 交付 |
|---|---|
| 1–2 | BlockGrid + MoveRules + 单测 |
| 3–4 | AStar + 单测（6 类地形） |
| 5–6 | GridSnapshot + PathService + query_path |
| 7–8 | PathFollower + path_to |
| 9–10 | goto / mine_n 接入 + planner prompt |
| 11–12 | 实机验收 8 项 + 调参 |
| 13–14 | 文档 + 0.3.0 打包 |

### 4.11 风险

| 风险 | 对策 |
|---|---|
| 客户端看不到未加载区块 | 半径限制 + 明确返回 unreachable |
| 跳跃物理与网格假设不一致 | 实机校准跳距/跳高，宁可少跳 |
| 移动实体挡路 | 执行时检测碰撞，卡住重规划 |
| 服务器反作弊 | 拟人转向、限速，不承诺公共服 |
| 内存/耗时 | 2.5D、节点上限、快照缓存 |

---

## 5. 已拍板的决定

| 日期 | 决定 |
|---|---|
| 2026-10-23 | 验收任务用服主自己的世界，不另建 |
| 2026-10-23 | Planner 与 agent 使用同一模型，默认 DeepSeek |
| 2026-10-23 | 先做契约层（schema + verifier + executor），再接 LLM |
| 2026-10-23 | 所有计划写入本文件；任务开始前先读 |
| 2026-10-23 | M1 采用 2.5D A*，v1 不做挖/放/门/梯子/游泳 |

---

## 6. 风险登记

| 风险 | 对策 |
|---|---|
| 模型规划出做不到的步骤 | prompt 写死能力边界 + schema 校验 + dry-run |
| 坐标过期 | 每节点执行前重新拉状态 |
| 验证器与游戏状态不一致 | 只读客户端真实状态 |
| 死亡/掉物品 | checkpoint + 重规划 |
| 成本失控 | 独立预算 + 硬上限 |
| 任务粒度太碎 | 强制 ≥ 一个宏动作 |

---

## 7. 进度记录

| 日期 | 内容 | 提交 | CI |
|---|---|---|---|
| 2026-10-22 | 0.1.0：观测、动作、查询、记忆、技能、反思 | cb25e35 | 三系统通过 |
| 2026-10-23 | 包命名改为 `mc_agent-<mc>-forge-<ver>.jar` | caf6f6f | Linux/macOS 通过，Windows 待确认 |
| 2026-10-23 | 0.2.0：契约层 + Planner/Replanner + mine_n/goto + plan 命令 | 341813a | 三系统通过（15 单测） |
| 2026-10-23 | 0.3.0：BlockGrid/MoveRules/A*、query_path、path_to、goto 接入 | 1b3cf04 | 三系统通过（23 单测） |
