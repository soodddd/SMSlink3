# SMS-link Android App 全自动开发方案
**版本：1.0**  
**日期：2026-04-19**

---

## 一、方案概述

基于 SMS-link 技术需求书 v5.0，实现**完全自动化**的开发、审查、测试流程。

### 核心目标
- **自动资料搜集**：Claude 自动搜索 Flutter、Android、mDNS 等技术资料
- **自动代码生成**：Claude 作为主开发模型，按需求书逐模块实现
- **多模型审查**：GPT-5.4 和 Gemini 3 自动审查代码质量和需求符合度
- **自动模拟器测试**：每次提交自动在模拟器运行功能测试
- **自动修复循环**：发现问题自动反馈给 Claude 重写

---

## 二、系统架构

```
┌─────────────────────────────────────────────────────────┐
│                   主控制器 (Master Script)                │
│              orchestrator.py - 协调所有流程               │
└─────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│  开发阶段      │   │  审查阶段      │   │  测试阶段      │
│  (Claude)     │   │ (GPT+Gemini)  │   │  (Emulator)   │
└───────────────┘   └───────────────┘   └───────────────┘
        │                   │                   │
        ▼                   ▼                   ▼
   代码生成          需求对比审查          功能验证测试
   资料搜集          代码质量审查          性能测试
   架构设计          安全审查              日志分析
        │                   │                   │
        └───────────────────┴───────────────────┘
                            │
                            ▼
                    ┌───────────────┐
                    │  反馈循环      │
                    │  问题修复      │
                    └───────────────┘
```

---

## 三、技术栈

### 3.1 核心组件
- **主控制器**：Python 脚本 (orchestrator.py)
- **Claude Code**：主开发模型（通过 CLI）
- **GPT-5.4**：代码质量审查（通过 OpenAI API）
- **Gemini 3**：需求符合度审查（通过 Google AI API）
- **Android Emulator**：自动化测试环境
- **Git Hooks**：触发审查和测试流程

### 3.2 依赖工具
```bash
# Python 依赖
pip install openai google-generativeai anthropic gitpython

# Android 工具
- Android SDK
- Android Emulator (API 34)
- adb

# Flutter 工具
- Flutter SDK 3.x
```

---

## 四、工作流程

### 阶段 1：需求分析与资料搜集
```
输入：SMS-link_技术需求书_v5.0_Flutter版.md
输出：技术资料库 + 开发计划

Claude 自动执行：
1. 解析需求文档，提取关键技术点
2. 搜索 Flutter mDNS、NotificationListenerService 等资料
3. 生成详细开发计划（按 Phase 1-7 分解）
4. 创建项目脚手架
```

### 阶段 2：代码开发（Claude 主导）
```
输入：开发计划 + 当前 Phase
输出：代码提交

Claude 自动执行：
1. 读取当前 Phase 任务
2. 实现功能代码（Dart + Kotlin）
3. 编写单元测试
4. 提交到 Git（触发审查流程）
```

### 阶段 3：多模型审查（并行执行）
```
输入：Git diff + 需求文档
输出：审查报告

GPT-5.4 审查（代码质量）：
- 代码规范性
- 性能问题
- 安全漏洞
- 最佳实践

Gemini 3 审查（需求符合度）：
- 功能完整性
- 协议格式正确性
- UI 设计要求
- 性能指标达标

审查结果：
- PASS：进入测试阶段
- FAIL：生成修复建议，返回 Claude 重写
```

### 阶段 4：模拟器测试
```
输入：APK 文件
输出：测试报告

自动执行：
1. 启动 Android 模拟器
2. 安装 APK
3. 运行功能测试脚本
4. 收集日志和截图
5. 生成测试报告

测试场景：
- 设备发现（mDNS）
- 配对流程（二维码）
- 通知镜像
- 短信协同
- 文件传输
- 剪贴板同步
```

### 阶段 5：反馈与修复
```
输入：审查报告 + 测试报告
输出：修复后的代码

自动执行：
1. 汇总所有问题
2. 生成修复提示（包含具体位置和建议）
3. 调用 Claude 修复
4. 重新进入审查-测试循环
5. 直到所有检查通过
```

---

## 五、目录结构

```
sms5/
├── orchestrator.py              # 主控制器
├── config/
│   ├── api_keys.json            # API 密钥配置
│   ├── review_rules.json        # 审查规则
│   └── test_scenarios.json      # 测试场景
├── scripts/
│   ├── claude_dev.py            # Claude 开发脚本
│   ├── gpt_reviewer.py          # GPT 审查脚本
│   ├── gemini_reviewer.py       # Gemini 审查脚本
│   ├── emulator_test.py         # 模拟器测试脚本
│   └── feedback_generator.py    # 反馈生成脚本
├── hooks/
│   ├── pre-commit               # 提交前审查
│   └── post-commit              # 提交后测试
├── reports/
│   ├── reviews/                 # 审查报告
│   ├── tests/                   # 测试报告
│   └── feedback/                # 反馈记录
├── knowledge_base/              # 技术资料库
│   ├── flutter_mdns.md
│   ├── android_notification.md
│   └── ...
└── smslink_flutter/             # Flutter 项目
    ├── lib/
    ├── android/
    ├── windows/
    └── test/
```

