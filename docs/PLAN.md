# MC Agent 计划与执行约定

> 最后更新：2026-10-23 21:00（UTC+8）
> 当前版本：0.1.0（1.20.1 Forge，客户端）
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
| M1 | A* 寻路 + 避水/避崖/搭桥 | 未开始 |
| M2 | GUI 交互 + 合成/箱子/熔炉 | 未开始 |
| M3 | 分层规划器 + 确定性验证器 | **进行中（契约层）** |
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

## 4. 已拍板的决定

| 日期 | 决定 |
|---|---|
| 2026-10-23 | 验收任务用服主自己的世界，不另建 |
| 2026-10-23 | Planner 与 agent 使用同一模型，默认 DeepSeek |
| 2026-10-23 | 先做契约层（schema + verifier + executor），再接 LLM |
| 2026-10-23 | 所有计划写入本文件；任务开始前先读 |

---

## 5. 风险登记

| 风险 | 对策 |
|---|---|
| 模型规划出做不到的步骤 | prompt 写死能力边界 + schema 校验 + dry-run |
| 坐标过期 | 每节点执行前重新拉状态 |
| 验证器与游戏状态不一致 | 只读客户端真实状态 |
| 死亡/掉物品 | checkpoint + 重规划 |
| 成本失控 | 独立预算 + 硬上限 |
| 任务粒度太碎 | 强制 ≥ 一个宏动作 |

---

## 6. 进度记录

| 日期 | 内容 | 提交 | CI |
|---|---|---|---|
| 2026-10-22 | 0.1.0：观测、动作、查询、记忆、技能、反思 | cb25e35 | 三系统通过 |
| 2026-10-23 | 包命名改为 `mc_agent-<mc>-forge-<ver>.jar` | caf6f6f | Linux/macOS 通过，Windows 待确认 |
| 2026-10-23 | M3 契约层开始 | 待填 | 待填 |
