// Neo4j 5.26 结构图 Schema。
// 应用 Neo4jSchemaService 使用相同的幂等语句；本文件供运维审核和手工迁移。

CREATE CONSTRAINT document_file_id_unique IF NOT EXISTS
FOR (d:Document) REQUIRE d.fileId IS UNIQUE;

CREATE CONSTRAINT section_id_unique IF NOT EXISTS
FOR (s:Section) REQUIRE s.sectionId IS UNIQUE;

CREATE CONSTRAINT chunk_id_unique IF NOT EXISTS
FOR (c:Chunk) REQUIRE c.chunkId IS UNIQUE;

CREATE CONSTRAINT entity_key_unique IF NOT EXISTS
FOR (e:Entity) REQUIRE e.entityKey IS UNIQUE;

CREATE INDEX chunk_file_id_index IF NOT EXISTS
FOR (c:Chunk) ON (c.fileId);

CREATE INDEX entity_type_index IF NOT EXISTS
FOR (e:Entity) ON (e.entityType);

CREATE FULLTEXT INDEX entity_name_fulltext IF NOT EXISTS
FOR (e:Entity) ON EACH [e.name, e.normalizedName, e.aliases];
