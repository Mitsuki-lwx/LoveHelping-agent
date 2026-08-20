package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户 Mapper 接口 —— 对应数据库表 <strong>users</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该接口是 MyBatis 的数据访问层（DAO），负责 {@link User} 实体
 * 与数据库表 {@code users} 之间的映射和 SQL 操作。用于管理系统用户的账号信息，
 * 包括用户的注册、查询、更新和删除等操作。
 * </p>
 *
 * <p>
 * <strong>继承关系：</strong>继承 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，
 * 自动获得了全部通用的 CRUD（增删改查）方法，<strong>无需编写任何额外的 SQL</strong>。
 * </p>
 *
 * <p>
 * <strong>已继承的 BaseMapper 常用方法包括：</strong>
 * </p>
 * <ul>
 *   <li>{@code insert(User entity)} —— 插入新用户记录</li>
 *   <li>{@code deleteById(Serializable id)} —— 根据主键 ID 删除用户</li>
 *   <li>{@code updateById(User entity)} —— 根据主键 ID 更新用户信息</li>
 *   <li>{@code selectById(Serializable id)} —— 根据主键 ID 查询单个用户</li>
 *   <li>{@code selectOne(Wrapper<User> wrapper)} —— 条件查询单个用户
 *       （如根据用户名查询：{@code selectOne(new QueryWrapper<User>().eq("username", "admin")})）</li>
 *   <li>{@code selectList(Wrapper<User> wrapper)} —— 条件查询用户列表
 *       （如查询某租户下所有用户：{@code selectList(new QueryWrapper<User>().eq("tenant_id", tenantId)})）</li>
 *   <li>{@code selectPage(Page<User> page, Wrapper<User> wrapper)} —— 分页条件查询用户</li>
 *   <li>{@code selectCount(Wrapper<User> wrapper)} —— 条件统计用户数量</li>
 * </ul>
 *
 * <p>
 * <strong>使用场景示例：</strong>
 * </p>
 * <ol>
 *   <li><strong>用户登录：</strong>通过 {@code selectOne} 按用户名查询用户，
 *       然后使用 Spring Security 验证密码是否匹配。</li>
 *   <li><strong>用户注册：</strong>通过 {@code insert} 将新用户信息（含加密密码）写入数据库。</li>
 *   <li><strong>用户管理：</strong>管理员通过 {@code selectList} 查询本租户下的所有用户列表。</li>
 *   <li><strong>账号禁用：</strong>通过 {@code updateById} 将 {@code enabled} 字段设为 {@code false}。</li>
 * </ol>
 *
 * <p>
 * <strong>说明：</strong>本接口未定义任何自定义 SQL 方法。如果后续有特殊的查询需求
 * （如按用户名精确查询、按租户查询用户列表等），可以在此接口中添加使用
 * {@link org.apache.ibatis.annotations.Select @Select} 注解的自定义方法，
 * 也可以直接使用 MyBatis-Plus 的条件构造器 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
 * 来灵活构建查询条件，无需修改此接口。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.entity.User
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
