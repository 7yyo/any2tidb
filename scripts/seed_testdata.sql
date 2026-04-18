SET QUOTED_IDENTIFIER ON;
GO
-- ============================================================
-- ms2tidb 手动测试用生产级 Schema
-- 数据库: salesdb, hrdb, inventorydb
-- 不与 testdb 下任何 it_* / vt_* 表冲突
-- 执行方式: sqlcmd -S 127.0.0.1,1433 -U sa -P 'Test1234!' -C -i seed_testdata.sql
-- ============================================================

-- ============================================================
-- 1. salesdb  —— 订单 / 客户 / 商品 / 发票
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'salesdb')
    CREATE DATABASE salesdb;
GO
USE salesdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'sales')
    EXEC('CREATE SCHEMA sales');
IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'finance')
    EXEC('CREATE SCHEMA finance');
GO

-- ── customers ────────────────────────────────────────────────
-- 简单查找表：CHECK 约束、DEFAULT、列注释、普通索引
IF OBJECT_ID('sales.customers','U') IS NULL
CREATE TABLE sales.customers (
    customer_id   INT           NOT NULL IDENTITY(1,1),
    customer_code VARCHAR(20)   NOT NULL,
    company_name  NVARCHAR(200) NOT NULL,
    contact_name  NVARCHAR(100) NULL,
    email         VARCHAR(254)  NULL,
    phone         VARCHAR(30)   NULL,
    country       CHAR(2)       NOT NULL DEFAULT 'CN',
    is_active     BIT           NOT NULL DEFAULT 1,
    credit_limit  DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_customers        PRIMARY KEY (customer_id),
    CONSTRAINT UQ_customers_code   UNIQUE      (customer_code),
    CONSTRAINT CK_customers_email  CHECK (email LIKE '%@%' OR email IS NULL),
    CONSTRAINT CK_customers_credit CHECK (credit_limit >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.customers') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'企业客户主表',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'customers';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.customers')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('sales.customers'),'customer_code','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'客户唯一编码，业务系统生成',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'customers',
        @level2type=N'COLUMN', @level2name=N'customer_code';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.customers') AND name = 'IX_customers_country')
    CREATE INDEX IX_customers_country ON sales.customers (country);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.customers') AND name = 'IX_customers_active')
    CREATE INDEX IX_customers_active  ON sales.customers (is_active, country);
GO

-- ── products ─────────────────────────────────────────────────
-- XML 列、filtered index、列注释
IF OBJECT_ID('sales.products','U') IS NULL
CREATE TABLE sales.products (
    product_id    INT           NOT NULL IDENTITY(1,1),
    sku           VARCHAR(50)   NOT NULL,
    product_name  NVARCHAR(300) NOT NULL,
    category_id   INT           NOT NULL,
    unit_price    DECIMAL(12,4) NOT NULL,
    cost_price    DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
    weight_kg     DECIMAL(8,3)  NULL,
    stock_qty     INT           NOT NULL DEFAULT 0,
    reorder_level INT           NOT NULL DEFAULT 10,
    is_active     BIT           NOT NULL DEFAULT 1,
    attributes    XML           NULL,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_products       PRIMARY KEY (product_id),
    CONSTRAINT UQ_products_sku   UNIQUE      (sku),
    CONSTRAINT CK_products_price CHECK (unit_price > 0),
    CONSTRAINT CK_products_cost  CHECK (cost_price >= 0),
    CONSTRAINT CK_products_stock CHECK (stock_qty >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.products') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'商品目录',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'products';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.products')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('sales.products'),'sku','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'SKU编码，全局唯一',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'products',
        @level2type=N'COLUMN', @level2name=N'sku';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.products')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('sales.products'),'attributes','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'扩展属性（颜色/尺码等），JSON 格式存 XML',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'products',
        @level2type=N'COLUMN', @level2name=N'attributes';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.products') AND name = 'IX_products_category')
    CREATE INDEX IX_products_category ON sales.products (category_id, is_active);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.products') AND name = 'IX_products_stock')
    CREATE INDEX IX_products_stock    ON sales.products (stock_qty) WHERE stock_qty <= 20;
GO

-- ── orders ───────────────────────────────────────────────────
-- 核心业务表：BIGINT PK、FK、多索引、TINYINT 枚举
IF OBJECT_ID('sales.orders','U') IS NULL
CREATE TABLE sales.orders (
    order_id        BIGINT        NOT NULL IDENTITY(1,1),
    order_no        VARCHAR(30)   NOT NULL,
    customer_id     INT           NOT NULL,
    order_date      DATE          NOT NULL,
    required_date   DATE          NULL,
    shipped_date    DATE          NULL,
    status          TINYINT       NOT NULL DEFAULT 1,
    channel         VARCHAR(20)   NOT NULL DEFAULT 'web',
    currency        CHAR(3)       NOT NULL DEFAULT 'CNY',
    subtotal        DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    tax_amount      DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    freight         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_amount    DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    remark          NVARCHAR(500) NULL,
    created_by      INT           NOT NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at      DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_orders          PRIMARY KEY (order_id),
    CONSTRAINT UQ_orders_no       UNIQUE      (order_no),
    CONSTRAINT FK_orders_customer FOREIGN KEY (customer_id) REFERENCES sales.customers (customer_id),
    CONSTRAINT CK_orders_status   CHECK (status IN (1,2,3,4,9)),
    CONSTRAINT CK_orders_total    CHECK (total_amount >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.orders') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'销售订单主表',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'orders';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.orders') AND name = 'IX_orders_customer')
    CREATE INDEX IX_orders_customer ON sales.orders (customer_id, order_date);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.orders') AND name = 'IX_orders_status')
    CREATE INDEX IX_orders_status   ON sales.orders (status, order_date);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.orders') AND name = 'IX_orders_date')
    CREATE INDEX IX_orders_date     ON sales.orders (order_date DESC);
GO

-- ── order_lines ──────────────────────────────────────────────
-- 复合 PK、INCLUDE 索引
IF OBJECT_ID('sales.order_lines','U') IS NULL
CREATE TABLE sales.order_lines (
    order_id      BIGINT        NOT NULL,
    line_no       SMALLINT      NOT NULL,
    product_id    INT           NOT NULL,
    quantity      INT           NOT NULL,
    unit_price    DECIMAL(12,4) NOT NULL,
    discount_pct  DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    line_total    DECIMAL(16,2) NOT NULL,
    note          NVARCHAR(200) NULL,
    CONSTRAINT PK_order_lines    PRIMARY KEY (order_id, line_no),
    CONSTRAINT FK_lines_order    FOREIGN KEY (order_id)   REFERENCES sales.orders   (order_id),
    CONSTRAINT FK_lines_product  FOREIGN KEY (product_id) REFERENCES sales.products (product_id),
    CONSTRAINT CK_lines_qty      CHECK (quantity > 0),
    CONSTRAINT CK_lines_disc     CHECK (discount_pct BETWEEN 0 AND 100)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.order_lines') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'订单明细行',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'order_lines';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.order_lines') AND name = 'IX_orderlines_product')
    CREATE INDEX IX_orderlines_product ON sales.order_lines (product_id) INCLUDE (quantity, line_total);
GO

-- ── finance.invoices ─────────────────────────────────────────
-- MONEY 类型、跨 schema FK
IF OBJECT_ID('finance.invoices','U') IS NULL
CREATE TABLE finance.invoices (
    invoice_id   INT          NOT NULL IDENTITY(1,1),
    invoice_no   VARCHAR(30)  NOT NULL,
    order_id     BIGINT       NOT NULL,
    invoice_date DATE         NOT NULL,
    due_date     DATE         NOT NULL,
    amount       MONEY        NOT NULL,
    paid_amount  MONEY        NOT NULL DEFAULT 0,
    status       TINYINT      NOT NULL DEFAULT 1,
    created_at   DATETIME2(3) NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_invoices        PRIMARY KEY (invoice_id),
    CONSTRAINT UQ_invoices_no     UNIQUE      (invoice_no),
    CONSTRAINT FK_invoices_order  FOREIGN KEY (order_id) REFERENCES sales.orders (order_id),
    CONSTRAINT CK_invoices_paid   CHECK (paid_amount >= 0),
    CONSTRAINT CK_invoices_status CHECK (status IN (1,2,3))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('finance.invoices') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'应收发票',
        @level0type=N'SCHEMA', @level0name=N'finance',
        @level1type=N'TABLE',  @level1name=N'invoices';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('finance.invoices') AND name = 'IX_invoices_order')
    CREATE INDEX IX_invoices_order ON finance.invoices (order_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('finance.invoices') AND name = 'IX_invoices_due')
    CREATE INDEX IX_invoices_due   ON finance.invoices (due_date, status);
GO


-- ============================================================
-- 2. hrdb  —— 组织 / 员工 / 考勤 / 薪资
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'hrdb')
    CREATE DATABASE hrdb;
