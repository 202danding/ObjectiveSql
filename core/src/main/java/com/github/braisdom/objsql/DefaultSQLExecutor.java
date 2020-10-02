/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.braisdom.objsql;

import com.github.braisdom.objsql.jdbc.QueryRunner;
import com.github.braisdom.objsql.jdbc.ResultSetHandler;
import com.github.braisdom.objsql.reflection.PropertyUtils;
import com.github.braisdom.objsql.transition.ColumnTransitional;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DefaultSQLExecutor<T> implements SQLExecutor<T> {

    private static final Logger logger = Databases.getLoggerFactory().create(DefaultSQLExecutor.class);
    private final QueryRunner queryRunner;

    public DefaultSQLExecutor() {
        queryRunner = new QueryRunner();
    }

    @Override
    public List<T> query(Connection connection, String sql, TableRowDescriptor tableRowDescriptor,
                         Object... params) throws SQLException {
        return Databases.sqlBenchmarking(() ->
                queryRunner.query(connection, sql,
                        new DomainModelListHandler(tableRowDescriptor, connection.getMetaData()), params), logger, sql, params);
    }

    @Override
    public T insert(Connection connection, String sql, TableRowDescriptor tableRowDescriptor,
                    Object... params) throws SQLException {
        return (T) Databases.sqlBenchmarking(() ->
                queryRunner.insert(connection, sql,
                        new DomainModelHandler(tableRowDescriptor, connection.getMetaData()), params), logger, sql, params);
    }

    @Override
    public int[] insert(Connection connection, String sql, TableRowDescriptor tableRowDescriptor,
                        Object[][] params) throws SQLException {
        return Databases.sqlBenchmarking(() ->
                queryRunner.insertBatch(connection, sql, params), logger, sql, params);
    }

    @Override
    public int execute(Connection connection, String sql, Object... params) throws SQLException {
        return Databases.sqlBenchmarking(() ->
                queryRunner.update(connection, sql, params), logger, sql, params);
    }
}

class DomainModelListHandler implements ResultSetHandler<List> {

    private final TableRowDescriptor tableRowDescriptor;
    private final DatabaseMetaData databaseMetaData;

    public DomainModelListHandler(TableRowDescriptor tableRowDescriptor,
                                  DatabaseMetaData databaseMetaData) {
        this.tableRowDescriptor = tableRowDescriptor;
        this.databaseMetaData = databaseMetaData;
    }

    @Override
    public List handle(ResultSet rs) throws SQLException {
        List results = new ArrayList();

        if (!rs.next()) return results;

        do {
            results.add(createBean(rs));
        } while (rs.next());

        return results;
    }

    private Object createBean(ResultSet rs) throws SQLException {
        Object bean = tableRowDescriptor.newInstance();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnLabel(i);
            String fieldName = tableRowDescriptor.getFieldName(columnName);
            Object rawColumnValue = rs.getObject(columnName);

            if (fieldName != null) {
                if (tableRowDescriptor.isTransitable(fieldName)) {
                    ColumnTransitional columnTransitional = tableRowDescriptor.getColumnTransition(fieldName);
                    Object value = columnTransitional == null ? rawColumnValue : columnTransitional
                            .rising(databaseMetaData, metaData, bean, tableRowDescriptor, fieldName, rawColumnValue);

                    Class fieldType = tableRowDescriptor.getFieldType(fieldName);
                    if (fieldType != null && value != null &&
                            !fieldType.isAssignableFrom(value.getClass()))
                        throw new ClassCastException(String.format("Inconsistent data types field:%s(%s) " +
                                        "vs column:%s(%s) in %s", fieldName, fieldType.getName(), columnName,
                                value.getClass().getName(), bean.getClass().getName()));

                    tableRowDescriptor.setValue(bean, fieldName, value);
                } else
                    tableRowDescriptor.setValue(bean, fieldName, rawColumnValue);
            } else {
                if (PropertyUtils.supportRawAttribute(bean))
                    PropertyUtils.writeRawAttribute(bean, columnName, rawColumnValue);
            }
        }

        return bean;
    }
}

class DomainModelHandler implements ResultSetHandler<Object> {

    private static final List<String> AUTO_GENERATE_COLUMN_NAMES = Arrays
            .asList(new String[]{"last_insert_rowid()", "GENERATED_KEY", "GENERATED_KEYS"});

    private final TableRowDescriptor tableRowDescriptor;
    private final DatabaseMetaData databaseMetaData;

    public DomainModelHandler(TableRowDescriptor tableRowDescriptor, DatabaseMetaData databaseMetaData) {
        this.tableRowDescriptor = tableRowDescriptor;
        this.databaseMetaData = databaseMetaData;
    }

    @Override
    public Object handle(ResultSet rs) throws SQLException {
        Object bean = tableRowDescriptor.newInstance();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            if (!rs.next()) break;

            String columnName = metaData.getColumnLabel(i);
            String fieldName = tableRowDescriptor.getFieldName(columnName);
            Object rawColumnValue = rs.getObject(columnName);

            if (AUTO_GENERATE_COLUMN_NAMES.contains(columnName)) {
                tableRowDescriptor.setAutoGeneratedPK(bean, rawColumnValue);
            } else {
                if (fieldName != null) {
                    if (tableRowDescriptor.isTransitable(fieldName)) {
                        ColumnTransitional columnTransitional = tableRowDescriptor.getColumnTransition(fieldName);
                        Object value = columnTransitional == null ? rawColumnValue : columnTransitional
                                .rising(databaseMetaData, metaData, bean, tableRowDescriptor, fieldName, rawColumnValue);

                        Class fieldType = tableRowDescriptor.getFieldType(fieldName);
                        if (fieldType != null && value != null &&
                                !fieldType.isAssignableFrom(value.getClass()))
                            throw new ClassCastException(String.format("Inconsistent data types field:%s(%s) " +
                                            "vs column:%s(%s) in %s", fieldName, fieldType.getName(), columnName,
                                    value.getClass().getName(), bean.getClass().getName()));

                        tableRowDescriptor.setValue(bean, fieldName, value);
                    } else
                        tableRowDescriptor.setValue(bean, fieldName, rawColumnValue);
                } else {
                    if (PropertyUtils.supportRawAttribute(bean))
                        PropertyUtils.writeRawAttribute(bean, columnName, rawColumnValue);
                }
            }
        }

        return bean;
    }
}
