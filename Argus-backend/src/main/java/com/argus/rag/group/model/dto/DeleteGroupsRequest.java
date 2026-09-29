package com.argus.rag.group.model.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量删除群组请求（单个删除传一个元素即可）。
 */
public record DeleteGroupsRequest(
        /** 要删除的群组 ID 列表 */
        @NotEmpty(message = "请选择要删除的小组")
        List<Long> groupIds
) {
}
