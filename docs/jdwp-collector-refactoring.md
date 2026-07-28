# JDWP Collector 重构完成说明

## 1. 重构目标

本次重构把原来只通过 MCP 暴露的 JDWP 能力拆成“可复用核心 + 两种适配器”：

```text
jdwp-core
├── jdwp-mcp-server         交互式调查
└── jdwp-batch-collector    计划驱动的批量采集
```

调度算法无需添加埋点，也不依赖 Collector。目标 UT 只需用 JDWP 参数启动。

## 2. 已完成内容

### `jdwp-core`

- `JdwpEndpoint`：JDWP 目标地址及参数校验；
- `JdiSocketAttacher`：无 Spring、无 MCP 的 SocketAttach；
- `SnapshotLimits`：对象深度、元素数和字符串长度硬限制；
- `JdiValueSnapshotter`：无目标方法调用的有界对象/数组/字段快照；
- `FrameSnapshotter`：调用栈、局部变量及 `this` 快照。

现有 `JDIConnectionService` 已改为复用 `JdiSocketAttacher`，因此 core 不是旁路复制。

### `jdwp-batch-collector`

- 读取并校验 `debug-plan.json`；
- 支持 CLI 覆盖 host、port 和输出目录；
- 为已加载类设置 `BreakpointRequest`；
- 为未加载类设置 `ClassPrepareRequest`，加载后自动绑定；
- tracepoint 命中后短暂停当前事件线程、采集、立即恢复；
- 支持按 method 过滤源代码行；
- 支持 `maxHits`、`maxEvents` 和 idle timeout；
- VM death/disconnect 时自动完成；
- 输出严格的一事件一行 `raw-trace.jsonl`；
- 输出 `collection-manifest.json`，记录计划、目标、完成原因、命中数和绑定位置数；
- 生成独立可执行 jar，不启动 Spring，也不经过 MCP。

## 3. 运行数据流

```text
用户问题
  -> 大模型/规则生成 debug-plan.json
  -> UT 以 suspend=y 启动
  -> Batch Collector attach
  -> 安装 tracepoint
  -> 自动 resume UT
  -> 命中时采集 stack/locals/this/object snapshot
  -> raw-trace.jsonl + collection-manifest.json
  -> 后续 trace normalizer
  -> Domain Trace
  -> 静态代码 + 动态证据分析
```

## 4. 已验证结果

验证对象：

```text
SimpleWaferSchedulerTest#complexParallelModeSchedulesThreeJobsAcrossFiveChambers
```

采集点：

```text
SimpleWaferScheduler.scheduleWafer():120
```

结果：

- UT 成功；
- 安装位置数：1；
- tracepoint 命中：165；
- 输出事件：167；
- VM death 正常结束；
- JSONL：167 行，逐行解析失败数 0。

## 5. 当前边界

本版本定位为 Raw Trace Collector，不在采集层加入晶圆调度语义：

- 尚未实现表达式求值；当前采集 stack、locals、`this` 和对象字段；
- 尚未把原始变量转换成 `candidate_generated` 等 Domain Trace；
- 尚未实现采集开销统计、脱敏规则和字段 allow/deny list；
- 尚未实现方法进入/退出事件，当前 tracepoint 基于源码行；
- 不建议 MCP Server 和 Batch Collector 同时连接同一个 UT JVM。

## 6. 后续演进顺序

1. `debug-plan-builder`：从用户问题和静态代码生成采集计划；
2. 字段路径投影：只采 `candidate.id`、`score`、资源摘要等指定路径；
3. `trace-normalizer`：Raw Trace 转换为调度 Domain Trace；
4. `trace-validator`：验证资源冲突、先后约束及输出一致性；
5. `Evidence Sufficiency Evaluator`：判断证据是否足以回答问题；
6. 证据不足时重新运行同一 UT，通过 MCP 进行集中调查；
7. 生成 `debug-report.md` 和 `debug-viewer.html`。

## 7. 构建与运行

```powershell
mvn -pl jdwp-batch-collector -am test package

java --add-modules jdk.jdi `
  -jar jdwp-batch-collector/target/jdwp-batch-collector.jar collect `
  --plan examples/wafer-scheduler-debug-plan.json `
  --output D:/javacode/hellomvn/output/jdwp-trace
```

完整使用细节见 `docs/batch-collector.md`。