GO
USE hrdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'hr')
    EXEC('CREATE SCHEMA hr');
GO

-- ── departments ──────────────────────────────────────────────
-- 自引用 FK（树形结构）
IF OBJECT_ID('hr.departments','U') IS NULL
CREATE TABLE hr.departments (
    dept_id   SMALLINT      NOT NULL IDENTITY(1,1),
    dept_code VARCHAR(10)   NOT NULL,
    dept_name NVARCHAR(100) NOT NULL,
    parent_id SMALLINT      NULL,
    manager_id INT          NULL,
    level_no  TINYINT       NOT NULL DEFAULT 1,
    is_active BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_departments      PRIMARY KEY (dept_id),
    CONSTRAINT UQ_departments_code UNIQUE      (dept_code),
    CONSTRAINT FK_dept_parent      FOREIGN KEY (parent_id) REFERENCES hr.departments (dept_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.departments') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'部门组织树',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'departments';
GO

-- ── employees ────────────────────────────────────────────────
-- 大表：多 UNIQUE、自引用 FK、多索引、列注释
IF OBJECT_ID('hr.employees','U') IS NULL
CREATE TABLE hr.employees (
    emp_id         INT           NOT NULL IDENTITY(10000,1),
    emp_no         VARCHAR(20)   NOT NULL,
    id_card        CHAR(18)      NULL,
    first_name     NVARCHAR(50)  NOT NULL,
    last_name      NVARCHAR(50)  NOT NULL,
    gender         CHAR(1)       NOT NULL DEFAULT 'M',
    birth_date     DATE          NULL,
    hire_date      DATE          NOT NULL,
    termination_dt DATE          NULL,
    dept_id        SMALLINT      NOT NULL,
    job_title      NVARCHAR(100) NULL,
    manager_id     INT           NULL,
    email          VARCHAR(254)  NOT NULL,
    mobile         VARCHAR(20)   NULL,
    base_salary    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    is_active      BIT           NOT NULL DEFAULT 1,
    photo_url      VARCHAR(500)  NULL,
    created_at     DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at     DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_employees       PRIMARY KEY (emp_id),
    CONSTRAINT UQ_employees_no    UNIQUE (emp_no),
    CONSTRAINT UQ_employees_email UNIQUE (email),
    CONSTRAINT FK_emp_dept        FOREIGN KEY (dept_id)    REFERENCES hr.departments (dept_id),
    CONSTRAINT FK_emp_manager     FOREIGN KEY (manager_id) REFERENCES hr.employees   (emp_id),
    CONSTRAINT CK_emp_gender      CHECK (gender IN ('M','F','X')),
    CONSTRAINT CK_emp_salary      CHECK (base_salary >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.employees') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'员工主档',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'employees';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.employees')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('hr.employees'),'emp_no','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'工号，入职时系统生成',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'employees',
        @level2type=N'COLUMN', @level2name=N'emp_no';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.employees')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('hr.employees'),'id_card','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'身份证号，可为空（外籍）',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'employees',
        @level2type=N'COLUMN', @level2name=N'id_card';
GO
-- departments.manager_id 后向引用 employees，employees 建完后补 FK
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_dept_manager' AND parent_object_id = OBJECT_ID('hr.departments'))
    ALTER TABLE hr.departments
        ADD CONSTRAINT FK_dept_manager FOREIGN KEY (manager_id) REFERENCES hr.employees (emp_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.employees') AND name = 'IX_emp_dept')
    CREATE INDEX IX_emp_dept    ON hr.employees (dept_id, is_active);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.employees') AND name = 'IX_emp_manager')
    CREATE INDEX IX_emp_manager ON hr.employees (manager_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.employees') AND name = 'IX_emp_hire')
    CREATE INDEX IX_emp_hire    ON hr.employees (hire_date);
GO

-- ── attendance ───────────────────────────────────────────────
-- 高频写入宽表：BIGINT IDENTITY、UNIQUE 复合键
IF OBJECT_ID('hr.attendance','U') IS NULL
CREATE TABLE hr.attendance (
    att_id     BIGINT        NOT NULL IDENTITY(1,1),
    emp_id     INT           NOT NULL,
    att_date   DATE          NOT NULL,
    check_in   DATETIME2(0)  NULL,
    check_out  DATETIME2(0)  NULL,
    work_hours DECIMAL(4,2)  NULL,
    overtime_h DECIMAL(4,2)  NOT NULL DEFAULT 0.00,
    leave_type TINYINT       NULL,
    source     TINYINT       NOT NULL DEFAULT 1,
    remark     NVARCHAR(200) NULL,
    CONSTRAINT PK_attendance          PRIMARY KEY (att_id),
    CONSTRAINT UQ_attendance_emp_date UNIQUE (emp_id, att_date),
    CONSTRAINT FK_att_emp             FOREIGN KEY (emp_id) REFERENCES hr.employees (emp_id),
    CONSTRAINT CK_att_hours           CHECK (work_hours IS NULL OR work_hours BETWEEN 0 AND 24)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.attendance') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'员工每日考勤记录',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'attendance';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.attendance') AND name = 'IX_att_emp_date')
    CREATE INDEX IX_att_emp_date ON hr.attendance (emp_id, att_date DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.attendance') AND name = 'IX_att_date')
    CREATE INDEX IX_att_date     ON hr.attendance (att_date);
GO

-- ── payroll ──────────────────────────────────────────────────
-- 薪资结算：多 DECIMAL 列、UNIQUE 三列约束
IF OBJECT_ID('hr.payroll','U') IS NULL
CREATE TABLE hr.payroll (
    payroll_id   INT           NOT NULL IDENTITY(1,1),
    emp_id       INT           NOT NULL,
    pay_year     SMALLINT      NOT NULL,
    pay_month    TINYINT       NOT NULL,
    base_salary  DECIMAL(12,2) NOT NULL,
    overtime_pay DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    bonus        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    allowance    DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    deduction    DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    social_ins   DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    housing_fund DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    income_tax   DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    net_pay      DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status       TINYINT       NOT NULL DEFAULT 1,
    paid_at      DATETIME2(3)  NULL,
    created_at   DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_payroll            PRIMARY KEY (payroll_id),
    CONSTRAINT UQ_payroll_emp_period UNIQUE (emp_id, pay_year, pay_month),
    CONSTRAINT FK_payroll_emp        FOREIGN KEY (emp_id) REFERENCES hr.employees (emp_id),
    CONSTRAINT CK_payroll_month      CHECK (pay_month BETWEEN 1 AND 12),
    CONSTRAINT CK_payroll_net        CHECK (net_pay >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.payroll') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'月度薪资结算单',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'payroll';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.payroll') AND name = 'IX_payroll_emp')
    CREATE INDEX IX_payroll_emp    ON hr.payroll (emp_id, pay_year, pay_month);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.payroll') AND name = 'IX_payroll_status')
    CREATE INDEX IX_payroll_status ON hr.payroll (status, pay_year, pay_month);
GO


-- ============================================================
-- 3. inventorydb  —— 仓库 / 库位 / 库存账 / 流水
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'inventorydb')
    CREATE DATABASE inventorydb;
GO
USE inventorydb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'wms')
    EXEC('CREATE SCHEMA wms');
GO

-- ── warehouses ───────────────────────────────────────────────
IF OBJECT_ID('wms.warehouses','U') IS NULL
CREATE TABLE wms.warehouses (
    wh_id       SMALLINT      NOT NULL IDENTITY(1,1),
    wh_code     VARCHAR(10)   NOT NULL,
    wh_name     NVARCHAR(100) NOT NULL,
    city        NVARCHAR(50)  NULL,
    address     NVARCHAR(300) NULL,
    capacity_m2 DECIMAL(10,2) NULL,
    is_active   BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_warehouses      PRIMARY KEY (wh_id),
    CONSTRAINT UQ_warehouses_code UNIQUE (wh_code)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.warehouses') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'仓库主档',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'warehouses';
GO

-- ── locations ────────────────────────────────────────────────
-- 库位：多类型列、UNIQUE 复合键
IF OBJECT_ID('wms.locations','U') IS NULL
CREATE TABLE wms.locations (
    loc_id     INT          NOT NULL IDENTITY(1,1),
    wh_id      SMALLINT     NOT NULL,
    loc_code   VARCHAR(20)  NOT NULL,
    zone       VARCHAR(10)  NULL,
    aisle      VARCHAR(5)   NULL,
    bay        SMALLINT     NULL,
    level_no   TINYINT      NULL,
    loc_type   TINYINT      NOT NULL DEFAULT 1,
    max_weight DECIMAL(8,2) NULL,
    is_active  BIT          NOT NULL DEFAULT 1,
    CONSTRAINT PK_locations      PRIMARY KEY (loc_id),
    CONSTRAINT UQ_locations_code UNIQUE (wh_id, loc_code),
    CONSTRAINT FK_loc_wh         FOREIGN KEY (wh_id) REFERENCES wms.warehouses (wh_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.locations') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'仓库库位',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'locations';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.locations') AND name = 'IX_locations_wh')
    CREATE INDEX IX_locations_wh ON wms.locations (wh_id, is_active);
GO

-- ── inventory ────────────────────────────────────────────────
-- 计算列 qty_available、filtered index、DECIMAL(12,4) 成本
IF OBJECT_ID('wms.inventory','U') IS NULL
CREATE TABLE wms.inventory (
    inv_id           BIGINT        NOT NULL IDENTITY(1,1),
    wh_id            SMALLINT      NOT NULL,
    loc_id           INT           NULL,
    sku              VARCHAR(50)   NOT NULL,
    batch_no         VARCHAR(30)   NULL,
    qty_on_hand      INT           NOT NULL DEFAULT 0,
    qty_reserved     INT           NOT NULL DEFAULT 0,
    qty_available    AS (qty_on_hand - qty_reserved),
    unit_cost        DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
    expiry_date      DATE          NULL,
    last_counted_at  DATETIME2(3)  NULL,
    updated_at       DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_inventory              PRIMARY KEY (inv_id),
    CONSTRAINT UQ_inventory_loc_sku_batch UNIQUE (wh_id, loc_id, sku, batch_no),
    CONSTRAINT FK_inv_wh                 FOREIGN KEY (wh_id)   REFERENCES wms.warehouses (wh_id),
    CONSTRAINT FK_inv_loc                FOREIGN KEY (loc_id)  REFERENCES wms.locations  (loc_id),
    CONSTRAINT CK_inv_qty_hand           CHECK (qty_on_hand >= 0),
    CONSTRAINT CK_inv_qty_reserved       CHECK (qty_reserved >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.inventory') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'库存账',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'inventory';
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.inventory')
                 AND minor_id = COLUMNPROPERTY(OBJECT_ID('wms.inventory'),'qty_available','ColumnId')
                 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'可用量 = 在手 - 锁定，计算列',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'inventory',
        @level2type=N'COLUMN', @level2name=N'qty_available';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.inventory') AND name = 'IX_inv_sku')
    CREATE INDEX IX_inv_sku    ON wms.inventory (sku, wh_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.inventory') AND name = 'IX_inv_expiry')
    CREATE INDEX IX_inv_expiry ON wms.inventory (expiry_date) WHERE expiry_date IS NOT NULL;
GO

-- ── stock_movements ──────────────────────────────────────────
-- 仅追加流水：极宽表、多索引、INCLUDE 索引、filtered index
IF OBJECT_ID('wms.stock_movements','U') IS NULL
CREATE TABLE wms.stock_movements (
    mvt_id     BIGINT        NOT NULL IDENTITY(1,1),
    mvt_type   TINYINT       NOT NULL,
    mvt_no     VARCHAR(30)   NOT NULL,
    mvt_date   DATE          NOT NULL,
    wh_id      SMALLINT      NOT NULL,
    loc_id     INT           NULL,
    sku        VARCHAR(50)   NOT NULL,
    batch_no   VARCHAR(30)   NULL,
    qty        INT           NOT NULL,
    unit_cost  DECIMAL(12,4) NULL,
    ref_type   VARCHAR(20)   NULL,
    ref_id     BIGINT        NULL,
    created_by INT           NOT NULL,
    created_at DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    remark     NVARCHAR(300) NULL,
    CONSTRAINT PK_stock_movements PRIMARY KEY (mvt_id),
    CONSTRAINT UQ_mvt_no          UNIQUE      (mvt_no),
    CONSTRAINT FK_mvt_wh          FOREIGN KEY (wh_id)   REFERENCES wms.warehouses (wh_id),
    CONSTRAINT FK_mvt_loc         FOREIGN KEY (loc_id)  REFERENCES wms.locations  (loc_id),
    CONSTRAINT CK_mvt_type        CHECK (mvt_type IN (1,2,3,4,5)),
    CONSTRAINT CK_mvt_qty         CHECK (qty <> 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.stock_movements') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'库存流水（仅追加，不更新）',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'stock_movements';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.stock_movements') AND name = 'IX_mvt_sku')
    CREATE INDEX IX_mvt_sku     ON wms.stock_movements (sku, mvt_date DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.stock_movements') AND name = 'IX_mvt_type')
    CREATE INDEX IX_mvt_type    ON wms.stock_movements (mvt_type, mvt_date DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.stock_movements') AND name = 'IX_mvt_ref')
    CREATE INDEX IX_mvt_ref     ON wms.stock_movements (ref_type, ref_id) WHERE ref_id IS NOT NULL;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.stock_movements') AND name = 'IX_mvt_wh_date')
    CREATE INDEX IX_mvt_wh_date ON wms.stock_movements (wh_id, mvt_date DESC) INCLUDE (qty, unit_cost);
GO

PRINT 'seed_testdata.sql completed successfully.';
