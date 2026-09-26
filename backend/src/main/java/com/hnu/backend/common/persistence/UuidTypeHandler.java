package com.hnu.backend.common.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** 在 MyBatis 与 JDBC 之间直接映射 PostgreSQL UUID 类型。 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
  /** {@inheritDoc} */
  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, value);
  }

  /** {@inheritDoc} */
  @Override
  public UUID getNullableResult(ResultSet rs, String name) throws SQLException {
    return rs.getObject(name, UUID.class);
  }

  /** {@inheritDoc} */
  @Override
  public UUID getNullableResult(ResultSet rs, int index) throws SQLException {
    return rs.getObject(index, UUID.class);
  }

  /** {@inheritDoc} */
  @Override
  public UUID getNullableResult(CallableStatement cs, int index) throws SQLException {
    return cs.getObject(index, UUID.class);
  }
}
