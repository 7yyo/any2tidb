# HTTP 控制平面 & 任务管理 设计文档

## 概述

当前 any2tidb 的 sync 模式只能前台运行、Ctrl+C 停止，用户感知状态仅靠日志。本设计为长驻进程（sync/snapshot）加入嵌入 HTTP 控制平面，并新增 CLI 管理命令，实现运行时查询、优雅停止、多任务管理。

---

## 架构

```
┌─ CLI 管理命令 (新) ──┐     ┌─ 现有模式 ───────────────┐
│  sqlserver status     │     │  schema / dump / snapshot │
│  sqlserver stop       │     │  sync ──────┐            │
│  sqlserver list       │     │             │            │
└───────────┬───────────┘     └─────────────┼────────────┘
            │                               │
            │ 读 pid/*.port                  │ 启动时绑定端口
            │ HTTP 请求                      │ 写 pid/<task>.port
            │                               │
            ▼                               ▼
   ┌────────────────────────────────────────────────┐
   │              HttpControlPlane                   │
   │  GET /health  │  GET /status  │  POST /stop    │
   └──────────────────────┬─────────────────────────┘
                          │
                          │ 查询 lag (SQL Server 直连)
                          │ 管理 CountDownLatch / shuttingDown
                          ▼
                    SyncEngine(s)
```

新增三个独立组件：

| 组件 | 职责 | 文件 |
|------|------|------|
| `HttpControlPlane` | 嵌入 HTTP server、端口分配/释放、.port 文件读写、lag 查询 | `src/main/java/com/tool/control/` |
| `TaskManager` | `status`/`stop`/`list` 的 CLI 逻辑：读 .port、发 HTTP、格式化输出 | `src/main/java/com/tool/control/` |
| `SyncStep` 改动 | 启动时创建 HttpControlPlane，集成 `/stop` 信号 | 修改现有文件 |

---

## 任务名

### --task-name

```
java -jar any2tidb.jar sqlserver sync --task-name hrdb-prod --databases HRDB &
```

- 默认值：`any2tidb_<dbName>`（单库）/ `any2tidb_sync`（多库）
- 用途：.port 文件名、日志前缀、`/status` 返回的 task 字段、CLI 管理命令识别目标

### .port 文件

路径：`pid/<task-name>.port`，文件内容为端口号（纯文本，一行整数）。

生命周期：

```
sync 启动 → auto-port 分配 → 写 pid/<task>.port
                                                ← 进程运行中
POST /stop 或 Ctrl+C → engine.close() → 删 pid/<task>.port → 退出
```

- 进程退出时删除。文件存在 = 任务在运行。
- 如果进程被 kill -9 杀掉，.port 文件残留。`list`/`status` 遇到该端口无响应时自动清理。

---

## HTTP 端点

### 基础

- 实现：`com.sun.net.httpserver.HttpServer`（JDK 自带，零外部依赖）
- 超时：所有 handler 5 秒内返回
- 所有响应 Content-Type: application/json

### GET /health

存活检查。返回 200。

```json
{"status": "ok"}
```

### GET /status

返回任务当前状态和数据库级复制详情。

```json
{
  "task": "hrdb-prod",
  "source": "sqlserver",
  "mode": "sync",
  "state": "running",
  "uptimeSeconds": 9252,
  "httpPort": 54321,
  "databases": [
    {
      "name": "HRDB",
      "state": "streaming",
      "lagSeconds": 3,
      "lastEventTime": "2026-05-02T15:30:01"
    }
  ]
}
```

lag 查询逻辑：从 SyncSink 收集每个数据库的 last commit LSN，在 /status handler 中通过 JDBC 直连 SQL Server，调用 `sys.fn_cdc_map_lsn_to_time` 和 `DATEDIFF` 计算 lag 秒数。数据来源：

- `lastCommitLsn`：SyncSink.handleBatch() 中解析 Debezium 事件 `source.commit_lsn` 字段
- `uptimeSeconds`：HttpControlPlane 记录启动 Instant

此逻辑从之前删除的 `logLag()` 方法恢复，改为按需触发（HTTP 请求驱动，不再每 30 秒定时打印）。

### POST /stop

优雅停止。返回 200 确认。

```json
{"stopped": true, "task": "hrdb-prod"}
```

内部流程：

```
收到 POST /stop
  → 设置 shuttingDown.compareAndSet(false, true)
  → 遍历所有 engine，逐个 close()
  → engine.close() 内部 flush offset 到文件
  → allDone.countDown()
  → SyncStep 主线程醒来，正常清理
  → 删 pid/<task>.port
  → HttpServer.stop(0)
  → JVM 退出
```

与 Ctrl+C 的区别：`/stop` 是同步操作——shutdown hook 在 `engine.close()` 之后才返回 HTTP 响应，用户得到确认。Ctrl+C 在 shutdown hook 中执行相同的 close 流程，但可能打断当前批次。

---

## 端口分配

### auto-port（默认）

```
new ServerSocket(0).localPort  →  得到空闲端口  →  关闭 socket  →  HttpServer.bind(该端口)
```

- 启动时打印：`[INFO] task=hrdb-prod  HTTP  control: http://localhost:54321/`
- 写 `pid/hrdb-prod.port`，内容 `54321\n`

### 手动指定 --http-port

```
java -jar any2tidb.jar sqlserver sync --http-port 8080 ...
```

- 绑定失败时报错退出：`Error: port 8080 is already in use`
- 仍然写 `pid/<task>.port`

---

## CLI 管理命令

语法保持 `any2tidb <source> <mode>` 结构。管理命令与 source 相关（`sqlserver` 的 status 需要 SQL Server 特有 LSN 查询），source 参数有意义。

