package com.tool.schema.verifier;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface SchemaVerifier {
    VerifyResult verify(Connection srcConn, Connection tidbConn,
                        String schema, String tableName) throws SQLException;

    List<VerifyResult> verifyAll(Connection srcConn, Connection tidbConn,
                                 List<String[]> tableList) throws SQLException;
}
