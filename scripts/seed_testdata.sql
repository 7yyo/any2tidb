SET QUOTED_IDENTIFIER ON;
GO
-- ============================================================
-- ms2tidb 手动测试用生产级 Schema
-- 数据库: salesdb, hrdb, inventorydb, crmdb, configdb, auditdb
-- 共约 50 张表，覆盖 ERP / CRM / WMS / HR / SYS / Audit 域
-- 不与 testdb 下任何 it_* / vt_* 表冲突
-- 执行方式:
--   sqlcmd -S 127.0.0.1,1433 -U sa -P 'Test1234!' -C -i seed_testdata.sql
-- ============================================================


-- ============================================================
-- 1. salesdb  —— 品类 / 客户 / 促销 / 订单 / 发货 / 退货 / 财务
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

-- ── 1. sales.categories ──────────────────────────────────────
-- 自引用树形品类
IF OBJECT_ID('sales.categories','U') IS NULL
CREATE TABLE sales.categories (
    cat_id      SMALLINT      NOT NULL IDENTITY(1,1),
    cat_code    VARCHAR(20)   NOT NULL,
    cat_name    NVARCHAR(100) NOT NULL,
    parent_id   SMALLINT      NULL,
    sort_order  SMALLINT      NOT NULL DEFAULT 0,
    is_active   BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_categories      PRIMARY KEY (cat_id),
    CONSTRAINT UQ_categories_code UNIQUE (cat_code),
    CONSTRAINT FK_cat_parent      FOREIGN KEY (parent_id) REFERENCES sales.categories (cat_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.categories') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'商品品类树',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'categories';
GO

-- ── 2. sales.customers ───────────────────────────────────────
-- CHECK 约束、DEFAULT、普通索引
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

-- ── 3. sales.products ────────────────────────────────────────
-- XML 列、filtered index
IF OBJECT_ID('sales.products','U') IS NULL
CREATE TABLE sales.products (
    product_id    INT           NOT NULL IDENTITY(1,1),
    sku           VARCHAR(50)   NOT NULL,
    product_name  NVARCHAR(300) NOT NULL,
    category_id   SMALLINT      NOT NULL,
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
    CONSTRAINT FK_products_cat   FOREIGN KEY (category_id) REFERENCES sales.categories (cat_id),
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
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.products') AND name = 'IX_products_category')
    CREATE INDEX IX_products_category ON sales.products (category_id, is_active);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.products') AND name = 'IX_products_stock')
    CREATE INDEX IX_products_stock    ON sales.products (stock_qty) WHERE stock_qty <= 20;
GO

-- ── 4. sales.promotions ──────────────────────────────────────
-- 促销活动：DATE 范围、TINYINT 枚举
IF OBJECT_ID('sales.promotions','U') IS NULL
CREATE TABLE sales.promotions (
    promo_id      INT           NOT NULL IDENTITY(1,1),
    promo_code    VARCHAR(30)   NOT NULL,
    promo_name    NVARCHAR(200) NOT NULL,
    promo_type    TINYINT       NOT NULL DEFAULT 1,  -- 1=满减 2=折扣 3=赠品
    discount_pct  DECIMAL(5,2)  NULL,
    discount_amt  DECIMAL(12,2) NULL,
    min_order_amt DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    is_active     BIT           NOT NULL DEFAULT 1,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_promotions      PRIMARY KEY (promo_id),
    CONSTRAINT UQ_promotions_code UNIQUE (promo_code),
    CONSTRAINT CK_promo_type      CHECK (promo_type IN (1,2,3)),
    CONSTRAINT CK_promo_dates     CHECK (end_date >= start_date),
    CONSTRAINT CK_promo_disc      CHECK (discount_pct IS NULL OR discount_pct BETWEEN 0 AND 100)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.promotions') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'促销活动主表',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'promotions';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.promotions') AND name = 'IX_promo_date')
    CREATE INDEX IX_promo_date ON sales.promotions (start_date, end_date) WHERE is_active = 1;
GO

-- ── 5. sales.promotion_items ─────────────────────────────────
-- 促销明细：复合 PK
IF OBJECT_ID('sales.promotion_items','U') IS NULL
CREATE TABLE sales.promotion_items (
    promo_id   INT         NOT NULL,
    product_id INT         NOT NULL,
    extra_qty  SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT PK_promo_items        PRIMARY KEY (promo_id, product_id),
    CONSTRAINT FK_promo_items_promo  FOREIGN KEY (promo_id)   REFERENCES sales.promotions (promo_id),
    CONSTRAINT FK_promo_items_prod   FOREIGN KEY (product_id) REFERENCES sales.products   (product_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.promotion_items') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'促销活动适用商品',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'promotion_items';
GO

-- ── 6. sales.orders ──────────────────────────────────────────
-- 核心业务表：BIGINT PK、FK、多索引
IF OBJECT_ID('sales.orders','U') IS NULL
CREATE TABLE sales.orders (
    order_id        BIGINT        NOT NULL IDENTITY(1,1),
    order_no        VARCHAR(30)   NOT NULL,
    customer_id     INT           NOT NULL,
    promo_id        INT           NULL,
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
    CONSTRAINT FK_orders_customer FOREIGN KEY (customer_id) REFERENCES sales.customers  (customer_id),
    CONSTRAINT FK_orders_promo    FOREIGN KEY (promo_id)    REFERENCES sales.promotions (promo_id),
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

-- ── 7. sales.order_lines ─────────────────────────────────────
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

-- ── 8. sales.shipments ───────────────────────────────────────
IF OBJECT_ID('sales.shipments','U') IS NULL
CREATE TABLE sales.shipments (
    shipment_id     INT           NOT NULL IDENTITY(1,1),
    shipment_no     VARCHAR(30)   NOT NULL,
    order_id        BIGINT        NOT NULL,
    carrier         NVARCHAR(100) NULL,
    tracking_no     VARCHAR(100)  NULL,
    ship_date       DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    expected_date   DATE          NULL,
    delivered_date  DATETIME2(3)  NULL,
    status          TINYINT       NOT NULL DEFAULT 1,   -- 1=待发 2=在途 3=已达 4=异常
    weight_kg       DECIMAL(8,3)  NULL,
    freight_cost    DECIMAL(10,2) NULL,
    ship_to_name    NVARCHAR(100) NULL,
    ship_to_address NVARCHAR(500) NULL,
    ship_to_city    NVARCHAR(100) NULL,
    ship_to_country CHAR(2)       NOT NULL DEFAULT 'CN',
    remark          NVARCHAR(300) NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_shipments      PRIMARY KEY (shipment_id),
    CONSTRAINT UQ_shipments_no   UNIQUE (shipment_no),
    CONSTRAINT FK_ship_order     FOREIGN KEY (order_id) REFERENCES sales.orders (order_id),
    CONSTRAINT CK_ship_status    CHECK (status IN (1,2,3,4))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.shipments') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'发货单',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'shipments';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.shipments') AND name = 'IX_shipments_order')
    CREATE INDEX IX_shipments_order  ON sales.shipments (order_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.shipments') AND name = 'IX_shipments_status')
    CREATE INDEX IX_shipments_status ON sales.shipments (status, ship_date DESC);
GO

-- ── 9. sales.shipment_lines ──────────────────────────────────
IF OBJECT_ID('sales.shipment_lines','U') IS NULL
CREATE TABLE sales.shipment_lines (
    shipment_id INT      NOT NULL,
    line_no     SMALLINT NOT NULL,
    order_id    BIGINT   NOT NULL,
    order_line  SMALLINT NOT NULL,
    product_id  INT      NOT NULL,
    qty_shipped INT      NOT NULL,
    CONSTRAINT PK_shipment_lines        PRIMARY KEY (shipment_id, line_no),
    CONSTRAINT FK_shipline_shipment     FOREIGN KEY (shipment_id)       REFERENCES sales.shipments   (shipment_id),
    CONSTRAINT FK_shipline_order_line   FOREIGN KEY (order_id, order_line) REFERENCES sales.order_lines (order_id, line_no),
    CONSTRAINT FK_shipline_product      FOREIGN KEY (product_id)        REFERENCES sales.products    (product_id),
    CONSTRAINT CK_shipline_qty          CHECK (qty_shipped > 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.shipment_lines') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'发货单明细',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'shipment_lines';
GO

-- ── 10. sales.return_orders ──────────────────────────────────
IF OBJECT_ID('sales.return_orders','U') IS NULL
CREATE TABLE sales.return_orders (
    return_id   INT           NOT NULL IDENTITY(1,1),
    return_no   VARCHAR(30)   NOT NULL,
    order_id    BIGINT        NOT NULL,
    reason_code VARCHAR(20)   NOT NULL,
    reason_desc NVARCHAR(500) NULL,
    status      TINYINT       NOT NULL DEFAULT 1,
    refund_amt  DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_by  INT           NOT NULL,
    created_at  DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    resolved_at DATETIME2(3)  NULL,
    CONSTRAINT PK_return_orders     PRIMARY KEY (return_id),
    CONSTRAINT UQ_return_no         UNIQUE (return_no),
    CONSTRAINT FK_return_order      FOREIGN KEY (order_id) REFERENCES sales.orders (order_id),
    CONSTRAINT CK_return_status     CHECK (status IN (1,2,3,4)),
    CONSTRAINT CK_return_refund     CHECK (refund_amt >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.return_orders') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'退货申请单',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'return_orders';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sales.return_orders') AND name = 'IX_return_order')
    CREATE INDEX IX_return_order ON sales.return_orders (order_id);
GO

-- ── 11. sales.return_lines ───────────────────────────────────
IF OBJECT_ID('sales.return_lines','U') IS NULL
CREATE TABLE sales.return_lines (
    return_id  INT      NOT NULL,
    line_no    SMALLINT NOT NULL,
    product_id INT      NOT NULL,
    qty        INT      NOT NULL,
    unit_price DECIMAL(12,4) NOT NULL,
    CONSTRAINT PK_return_lines       PRIMARY KEY (return_id, line_no),
    CONSTRAINT FK_retline_return     FOREIGN KEY (return_id)  REFERENCES sales.return_orders (return_id),
    CONSTRAINT FK_retline_product    FOREIGN KEY (product_id) REFERENCES sales.products      (product_id),
    CONSTRAINT CK_retline_qty        CHECK (qty > 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sales.return_lines') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'退货明细行',
        @level0type=N'SCHEMA', @level0name=N'sales',
        @level1type=N'TABLE',  @level1name=N'return_lines';
GO

-- ── 12. finance.invoices ─────────────────────────────────────
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

-- ── 13. finance.payment_records ──────────────────────────────
-- 收款流水：UNIQUEIDENTIFIER 业务号、DATETIME2(6) 高精度时间戳
IF OBJECT_ID('finance.payment_records','U') IS NULL
CREATE TABLE finance.payment_records (
    pay_id         BIGINT           NOT NULL IDENTITY(1,1),
    pay_ref        UNIQUEIDENTIFIER NOT NULL DEFAULT NEWSEQUENTIALID(),
    invoice_id     INT              NOT NULL,
    pay_channel    VARCHAR(20)      NOT NULL,  -- alipay/wechat/bank/cash
    pay_amount     MONEY            NOT NULL,
    currency       CHAR(3)          NOT NULL DEFAULT 'CNY',
    exchange_rate  DECIMAL(10,6)    NOT NULL DEFAULT 1.000000,
    pay_time       DATETIME2(6)     NOT NULL DEFAULT GETDATE(),
    third_party_no VARCHAR(100)     NULL,
    status         TINYINT          NOT NULL DEFAULT 2,  -- 1=待确认 2=成功 3=失败 4=退款
    remark         NVARCHAR(300)    NULL,
    CONSTRAINT PK_payment_records    PRIMARY KEY (pay_id),
    CONSTRAINT UQ_payment_ref        UNIQUE (pay_ref),
    CONSTRAINT FK_pay_invoice        FOREIGN KEY (invoice_id) REFERENCES finance.invoices (invoice_id),
    CONSTRAINT CK_pay_amount         CHECK (pay_amount > 0),
    CONSTRAINT CK_pay_status         CHECK (status IN (1,2,3,4))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('finance.payment_records') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'收款流水',
        @level0type=N'SCHEMA', @level0name=N'finance',
        @level1type=N'TABLE',  @level1name=N'payment_records';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('finance.payment_records') AND name = 'IX_pay_invoice')
    CREATE INDEX IX_pay_invoice ON finance.payment_records (invoice_id, pay_time DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('finance.payment_records') AND name = 'IX_pay_time')
    CREATE INDEX IX_pay_time    ON finance.payment_records (pay_time DESC);
GO

-- ── 14. finance.cost_centers ─────────────────────────────────
IF OBJECT_ID('finance.cost_centers','U') IS NULL
CREATE TABLE finance.cost_centers (
    cc_id       SMALLINT      NOT NULL IDENTITY(1,1),
    cc_code     VARCHAR(10)   NOT NULL,
    cc_name     NVARCHAR(100) NOT NULL,
    parent_id   SMALLINT      NULL,
    budget_amt  DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    is_active   BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_cost_centers      PRIMARY KEY (cc_id),
    CONSTRAINT UQ_cost_centers_code UNIQUE (cc_code),
    CONSTRAINT FK_cc_parent         FOREIGN KEY (parent_id) REFERENCES finance.cost_centers (cc_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('finance.cost_centers') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'成本中心树',
        @level0type=N'SCHEMA', @level0name=N'finance',
        @level1type=N'TABLE',  @level1name=N'cost_centers';
GO


-- ============================================================
-- 2. hrdb  —— 组织 / 岗位 / 员工 / 考勤 / 请假 / 薪资 / 培训
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'hrdb')
    CREATE DATABASE hrdb;
GO
USE hrdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'hr')
    EXEC('CREATE SCHEMA hr');
GO

-- ── 15. hr.departments ───────────────────────────────────────
-- 自引用 FK
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

-- ── 16. hr.positions ─────────────────────────────────────────
IF OBJECT_ID('hr.positions','U') IS NULL
CREATE TABLE hr.positions (
    pos_id        SMALLINT      NOT NULL IDENTITY(1,1),
    pos_code      VARCHAR(20)   NOT NULL,
    pos_name      NVARCHAR(100) NOT NULL,
    dept_id       SMALLINT      NOT NULL,
    grade         TINYINT       NOT NULL DEFAULT 1,
    salary_min    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    salary_max    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    is_management BIT           NOT NULL DEFAULT 0,
    is_active     BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_positions      PRIMARY KEY (pos_id),
    CONSTRAINT UQ_positions_code UNIQUE (pos_code),
    CONSTRAINT FK_pos_dept       FOREIGN KEY (dept_id) REFERENCES hr.departments (dept_id),
    CONSTRAINT CK_pos_salary     CHECK (salary_max >= salary_min AND salary_min >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.positions') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'岗位定编表',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'positions';
GO

-- ── 17. hr.employees ─────────────────────────────────────────
-- 大表：多 UNIQUE、自引用 FK、多索引
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
    pos_id         SMALLINT      NULL,
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
    CONSTRAINT FK_emp_pos         FOREIGN KEY (pos_id)     REFERENCES hr.positions   (pos_id),
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

-- ── 18. hr.attendance ────────────────────────────────────────
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

-- ── 19. hr.leave_types ───────────────────────────────────────
IF OBJECT_ID('hr.leave_types','U') IS NULL
CREATE TABLE hr.leave_types (
    leave_type_id   TINYINT       NOT NULL IDENTITY(1,1),
    type_code       VARCHAR(10)   NOT NULL,
    type_name       NVARCHAR(50)  NOT NULL,
    max_days_year   SMALLINT      NOT NULL DEFAULT 0,
    is_paid         BIT           NOT NULL DEFAULT 1,
    is_active       BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_leave_types      PRIMARY KEY (leave_type_id),
    CONSTRAINT UQ_leave_type_code  UNIQUE (type_code)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.leave_types') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'假期类型字典',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'leave_types';
GO

-- ── 20. hr.leave_applications ────────────────────────────────
IF OBJECT_ID('hr.leave_applications','U') IS NULL
CREATE TABLE hr.leave_applications (
    leave_id      INT           NOT NULL IDENTITY(1,1),
    emp_id        INT           NOT NULL,
    leave_type_id TINYINT       NOT NULL,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    days_count    DECIMAL(4,1)  NOT NULL,
    reason        NVARCHAR(500) NULL,
    approver_id   INT           NULL,
    status        TINYINT       NOT NULL DEFAULT 1,  -- 1=待审 2=批准 3=拒绝 4=撤回
    applied_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    approved_at   DATETIME2(3)  NULL,
    CONSTRAINT PK_leave_applications   PRIMARY KEY (leave_id),
    CONSTRAINT FK_leave_emp            FOREIGN KEY (emp_id)        REFERENCES hr.employees   (emp_id),
    CONSTRAINT FK_leave_type           FOREIGN KEY (leave_type_id) REFERENCES hr.leave_types (leave_type_id),
    CONSTRAINT FK_leave_approver       FOREIGN KEY (approver_id)   REFERENCES hr.employees   (emp_id),
    CONSTRAINT CK_leave_dates          CHECK (end_date >= start_date),
    CONSTRAINT CK_leave_status         CHECK (status IN (1,2,3,4)),
    CONSTRAINT CK_leave_days           CHECK (days_count > 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.leave_applications') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'员工请假申请',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'leave_applications';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.leave_applications') AND name = 'IX_leave_emp')
    CREATE INDEX IX_leave_emp    ON hr.leave_applications (emp_id, start_date DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.leave_applications') AND name = 'IX_leave_status')
    CREATE INDEX IX_leave_status ON hr.leave_applications (status, applied_at DESC);
GO

-- ── 21. hr.payroll ───────────────────────────────────────────
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

-- ── 22. hr.training_courses ──────────────────────────────────
IF OBJECT_ID('hr.training_courses','U') IS NULL
CREATE TABLE hr.training_courses (
    course_id    INT           NOT NULL IDENTITY(1,1),
    course_code  VARCHAR(20)   NOT NULL,
    course_name  NVARCHAR(200) NOT NULL,
    course_type  TINYINT       NOT NULL DEFAULT 1,  -- 1=内训 2=外训 3=在线
    duration_hrs DECIMAL(5,1)  NOT NULL,
    provider     NVARCHAR(100) NULL,
    cost_per_pax DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    is_mandatory BIT           NOT NULL DEFAULT 0,
    is_active    BIT           NOT NULL DEFAULT 1,
    created_at   DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_training_courses     PRIMARY KEY (course_id),
    CONSTRAINT UQ_training_course_code UNIQUE (course_code),
    CONSTRAINT CK_course_type          CHECK (course_type IN (1,2,3)),
    CONSTRAINT CK_course_duration      CHECK (duration_hrs > 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.training_courses') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'培训课程目录',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'training_courses';
GO

-- ── 23. hr.training_enrollments ──────────────────────────────
IF OBJECT_ID('hr.training_enrollments','U') IS NULL
CREATE TABLE hr.training_enrollments (
    enrollment_id INT           NOT NULL IDENTITY(1,1),
    course_id     INT           NOT NULL,
    emp_id        INT           NOT NULL,
    enroll_date   DATE          NOT NULL,
    start_date    DATE          NULL,
    complete_date DATE          NULL,
    score         DECIMAL(5,2)  NULL,
    status        TINYINT       NOT NULL DEFAULT 1,  -- 1=报名 2=进行中 3=完成 4=放弃
    cost_actual   DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    CONSTRAINT PK_training_enrollments    PRIMARY KEY (enrollment_id),
    CONSTRAINT UQ_training_emp_course     UNIQUE (course_id, emp_id, enroll_date),
    CONSTRAINT FK_enroll_course           FOREIGN KEY (course_id) REFERENCES hr.training_courses (course_id),
    CONSTRAINT FK_enroll_emp              FOREIGN KEY (emp_id)    REFERENCES hr.employees        (emp_id),
    CONSTRAINT CK_enroll_status           CHECK (status IN (1,2,3,4)),
    CONSTRAINT CK_enroll_score            CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('hr.training_enrollments') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'员工培训报名与完成记录',
        @level0type=N'SCHEMA', @level0name=N'hr',
        @level1type=N'TABLE',  @level1name=N'training_enrollments';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('hr.training_enrollments') AND name = 'IX_enroll_emp')
    CREATE INDEX IX_enroll_emp ON hr.training_enrollments (emp_id, enroll_date DESC);
GO


-- ============================================================
-- 3. inventorydb  —— 供应商 / 采购 / 仓库 / 库位 / 库存账 / 盘点 / 流水
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'inventorydb')
    CREATE DATABASE inventorydb;
GO
USE inventorydb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'wms')
    EXEC('CREATE SCHEMA wms');
GO

-- ── 24. wms.suppliers ────────────────────────────────────────
IF OBJECT_ID('wms.suppliers','U') IS NULL
CREATE TABLE wms.suppliers (
    supplier_id   INT           NOT NULL IDENTITY(1,1),
    supplier_code VARCHAR(20)   NOT NULL,
    supplier_name NVARCHAR(200) NOT NULL,
    contact_name  NVARCHAR(100) NULL,
    email         VARCHAR(254)  NULL,
    phone         VARCHAR(30)   NULL,
    country       CHAR(2)       NOT NULL DEFAULT 'CN',
    payment_terms TINYINT       NOT NULL DEFAULT 30,  -- 账期天数
    rating        TINYINT       NOT NULL DEFAULT 3,
    is_active     BIT           NOT NULL DEFAULT 1,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_suppliers      PRIMARY KEY (supplier_id),
    CONSTRAINT UQ_suppliers_code UNIQUE (supplier_code),
    CONSTRAINT CK_suppliers_rating CHECK (rating BETWEEN 1 AND 5)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.suppliers') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'供应商主档',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'suppliers';
GO

-- ── 25. wms.purchase_orders ──────────────────────────────────
IF OBJECT_ID('wms.purchase_orders','U') IS NULL
CREATE TABLE wms.purchase_orders (
    po_id        INT           NOT NULL IDENTITY(1,1),
    po_no        VARCHAR(30)   NOT NULL,
    supplier_id  INT           NOT NULL,
    wh_id        SMALLINT      NOT NULL,
    order_date   DATE          NOT NULL,
    expected_dt  DATE          NULL,
    received_dt  DATE          NULL,
    status       TINYINT       NOT NULL DEFAULT 1,  -- 1=草稿 2=已确认 3=部分收货 4=完成 9=取消
    currency     CHAR(3)       NOT NULL DEFAULT 'CNY',
    total_amount DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    created_by   INT           NOT NULL,
    created_at   DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at   DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    remark       NVARCHAR(500) NULL,
    CONSTRAINT PK_purchase_orders    PRIMARY KEY (po_id),
    CONSTRAINT UQ_po_no              UNIQUE (po_no),
    CONSTRAINT FK_po_supplier        FOREIGN KEY (supplier_id) REFERENCES wms.suppliers  (supplier_id),
    CONSTRAINT FK_po_wh              FOREIGN KEY (wh_id)       REFERENCES wms.warehouses (wh_id),
    CONSTRAINT CK_po_status          CHECK (status IN (1,2,3,4,9)),
    CONSTRAINT CK_po_amount          CHECK (total_amount >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.purchase_orders') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'采购订单主表',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'purchase_orders';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.purchase_orders') AND name = 'IX_po_supplier')
    CREATE INDEX IX_po_supplier ON wms.purchase_orders (supplier_id, order_date DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.purchase_orders') AND name = 'IX_po_status')
    CREATE INDEX IX_po_status   ON wms.purchase_orders (status, expected_dt);
GO

-- ── 26. wms.purchase_lines ───────────────────────────────────
IF OBJECT_ID('wms.purchase_lines','U') IS NULL
CREATE TABLE wms.purchase_lines (
    po_id          INT           NOT NULL,
    line_no        SMALLINT      NOT NULL,
    sku            VARCHAR(50)   NOT NULL,
    qty_ordered    INT           NOT NULL,
    qty_received   INT           NOT NULL DEFAULT 0,
    unit_price     DECIMAL(12,4) NOT NULL,
    line_amount    DECIMAL(16,2) NOT NULL,
    expected_date  DATE          NULL,
    CONSTRAINT PK_purchase_lines      PRIMARY KEY (po_id, line_no),
    CONSTRAINT FK_poline_po           FOREIGN KEY (po_id) REFERENCES wms.purchase_orders (po_id),
    CONSTRAINT CK_poline_qty          CHECK (qty_ordered > 0),
    CONSTRAINT CK_poline_received     CHECK (qty_received >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.purchase_lines') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'采购订单明细',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'purchase_lines';
GO

-- ── 27. wms.warehouses ───────────────────────────────────────
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

-- ── 28. wms.locations ────────────────────────────────────────
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

-- ── 29. wms.inventory ────────────────────────────────────────
-- 计算列 qty_available
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
    CONSTRAINT PK_inventory               PRIMARY KEY (inv_id),
    CONSTRAINT UQ_inventory_loc_sku_batch UNIQUE (wh_id, loc_id, sku, batch_no),
    CONSTRAINT FK_inv_wh                  FOREIGN KEY (wh_id)  REFERENCES wms.warehouses (wh_id),
    CONSTRAINT FK_inv_loc                 FOREIGN KEY (loc_id) REFERENCES wms.locations  (loc_id),
    CONSTRAINT CK_inv_qty_hand            CHECK (qty_on_hand >= 0),
    CONSTRAINT CK_inv_qty_reserved        CHECK (qty_reserved >= 0)
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

-- ── 30. wms.stock_movements ──────────────────────────────────
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
    CONSTRAINT FK_mvt_wh          FOREIGN KEY (wh_id)  REFERENCES wms.warehouses (wh_id),
    CONSTRAINT FK_mvt_loc         FOREIGN KEY (loc_id) REFERENCES wms.locations  (loc_id),
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

-- ── 31. wms.inventory_counts ─────────────────────────────────
-- 盘点任务单
IF OBJECT_ID('wms.inventory_counts','U') IS NULL
CREATE TABLE wms.inventory_counts (
    count_id     INT           NOT NULL IDENTITY(1,1),
    count_no     VARCHAR(30)   NOT NULL,
    wh_id        SMALLINT      NOT NULL,
    count_type   TINYINT       NOT NULL DEFAULT 1,  -- 1=全盘 2=循环盘 3=抽盘
    plan_date    DATE          NOT NULL,
    actual_date  DATE          NULL,
    status       TINYINT       NOT NULL DEFAULT 1,  -- 1=计划 2=进行中 3=完成 4=取消
    created_by   INT           NOT NULL,
    created_at   DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    closed_at    DATETIME2(3)  NULL,
    remark       NVARCHAR(300) NULL,
    CONSTRAINT PK_inventory_counts   PRIMARY KEY (count_id),
    CONSTRAINT UQ_count_no           UNIQUE (count_no),
    CONSTRAINT FK_count_wh           FOREIGN KEY (wh_id) REFERENCES wms.warehouses (wh_id),
    CONSTRAINT CK_count_type         CHECK (count_type IN (1,2,3)),
    CONSTRAINT CK_count_status       CHECK (status IN (1,2,3,4))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.inventory_counts') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'库存盘点任务单',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'inventory_counts';
GO

-- ── 32. wms.inventory_count_lines ────────────────────────────
IF OBJECT_ID('wms.inventory_count_lines','U') IS NULL
CREATE TABLE wms.inventory_count_lines (
    count_id     INT           NOT NULL,
    line_no      INT           NOT NULL,
    loc_id       INT           NULL,
    sku          VARCHAR(50)   NOT NULL,
    batch_no     VARCHAR(30)   NULL,
    qty_system   INT           NOT NULL DEFAULT 0,
    qty_counted  INT           NULL,
    qty_diff     AS (ISNULL(qty_counted, 0) - qty_system),
    unit_cost    DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
    counted_by   INT           NULL,
    counted_at   DATETIME2(3)  NULL,
    CONSTRAINT PK_count_lines       PRIMARY KEY (count_id, line_no),
    CONSTRAINT FK_countline_task    FOREIGN KEY (count_id) REFERENCES wms.inventory_counts (count_id),
    CONSTRAINT FK_countline_loc     FOREIGN KEY (loc_id)   REFERENCES wms.locations        (loc_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('wms.inventory_count_lines') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'盘点明细行，qty_diff 为计算列',
        @level0type=N'SCHEMA', @level0name=N'wms',
        @level1type=N'TABLE',  @level1name=N'inventory_count_lines';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('wms.inventory_count_lines') AND name = 'IX_countline_sku')
    CREATE INDEX IX_countline_sku ON wms.inventory_count_lines (sku, count_id);
GO


-- ============================================================
-- 4. crmdb  —— 线索 / 联系人 / 商机 / 活动 / 营销活动
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'crmdb')
    CREATE DATABASE crmdb;
GO
USE crmdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'crm')
    EXEC('CREATE SCHEMA crm');
GO

-- ── 33. crm.leads ────────────────────────────────────────────
IF OBJECT_ID('crm.leads','U') IS NULL
CREATE TABLE crm.leads (
    lead_id       INT           NOT NULL IDENTITY(1,1),
    lead_code     VARCHAR(20)   NOT NULL,
    company_name  NVARCHAR(200) NOT NULL,
    contact_name  NVARCHAR(100) NULL,
    email         VARCHAR(254)  NULL,
    phone         VARCHAR(30)   NULL,
    source        TINYINT       NOT NULL DEFAULT 1, -- 1=官网 2=展会 3=电话 4=推荐 5=广告
    status        TINYINT       NOT NULL DEFAULT 1, -- 1=新建 2=接触中 3=已转化 4=已放弃
    assigned_to   INT           NULL,
    score         SMALLINT      NOT NULL DEFAULT 0,
    country       CHAR(2)       NOT NULL DEFAULT 'CN',
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_leads      PRIMARY KEY (lead_id),
    CONSTRAINT UQ_lead_code  UNIQUE (lead_code),
    CONSTRAINT CK_lead_source CHECK (source IN (1,2,3,4,5)),
    CONSTRAINT CK_lead_status CHECK (status IN (1,2,3,4)),
    CONSTRAINT CK_lead_score  CHECK (score BETWEEN 0 AND 100)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.leads') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'销售线索',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'leads';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.leads') AND name = 'IX_leads_status')
    CREATE INDEX IX_leads_status  ON crm.leads (status, assigned_to);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.leads') AND name = 'IX_leads_created')
    CREATE INDEX IX_leads_created ON crm.leads (created_at DESC);
GO

-- ── 34. crm.contacts ─────────────────────────────────────────
IF OBJECT_ID('crm.contacts','U') IS NULL
CREATE TABLE crm.contacts (
    contact_id    INT           NOT NULL IDENTITY(1,1),
    lead_id       INT           NULL,
    first_name    NVARCHAR(50)  NOT NULL,
    last_name     NVARCHAR(50)  NOT NULL,
    title         NVARCHAR(50)  NULL,
    department    NVARCHAR(100) NULL,
    email         VARCHAR(254)  NULL,
    phone         VARCHAR(30)   NULL,
    mobile        VARCHAR(30)   NULL,
    linkedin_url  VARCHAR(300)  NULL,
    is_primary    BIT           NOT NULL DEFAULT 0,
    do_not_call   BIT           NOT NULL DEFAULT 0,
    do_not_email  BIT           NOT NULL DEFAULT 0,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_contacts       PRIMARY KEY (contact_id),
    CONSTRAINT FK_contact_lead   FOREIGN KEY (lead_id) REFERENCES crm.leads (lead_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.contacts') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'联系人',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'contacts';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.contacts') AND name = 'IX_contacts_lead')
    CREATE INDEX IX_contacts_lead ON crm.contacts (lead_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.contacts') AND name = 'IX_contacts_email')
    CREATE INDEX IX_contacts_email ON crm.contacts (email) WHERE email IS NOT NULL;
GO

-- ── 35. crm.opportunities ────────────────────────────────────
IF OBJECT_ID('crm.opportunities','U') IS NULL
CREATE TABLE crm.opportunities (
    opp_id         INT           NOT NULL IDENTITY(1,1),
    opp_code       VARCHAR(20)   NOT NULL,
    opp_name       NVARCHAR(200) NOT NULL,
    lead_id        INT           NULL,
    stage          TINYINT       NOT NULL DEFAULT 1, -- 1=识别 2=方案 3=谈判 4=赢单 5=败单
    probability    DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    amount         DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    currency       CHAR(3)       NOT NULL DEFAULT 'CNY',
    close_date     DATE          NULL,
    assigned_to    INT           NOT NULL,
    lost_reason    NVARCHAR(300) NULL,
    created_at     DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    updated_at     DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_opportunities      PRIMARY KEY (opp_id),
    CONSTRAINT UQ_opp_code           UNIQUE (opp_code),
    CONSTRAINT FK_opp_lead           FOREIGN KEY (lead_id) REFERENCES crm.leads (lead_id),
    CONSTRAINT CK_opp_stage          CHECK (stage IN (1,2,3,4,5)),
    CONSTRAINT CK_opp_prob           CHECK (probability BETWEEN 0 AND 100),
    CONSTRAINT CK_opp_amount         CHECK (amount >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.opportunities') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'销售商机',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'opportunities';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.opportunities') AND name = 'IX_opp_stage')
    CREATE INDEX IX_opp_stage  ON crm.opportunities (stage, assigned_to);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.opportunities') AND name = 'IX_opp_close')
    CREATE INDEX IX_opp_close  ON crm.opportunities (close_date);
GO

-- ── 36. crm.opportunity_products ─────────────────────────────
IF OBJECT_ID('crm.opportunity_products','U') IS NULL
CREATE TABLE crm.opportunity_products (
    opp_id       INT           NOT NULL,
    line_no      SMALLINT      NOT NULL,
    product_code VARCHAR(50)   NOT NULL,
    product_name NVARCHAR(300) NOT NULL,
    quantity     INT           NOT NULL DEFAULT 1,
    unit_price   DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
    discount_pct DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    line_amount  DECIMAL(16,2) NOT NULL DEFAULT 0.00,
    CONSTRAINT PK_opp_products       PRIMARY KEY (opp_id, line_no),
    CONSTRAINT FK_opprod_opp         FOREIGN KEY (opp_id) REFERENCES crm.opportunities (opp_id),
    CONSTRAINT CK_opprod_qty         CHECK (quantity > 0),
    CONSTRAINT CK_opprod_disc        CHECK (discount_pct BETWEEN 0 AND 100)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.opportunity_products') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'商机报价明细',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'opportunity_products';
GO

-- ── 37. crm.activities ───────────────────────────────────────
IF OBJECT_ID('crm.activities','U') IS NULL
CREATE TABLE crm.activities (
    activity_id   BIGINT        NOT NULL IDENTITY(1,1),
    act_type      TINYINT       NOT NULL DEFAULT 1, -- 1=电话 2=邮件 3=会议 4=演示 5=其他
    subject       NVARCHAR(300) NOT NULL,
    opp_id        INT           NULL,
    lead_id       INT           NULL,
    contact_id    INT           NULL,
    scheduled_dt  DATETIME2(3)  NULL,
    actual_dt     DATETIME2(3)  NULL,
    duration_min  SMALLINT      NULL,
    outcome       NVARCHAR(500) NULL,
    status        TINYINT       NOT NULL DEFAULT 1, -- 1=计划 2=完成 3=取消
    created_by    INT           NOT NULL,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_activities     PRIMARY KEY (activity_id),
    CONSTRAINT FK_act_opp        FOREIGN KEY (opp_id)     REFERENCES crm.opportunities (opp_id),
    CONSTRAINT FK_act_lead       FOREIGN KEY (lead_id)    REFERENCES crm.leads         (lead_id),
    CONSTRAINT FK_act_contact    FOREIGN KEY (contact_id) REFERENCES crm.contacts      (contact_id),
    CONSTRAINT CK_act_type       CHECK (act_type IN (1,2,3,4,5)),
    CONSTRAINT CK_act_status     CHECK (status IN (1,2,3))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.activities') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'销售活动跟进记录',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'activities';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.activities') AND name = 'IX_act_opp')
    CREATE INDEX IX_act_opp     ON crm.activities (opp_id, actual_dt DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.activities') AND name = 'IX_act_lead')
    CREATE INDEX IX_act_lead    ON crm.activities (lead_id, actual_dt DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.activities') AND name = 'IX_act_created')
    CREATE INDEX IX_act_created ON crm.activities (created_by, created_at DESC);
GO

-- ── 38. crm.campaigns ────────────────────────────────────────
IF OBJECT_ID('crm.campaigns','U') IS NULL
CREATE TABLE crm.campaigns (
    campaign_id   INT           NOT NULL IDENTITY(1,1),
    campaign_code VARCHAR(20)   NOT NULL,
    campaign_name NVARCHAR(200) NOT NULL,
    campaign_type TINYINT       NOT NULL DEFAULT 1, -- 1=邮件 2=电话 3=社媒 4=展会 5=线下
    budget        DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    actual_cost   DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    target_leads  INT           NOT NULL DEFAULT 0,
    actual_leads  INT           NOT NULL DEFAULT 0,
    status        TINYINT       NOT NULL DEFAULT 1, -- 1=计划 2=进行中 3=完成 4=取消
    owner_id      INT           NOT NULL,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_campaigns       PRIMARY KEY (campaign_id),
    CONSTRAINT UQ_campaign_code   UNIQUE (campaign_code),
    CONSTRAINT CK_campaign_type   CHECK (campaign_type IN (1,2,3,4,5)),
    CONSTRAINT CK_campaign_status CHECK (status IN (1,2,3,4)),
    CONSTRAINT CK_campaign_dates  CHECK (end_date >= start_date),
    CONSTRAINT CK_campaign_budget CHECK (budget >= 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.campaigns') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'营销活动',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'campaigns';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('crm.campaigns') AND name = 'IX_campaigns_status')
    CREATE INDEX IX_campaigns_status ON crm.campaigns (status, start_date);
GO

-- ── 39. crm.campaign_members ─────────────────────────────────
IF OBJECT_ID('crm.campaign_members','U') IS NULL
CREATE TABLE crm.campaign_members (
    campaign_id INT      NOT NULL,
    lead_id     INT      NOT NULL,
    joined_at   DATETIME2(3) NOT NULL DEFAULT GETDATE(),
    responded   BIT      NOT NULL DEFAULT 0,
    CONSTRAINT PK_campaign_members       PRIMARY KEY (campaign_id, lead_id),
    CONSTRAINT FK_cmember_campaign       FOREIGN KEY (campaign_id) REFERENCES crm.campaigns (campaign_id),
    CONSTRAINT FK_cmember_lead           FOREIGN KEY (lead_id)     REFERENCES crm.leads     (lead_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('crm.campaign_members') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'营销活动线索成员',
        @level0type=N'SCHEMA', @level0name=N'crm',
        @level1type=N'TABLE',  @level1name=N'campaign_members';
GO


-- ============================================================
-- 5. configdb  —— 多租户配置 / 用户 / 权限 / 菜单
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'configdb')
    CREATE DATABASE configdb;
GO
USE configdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'sys2')
    EXEC('CREATE SCHEMA sys2');
GO

-- ── 40. sys2.tenants ─────────────────────────────────────────
IF OBJECT_ID('sys2.tenants','U') IS NULL
CREATE TABLE sys2.tenants (
    tenant_id     SMALLINT      NOT NULL IDENTITY(1,1),
    tenant_code   VARCHAR(20)   NOT NULL,
    tenant_name   NVARCHAR(100) NOT NULL,
    db_prefix     VARCHAR(20)   NOT NULL,
    plan_type     TINYINT       NOT NULL DEFAULT 1,  -- 1=标准 2=企业 3=旗舰
    expire_date   DATE          NULL,
    is_active     BIT           NOT NULL DEFAULT 1,
    created_at    DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_tenants      PRIMARY KEY (tenant_id),
    CONSTRAINT UQ_tenant_code  UNIQUE (tenant_code),
    CONSTRAINT UQ_tenant_db    UNIQUE (db_prefix),
    CONSTRAINT CK_tenant_plan  CHECK (plan_type IN (1,2,3))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.tenants') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'租户主表（多租户 SaaS 模式）',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'tenants';
GO

-- ── 41. sys2.app_configs ─────────────────────────────────────
-- KV 配置表：NVARCHAR(MAX) 值字段
IF OBJECT_ID('sys2.app_configs','U') IS NULL
CREATE TABLE sys2.app_configs (
    config_id   INT            NOT NULL IDENTITY(1,1),
    tenant_id   SMALLINT       NOT NULL,
    config_key  VARCHAR(100)   NOT NULL,
    config_val  NVARCHAR(MAX)  NULL,
    data_type   VARCHAR(20)    NOT NULL DEFAULT 'string', -- string/int/bool/json
    is_secret   BIT            NOT NULL DEFAULT 0,
    description NVARCHAR(300)  NULL,
    updated_at  DATETIME2(3)   NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_app_configs        PRIMARY KEY (config_id),
    CONSTRAINT UQ_config_tenant_key  UNIQUE (tenant_id, config_key),
    CONSTRAINT FK_config_tenant      FOREIGN KEY (tenant_id) REFERENCES sys2.tenants (tenant_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.app_configs') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'应用配置 KV 表，支持多租户',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'app_configs';
GO

-- ── 42. sys2.menus ───────────────────────────────────────────
-- 导航菜单树：自引用 + 排序
IF OBJECT_ID('sys2.menus','U') IS NULL
CREATE TABLE sys2.menus (
    menu_id     SMALLINT      NOT NULL IDENTITY(1,1),
    menu_code   VARCHAR(50)   NOT NULL,
    menu_name   NVARCHAR(100) NOT NULL,
    parent_id   SMALLINT      NULL,
    route_path  VARCHAR(200)  NULL,
    icon        VARCHAR(50)   NULL,
    sort_order  SMALLINT      NOT NULL DEFAULT 0,
    is_visible  BIT           NOT NULL DEFAULT 1,
    is_active   BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_menus      PRIMARY KEY (menu_id),
    CONSTRAINT UQ_menu_code  UNIQUE (menu_code),
    CONSTRAINT FK_menu_parent FOREIGN KEY (parent_id) REFERENCES sys2.menus (menu_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.menus') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'导航菜单树',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'menus';
GO

-- ── 43. sys2.roles ───────────────────────────────────────────
IF OBJECT_ID('sys2.roles','U') IS NULL
CREATE TABLE sys2.roles (
    role_id     SMALLINT      NOT NULL IDENTITY(1,1),
    tenant_id   SMALLINT      NOT NULL,
    role_code   VARCHAR(50)   NOT NULL,
    role_name   NVARCHAR(100) NOT NULL,
    is_system   BIT           NOT NULL DEFAULT 0,
    is_active   BIT           NOT NULL DEFAULT 1,
    created_at  DATETIME2(3)  NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_roles             PRIMARY KEY (role_id),
    CONSTRAINT UQ_role_tenant_code  UNIQUE (tenant_id, role_code),
    CONSTRAINT FK_role_tenant       FOREIGN KEY (tenant_id) REFERENCES sys2.tenants (tenant_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.roles') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'权限角色',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'roles';
GO

-- ── 44. sys2.permissions ─────────────────────────────────────
IF OBJECT_ID('sys2.permissions','U') IS NULL
CREATE TABLE sys2.permissions (
    perm_id     INT           NOT NULL IDENTITY(1,1),
    perm_code   VARCHAR(100)  NOT NULL,
    perm_name   NVARCHAR(150) NOT NULL,
    module      VARCHAR(50)   NOT NULL,
    action      VARCHAR(20)   NOT NULL DEFAULT 'read', -- read/write/delete/admin
    menu_id     SMALLINT      NULL,
    is_active   BIT           NOT NULL DEFAULT 1,
    CONSTRAINT PK_permissions    PRIMARY KEY (perm_id),
    CONSTRAINT UQ_perm_code      UNIQUE (perm_code),
    CONSTRAINT FK_perm_menu      FOREIGN KEY (menu_id) REFERENCES sys2.menus (menu_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.permissions') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'操作权限定义表',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'permissions';
GO

-- ── 45. sys2.role_permissions ────────────────────────────────
IF OBJECT_ID('sys2.role_permissions','U') IS NULL
CREATE TABLE sys2.role_permissions (
    role_id   SMALLINT NOT NULL,
    perm_id   INT      NOT NULL,
    granted   BIT      NOT NULL DEFAULT 1,
    CONSTRAINT PK_role_permissions    PRIMARY KEY (role_id, perm_id),
    CONSTRAINT FK_rp_role             FOREIGN KEY (role_id) REFERENCES sys2.roles       (role_id),
    CONSTRAINT FK_rp_perm             FOREIGN KEY (perm_id) REFERENCES sys2.permissions (perm_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.role_permissions') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'角色权限关联',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'role_permissions';
GO

-- ── 46. sys2.users ───────────────────────────────────────────
-- UNIQUEIDENTIFIER 主键、密码 Hash、多索引
IF OBJECT_ID('sys2.users','U') IS NULL
CREATE TABLE sys2.users (
    user_id       UNIQUEIDENTIFIER NOT NULL DEFAULT NEWSEQUENTIALID(),
    tenant_id     SMALLINT         NOT NULL,
    username      VARCHAR(50)      NOT NULL,
    email         VARCHAR(254)     NOT NULL,
    password_hash CHAR(64)         NOT NULL,   -- SHA-256 hex
    display_name  NVARCHAR(100)    NOT NULL,
    avatar_url    VARCHAR(500)     NULL,
    is_active     BIT              NOT NULL DEFAULT 1,
    is_admin      BIT              NOT NULL DEFAULT 0,
    last_login_at DATETIME2(6)     NULL,
    created_at    DATETIME2(3)     NOT NULL DEFAULT GETDATE(),
    updated_at    DATETIME2(3)     NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_users                PRIMARY KEY (user_id),
    CONSTRAINT UQ_users_tenant_uname   UNIQUE (tenant_id, username),
    CONSTRAINT UQ_users_tenant_email   UNIQUE (tenant_id, email),
    CONSTRAINT FK_users_tenant         FOREIGN KEY (tenant_id) REFERENCES sys2.tenants (tenant_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.users') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'系统用户，主键为 NEWSEQUENTIALID()',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'users';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sys2.users') AND name = 'IX_users_tenant')
    CREATE INDEX IX_users_tenant ON sys2.users (tenant_id, is_active);
GO

-- ── 47. sys2.user_roles ──────────────────────────────────────
IF OBJECT_ID('sys2.user_roles','U') IS NULL
CREATE TABLE sys2.user_roles (
    user_id    UNIQUEIDENTIFIER NOT NULL,
    role_id    SMALLINT         NOT NULL,
    granted_at DATETIME2(3)     NOT NULL DEFAULT GETDATE(),
    granted_by UNIQUEIDENTIFIER NULL,
    CONSTRAINT PK_user_roles    PRIMARY KEY (user_id, role_id),
    CONSTRAINT FK_ur_user       FOREIGN KEY (user_id) REFERENCES sys2.users (user_id),
    CONSTRAINT FK_ur_role       FOREIGN KEY (role_id) REFERENCES sys2.roles (role_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.user_roles') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'用户角色关联',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'user_roles';
GO

-- ── 48. sys2.operation_logs ──────────────────────────────────
-- 操作日志：极宽追加表、NVARCHAR(MAX) 内容字段
IF OBJECT_ID('sys2.operation_logs','U') IS NULL
CREATE TABLE sys2.operation_logs (
    log_id       BIGINT           NOT NULL IDENTITY(1,1),
    tenant_id    SMALLINT         NOT NULL,
    user_id      UNIQUEIDENTIFIER NULL,
    username     VARCHAR(50)      NULL,
    module       VARCHAR(50)      NOT NULL,
    action       VARCHAR(50)      NOT NULL,
    resource_id  VARCHAR(100)     NULL,
    ip_address   VARCHAR(45)      NULL,   -- IPv6 最长 45 字符
    user_agent   NVARCHAR(500)    NULL,
    request_body NVARCHAR(MAX)    NULL,
    response_ok  BIT              NOT NULL DEFAULT 1,
    error_msg    NVARCHAR(1000)   NULL,
    duration_ms  INT              NULL,
    created_at   DATETIME2(6)     NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_operation_logs PRIMARY KEY (log_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('sys2.operation_logs') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'用户操作日志（仅追加）',
        @level0type=N'SCHEMA', @level0name=N'sys2',
        @level1type=N'TABLE',  @level1name=N'operation_logs';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sys2.operation_logs') AND name = 'IX_oplog_tenant_time')
    CREATE INDEX IX_oplog_tenant_time ON sys2.operation_logs (tenant_id, created_at DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sys2.operation_logs') AND name = 'IX_oplog_user_time')
    CREATE INDEX IX_oplog_user_time   ON sys2.operation_logs (user_id, created_at DESC) WHERE user_id IS NOT NULL;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('sys2.operation_logs') AND name = 'IX_oplog_module')
    CREATE INDEX IX_oplog_module      ON sys2.operation_logs (module, action, created_at DESC);
GO


-- ============================================================
-- 6. auditdb  —— 数据变更历史 / 登录日志
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = 'auditdb')
    CREATE DATABASE auditdb;
GO
USE auditdb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'audit')
    EXEC('CREATE SCHEMA audit');
GO

-- ── 49. audit.change_logs ────────────────────────────────────
-- 数据变更历史：before_val / after_val 用 NVARCHAR(MAX) 存 JSON diff
IF OBJECT_ID('audit.change_logs','U') IS NULL
CREATE TABLE audit.change_logs (
    change_id    BIGINT           NOT NULL IDENTITY(1,1),
    tenant_id    SMALLINT         NOT NULL,
    changed_by   UNIQUEIDENTIFIER NULL,
    username     VARCHAR(50)      NULL,
    db_name      SYSNAME          NOT NULL,
    schema_name  SYSNAME          NOT NULL,
    table_name   SYSNAME          NOT NULL,
    operation    CHAR(1)          NOT NULL,   -- I=INSERT U=UPDATE D=DELETE
    pk_value     VARCHAR(200)     NOT NULL,
    before_val   NVARCHAR(MAX)    NULL,        -- JSON，UPDATE/DELETE 前快照
    after_val    NVARCHAR(MAX)    NULL,         -- JSON，INSERT/UPDATE 后快照
    changed_at   DATETIME2(6)     NOT NULL DEFAULT GETDATE(),
    ip_address   VARCHAR(45)      NULL,
    CONSTRAINT PK_change_logs   PRIMARY KEY (change_id),
    CONSTRAINT CK_change_op     CHECK (operation IN ('I','U','D'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('audit.change_logs') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'数据变更历史（逻辑 CDC，追加写入）',
        @level0type=N'SCHEMA', @level0name=N'audit',
        @level1type=N'TABLE',  @level1name=N'change_logs';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('audit.change_logs') AND name = 'IX_chglog_table_pk')
    CREATE INDEX IX_chglog_table_pk ON audit.change_logs (db_name, schema_name, table_name, pk_value, changed_at DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('audit.change_logs') AND name = 'IX_chglog_user_time')
    CREATE INDEX IX_chglog_user_time ON audit.change_logs (changed_by, changed_at DESC) WHERE changed_by IS NOT NULL;
GO

-- ── 50. audit.login_logs ─────────────────────────────────────
-- 登录日志：含设备指纹、地理信息
IF OBJECT_ID('audit.login_logs','U') IS NULL
CREATE TABLE audit.login_logs (
    login_id      BIGINT           NOT NULL IDENTITY(1,1),
    tenant_id     SMALLINT         NOT NULL,
    user_id       UNIQUEIDENTIFIER NULL,
    username      VARCHAR(50)      NOT NULL,
    login_time    DATETIME2(6)     NOT NULL DEFAULT GETDATE(),
    logout_time   DATETIME2(6)     NULL,
    ip_address    VARCHAR(45)      NOT NULL,
    country_code  CHAR(2)          NULL,
    city          NVARCHAR(100)    NULL,
    device_type   TINYINT          NOT NULL DEFAULT 1,  -- 1=PC 2=Mobile 3=Tablet 4=API
    os_name       VARCHAR(50)      NULL,
    browser       VARCHAR(100)     NULL,
    success       BIT              NOT NULL DEFAULT 1,
    fail_reason   VARCHAR(100)     NULL,
    session_token CHAR(64)         NULL,
    CONSTRAINT PK_login_logs  PRIMARY KEY (login_id),
    CONSTRAINT CK_device_type CHECK (device_type IN (1,2,3,4))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.extended_properties
               WHERE major_id = OBJECT_ID('audit.login_logs') AND minor_id = 0 AND name = 'MS_Description')
    EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'用户登录日志',
        @level0type=N'SCHEMA', @level0name=N'audit',
        @level1type=N'TABLE',  @level1name=N'login_logs';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('audit.login_logs') AND name = 'IX_loginlog_tenant_time')
    CREATE INDEX IX_loginlog_tenant_time ON audit.login_logs (tenant_id, login_time DESC);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('audit.login_logs') AND name = 'IX_loginlog_user_time')
    CREATE INDEX IX_loginlog_user_time   ON audit.login_logs (user_id, login_time DESC) WHERE user_id IS NOT NULL;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('audit.login_logs') AND name = 'IX_loginlog_fail')
    CREATE INDEX IX_loginlog_fail        ON audit.login_logs (tenant_id, login_time DESC) WHERE success = 0;
GO


PRINT 'seed_testdata.sql completed: 50 tables across salesdb / hrdb / inventorydb / crmdb / configdb / auditdb.';
