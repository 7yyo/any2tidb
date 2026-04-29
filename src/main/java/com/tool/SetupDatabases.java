package com.tool;

import java.sql.*;

/**
 * One-shot utility: creates sample enterprise databases on SQL Server for testing any2tidb.
 * Run: mvn exec:java -Dexec.mainClass="com.tool.SetupDatabases" -q 2>&1
 */
public class SetupDatabases {

    static final String HOST = "127.0.0.1";
    static final int PORT = 1433;
    static final String USER = "sa";
    static final String PASS = "test@123";

    static String masterUrl() {
        return String.format("jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true;loginTimeout=5", HOST, PORT);
    }

    static String dbUrl(String db) {
        return String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;loginTimeout=5", HOST, PORT, db);
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // -- Create databases --
        String[] dbs = {"SalesDB", "HRDB"};
        for (String db : dbs) {
            try (Connection c = DriverManager.getConnection(masterUrl(), USER, PASS);
                 Statement s = c.createStatement()) {
                s.execute("IF DB_ID('" + db + "') IS NULL CREATE DATABASE [" + db + "]");
                System.out.println("Database " + db + " ready.");
            }
        }

        // -- SalesDB tables --
        try (Connection c = DriverManager.getConnection(dbUrl("SalesDB"), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE dbo.Customers (
                    CustomerID      INT IDENTITY(1,1) NOT NULL,
                    CustomerCode    AS 'CUST-' + RIGHT('00000' + CAST(CustomerID AS VARCHAR(5)), 5),
                    CompanyName     NVARCHAR(200) NOT NULL,
                    ContactPerson   NVARCHAR(100) NULL,
                    Email           NVARCHAR(256) NULL,
                    Phone           NVARCHAR(50) NULL,
                    AddressLine1    NVARCHAR(256) NULL,
                    City            NVARCHAR(100) NULL,
                    Country         NVARCHAR(60) NOT NULL DEFAULT 'CN',
                    CreditLimit     DECIMAL(18,2) NOT NULL DEFAULT 0,
                    IsActive        BIT NOT NULL DEFAULT 1,
                    CustomerSince   DATETIME2 NOT NULL DEFAULT GETDATE(),
                    RowGuid         UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
                    Notes           NVARCHAR(MAX) NULL,
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    UpdatedAt       DATETIME2 NULL,
                    CONSTRAINT PK_Customers PRIMARY KEY CLUSTERED (CustomerID),
                    CONSTRAINT UQ_Customers_Email UNIQUE (Email),
                    CONSTRAINT CK_Customers_CreditLimit CHECK (CreditLimit >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.Products (
                    ProductID       INT IDENTITY(1,1) NOT NULL,
                    ProductCode     NVARCHAR(30) NOT NULL,
                    ProductName     NVARCHAR(256) NOT NULL,
                    Category        NVARCHAR(50) NOT NULL,
                    UnitPrice       DECIMAL(18,4) NOT NULL DEFAULT 0,
                    CostPrice       DECIMAL(18,4) NOT NULL DEFAULT 0,
                    StockQuantity   INT NOT NULL DEFAULT 0,
                    ReorderLevel    INT NOT NULL DEFAULT 10,
                    IsDiscontinued  BIT NOT NULL DEFAULT 0,
                    Weight_KG       DECIMAL(8,3) NULL,
                    Specifications  NVARCHAR(MAX) NULL,
                    ProductImage    VARBINARY(MAX) NULL,
                    RowGuid         UNIQUEIDENTIFIER NOT NULL DEFAULT NEWSEQUENTIALID(),
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_Products PRIMARY KEY CLUSTERED (ProductID),
                    CONSTRAINT UQ_Products_Code UNIQUE (ProductCode),
                    CONSTRAINT CK_Products_UnitPrice CHECK (UnitPrice >= 0),
                    CONSTRAINT CK_Products_CostPrice CHECK (CostPrice >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.Orders (
                    OrderID         BIGINT IDENTITY(100001,1) NOT NULL,
                    OrderNumber     AS 'ORD-' + RIGHT('00000000' + CAST(OrderID AS VARCHAR(8)), 8),
                    CustomerID      INT NOT NULL,
                    OrderDate       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    RequiredDate    DATE NULL,
                    ShippedDate     DATE NULL,
                    Status          NVARCHAR(20) NOT NULL DEFAULT 'Pending',
                    ShippingMethod  NVARCHAR(50) NULL,
                    FreightCharge   DECIMAL(10,2) NOT NULL DEFAULT 0,
                    SubTotal        DECIMAL(18,2) NOT NULL,
                    TaxAmount       DECIMAL(18,2) NOT NULL DEFAULT 0,
                    TotalAmount     AS SubTotal + TaxAmount + FreightCharge,
                    Currency        NVARCHAR(3) NOT NULL DEFAULT 'CNY',
                    PaymentTerms    NVARCHAR(30) NOT NULL DEFAULT 'NET30',
                    SalesPerson     NVARCHAR(100) NULL,
                    Remarks         NVARCHAR(MAX) NULL,
                    TrackingGuid    UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    UpdatedAt       DATETIME2 NULL,
                    CONSTRAINT PK_Orders PRIMARY KEY CLUSTERED (OrderID),
                    CONSTRAINT FK_Orders_Customer FOREIGN KEY (CustomerID) REFERENCES dbo.Customers(CustomerID),
                    CONSTRAINT CK_Orders_Status CHECK (Status IN ('Pending','Confirmed','Shipped','Delivered','Cancelled')),
                    CONSTRAINT CK_Orders_Freight CHECK (FreightCharge >= 0),
                    CONSTRAINT CK_Orders_Tax CHECK (TaxAmount >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.OrderDetails (
                    OrderDetailID   BIGINT IDENTITY(1,1) NOT NULL,
                    OrderID         BIGINT NOT NULL,
                    ProductID       INT NOT NULL,
                    Quantity        INT NOT NULL DEFAULT 1,
                    UnitPrice       DECIMAL(18,4) NOT NULL,
                    DiscountPct     DECIMAL(5,2) NOT NULL DEFAULT 0,
                    LineTotal       AS Quantity * UnitPrice * (1 - DiscountPct / 100),
                    CONSTRAINT PK_OrderDetails PRIMARY KEY CLUSTERED (OrderDetailID),
                    CONSTRAINT FK_OrderDetails_Order FOREIGN KEY (OrderID) REFERENCES dbo.Orders(OrderID),
                    CONSTRAINT FK_OrderDetails_Product FOREIGN KEY (ProductID) REFERENCES dbo.Products(ProductID),
                    CONSTRAINT CK_OrderDetails_Quantity CHECK (Quantity > 0),
                    CONSTRAINT CK_OrderDetails_Discount CHECK (DiscountPct >= 0 AND DiscountPct <= 100)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.Invoices (
                    InvoiceID       INT IDENTITY(1,1) NOT NULL,
                    InvoiceNumber   NVARCHAR(30) NOT NULL,
                    OrderID         BIGINT NOT NULL,
                    InvoiceDate     DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    DueDate         DATE NOT NULL,
                    PaidDate        DATE NULL,
                    Amount          DECIMAL(18,2) NOT NULL,
                    TaxAmount       DECIMAL(18,2) NOT NULL DEFAULT 0,
                    TotalAmount     AS Amount + TaxAmount,
                    PaymentMethod   NVARCHAR(30) NULL,
                    IsPaid          AS CASE WHEN PaidDate IS NOT NULL THEN 1 ELSE 0 END,
                    Notes           NVARCHAR(500) NULL,
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_Invoices PRIMARY KEY CLUSTERED (InvoiceID),
                    CONSTRAINT FK_Invoices_Order FOREIGN KEY (OrderID) REFERENCES dbo.Orders(OrderID),
                    CONSTRAINT UQ_Invoices_Number UNIQUE (InvoiceNumber),
                    CONSTRAINT CK_Invoices_Amount CHECK (Amount > 0)
                )
                """);
            s.execute("""
                CREATE NONCLUSTERED INDEX IX_Orders_CustomerID ON dbo.Orders(CustomerID)
                """);
            s.execute("""
                CREATE NONCLUSTERED INDEX IX_Orders_OrderDate ON dbo.Orders(OrderDate DESC)
                """);
            s.execute("""
                CREATE NONCLUSTERED INDEX IX_OrderDetails_OrderID ON dbo.OrderDetails(OrderID)
                """);
            s.execute("""
                CREATE NONCLUSTERED INDEX IX_OrderDetails_ProductID ON dbo.OrderDetails(ProductID)
                """);
            s.execute("""
                CREATE NONCLUSTERED INDEX IX_Invoices_OrderID ON dbo.Invoices(OrderID)
                """);
            System.out.println("SalesDB tables created: Customers, Products, Orders, OrderDetails, Invoices");
        }

        // -- Insert sample data into SalesDB --
        try (Connection c = DriverManager.getConnection(dbUrl("SalesDB"), USER, PASS);
             Statement s = c.createStatement()) {

            s.execute("SET IDENTITY_INSERT dbo.Customers ON");
            s.execute("""
                INSERT INTO dbo.Customers (CustomerID, CompanyName, ContactPerson, Email, Phone, City, Country, CreditLimit, Notes)
                VALUES
                (1, N'环球科技有限公司', N'张伟', 'zhangwei@globaltech.cn', '010-8888-0001', N'北京', 'CN', 500000.00, N'VIP客户'),
                (2, N'太平洋贸易集团', N'李娜', 'lina@pacific-trade.cn', '021-6666-0002', N'上海', 'CN', 300000.00, N'长期合作伙伴'),
                (3, N'南方制造有限公司', N'王强', 'wangq@southmfg.cn', '0755-3333-0003', N'深圳', 'CN', 200000.00, NULL),
                (4, 'GlobalTech Inc.', 'John Smith', 'jsmith@globaltech.com', '+1-415-555-0101', 'San Francisco', 'US', 1000000.00, 'International partner'),
                (5, N'星辰数据有限公司', N'赵敏', 'zhaomin@stardata.cn', '028-9999-0005', N'成都', 'CN', 150000.00, NULL)
                """);
            s.execute("SET IDENTITY_INSERT dbo.Customers OFF");

            s.execute("SET IDENTITY_INSERT dbo.Products ON");
            s.execute("""
                INSERT INTO dbo.Products (ProductID, ProductCode, ProductName, Category, UnitPrice, CostPrice, StockQuantity, Weight_KG, Specifications)
                VALUES
                (1, 'SRV-001', N'高性能服务器 X500', N'硬件', 85000.0000, 62000.0000, 50, 25.500, N'CPU: 64核, 内存: 512GB, 存储: 4TB SSD'),
                (2, 'SRV-002', N'存储阵列 S200', N'硬件', 120000.0000, 88000.0000, 20, 32.000, N'容量: 100TB, RAID 6, 双控制器'),
                (3, 'SW-001', N'企业ERP管理系统', N'软件', 350000.0000, 0.0000, 999, NULL, N'并发用户数: 5000+'),
                (4, 'SW-002', N'数据中台解决方案', N'软件', 500000.0000, 0.0000, 999, NULL, N'含数据治理、数据湖、实时计算引擎'),
                (5, 'NET-001', N'万兆核心交换机', N'网络设备', 45000.0000, 32000.0000, 100, 5.200, N'48口 10G SFP+'),
                (6, 'SRV-003', N'GPU计算节点 G100', N'硬件', 280000.0000, 210000.0000, 15, 18.000, N'8x NVIDIA A100 80GB'),
                (7, 'SVC-001', N'IT运维服务(年度)', N'服务', 200000.0000, 0.0000, 999, NULL, N'7x24小时, 4小时现场响应'),
                (8, 'SVC-002', N'数据库迁移服务', N'服务', 150000.0000, 0.0000, 999, NULL, N'含评估、迁移、验证')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Products OFF");

            s.execute("SET IDENTITY_INSERT dbo.Orders ON");
            s.execute("""
                INSERT INTO dbo.Orders (OrderID, CustomerID, OrderDate, RequiredDate, Status, ShippingMethod, FreightCharge, SubTotal, TaxAmount, SalesPerson)
                VALUES
                (100001, 1, '2026-04-01 09:30:00', '2026-04-15', 'Delivered', N'顺丰速运', 500.00, 435000.00, 56550.00, N'陈经理'),
                (100002, 2, '2026-04-05 14:00:00', '2026-04-20', 'Shipped', N'德邦物流', 1200.00, 620000.00, 80600.00, N'刘主管'),
                (100003, 4, '2026-04-10 11:00:00', '2026-05-01', 'Confirmed', 'DHL', 3500.00, 1500000.00, 0.00, 'Sarah Chen'),
                (100004, 3, '2026-04-15 16:30:00', '2026-04-30', 'Pending', NULL, 0.00, 280000.00, 36400.00, N'陈经理'),
                (100005, 1, '2026-04-20 08:00:00', '2026-05-05', 'Confirmed', N'顺丰速运', 800.00, 700000.00, 91000.00, N'陈经理')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Orders OFF");

            s.execute("SET IDENTITY_INSERT dbo.OrderDetails ON");
            s.execute("""
                INSERT INTO dbo.OrderDetails (OrderDetailID, OrderID, ProductID, Quantity, UnitPrice, DiscountPct)
                VALUES
                (1, 100001, 1, 2, 85000.0000, 0),
                (2, 100001, 5, 3, 45000.0000, 5.00),
                (3, 100001, 7, 1, 200000.0000, 0),
                (4, 100002, 2, 1, 120000.0000, 0),
                (5, 100002, 3, 1, 350000.0000, 10.00),
                (6, 100002, 8, 1, 150000.0000, 0),
                (7, 100003, 4, 3, 500000.0000, 0),
                (8, 100004, 6, 1, 280000.0000, 0),
                (9, 100005, 3, 2, 350000.0000, 0)
                """);
            s.execute("SET IDENTITY_INSERT dbo.OrderDetails OFF");

            s.execute("SET IDENTITY_INSERT dbo.Invoices ON");
            s.execute("""
                INSERT INTO dbo.Invoices (InvoiceID, InvoiceNumber, OrderID, InvoiceDate, DueDate, PaidDate, Amount, TaxAmount, PaymentMethod)
                VALUES
                (1, 'INV-2026-0001', 100001, '2026-04-02', '2026-05-02', '2026-04-28', 435000.00, 56550.00, N'银行转账'),
                (2, 'INV-2026-0002', 100002, '2026-04-06', '2026-05-06', NULL, 620000.00, 80600.00, NULL),
                (3, 'INV-2026-0003', 100004, '2026-04-16', '2026-05-16', NULL, 280000.00, 36400.00, N'银行承兑汇票')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Invoices OFF");

            System.out.println("SalesDB sample data inserted.");
        }

        // -- HRDB tables --
        try (Connection c = DriverManager.getConnection(dbUrl("HRDB"), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE dbo.Departments (
                    DepartmentID    INT IDENTITY(1,1) NOT NULL,
                    DeptCode        NVARCHAR(20) NOT NULL,
                    DeptName        NVARCHAR(100) NOT NULL,
                    ParentDeptID    INT NULL,
                    ManagerID       INT NULL,
                    Budget          DECIMAL(18,2) NOT NULL DEFAULT 0,
                    Headcount       INT NOT NULL DEFAULT 0,
                    Location        NVARCHAR(100) NULL,
                    Description     NVARCHAR(MAX) NULL,
                    IsActive        BIT NOT NULL DEFAULT 1,
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_Departments PRIMARY KEY CLUSTERED (DepartmentID),
                    CONSTRAINT UQ_Departments_Code UNIQUE (DeptCode),
                    CONSTRAINT CK_Departments_Budget CHECK (Budget >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.Employees (
                    EmployeeID      INT IDENTITY(1000,1) NOT NULL,
                    EmployeeCode    AS 'EMP' + RIGHT('00000' + CAST(EmployeeID AS VARCHAR(5)), 5),
                    FirstName       NVARCHAR(50) NOT NULL,
                    LastName        NVARCHAR(50) NOT NULL,
                    FullName        AS LastName + FirstName,
                    Email           NVARCHAR(256) NULL,
                    Phone           NVARCHAR(50) NULL,
                    HireDate        DATE NOT NULL,
                    BirthDate       DATE NULL,
                    Gender          NCHAR(1) NULL,
                    DepartmentID    INT NOT NULL,
                    JobTitle        NVARCHAR(100) NOT NULL,
                    Salary          DECIMAL(18,2) NOT NULL,
                    BonusTarget     DECIMAL(5,2) NOT NULL DEFAULT 0,
                    EmploymentType  NVARCHAR(20) NOT NULL DEFAULT N'全职',
                    IsActive        BIT NOT NULL DEFAULT 1,
                    ManagerID       INT NULL,
                    IdentityNumber  NVARCHAR(30) NULL,
                    Address         NVARCHAR(256) NULL,
                    EmergencyContact NVARCHAR(100) NULL,
                    EducationLevel  NVARCHAR(30) NULL,
                    RowGuid         UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    UpdatedAt       DATETIME2 NULL,
                    CONSTRAINT PK_Employees PRIMARY KEY CLUSTERED (EmployeeID),
                    CONSTRAINT FK_Employees_Dept FOREIGN KEY (DepartmentID) REFERENCES dbo.Departments(DepartmentID),
                    CONSTRAINT UQ_Employees_Email UNIQUE (Email),
                    CONSTRAINT CK_Employees_Salary CHECK (Salary > 0),
                    CONSTRAINT CK_Employees_Gender CHECK (Gender IN (N'M', N'F', N'O')),
                    CONSTRAINT CK_Employees_EmploymentType CHECK (EmploymentType IN (N'全职', N'兼职', N'实习', N'外包', N'顾问'))
                )
                """);
            s.execute("""
                CREATE TABLE dbo.SalaryHistory (
                    HistoryID       BIGINT IDENTITY(1,1) NOT NULL,
                    EmployeeID      INT NOT NULL,
                    EffectiveDate   DATE NOT NULL,
                    EndDate         DATE NULL,
                    OldSalary       DECIMAL(18,2) NOT NULL,
                    NewSalary       DECIMAL(18,2) NOT NULL,
                    ChangeReason    NVARCHAR(200) NULL,
                    ApprovedBy      NVARCHAR(100) NULL,
                    AdjustPct       AS ROUND((NewSalary - OldSalary) / NULLIF(OldSalary, 0) * 100, 2),
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_SalaryHistory PRIMARY KEY CLUSTERED (HistoryID),
                    CONSTRAINT FK_SalaryHistory_Employee FOREIGN KEY (EmployeeID) REFERENCES dbo.Employees(EmployeeID),
                    CONSTRAINT CK_SalaryHistory_NewSalary CHECK (NewSalary > 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.Attendance (
                    AttendanceID    BIGINT IDENTITY(1,1) NOT NULL,
                    EmployeeID      INT NOT NULL,
                    AttendanceDate  DATE NOT NULL,
                    CheckIn         DATETIME2 NULL,
                    CheckOut        DATETIME2 NULL,
                    Status          NVARCHAR(20) NOT NULL DEFAULT 'Present',
                    WorkHours       AS DATEDIFF(MINUTE, CheckIn, CheckOut) / 60.0,
                    OvertimeHours   DECIMAL(4,1) NOT NULL DEFAULT 0,
                    Remark          NVARCHAR(200) NULL,
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_Attendance PRIMARY KEY CLUSTERED (AttendanceID),
                    CONSTRAINT FK_Attendance_Employee FOREIGN KEY (EmployeeID) REFERENCES dbo.Employees(EmployeeID),
                    CONSTRAINT CK_Attendance_Status CHECK (Status IN ('Present','Absent','Late','HalfDay','Leave','BusinessTrip')),
                    CONSTRAINT CK_Attendance_Overtime CHECK (OvertimeHours >= 0)
                )
                """);
            s.execute("""
                CREATE TABLE dbo.PerformanceReview (
                    ReviewID        INT IDENTITY(1,1) NOT NULL,
                    EmployeeID      INT NOT NULL,
                    ReviewPeriod    NVARCHAR(20) NOT NULL,
                    ReviewDate      DATE NOT NULL,
                    OverallRating   NCHAR(1) NOT NULL,
                    GoalAchievement DECIMAL(5,2) NULL,
                    TeamworkScore   DECIMAL(5,2) NULL,
                    TechnicalScore  DECIMAL(5,2) NULL,
                    Strengths       NVARCHAR(MAX) NULL,
                    Improvements    NVARCHAR(MAX) NULL,
                    ReviewerID      INT NULL,
                    IsFinalized     BIT NOT NULL DEFAULT 0,
                    CreatedAt       DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    CONSTRAINT PK_PerformanceReview PRIMARY KEY CLUSTERED (ReviewID),
                    CONSTRAINT FK_PerfReview_Employee FOREIGN KEY (EmployeeID) REFERENCES dbo.Employees(EmployeeID),
                    CONSTRAINT CK_PerfReview_Rating CHECK (OverallRating IN ('S','A','B','C','D')),
                    CONSTRAINT CK_PerfReview_Scores CHECK (GoalAchievement BETWEEN 0 AND 100 AND TeamworkScore BETWEEN 0 AND 100 AND TechnicalScore BETWEEN 0 AND 100)
                )
                """);

            // Indexes for HRDB
            s.execute("CREATE NONCLUSTERED INDEX IX_Employees_DeptID ON dbo.Employees(DepartmentID)");
            s.execute("CREATE NONCLUSTERED INDEX IX_Employees_Name ON dbo.Employees(LastName, FirstName)");
            s.execute("CREATE NONCLUSTERED INDEX IX_SalaryHistory_EmployeeID ON dbo.SalaryHistory(EmployeeID)");
            s.execute("CREATE NONCLUSTERED INDEX IX_Attendance_EmployeeID ON dbo.Attendance(EmployeeID)");
            s.execute("CREATE NONCLUSTERED INDEX IX_Attendance_Date ON dbo.Attendance(AttendanceDate)");
            s.execute("CREATE NONCLUSTERED INDEX IX_PerfReview_EmployeeID ON dbo.PerformanceReview(EmployeeID)");

            // Self-referencing FKs
            s.execute("ALTER TABLE dbo.Departments ADD CONSTRAINT FK_Departments_Parent FOREIGN KEY (ParentDeptID) REFERENCES dbo.Departments(DepartmentID)");
            s.execute("ALTER TABLE dbo.Employees ADD CONSTRAINT FK_Employees_Manager FOREIGN KEY (ManagerID) REFERENCES dbo.Employees(EmployeeID)");
            s.execute("ALTER TABLE dbo.PerformanceReview ADD CONSTRAINT FK_PerfReview_Reviewer FOREIGN KEY (ReviewerID) REFERENCES dbo.Employees(EmployeeID)");

            System.out.println("HRDB tables created: Departments, Employees, SalaryHistory, Attendance, PerformanceReview");
        }

        // -- Insert sample data into HRDB --
        try (Connection c = DriverManager.getConnection(dbUrl("HRDB"), USER, PASS);
             Statement s = c.createStatement()) {

            s.execute("SET IDENTITY_INSERT dbo.Departments ON");
            s.execute("""
                INSERT INTO dbo.Departments (DepartmentID, DeptCode, DeptName, ParentDeptID, Budget, Headcount, Location, Description)
                VALUES
                (1, 'EXEC', N'总裁办', NULL, 5000000.00, 8, N'总部大楼18层', N'公司高层管理'),
                (2, 'TECH', N'技术部', NULL, 30000000.00, 120, N'总部大楼10-12层', N'核心技术研发'),
                (3, 'SALES', N'销售部', NULL, 15000000.00, 60, N'总部大楼6层', N'全国销售管理'),
                (4, 'HR', N'人力资源部', NULL, 5000000.00, 20, N'总部大楼5层', N'人力资源管理'),
                (5, 'FIN', N'财务部', NULL, 3000000.00, 15, N'总部大楼5层', N'财务管理'),
                (6, 'AI-Lab', N'AI实验室', 2, 20000000.00, 40, N'总部大楼12层', N'人工智能研究'),
                (7, 'Cloud-Ops', N'云平台运维', 2, 10000000.00, 30, N'总部大楼11层', N'私有云与混合云运维'),
                (8, 'North-Sales', N'北方区销售', 3, 6000000.00, 25, N'北京分公司', N'京津冀及东北区域'),
                (9, 'South-Sales', N'南方区销售', 3, 5000000.00, 20, N'深圳分公司', N'华南区域')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Departments OFF");

            s.execute("SET IDENTITY_INSERT dbo.Employees ON");
            s.execute("""
                INSERT INTO dbo.Employees (EmployeeID, FirstName, LastName, Email, Phone, HireDate, BirthDate, Gender, DepartmentID, JobTitle, Salary, BonusTarget, EmploymentType, ManagerID, IdentityNumber, EducationLevel)
                VALUES
                (1000, N'国栋', N'李', 'liguodong@company.cn', '010-8888-1000', '2015-03-01', '1978-06-15', N'M', 1, N'CEO', 500000.00, 30.00, N'全职', NULL, '310101197806152345', N'博士'),
                (1001, N'明辉', N'王', 'wangmh@company.cn', '010-8888-1001', '2016-07-15', '1982-09-20', N'M', 2, N'CTO', 400000.00, 25.00, N'全职', 1000, '310101198209202456', N'博士'),
                (1002, N'芳', N'陈', 'chenfang@company.cn', '010-8888-1002', '2017-01-10', '1985-03-08', N'F', 3, N'销售总监', 350000.00, 20.00, N'全职', 1000, '310101198503083567', N'硕士'),
                (1003, N'雪梅', N'刘', 'liuxm@company.cn', '010-8888-1003', '2018-04-20', '1990-11-12', N'F', 4, N'HR总监', 250000.00, 15.00, N'全职', 1000, '310101199011124678', N'硕士'),
                (1004, N'志远', N'张', 'zhangzy@company.cn', '010-8888-1004', '2019-09-01', '1992-04-25', N'M', 6, N'AI首席研究员', 350000.00, 20.00, N'全职', 1001, '310101199204255789', N'博士'),
                (1005, N'晓宇', N'赵', 'zhaoxy@company.cn', '010-8888-1005', '2020-02-15', '1993-07-30', N'M', 7, N'云架构师', 280000.00, 15.00, N'全职', 1001, '310101199307306890', N'硕士'),
                (1006, N'雪', N'周', 'zhou.xue@company.cn', '021-6666-1006', '2021-06-01', '1995-01-18', N'F', 8, N'区域销售经理', 200000.00, 15.00, N'全职', 1002, '310101199501187901', N'本科'),
                (1007, N'浩然', N'孙', 'sunhr@company.cn', '0755-3333-1007', '2022-03-15', '1994-09-05', N'M', 9, N'区域销售经理', 180000.00, 15.00, N'全职', 1002, '310101199409058012', N'硕士'),
                (1008, N'静', N'吴', 'wujing@company.cn', '010-8888-1008', '2022-08-01', '1996-12-20', N'F', 5, N'财务经理', 220000.00, 10.00, N'全职', 1000, '310101199612209123', N'硕士'),
                (1009, N'鹏飞', N'马', 'mapf@company.cn', '010-8888-1009', '2023-01-10', '1997-05-15', N'M', 6, N'算法工程师', 180000.00, 10.00, N'全职', 1004, '310101199705159234', N'硕士'),
                (1010, N'欣怡', N'林', 'linxy@company.cn', '010-8888-1010', '2023-09-01', '1998-08-08', N'F', 4, N'招聘主管', 120000.00, 5.00, N'全职', 1003, '310101199808089345', N'本科'),
                (1011, N'子涵', N'黄', 'huangzh@company.cn', '010-8888-1011', '2024-03-01', '2000-02-14', N'M', 7, N'DevOps工程师', 150000.00, 10.00, N'全职', 1005, '310101200002149456', N'本科'),
                (1012, N'雨桐', N'郑', 'zhengyutong@company.cn', '010-8888-1012', '2024-07-15', '1999-10-01', N'F', 5, N'会计', 100000.00, 5.00, N'全职', 1008, '310101199910019567', N'本科')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Employees OFF");

            s.execute("SET IDENTITY_INSERT dbo.SalaryHistory ON");
            s.execute("""
                INSERT INTO dbo.SalaryHistory (HistoryID, EmployeeID, EffectiveDate, EndDate, OldSalary, NewSalary, ChangeReason, ApprovedBy)
                VALUES
                (1, 1000, '2024-01-01', '2024-12-31', 450000.00, 500000.00, N'年度调薪', N'董事会'),
                (2, 1004, '2025-01-01', NULL, 300000.00, 350000.00, N'晋升调薪', N'李国栋'),
                (3, 1007, '2024-07-01', '2025-06-30', 150000.00, 180000.00, N'年终评估调薪', N'陈芳'),
                (4, 1009, '2025-03-01', NULL, 150000.00, 180000.00, N'项目奖励调薪', N'王明辉'),
                (5, 1010, '2025-01-01', NULL, 100000.00, 120000.00, N'年度调薪', N'刘雪梅')
                """);
            s.execute("SET IDENTITY_INSERT dbo.SalaryHistory OFF");

            s.execute("SET IDENTITY_INSERT dbo.Attendance ON");
            s.execute("""
                INSERT INTO dbo.Attendance (AttendanceID, EmployeeID, AttendanceDate, CheckIn, CheckOut, Status, OvertimeHours, Remark)
                VALUES
                (1, 1000, '2026-04-27', '2026-04-27 07:55:00', '2026-04-27 19:30:00', 'Present', 2.5, NULL),
                (2, 1004, '2026-04-27', '2026-04-27 09:30:00', '2026-04-27 22:00:00', 'Late', 4.0, N'模型训练上线'),
                (3, 1005, '2026-04-27', '2026-04-27 08:00:00', '2026-04-27 18:00:00', 'Present', 0, NULL),
                (4, 1000, '2026-04-28', '2026-04-28 08:05:00', '2026-04-28 18:30:00', 'Present', 0.5, NULL),
                (5, 1005, '2026-04-28', NULL, NULL, 'BusinessTrip', 0, N'北京出差-客户现场'),
                (6, 1011, '2026-04-28', '2026-04-28 08:45:00', NULL, 'Present', 0, N'值班待命中')
                """);
            s.execute("SET IDENTITY_INSERT dbo.Attendance OFF");

            s.execute("SET IDENTITY_INSERT dbo.PerformanceReview ON");
            s.execute("""
                INSERT INTO dbo.PerformanceReview (ReviewID, EmployeeID, ReviewPeriod, ReviewDate, OverallRating, GoalAchievement, TeamworkScore, TechnicalScore, Strengths, Improvements, ReviewerID, IsFinalized)
                VALUES
                (1, 1004, '2025-H2', '2026-01-15', 'S', 95.00, 92.00, 98.00, N'主导大模型推理优化项目，性能提升300%', N'团队分享次数可增加', 1001, 1),
                (2, 1005, '2025-H2', '2026-01-15', 'A', 90.00, 95.00, 92.00, N'完成混合云架构迁移，零事故', NULL, 1001, 1),
                (3, 1009, '2025-H2', '2026-01-18', 'B', 85.00, 88.00, 90.00, N'模型优化工作中表现优秀', N'代码文档需要完善', 1004, 1),
                (4, 1011, '2025-H2', '2026-01-20', 'A', 92.00, 85.00, 90.00, N'CI/CD流程改造提升了发布效率', NULL, 1005, 1)
                """);
            s.execute("SET IDENTITY_INSERT dbo.PerformanceReview OFF");

            System.out.println("HRDB sample data inserted.");
        }

        System.out.println("\nDone. SalesDB and HRDB are ready.");
    }
}
