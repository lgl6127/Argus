package com.argus.rag.common.enums;

/** 知识库/分组状态 */
public enum GroupStatus {
    /** 正常使用中 */
    ACTIVE,
    /** 已归档（删除小组采用软删除策略，归档后对所有业务查询不可见） */
    ARCHIVED
}
