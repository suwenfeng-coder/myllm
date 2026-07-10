# MinIO 对象键完整性修复设计

## 背景

当前原文件和解析文件的对象键只包含日期与清洗后的文件名：

```text
uploads/2026-07-10/report.pdf
parsed/2026-07-10/report.parsed.md
```

同一天上传两个同名文件，或者两个不同文件名清洗后得到同一名称时，后一次写入会覆盖前一次写入。数据库仍可产生两个不同的 `fileId`，但两条业务记录可能指向同一个 MinIO 对象；清理其中一条记录时还可能删除另一条记录依赖的对象。

## 目标

1. 新上传对象的键必须包含完整 `fileId`，消除同名覆盖和交叉误删。
2. 对象文件名保持人工可读，优先显示原始文件名主体。
3. 原文件与解析文件能够通过名称直接配对。
4. 旧记录继续使用数据库中已保存的对象路径，不迁移、不重命名，也不删除旧对象。
5. 修改仅覆盖对象键生成和上传调用链，不改变数据库表结构、下载方式或清理接口。

## 非目标

- 不批量迁移历史 MinIO 对象。
- 不扫描、复制或删除历史对象。
- 不改变 `DocumentStorageLog` 的字段结构。
- 不调整日期目录、bucket 或配置前缀。
- 不同时处理上传补偿、任务并发或其他审查项；这些问题按后续阶段分别设计和实施。

## 对象键格式

新对象继续保留现有前缀和日期目录，并将完整 `fileId` 拼入文件名：

```text
{prefix}/{yyyy-MM-dd}/{sanitized-stem}__{validated-file-id}{extension}
```

以 `report.pdf` 和 `fileId=550e8400-e29b-41d4-a716-446655440000` 为例：

```text
uploads/2026-07-10/report__550e8400-e29b-41d4-a716-446655440000.pdf
parsed/2026-07-10/report__550e8400-e29b-41d4-a716-446655440000.parsed.md
```

规则如下：

- 文件名主体在前，便于在 MinIO 控制台中人工识别。
- 分隔符固定为双下划线 `__`。
- 使用完整 `fileId`，不截断，不使用概率性短 ID。
- 原文件保留最后一个扩展名；无扩展名文件不额外添加扩展名。
- 解析文件固定使用 `.parsed.md` 后缀。
- 文件名继续使用现有安全字符规则清洗，路径分隔符不得进入对象文件名。
- `fileId` 去除首尾空白后必须匹配 `[A-Za-z0-9._-]+`；不替换其中字符，避免两个不同 ID 清洗成同一值。
- `fileId` 为 `null`、空白或包含上述字符集以外的字符时，在写入任何 MinIO 对象前抛出 `IllegalArgumentException`。当前生产链路生成的标准 UUID 满足该约束。
- `null`、空白或仅含路径的原始文件名继续回退到现有的 `unknown` 语义。

## 组件与数据流

### `MinioObjectPaths`

对象键生成由该类统一负责。公开方法增加 `fileId` 参数，并生成带唯一后缀的原文件名和解析文件名。文件名拆分、清洗和拼接逻辑只保留一份，避免原文件与解析文件采用不同规则。

### `MinioStorageService`

`storeOriginal` 和 `storeParsed` 增加 `fileId` 参数，将其传给 `MinioObjectPaths`。返回的 `MinioStoredObject.fileName` 使用实际写入对象的最终文件名，使审计记录与 MinIO 对象一致。

### `DocumentStorageService`

该服务已经接收 `fileId`。它在调用任何 MinIO 写入前完成非空校验，并把同一个规范化 `fileId` 传给原文件和解析文件写入方法。数据库仍保存 MinIO 返回的完整对象路径，因此旧记录和新记录可以共存。

数据流为：

```text
FileEmbeddingService 生成 UUID fileId
  -> DocumentStorageService 校验并规范化 fileId
  -> MinioStorageService 写入原文件与解析文件
  -> MinioObjectPaths 生成可读且唯一的对象键
  -> DocumentStorageLog 保存实际对象路径
```

## 兼容性

历史记录不需要迁移。读取和删除逻辑以 `DocumentStorageLog.originalObjectPath` 与 `parsedObjectPath` 为准，并不根据新规则重新计算路径，因此：

- 历史对象继续使用 `日期/文件名` 路径。
- 新对象使用 `日期/文件名主体__fileId.扩展名` 路径。
- 清理服务删除数据库记录中明确保存的路径，不需要区分新旧格式。

不存在自动回写或延迟迁移，避免在修复过程中引入批量对象操作。

## 失败与补偿边界

本阶段保证无效 `fileId` 在第一次对象写入前失败，避免产生无法关联的对象。原文件写入成功而解析文件或数据库写入失败时的跨存储补偿属于单独审查项，不在本阶段顺带重构；后续阶段将以幂等补偿设计处理。

对象键的唯一性依赖业务层生成的 UUID `fileId`。数据库已有 `file_id` 唯一索引，若调用方错误复用同一 `fileId`，数据库仍会拒绝第二条存储记录；本阶段不改变该语义。

## 测试设计

实施遵循测试先行：

1. 扩展 `MinioObjectPathsTests`，先验证相同日期、相同文件名、不同 `fileId` 生成不同对象键。
2. 验证原文件与解析文件都采用 `文件名主体__完整fileId` 格式。
3. 验证中文、路径字符、空白文件名、无扩展名和多点扩展名的处理。
4. 验证空白或包含路径分隔符的 `fileId` 在生成路径时被拒绝。
5. 增加 `MinioStorageService` 定向测试，确认传入 MinIO SDK 的对象键和返回文件名一致。
6. 增加 `DocumentStorageService` 定向测试，确认规范化后的同一 `fileId` 同时传给两次存储调用，且空白 `fileId` 不产生存储调用。
7. 定向测试通过后运行 `make test-fast` 与 `make verify`。

## 验收标准

- 两个同日、同名、不同 `fileId` 的文件拥有不同原文件对象键和不同解析文件对象键。
- 对象键中可直接看到清洗后的原文件名主体与完整 `fileId`。
- 原文件扩展名和解析文件 `.parsed.md` 后缀正确。
- `DocumentStorageLog` 保存的文件名、原对象路径和解析对象路径与实际写入一致。
- 无效 `fileId` 不会触发 MinIO 写入。
- 不修改或删除任何历史对象及其数据库路径。
- 全量快速测试与仓库验证通过。
