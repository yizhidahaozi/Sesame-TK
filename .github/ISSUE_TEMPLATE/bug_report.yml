name: 错误报告
description: 提交一个错误报告。
title: "[Bug]: "
labels: ["bug", "triage"]
projects: ["octo-org/1", "octo-org/44"]
assignees:
  - octocat
body:
  - type: markdown
    attributes:
      value: |
        感谢您抽出时间填写此错误报告！
  - type: input
    id: contact
    attributes:
      label: 联系方式
      description: 如果我们需要更多信息，如何与您联系？
      placeholder: 例如：email@example.com
    validations:
      required: false
  - type: textarea
    id: what-happened
    attributes:
      label: 发生了什么？
      description: 请同时告诉我们，您原本期望发生什么？
      placeholder: 请告诉我们您看到的情况！
      value: "出现了一个错误！"
    validations:
      required: true
  - type: dropdown
    id: version
    attributes:
      label: 版本
      description: 您正在运行我们软件的哪个版本？
      options:
        - 1.0.2（默认）
        - 1.0.3（Edge）
      default: 0
    validations:
      required: true
  - type: dropdown
    id: browsers
    attributes:
      label: 您在哪些浏览器上遇到了这个问题？
      multiple: true
      options:
        - Firefox
        - Chrome
        - Safari
        - Microsoft Edge
  - type: textarea
    id: logs
    attributes:
      label: 相关日志输出
      description: 请复制并粘贴任何相关日志输出。这些内容将自动格式化为代码，无需使用反引号。
      render: shell
  - type: checkboxes
    id: terms
    attributes:
      label: 行为准则
      description: 提交此问题即表示您同意遵守我们的[行为准则](https://example.com)。
      options:
        - label: 我同意遵守本项目的行为准则
          required: true
