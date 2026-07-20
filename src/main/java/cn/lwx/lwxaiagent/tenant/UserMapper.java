package cn.lwx.lwxaiagent.tenant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper（MyBatis-Plus）。
 * 继承 BaseMapper 后自动获得 CRUD 方法，无需写 XML。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
