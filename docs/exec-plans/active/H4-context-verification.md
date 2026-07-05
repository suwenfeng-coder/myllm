# H4：Context 与 Verification — 执行计划

- **状态**：进行中
- **日期**：2026-07-05
- **约束**：本阶段只做离线代码和测试验证，不启动/重启应用

## 已完成

- Context 按系统策略、用户目标、工具目录、证据的固定优先级组装。
- 增加 Prompt、Observation、单 Observation 和数量四层预算。
- Tool/RAG 输出统一包装为转义后的 `UNTRUSTED_EVIDENCE`。
- 从实际 RAG citation 生成 `fileId:chunkIndex` 引用白名单。
- 最终答案机械校验引用缺失、伪造、重复和长度。
- 校验失败最多 Repair 两次；每次写 Step/Event 并计入模型预算。
- Tool 结果字节上限由声明性元数据改为机械执行，超限 fail-closed。
- Fixture Replay 覆盖“伪造引用失败 → 使用真实引用修复成功”。

## 尚未完成

- 超大 Tool 结果写入 MinIO Artifact 后只注入摘要；当前策略是拒绝超限结果。
- 为 citation 增加 `contentHash`，校验向量库中的 Chunk 未被替换。
- 校验请求限定文件范围、关键结论引用覆盖率和 Graph evidenceChunkIds。
- Worker 恢复后从 Artifact 重建完整证据上下文；当前仅恢复脱敏摘要并 fail-safe 清空引用白名单。
- 可选独立模型 groundedness 校验，不作为唯一安全门。

## 验收用例

1. 外部内容伪造 `</UNTRUSTED_EVIDENCE>` 时必须被转义。
2. 不在本轮实际命中集合中的 sourceId 不得完成 Run。
3. Repair 超过配置上限必须失败，不再调用模型。
4. Context 与 Tool 结果不得超过配置预算。

## 验证命令

```bash
mvn -q -Dtest='com.example.myllm.harness.**' test
```
