package com.nhl.dflib.jdbc.connector.tx;

import com.nhl.dflib.DataFrame;
import com.nhl.dflib.jdbc.Jdbc;
import com.nhl.dflib.jdbc.connector.JdbcConnector;
import com.nhl.dflib.jdbc.unit.BaseDbTest;
import com.nhl.dflib.unit.DataFrameAsserts;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.Assert.*;

public class TxIT extends BaseDbTest {

    private JdbcConnector connector;

    @Before
    public void createConnector() {
        this.connector = Jdbc.connector(getDataSource());
    }

    @Test
    public void testRun() {

        DataFrame df1 = DataFrame.newFrame("id", "name", "salary")
                .foldByRow(
                        1L, "n1", 50_000.01,
                        2L, "n2", 120_000.);

        DataFrame df2 = DataFrame.newFrame("id", "name", "salary")
                .foldByRow(
                        3L, "n3", 60_000.01,
                        4L, "n4", 1_000.);

        Tx.newTransaction(connector)
                .run(c -> {
                            c.tableSaver("t1").save(df1);
                            c.tableSaver("t1").save(df2);
                        }
                );

        DataFrame df_12 = connector
                .tableLoader("t1")
                .load()
                .sort("id", true);

        new DataFrameAsserts(df_12, "id", "name", "salary")
                .expectHeight(4)
                .expectRow(0, 1L, "n1", 50_000.01)
                .expectRow(1, 2L, "n2", 120_000.)
                .expectRow(2, 3L, "n3", 60_000.01)
                .expectRow(3, 4L, "n4", 1_000.);
    }

    @Test
    public void testRun_Isolation() {

        Tx.newTransaction(connector)
                .isolation(TxIsolation.read_committed).run(txConnector -> {
                    int il;
                    try {
                        il = txConnector.getConnection().getTransactionIsolation();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(Connection.TRANSACTION_READ_COMMITTED, il);
                }
        );

        Tx.newTransaction(connector)
                .isolation(TxIsolation.serializable).run(txConnector -> {
                    int il;
                    try {
                        il = txConnector.getConnection().getTransactionIsolation();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(Connection.TRANSACTION_SERIALIZABLE, il);
                }
        );
    }

    @Test
    public void testRun_Rollback() {

        DataFrame df1 = DataFrame.newFrame("id", "name", "salary")
                .foldByRow(
                        1L, "n1", 50_000.01,
                        2L, "n2", 120_000.);

        DataFrame df2 = DataFrame.newFrame("id", "name", "salary")
                .foldByRow(
                        3L, "n3", 60_000.01,
                        4L, "n4", 1_000.);

        try {
            Tx.newTransaction(connector)
                    .run(c -> {
                                c.tableSaver("t1").save(df1);
                                c.tableSaver("no_such_table").save(df2);
                            }
                    );
            fail("Exception expected");

        } catch (RuntimeException e) {
            // expected
        }

        DataFrame df_12 = connector
                .tableLoader("t1")
                .load();

        // the transaction must have been rolled back and no data saved
        new DataFrameAsserts(df_12, "id", "name", "salary").expectHeight(0);
    }
}