---

## 六、配置文件示例

### 6.1 API 密钥配置 (config/api_keys.json)
```json
{
  "openai": {
    "api_key": "sk-...",
    "model": "gpt-5.4"
  },
  "google": {
    "api_key": "AIza...",
    "model": "gemini-3.0-pro"
  },
  "anthropic": {
    "api_key": "sk-ant-..."
  }
}
```

### 6.2 审查规则 (config/review_rules.json)
```json
{
  "gpt_focus": [
    "代码规范性",
    "性能优化",
    "安全漏洞",
    "错误处理"
  ],
  "gemini_focus": [
    "需求符合度",
    "协议格式",
    "UI设计要求",
    "性能指标"
  ],
  "blocking_issues": [
    "安全漏洞",
    "核心功能缺失",
    "协议格式错误"
  ]
}
```

### 6.3 测试场景 (config/test_scenarios.json)
```json
{
  "phase1_tests": [
    {
      "name": "设备发现测试",
      "steps": [
        "启动应用",
        "检查 mDNS 广播",
        "验证设备信息"
      ]
    }
  ],
  "phase2_tests": [
    {
      "name": "通知镜像测试",
      "steps": [
        "发送测试通知",
        "验证通知接收",
        "检查图标显示"
      ]
    }
  ]
}
```

---

## 七、核心脚本设计

### 7.1 主控制器 (orchestrator.py)
```python
class AutoDevOrchestrator:
    def __init__(self):
        self.claude = ClaudeClient()
        self.gpt = GPTReviewer()
        self.gemini = GeminiReviewer()
        self.emulator = EmulatorTester()
    
    def run_phase(self, phase_num):
        """运行一个完整的开发阶段"""
        while True:
            # 1. Claude 开发
            code_changes = self.claude.develop(phase_num)
            
            # 2. 并行审查
            gpt_result = self.gpt.review(code_changes)
            gemini_result = self.gemini.review(code_changes)
            
            # 3. 检查审查结果
            if gpt_result.passed and gemini_result.passed:
                # 4. 模拟器测试
                test_result = self.emulator.test(phase_num)
                
                if test_result.passed:
                    print(f"✅ Phase {phase_num} 完成")
                    break
                else:
                    # 测试失败，生成反馈
                    feedback = self.generate_feedback(test_result)
            else:
                # 审查失败，生成反馈
                feedback = self.generate_feedback(gpt_result, gemini_result)
            
            # 5. 反馈给 Claude 修复
            self.claude.fix(feedback)
```

### 7.2 Claude 开发脚本 (scripts/claude_dev.py)
```python
class ClaudeClient:
    def develop(self, phase_num):
        """调用 Claude Code CLI 开发"""
        prompt = self.load_phase_prompt(phase_num)
        
        # 通过 Claude Code CLI 执行
        result = subprocess.run([
            'claude',
            '--prompt', prompt,
            '--context', 'SMS-link_技术需求书_v5.0_Flutter版.md'
        ], capture_output=True)
        
        return result
    
    def fix(self, feedback):
        """根据反馈修复代码"""
        prompt = f"""
        审查和测试发现以下问题：
        {feedback}
        
        请修复这些问题，确保：
        1. 符合需求文档要求
        2. 通过代码质量检查
        3. 通过功能测试
        """
        # 调用 Claude Code
```

### 7.3 GPT 审查脚本 (scripts/gpt_reviewer.py)
```python
class GPTReviewer:
    def review(self, code_changes):
        """使用 GPT-5.4 审查代码质量"""
        prompt = f"""
        你是代码质量审查专家。请审查以下代码变更：
        
        {code_changes}
        
        重点检查：
        1. 代码规范性（命名、格式、注释）
        2. 性能问题（内存泄漏、循环优化）
        3. 安全漏洞（权限、数据验证）
        4. 错误处理（异常捕获、边界情况）
        
        输出格式：
        {{
          "passed": true/false,
          "issues": [
            {{"severity": "high/medium/low", "location": "文件:行号", "description": "问题描述", "suggestion": "修复建议"}}
          ]
        }}
        """
        
        response = openai.ChatCompletion.create(
            model="gpt-5.4",
            messages=[{"role": "user", "content": prompt}]
        )
        
        return json.loads(response.choices[0].message.content)
```

