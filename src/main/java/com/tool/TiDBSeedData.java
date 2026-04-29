package com.tool;

import java.sql.*;

/**
 * Insert a few rows into some TiDB tables to test DROP protection.
 * Usage: mvn exec:java -Dexec.mainClass="com.tool.TiDBSeedData" -q
 */
public class TiDBSeedData {

    static final String URL = "jdbc:mysql://127.0.0.1:4000?useSSL=false&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASS = "";

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            // Seed Customers table
            try (Statement s = c.createStatement()) {
                s.execute("USE SalesDB");
                s.execute("INSERT INTO Customers (CompanyName, ContactPerson, Email, Phone, City, Country, CreditLimit, RowGuid) " +
                          "VALUES ('Test Corp', 'Test User', 'test@test.com', '12345', 'Shanghai', 'CN', 100000, UUID())");
                System.out.println("TiDB SalesDB.Customers: 1 row inserted");
            } catch (Exception e) {
                System.out.println("Customers skipped: " + e.getMessage());
            }

            // Seed Products table
            try (Statement s = c.createStatement()) {
                s.execute("USE SalesDB");
                s.execute("INSERT INTO Products (ProductCode, ProductName, Category, UnitPrice, CostPrice, StockQuantity, RowGuid) " +
                          "VALUES ('TST-001', 'Test Product', 'Test', 99.99, 50.00, 10, UUID())");
                System.out.println("TiDB SalesDB.Products: 1 row inserted");
            } catch (Exception e) {
                System.out.println("Products skipped: " + e.getMessage());
            }

            System.out.println("Done.");
        }
    }
}
