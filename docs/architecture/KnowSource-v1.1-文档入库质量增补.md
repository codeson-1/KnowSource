# KnowSource v1.1 文档入库质量增补

版本日期：2026-07-01

## 定位

v1.1 的主题是“从能上传文档，升级到高质量文档理解”。它不改变 v1.0 的发布/索引/问答主链路，而是在入库解析阶段补强 OCR 检测、表格结构化、版面感知切块和质量报告。

## 已落地能力

- 扫描 PDF / 图片型 PDF 检测：PDF 按页抽取文本，空文本页记录为 `ocrRequiredPages`。默认不强依赖 OCR 运行时。
- 本地 OCR 插拔点：`knowsource.ingest.ocr.enabled=true` 时尝试调用本机 `tesseract`，命令、语言和超时均可配置。
- 表格结构化抽取：Markdown 管道表、PDF/Word 文本中的管道/制表符/多空格表格会被规范化为 Markdown 表格，并在 chunk metadata 中保留行列数。
- 版面感知切块：标题路径、表格标题、列表块、页码、源块序号、源偏移继续随 chunk 入库并写入向量 metadata。
- 文档解析质量报告：`ingest_tasks` 增加页数、抽取页数、空页数、表格数、结构化表格数、失败页数、OCR 页数与 JSONB 明细。
- 前端可视化：Documents 列表显示质量摘要，详情抽屉展示页级质量报告和 chunk 的结构化 metadata。

## 数据模型

新增迁移：

- `V6__ingest_quality_report.sql`

新增/扩展字段：

- `ingest_tasks.page_count`
- `ingest_tasks.extracted_page_count`
- `ingest_tasks.empty_page_count`
- `ingest_tasks.table_count`
- `ingest_tasks.structured_table_count`
- `ingest_tasks.failed_page_count`
- `ingest_tasks.ocr_required_page_count`
- `ingest_tasks.ocr_applied_page_count`
- `ingest_tasks.quality_report JSONB`

`quality_report` 当前保存：

- `emptyPages`
- `failedPages`
- `ocrRequiredPages`
- `warnings`

## 配置

```yaml
knowsource:
  ingest:
    ocr:
      enabled: false
      command: tesseract
      language: chi_sim+eng
      timeout-seconds: 30
```

默认关闭 OCR，以保持本地/面试 demo 零额外 OCR 依赖。部署环境需要扫描件实 OCR 时，安装 `tesseract` 后开启配置即可。

## 仍待演进

- 云 OCR / 多模态 OCR 适配。
- 基于坐标的复杂表格重建。
- 页眉页脚、跨页表格、图片说明等更细的版面语义。
- OCR 结果置信度和低置信页人工复核工作流。