### list

```
any2tidb sqlserver list
```

扫 `pid/` 目录下所有 `.port` 文件，逐一发 `GET /health`，存活则发 `GET /status` 取详情。

文本输出（照 TiUP `cluster list` 表格式）：

```
Name            Mode    Uptime    Port
hrdb-prod       sync    2h 34m    54321
inventory-mig   sync    1h 12m    54322
```

无运行任务时：

```
No running tasks.
```

`--json` 输出：

```json
{
  "source": "sqlserver",
  "tasks": [
    {"name": "hrdb-prod", "mode": "sync", "uptimeSeconds": 9252, "httpPort": 54321},
    {"name": "inventory-mig", "mode": "sync", "uptimeSeconds": 4330, "httpPort": 54322}
  ]
}
```

实现要点：

- 只扫 `pid/` 目录下 `*.port` 文件名，提取任务名
- 对每个端口发 HTTP 请求，超时 2 秒
- 连接失败或超时的端口：自动删掉残留 `.port` 文件（进程被 kill -9 后的垃圾回收）

### status

```
any2tidb sqlserver status --task-name hrdb-prod
```

读 `pid/hrdb-prod.port` → `GET http://localhost:<port>/status` → 格式化。

找到任务：

```
Task Name:    hrdb-prod
Mode:         sync (sqlserver → TiDB)
Uptime:       2h 34m 12s
HTTP Port:    54321

Database      State       Lag    Last Event
HRDB          streaming   3s     2026-05-02 15:30:01
Inventory     streaming   0s     2026-05-02 15:30:04
```

未找到（.port 文件不存在）：

```
Task hrdb-prod is not running.
```

`.port` 文件存在但 HTTP 无响应（进程崩溃但文件残留）：

```
Task hrdb-prod appears to have crashed (port 54321 not responding).
Cleaned up stale pid/hrdb-prod.port.
```

`--json` 输出同 `/status` HTTP 响应体。

### stop

```
any2tidb sqlserver stop --task-name hrdb-prod
```

读 `.port` → `POST http://localhost:<port>/stop` → 等待响应。

成功：

```
Task hrdb-prod stopped.
```

任务不存在：

```
Task hrdb-prod is not running.
```

---

## 文件变更清单

### 新建

| 文件 | 说明 |
|------|------|
| `src/main/java/com/tool/control/HttpControlPlane.java` | HTTP server 的创建、端点注册、端口分配、.port 文件读写、lag 查询 |
| `src/main/java/com/tool/control/TaskManager.java` | `list`/`status`/`stop` CLI 管理命令的调度逻辑 |

### 修改

| 文件 | 改动 |
|------|------|
| `src/main/java/com/tool/App.java` | 注册 `status`/`stop`/`list` 三种新模式到 MODES、knownFlags、help text、run() dispatch |
| `src/main/java/com/tool/sync/SyncStep.java` | 启动时创建 HttpControlPlane，集成 `/stop` 信号；恢复 LSN 收集（上次删除的 lastCommitLsn 追踪）|
| `src/main/java/com/tool/sync/SyncSink.java` | 恢复 `commitLsn` 解析与 `AtomicReference` 记录，供 /status 查询 |
| `src/main/java/com/tool/snapshot/sink/SnapshotJsonParser.java` | 恢复 `ParsedRecord.commitLsn` 字段 |
| `src/main/java/com/tool/SyncRunner.java` | 传入 `--task-name` 和 `--http-port` 参数 |

---

## 配置

新增 CLI flags：

| Flag | 适用模式 | 默认值 | 说明 |
|------|----------|--------|------|
| `--task-name=NAME` | sync | `any2tidb_sync` | 任务名 |
| `--http-port=PORT` | sync | `0`（auto-port） | HTTP 控制平面端口。0 = 自动分配 |

---

## 错误处理与边界情况

| 场景 | 行为 |
|------|------|
| auto-port 找不到空闲端口 | 极少发生，报错退出 |
| --http-port 指定端口被占用 | 报错退出，提示端口被占用 |
| .port 文件写入失败（目录不存在） | 自动创建 `pid/` 目录 |
| .port 文件写入失败（权限问题） | WARN 日志，进程继续运行（仅管理命令不可用） |
| 进程被 kill -9 | .port 文件残留。下次 `list`/`status` 遇到无响应端口时自动清理 |
| 多个任务同名 | 默认任务名 `any2tidb_sync` 重复。启动时检测 .port 文件已存在 → 报错退出，提示使用 --task-name |
| SnapshotJsonParser 和 SyncSink 恢复 commitLsn | 纯还原之前删除的代码，行为不变 |
| shutdown hook 和 /stop 并发 | `AtomicBoolean shuttingDown` 保证只执行一次 |

---

## 不在范围内

- 持久化任务历史/日志（无数据库，无存储）
- Web UI / 仪表板
- REST API 鉴权（工具在本地或内网运行）
- snapshot 模式的 HTTP 控制平面（本次仅实现 sync 模式；snapshot 后续可复用 HttpControlPlane）
- `migrate` 复合模式（独立功能，不在本设计内）

---

## 接口稳定性

以下两项是内部实现细节，不保证向前兼容：

- `.port` 文件路径（`pid/<task-name>.port`），后续版本可能迁移到 `~/.any2tidb/`
- `/status` JSON 响应体字段（后续版本可能新增字段）

以下承诺稳定：

- `/health` 200 语义
- `/stop` 200 语义
- `--task-name` flag 名和语义
- `sqlserver status/stop/list` CLI 命令结构
