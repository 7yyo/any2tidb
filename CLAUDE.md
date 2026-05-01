# CLAUDE.md — any2tidb

## Skill dispatch rules

收到用户请求后，先走决策树匹配场景，再调用对应 skill。不要跳过 skill 直接写代码。

### 决策树

```
用户请求
├─ 纯理解/探索 ("how does X work", "explain Y", "summarize Z")
│   → 不调 skill，直接探索代码 + 回答
│
├─ 单行/琐碎修改 (typo, string, config value, 改一个常量)
│   → 不调 skill，直接改
│
├─ Bug/异常/不工作 ("fix X", "X is broken", 报错, 测试失败)
│   → systematic-debugging (先诊断，禁止猜测式修复)
│   → TDD (补复现测试)
│   → simplify (自查改动质量)
│   → verification-before-completion (跑验证再声称完成)
│
├─ 新功能/加行为 ("add X", "implement Y", "create Z", "支持...")
│   → brainstorming (先理需求、定边界)
│   → writing-plans (写出可执行的步骤)
│   → (可选) using-git-worktrees (隔离工作区)
│   → TDD (RED → GREEN → REFACTOR)
│   → (多文件/独立子任务) subagent-driven-development
│   → simplify
│   → verification-before-completion
│   → requesting-code-review
│   → finishing-a-development-branch
│
├─ 重构 (不改行为, "refactor X", "clean up Y", "extract Z", "统一...")
│   → brainstorming (确认重构目标和边界)
│   → writing-plans
│   → simplify
│   → verification-before-completion (确认无回归)
│
├─ 多独立任务 (A、B、C 互不依赖)
│   → dispatching-parallel-agents
│
├─ 收到 code review 反馈
│   → receiving-code-review (严谨验证每一条，不盲目接受)
│
├─ 实现完成、准备合并
│   → verification-before-completion
│   → requesting-code-review
│   → finishing-a-development-branch
│
└─ 编写/修改 skill
    → writing-skills
```

### 简单/琐碎修改的定义

以下情况视为琐碎，不触发 skill 流程：
- 修单行 typo、字符串文案、日志内容
- 改单个常量值、配置值
- 加一行日志或注释
- 文件重命名（纯路径变更）

以下情况**不是**琐碎，必须走 skill 流程：
- 任何涉及逻辑/行为的修改
- 任何涉及类型/接口/API 的修改
- 批量改名（可能影响引用）
- 改 SQL 或数据库相关代码

### 调用时机细节

- **brainstorming**: 在进入 EnterPlanMode 之前就要调用。不要先探索代码再决定用不用——先调 skill 再探索。
- **TDD**: 在写任何实现代码前调用。即使 "改动很小" 也至少要补一个覆盖变更的测试。
- **systematic-debugging**: 遇到 bug 的第一个动作。不要先读代码猜原因，让 skill 指导诊断流程。
- **verification-before-completion**: 声称 "完成/修好/通过了" 之前必须调用。先跑验证，输出结果，再下结论。
- **requesting-code-review**: 完成一个完整功能点后调用，不是在每次 commit 后调用。

### 多 skill 链式调用

当触发链式流程 (如 `brainstorming → writing-plans → TDD → ...`) 时：
- 每个 skill 完成后，自然衔接下一个，不需要等用户说 "继续"
- 但如果 skill 之间有不确定性 (如 plan 需要用户确认)，停下来等，不要自作主张

## 项目约束

- 所有新建文件必须在 `/Users/sev7nyo/code/any2tidb/tmp/` 下，禁止写 `/tmp`
- 写代码前先查文档/验证 API 用法，不要凭记忆猜测
- memory 只沉淀有价值的、跨 session 的洞察，不灌原始笔记
- **mem9-recall**: 不主动调。只在用户明确要求 "查记忆" 或 auto-memory 明显缺信息时调。auto-memory（MEMORY.md + linked files）已随 session 启动自动加载，不需要重复查询
- **mem9-store**: 不主动调。只在用户明确说 "记住" 且涉及跨项目共享时调。仅限本项目的洞察走本地 auto-memory
