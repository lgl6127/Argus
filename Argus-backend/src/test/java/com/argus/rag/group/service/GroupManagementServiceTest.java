package com.argus.rag.group.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.group.mapper.GroupJoinRequestMapper;
import com.argus.rag.group.mapper.GroupMembershipMapper;
import com.argus.rag.group.model.dto.CreateGroupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupManagementService#createGroup} 小组名称唯一性校验单元测试。
 */
@ExtendWith(MockitoExtension.class)
class GroupManagementServiceTest {

    private static final Long OWNER_USER_ID = 2L;
    private static final Long GROUP_ID = 100L;
    private static final String GROUP_NAME = "人文历史";
    private static final String DUPLICATE_MESSAGE = "小组名称已存在，请更换名称";

    @Mock
    private GroupMembershipMapper groupMembershipMapper;

    @Mock
    private GroupJoinRequestMapper groupJoinRequestMapper;

    @Mock
    private GroupMembershipService groupMembershipService;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private GroupManagementService groupManagementService;

    private CurrentUserService.CurrentUser businessUser() {
        return new CurrentUserService.CurrentUser(OWNER_USER_ID, "lgl12345", "lgl12345", SystemRole.USER, false);
    }

    @Test
    @DisplayName("正常创建：名称无重复时插入群组并将创建者设为 OWNER")
    void createGroupSuccess() {
        when(currentUserService.requireBusinessUser()).thenReturn(businessUser());
        when(groupMembershipMapper.countActiveGroupsByName(GROUP_NAME)).thenReturn(0L);
        when(groupMembershipMapper.insertGroupReturningId(
                anyString(), eq(GROUP_NAME), anyString(), eq(OWNER_USER_ID), eq("ACTIVE")))
                .thenReturn(GROUP_ID);

        Long groupId = groupManagementService.createGroup(new CreateGroupRequest(GROUP_NAME, "历史资料"));

        assertThat(groupId).isEqualTo(GROUP_ID);
        verify(groupMembershipMapper).insertMembership(GROUP_ID, OWNER_USER_ID, "OWNER");
    }

    @Test
    @DisplayName("名称重复：已存在同名活跃群组时抛出业务错误且不插入")
    void createGroupRejectsDuplicateName() {
        when(currentUserService.requireBusinessUser()).thenReturn(businessUser());
        when(groupMembershipMapper.countActiveGroupsByName(GROUP_NAME)).thenReturn(1L);

        assertThatThrownBy(() -> groupManagementService.createGroup(new CreateGroupRequest(GROUP_NAME, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_MESSAGE);

        verify(groupMembershipMapper, never()).insertGroupReturningId(any(), any(), any(), anyLong(), any());
        verify(groupMembershipMapper, never()).insertMembership(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("并发冲突：插入时触发唯一索引冲突，翻译为同名业务错误")
    void createGroupTranslatesDuplicateKeyException() {
        when(currentUserService.requireBusinessUser()).thenReturn(businessUser());
        when(groupMembershipMapper.countActiveGroupsByName(GROUP_NAME)).thenReturn(0L);
        when(groupMembershipMapper.insertGroupReturningId(
                anyString(), eq(GROUP_NAME), anyString(), eq(OWNER_USER_ID), eq("ACTIVE")))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"uq_groups_active_name\""));

        assertThatThrownBy(() -> groupManagementService.createGroup(new CreateGroupRequest(GROUP_NAME, "历史资料")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_MESSAGE);

        verify(groupMembershipMapper, never()).insertMembership(anyLong(), anyLong(), any());
    }
}
