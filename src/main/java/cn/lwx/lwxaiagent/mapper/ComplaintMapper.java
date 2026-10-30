package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.Complaint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 投诉举报 Mapper —— 对应数据库表 <strong>complaint</strong>（V34）。
 * </p>
 *
 * <p>查询一律走 MyBatis-Plus 的 {@code LambdaQueryWrapper}（见
 * {@link cn.lwx.lwxaiagent.service.ComplaintService}），
 * 因此本接口只继承 {@link BaseMapper}，不额外声明 SQL。</p>
 */
@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {
}
