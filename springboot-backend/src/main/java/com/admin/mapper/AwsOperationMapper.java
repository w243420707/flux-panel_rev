package com.admin.mapper;

import com.admin.entity.AwsOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AwsOperationMapper extends BaseMapper<AwsOperation> {
    @Update("UPDATE aws_operation SET status=#{status}, message=#{message}, result_json=#{result}, "
            + "finished_at=#{now}, active_target=NULL WHERE id=#{id} AND status='PENDING'")
    int finish(@Param("id") String id, @Param("status") String status, @Param("message") String message,
               @Param("result") String result, @Param("now") long now);

    @Update("UPDATE aws_operation SET status='UNKNOWN', message='任务中断，请核对 AWS 资源后再操作', finished_at=#{now} "
            + "WHERE status='PENDING' AND created_at < #{before}")
    int expire(@Param("before") long before, @Param("now") long now);

    @Update("UPDATE aws_operation SET active_target=NULL WHERE id=#{id} AND status='UNKNOWN'")
    int acknowledge(@Param("id") String id);
}
