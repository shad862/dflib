package com.nhl.dflib.jdbc.connector;

import com.nhl.dflib.DataFrame;
import com.nhl.dflib.Series;
import com.nhl.dflib.jdbc.connector.metadata.DbColumnMetadata;
import com.nhl.dflib.jdbc.connector.statement.CompiledFromStatementBinderFactory;
import com.nhl.dflib.jdbc.connector.statement.FixedParamsBinderFactory;
import com.nhl.dflib.jdbc.connector.statement.SelectStatement;
import com.nhl.dflib.jdbc.connector.statement.SelectStatementNoParams;
import com.nhl.dflib.jdbc.connector.statement.SelectStatementWithParams;
import com.nhl.dflib.jdbc.connector.statement.StatementBinderFactory;
import com.nhl.dflib.jdbc.connector.statement.UpdateStatement;
import com.nhl.dflib.jdbc.connector.statement.UpdateStatementBatch;
import com.nhl.dflib.jdbc.connector.statement.UpdateStatementNoBatch;
import com.nhl.dflib.jdbc.connector.statement.UpdateStatementNoParams;
import com.nhl.dflib.jdbc.connector.statement.UpdateStatementParamsRow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @since 0.6
 */
public class StatementBuilder {

    private JdbcConnector connector;
    private String sql;
    private DbColumnMetadata[] paramDescriptors;
    private Series<?> params;
    private DataFrame batchParams;

    public StatementBuilder(JdbcConnector connector) {
        this.connector = connector;
    }

    public StatementBuilder sql(String sql) {
        this.sql = sql;
        return this;
    }

    public StatementBuilder paramDescriptors(DbColumnMetadata[] paramDescriptors) {
        this.paramDescriptors = paramDescriptors;
        return this;
    }

    public StatementBuilder bindBatch(DataFrame batchParams) {
        this.params = null;
        this.batchParams = batchParams;
        return this;
    }

    public StatementBuilder bind(Series<?> params) {
        this.params = params;
        this.batchParams = null;
        return this;
    }

    public StatementBuilder bind(Object[] params) {
        return bind(Series.forData(params));
    }

    public <T> T select(JdbcFunction<ResultSet, T> resultReader) {
        try (Connection c = connector.getConnection()) {
            return select(c, resultReader);
        } catch (SQLException e) {
            throw new RuntimeException("Error opening connection: " + e.getMessage(), e);
        }
    }

    public <T> T select(Connection connection, JdbcFunction<ResultSet, T> resultReader) {
        try {
            return createSelectStatement().select(connection, resultReader);
        } catch (SQLException e) {
            throw new RuntimeException("Error loading data from DB: " + e.getMessage(), e);
        }
    }

    public void update(Connection connection) {
        try {
            createUpdateStatement().update(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Error updating data in DB: " + e.getMessage(), e);
        }
    }

    protected SelectStatement createSelectStatement() {
        if (batchParams != null) {
            throw new IllegalStateException("Can't use batch params for 'select'");
        }

        return (params == null || params.size() == 0)
                ? new SelectStatementNoParams(sql, connector.getSqlLogger())
                : new SelectStatementWithParams(sql, params, createBinderFactory(), connector.getSqlLogger());
    }

    protected UpdateStatement createUpdateStatement() {
        if (params != null) {
            return new UpdateStatementParamsRow(sql, params, createBinderFactory(), connector.getSqlLogger());
        } else if (batchParams != null) {

            return connector.getMetadata().supportsBatchUpdates()
                    ? new UpdateStatementBatch(sql, batchParams, createBinderFactory(), connector.getSqlLogger())
                    : new UpdateStatementNoBatch(sql, batchParams, createBinderFactory(), connector.getSqlLogger());

        } else {
            return new UpdateStatementNoParams(sql, connector.getSqlLogger());
        }
    }

    protected StatementBinderFactory createBinderFactory() {
        return paramDescriptors != null
                ? new FixedParamsBinderFactory(connector.getBindConverterFactory(), paramDescriptors)
                : new CompiledFromStatementBinderFactory(connector.getBindConverterFactory());
    }


}
