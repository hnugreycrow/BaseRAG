package com.hnu.backend.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户账号和首次数据转交的数据访问接口。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
  /** 历史数据临时所有者，永远不允许登录或出现在账号列表中。 */
  UUID LEGACY_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  /**
   * 按规范化登录名查询用户。
   *
   * @param username 已规范化的用户名
   * @return 用户；不存在时返回 {@code null}
   */
  default User findByUsername(String username) {
    return selectOne(
        Wrappers.<User>lambdaQuery()
            .eq(User::getUsername, username)
            .ne(User::getId, LEGACY_OWNER_ID));
  }

  /**
   * 按标识查询真实用户。
   *
   * @param id 用户标识
   * @return 用户；不存在或为迁移占位用户时返回 {@code null}
   */
  default User find(UUID id) {
    return selectOne(
        Wrappers.<User>lambdaQuery().eq(User::getId, id).ne(User::getId, LEGACY_OWNER_ID));
  }

  /**
   * 统计已经初始化的真实用户。
   *
   * @return 真实用户数量
   */
  default long countRealUsers() {
    return selectCount(Wrappers.<User>lambdaQuery().ne(User::getId, LEGACY_OWNER_ID));
  }

  /**
   * 分页查询真实用户。
   *
   * @param query 可选的用户名或显示名搜索词
   * @param limit 最大返回数量
   * @param offset 分页偏移
   * @return 用户列表
   */
  default List<User> list(String query, int limit, long offset) {
    var wrapper =
        Wrappers.<User>lambdaQuery()
            .ne(User::getId, LEGACY_OWNER_ID)
            .and(
                query != null,
                nested ->
                    nested.like(User::getUsername, query).or().like(User::getDisplayName, query))
            .orderByDesc(User::getCreatedAt)
            .orderByAsc(User::getId)
            .last("LIMIT " + limit + " OFFSET " + offset);
    return selectList(wrapper);
  }

  /**
   * 统计真实用户列表中的匹配项。
   *
   * @param query 可选搜索词
   * @return 匹配数量
   */
  default long count(String query) {
    return selectCount(
        Wrappers.<User>lambdaQuery()
            .ne(User::getId, LEGACY_OWNER_ID)
            .and(
                query != null,
                nested ->
                    nested.like(User::getUsername, query).or().like(User::getDisplayName, query)));
  }

  /**
   * 统计启用的管理员，用于阻止系统失去最后一个管理员。
   *
   * @return 启用管理员数量
   */
  default long countEnabledAdmins() {
    return selectCount(
        Wrappers.<User>lambdaQuery()
            .eq(User::getRole, UserRole.ADMIN)
            .eq(User::isEnabled, true)
            .ne(User::getId, LEGACY_OWNER_ID));
  }

  /**
   * 锁定全部启用管理员，串行化可能移除管理员资格的安全操作。
   *
   * @return 当前启用的管理员；返回值仅用于持有行锁
   */
  @Select(
      "SELECT * FROM users WHERE role = 'ADMIN' AND enabled = true AND id <> '00000000-0000-0000-0000-000000000002' FOR UPDATE")
  List<User> lockEnabledAdmins();

  /**
   * 锁定遗留所有者行，串行化首次管理员初始化。
   *
   * @return 遗留所有者；迁移不完整时返回 {@code null}
   */
  @Select("SELECT * FROM users WHERE id = '00000000-0000-0000-0000-000000000002' FOR UPDATE")
  User lockLegacyOwner();

  /**
   * 将全部遗留知识库转交给首个管理员。
   *
   * @param ownerId 首个管理员标识
   * @return 更新数量
   */
  @Update(
      "UPDATE knowledge_bases SET owner_id = #{ownerId} WHERE owner_id = '00000000-0000-0000-0000-000000000002'")
  int transferKnowledgeBases(@Param("ownerId") UUID ownerId);

  /**
   * 将全部遗留会话转交给首个管理员。
   *
   * @param ownerId 首个管理员标识
   * @return 更新数量
   */
  @Update(
      "UPDATE conversations SET owner_id = #{ownerId} WHERE owner_id = '00000000-0000-0000-0000-000000000002'")
  int transferConversations(@Param("ownerId") UUID ownerId);

  /** 删除已完成使命的迁移占位用户。 */
  @Delete("DELETE FROM users WHERE id = '00000000-0000-0000-0000-000000000002'")
  int deleteLegacyOwner();
}
