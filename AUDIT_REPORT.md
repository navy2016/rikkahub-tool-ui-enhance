# 🔍 RikkaHub Workflow & Tool Calling 审查与修复报告

**审查日期**: 2026-03-31  
**审查视角**: 全球顶级 AI Agent 系统架构师 + Android 高级工程师  
**审查范围**: WorkflowFloatingPanel.kt 及工作流/工具调用完整链路  
**修复状态**: ✅ 全部补丁已应用  

---

## 📋 审查总结

| 问题 | 严重度 | 描述 | 根因 | 状态 |
|------|--------|------|------|------|
| BUG-1 | 🔴 Critical | PLAN/REVIEW 阶段不约束主代理 | 工具列表 + System Prompt 均无阶段过滤 | ✅ 已修复 |
| BUG-2 | 🔴 Critical | 模型输出工具调用为纯文本 JSON | Model.abilities 为空时 API 不携带 tools | ✅ 已修复 |

---

## 🐛 BUG-1：工作流阶段对主代理完全无约束（已修复）

### 根因
UI 层正确更新了 conversation.workflowState.phase，也传递到了 LocalTools.getTools(parentWorkflowPhase)，但:
1. 工具过滤：createContainerShellTool 等写工具未做阶段检查
2. 提示词：GenerationHandler 未注入阶段约束的 System Prompt

### 修复 — 7 个文件联动

| 文件 | 修改内容 |
|------|----------|
| GenerationPrompts.kt | 新增 buildWorkflowPhasePrompt() 函数 |
| LocalTools.kt | getTools() 中根据 isReadonlyPhase=true 使用 createSandboxShellReadonlyTool 替代完整工具 |
| GenerationHandler.kt | generateText() / generateInternal() 增加 workflowPhase 参数并注入 prompt |
| ChatService.kt | 传递 conversation.workflowState?.phase 到生成调用 + MCP 工具只读阶段过滤 |

### 关键代码变更

LocalTools.kt (getTools 方法):
```kotlin
val isReadonlyPhase = parentWorkflowPhase == WorkflowPhase.PLAN
    || parentWorkflowPhase == WorkflowPhase.REVIEW

if (sandboxId != null && options.contains(LocalToolOption.Container) && prootManager.isRunning) {
    if (isReadonlyPhase) {
        tools.add(createSandboxShellReadonlyTool(sandboxId))       // 只读
        tools.add(createContainerProcessTool(sandboxId))           // 只读
    } else {
        tools.add(createContainerShellTool(sandboxId, enabledSkills))  // 完整
        tools.add(createContainerShellBgTool(sandboxId, enabledSkills))
        tools.add(createContainerProcessTool(sandboxId))
    }
}
```

GenerationHandler.kt (generateInternal 方法):
```kotlin
val system = buildString {
    if (assistant.systemPrompt.isNotBlank()) {
        append(assistant.systemPrompt)
    }
    
    // 工作流阶段约束 Prompt
    if (workflowPhase != null) {
        appendLine()
        append(buildWorkflowPhasePrompt(workflowPhase))
    }
    // 记忆、摘要、工具提示...
}
```

---

## 🐛 BUG-2：模型无 Tool 定义导致纯文本输出（已修复）

### 根因
```kotlin
// Model.kt - abilities 是持久化字段，默认 emptyList()
val abilities: List<ModelAbility> = emptyList()
```
用户在 UI 中添加了模型但 abilities 未正确初始化，导致：
```kotlin
if (params.model.abilities.contains(ModelAbility.TOOL)) { ... }
// 空列表 → 不满足 → API 请求不携带 tools → 模型无法 function_call
```

### 修复方案：动态能力回退

三个 Provider 各添加一个 getModelAbilities() 辅助方法：
```kotlin
private fun getModelAbilities(model: Model): List<ModelAbility> {
    // 当 abilities 为空（手动添加/旧数据），动态从 ModelRegistry 解析
    return model.abilities.ifEmpty { ModelRegistry.MODEL_ABILITIES.getData(model.modelId) }
}
```

原检查 params.model.abilities.contains(ModelAbility.TOOL) 替换为 getModelAbilities(params.model).contains(ModelAbility.TOOL)。

| 文件 | 修改 |
|------|------|
| ClaudeProvider.kt | 添加 getModelAbilities() + 替换 tools 检查 |
| ChatCompletionsAPI.kt | 同上 |
| ResponseAPI.kt | 同上 |

---

## 📝 审查结论与建议

### 已实施修复
1. ✅ 工具级过滤：PLAN/REVIEW 阶段只暴露只读容器工具
2. ✅ Prompt 级约束：注入明确的行为限制指令
3. ✅ MCP 工具过滤：只读阶段不注入 MCP 工具
4. ✅ 动态能力回退：自动从 ModelRegistry 解析 abilities

### 后续增强建议
1. 沙箱层兜底：在 createContainerShellTool 内部增加 isReadOnly 标志，只读模式下对写操作直接返回 Error: Write operations prohibited in READ-ONLY mode。
2. UI 反馈：在 PLAN 阶段时，如果模型尝试调用写工具，UI 应展示明确的阶段提示。
3. Model.abilities 持久化修复：建议添加一次性数据库迁移，为所有已存模型重新计算并持久化 abilities 字段。

---

## 📂 修改文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| ai/src/.../ClaudeProvider.kt | ✅ 已改 | 动态 abilities 回退 |
| ai/src/.../ChatCompletionsAPI.kt | ✅ 已改 | 动态 abilities 回退 |
| ai/src/.../ResponseAPI.kt | ✅ 已改 | 动态 abilities 回退 |
| app/src/.../GenerationPrompts.kt | ✅ 已改 | 新增阶段 prompt |
| app/src/.../LocalTools.kt | ✅ 已改 | 工具阶段过滤 |
| app/src/.../GenerationHandler.kt | ✅ 已改 | prompt 注入 |
| app/src/.../ChatService.kt | ✅ 已改 | MCP 工具过滤 |

所有 .bak 备份文件已创建在修改文件同一目录下。