### 7.4 Gemini 审查脚本 (scripts/gemini_reviewer.py)
```python
class GeminiReviewer:
    def review(self, code_changes):
        """使用 Gemini 3 审查需求符合度"""
        # 读取需求文档
        requirements = self.load_requirements()
        
        prompt = f"""
        你是需求符合度审查专家。请对比需求文档和代码实现：
        
        需求文档：
        {requirements}
        
        代码变更：
        {code_changes}
        
        重点检查：
        1. 功能完整性（是否实现所有需求）
        2. 协议格式（JSON 格式是否符合规范）
        3. UI 设计要求（页面、交互是否符合）
        4. 性能指标（延迟、资源占用）
        
        输出格式：
        {{
          "passed": true/false,
          "missing_features": [],
          "protocol_errors": [],
          "ui_issues": [],
          "performance_concerns": []
        }}
        """
        
        response = genai.GenerativeModel('gemini-3.0-pro').generate_content(prompt)
        return json.loads(response.text)
```

### 7.5 模拟器测试脚本 (scripts/emulator_test.py)
```python
class EmulatorTester:
    def test(self, phase_num):
        """在模拟器中运行测试"""
        # 1. 启动模拟器
        self.start_emulator()
        
        # 2. 构建 APK
        self.build_apk()
        
        # 3. 安装 APK
        self.install_apk()
        
        # 4. 运行测试场景
        test_scenarios = self.load_test_scenarios(phase_num)
        results = []
        
        for scenario in test_scenarios:
            result = self.run_scenario(scenario)
            results.append(result)
        
        # 5. 收集日志
        logs = self.collect_logs()
        
        # 6. 生成报告
        return self.generate_report(results, logs)
    
    def run_scenario(self, scenario):
        """运行单个测试场景"""
        for step in scenario['steps']:
            # 使用 adb 执行操作
            if step == "启动应用":
                subprocess.run(['adb', 'shell', 'am', 'start', 'com.smslink/.MainActivity'])
            elif step == "检查 mDNS 广播":
                # 检查网络流量
                pass
            # ... 更多步骤
```

---

## 八、执行流程

### 启动全自动开发
```bash
# 1. 配置 API 密钥
cp config/api_keys.example.json config/api_keys.json
# 编辑 api_keys.json 填入密钥

# 2. 启动主控制器
python orchestrator.py --start-phase 1

# 系统将自动执行：
# - Phase 1: 基础框架与配对
# - Phase 2: 通知镜像
# - Phase 3: 文件传输
# - Phase 4: 短信协同
# - Phase 5: 剪贴板同步
# - Phase 6: Windows 端适配
# - Phase 7: 优化与测试
```

### 监控进度
```bash
# 查看当前状态
python orchestrator.py --status

# 查看审查报告
cat reports/reviews/phase1_review.json

# 查看测试报告
cat reports/tests/phase1_test.json
```

---

## 九、优势与特点

### 9.1 完全自动化
- 无需人工干预，从需求到测试全自动
- 24/7 持续开发，不受时间限制

### 9.2 多模型协作
- Claude：强大的代码生成和架构设计
- GPT-5.4：严格的代码质量把关
- Gemini 3：深度的需求符合度验证

### 9.3 快速迭代
- 发现问题立即修复
- 每个 Phase 完成后自动进入下一阶段
- 减少测试阶段的问题积累

### 9.4 可追溯性
- 所有审查和测试结果保存
- 问题修复历史完整记录
- 便于后期优化和调试

---

## 十、预期时间表

基于需求文档的 Phase 划分：

| Phase | 功能 | 预计时间 | 说明 |
|-------|------|---------|------|
| Phase 1 | 基础框架与配对 | 2-3 天 | 包含多轮审查和测试 |
| Phase 2 | 通知镜像 | 2-3 天 | Android 原生代码较多 |
| Phase 3 | 文件传输 | 2-3 天 | 需要性能测试 |
| Phase 4 | 短信协同 | 2-3 天 | 权限处理复杂 |
| Phase 5 | 剪贴板同步 | 1-2 天 | 相对简单 |
| Phase 6 | Windows 端适配 | 2-3 天 | 平台差异处理 |
| Phase 7 | 优化与测试 | 2-3 天 | 全面测试和优化 |

**总计：约 2-3 周完成全部开发和测试**

---

## 十一、下一步行动

1. **确认 API 密钥**：准备 OpenAI、Google AI、Anthropic 的 API 密钥
2. **配置 Android 环境**：安装 Android SDK 和模拟器
3. **创建项目结构**：生成 orchestrator.py 和相关脚本
4. **启动 Phase 1**：开始基础框架开发

---

**准备好开始了吗？我可以立即为你生成所有脚本和配置文件。**
