package com.tool.compare;

import java.sql.Connection;

public interface DataComparator {
    ComparisonReport compare(Connection source, Connection target, ComparisonConfig config);
}
