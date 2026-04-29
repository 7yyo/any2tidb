package com.tool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Insert sample rows into an existing SQL Server table.
 * Usage: mvn exec:java -Dexec.mainClass="com.tool.InsertSampleData" -q
 */
public class InsertSampleData {

    static final String DB = "SalesDB";
    static final String URL = String.format(
            "jdbc:sqlserver://127.0.0.1:1433;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5", DB);
    static final String USER = "sa";
    static final String PASS = "test@123";

    public static void main(String[] args) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {

            // Add more orders
            s.execute("""
                INSERT INTO dbo.Orders (CustomerID, OrderDate, RequiredDate, Status, ShippingMethod, FreightCharge, SubTotal, TaxAmount, SalesPerson)
                VALUES
                (2, '2026-04-22 10:00:00', '2026-05-10', 'Confirmed', N'德邦物流', 600.00, 330000.00, 42900.00, N'刘主管'),
                (3, '2026-04-25 13:30:00', '2026-05-08', 'Pending', NULL, 0.00, 120000.00, 15600.00, N'陈经理'),
                (5, '2026-04-27 09:15:00', '2026-05-15', 'Confirmed', N'顺丰速运', 350.00, 200000.00, 26000.00, N'陈经理')
                """);

            // Collect new order IDs (separate statement to avoid ResultSet conflict)
            List<Integer> newIds = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT OrderID FROM dbo.Orders WHERE OrderID > 100005 ORDER BY OrderID")) {
                while (rs.next()) newIds.add(rs.getInt(1));
            }

            // Insert order details for each new order
            int[][] products = {{1, 8, 5}, {6}, {7}};
            for (int i = 0; i < newIds.size() && i < products.length; i++) {
                int oid = newIds.get(i);
                try (Statement st = c.createStatement()) {
                    for (int pid : products[i]) {
                        st.execute(String.format(
                            "INSERT INTO dbo.OrderDetails (OrderID, ProductID, Quantity, UnitPrice, DiscountPct) " +
                            "SELECT %d, %d, 1, UnitPrice, 0 FROM dbo.Products WHERE ProductID = %d",
                            oid, pid, pid));
                    }
                }
            }

            // Add a product
            s.execute("SET IDENTITY_INSERT dbo.Products ON");
            s.execute("""
                INSERT INTO dbo.Products (ProductID, ProductCode, ProductName, Category, UnitPrice, CostPrice, StockQuantity, Specifications)
                VALUES
                (9, 'SEC-001', N'零信任安全网关', N'安全产品', 220000.0000, 165000.0000, 30, N'吞吐量: 40Gbps, 用户数: 50000+')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Products OFF");

            System.out.println("Inserted 3 orders + order details + 1 product into " + DB);
        }
    }
}
