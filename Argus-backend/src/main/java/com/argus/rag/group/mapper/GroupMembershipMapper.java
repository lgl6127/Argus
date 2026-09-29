package com.argus.rag.group.mapper;

import com.argus.rag.group.model.entity.Group;
import com.argus.rag.group.model.entity.GroupInvitation;
import com.argus.rag.group.model.vo.GroupMemberResponse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 群组成员关系 Mapper，提供群组成员、邀请相关的数据库操作。
 */
@Mapper
public interface GroupMembershipMapper extends BaseMapper<Group> {

    /** 查询用户拥有的活跃群组 */
    List<Map<String, Object>> selectOwnedGroupsByUserId(@Param("userId") Long userId);

    /** 查询用户加入的活跃群组（不含 OWNER） */
    List<Map<String, Object>> selectJoinedGroupsByUserId(@Param("userId") Long userId);

    /** 查询用户待处理的邀请，包含邀请人显示名称 */
    List<Map<String, Object>> selectPendingInvitationsByInviteeUserId(@Param("inviteeUserId") Long inviteeUserId);

    /** 查询用户在指定群组中的活跃成员角色，非成员时返回 null */
    String selectActiveMembershipRole(@Param("userId") Long userId, @Param("groupId") Long groupId);

    /** 统计用户在指定群组中的活跃成员数量 */
    Long countActiveMembership(@Param("userId") Long userId, @Param("groupId") Long groupId);

    /** 统计指定群组中指定用户的成员记录数 */
    Long countMembershipByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /** 统计指定群组中对指定用户的待处理邀请数 */
    Long countPendingInvitation(@Param("groupId") Long groupId, @Param("inviteeUserId") Long inviteeUserId);

    /** 统计指定用户 ID 对应的用户数量（用于校验用户是否存在） */
    Long countUserById(@Param("userId") Long userId);

    /** 按 ID 集合查询活跃群组 ID（用于批量删除前的存在性校验） */
    List<Long> selectActiveGroupIdsByIds(@Param("groupIds") List<Long> groupIds);

    /** 查询当前用户在给定群组 ID 集合中拥有指定角色的群组（用于批量删除前的 OWNER 权限校验） */
    List<Long> selectOwnerGroupIds(@Param("userId") Long userId, @Param("groupIds") List<Long> groupIds, @Param("ownerRole") String ownerRole);

    /** 批量归档群组（软删除：仅对 ACTIVE 状态群组生效，返回影响行数用于并发拦截） */
    int archiveGroups(@Param("groupIds") List<Long> groupIds);

    /** 批量取消多个群组的所有待处理邀请，避免产生孤立邀请 */
    int cancelPendingInvitationsByGroupIds(@Param("groupIds") List<Long> groupIds, @Param("canceledStatus") String canceledStatus);

    /** 插入群组并返回自增主键 */
    Long insertGroupReturningId(@Param("groupCode") String groupCode, @Param("groupName") String groupName, @Param("description") String description, @Param("ownerUserId") Long ownerUserId, @Param("status") String status);

    /** 插入成员关系记录 */
    int insertMembership(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("role") String role);

    /** 插入待处理邀请记录 */
    int insertPendingInvitation(@Param("groupId") Long groupId, @Param("inviterUserId") Long inviterUserId, @Param("inviteeUserId") Long inviteeUserId, @Param("status") String status);

    /** 插入待处理邀请并返回自增主键 */
    Long insertPendingInvitationReturningId(@Param("groupId") Long groupId, @Param("inviterUserId") Long inviterUserId, @Param("inviteeUserId") Long inviteeUserId, @Param("status") String status);

    /** 根据 ID 查询邀请记录 */
    GroupInvitation selectInvitationById(@Param("invitationId") Long invitationId);

    /** 更新邀请状态（乐观锁：仅当当前状态匹配 fromStatus 时才更新） */
    int updateInvitationStatus(@Param("invitationId") Long invitationId, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /** 查询指定群组的成员列表 */
    List<GroupMemberResponse> selectMembersByGroupId(@Param("groupId") Long groupId);

    /** 查询用户发出的邀请 */
    List<com.argus.rag.group.model.vo.MySentInvitationResponse> selectSentInvitationsByInviterUserId(@Param("inviterUserId") Long inviterUserId);

    /** 删除成员关系记录 */
    int deleteMembership(@Param("groupId") Long groupId, @Param("userId") Long userId);
}
