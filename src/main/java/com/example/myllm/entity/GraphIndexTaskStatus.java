package com.example.myllm.entity;

/** Neo4j 构图任务状态。 */
public enum GraphIndexTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    DEAD,
    CANCELED
}
