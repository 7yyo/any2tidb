package com.tool.loadgen;

import com.tool.config.AppConfig;
import com.tool.logging.Log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hardcoded CRUD load generator for dbo.Employees and dbo.Departments.
 * Inserts/updates/deletes/selects at a configurable rate to simulate
 * production traffic during migration testing.
 *
 * Usage: any2tidb sqlserver loadgen --database=HRDB --rate=5 --duration=300
 */
public class LoadGenerator {

    private static final String[] GENDERS = {"M", "F", "O"};
    private static final String[] EMPLOYMENT_TYPES = {"全职", "兼职", "实习", "外包", "顾问"};
    private static final String[] FIRST_NAMES = {
        "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael",
        "Linda", "David", "Elizabeth", "William", "Barbara", "Richard", "Susan",
        "Joseph", "Jessica", "Thomas", "Sarah", "Christopher", "Karen",
        "张伟", "李娜", "王芳", "赵敏", "陈静", "刘洋", "杨帆", "黄蕾",
        "Alejandro", "Sofia", "Hiroshi", "Yuki", "Olga", "Fatima"
    };
    private static final String[] LAST_NAMES = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
        "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
        "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "王", "李", "张", "刘", "陈", "杨", "赵", "黄",
        "Müller", "Bernard", "Tanaka", "Petrov", "Al-Farsi"
    };
    private static final String[] JOB_TITLES = {
        "Software Engineer", "Project Manager", "Data Analyst", "HR Specialist",
        "Accountant", "Sales Representative", "Marketing Manager", "DevOps Engineer",
        "QA Engineer", "Product Manager", "UX Designer", "Business Analyst"
    };
    private static final String[] DEPT_CODES = {"ENG", "SALES", "HR", "FIN", "MKT", "OPS", "RND", "ADMIN"};
    private static final String[] DEPT_NAMES = {
        "Engineering Department", "Sales Department", "Human Resources",
        "Finance Department", "Marketing Department", "Operations",
        "Research and Development", "Administration"
    };
    private static final String[] LOCATIONS = {
        "Beijing", "Shanghai", "Shenzhen", "Hangzhou", "Guangzhou",
        "Chengdu", "Nanjing", "Wuhan", "Singapore", "Tokyo"
    };
    private final AppConfig.DbConfig sourceConfig;
    private final String database;
    private final int ratePerSecond;
    private final int durationSeconds;
    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicLong insertDept = new AtomicLong();
    private final AtomicLong insertEmp = new AtomicLong();
    private final AtomicLong updateCount = new AtomicLong();
    private final AtomicLong deleteCount = new AtomicLong();
    private final AtomicLong selectCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final Random rng = new Random();

    private final List<Integer> deptIds = new ArrayList<>();
    private final List<Integer> empIds = new ArrayList<>();
    private static final int MAX_ID_CACHE = 500;

    private volatile boolean running = true;
    private Connection conn;

    public LoadGenerator(AppConfig.DbConfig sourceConfig, String database,
                         int ratePerSecond, int durationSeconds) {
        this.sourceConfig = sourceConfig;
        this.database = database;
        this.ratePerSecond = ratePerSecond;
        this.durationSeconds = durationSeconds;
    }

    public void start() throws Exception {
        conn = DriverManager.getConnection(
                sourceConfig.jdbcUrlTo(database),
                sourceConfig.getUsername(),
                sourceConfig.getPassword());

        // Dump actual schema so we know exact column names
        dumpSchema("dbo.Departments");
        dumpSchema("dbo.Employees");

        // Verify tables exist
        if (!tableExists("dbo.Departments") || !tableExists("dbo.Employees")) {
            Log.error(log, "Required tables not found in " + database,
                    "hint", "Run seed_testdata.sql first to create dbo.Departments and dbo.Employees");
            System.out.println("Error: dbo.Departments and/or dbo.Employees not found in " + database);
            System.out.println("Run seed_testdata.sql first: sqlcmd -S <host>,<port> -U <user> -P <pass> -C -i scripts/seed_testdata.sql");
            System.exit(1);
        }

        // Load existing IDs, bootstrap if tables are empty
        loadExistingIds();
        if (deptIds.isEmpty()) {
            bootstrapDepartments();
            loadExistingIds();
        }
        // Seed a few employees if none exist
        if (empIds.isEmpty() && !deptIds.isEmpty()) {
            for (int i = 0; i < 3; i++) insertEmployee();
        }

        Log.info(log, "loadgen starting",
                "database", database,
                "tables", "dbo.Departments, dbo.Employees",
                "rate", ratePerSecond + "/s",
                "duration", durationSeconds > 0 ? durationSeconds + "s" : "forever",
                "existingDepts", deptIds.size(),
                "existingEmps", empIds.size());

        long intervalMs = Math.max(200, 1000 / ratePerSecond);
        scheduler.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::reportProgress, 10, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            reportProgress();
            try { conn.close(); } catch (Exception ignored) {}
        }));

        if (durationSeconds > 0) {
            scheduler.schedule(() -> {
                Log.info(log, "loadgen duration reached, stopping");
                running = false;
                scheduler.shutdown();
                try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                reportProgress();
                try { conn.close(); } catch (Exception ignored) {}
            }, durationSeconds, TimeUnit.SECONDS);
        }

        while (running && !scheduler.isTerminated()) {
            Thread.sleep(500);
        }
        Log.info(log, "loadgen stopped",
                "insertDept", insertDept.get(), "insertEmp", insertEmp.get(),
                "updates", updateCount.get(), "deletes", deleteCount.get(),
                "selects", selectCount.get(), "errors", errorCount.get());
    }

    private void dumpSchema(String table) {
        Log.info(log, "schema dump for " + table);
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE "
                   + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // table name may include schema prefix; INFORMATION_SCHEMA uses bare table name
            String bareName = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
            ps.setString(1, bareName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Log.info(log, "  column",
                            "name", rs.getString(1),
                            "type", rs.getString(2),
                            "maxLen", rs.getObject(3),
                            "nullable", rs.getString(4));
                }
            }
        } catch (SQLException e) {
            Log.warn(log, "schema dump failed", "table", table, "error", e.getMessage());
        }
    }

    private boolean tableExists(String qualifiedName) {
        try (Statement s = conn.createStatement()) {
            s.executeQuery("SELECT TOP 1 1 FROM " + qualifiedName);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void loadExistingIds() {
        try (Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT DepartmentID FROM dbo.Departments");
            while (rs.next()) deptIds.add(rs.getInt(1));
        } catch (Exception e) { /* table may be empty */ }
        try (Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT TOP 500 EmployeeID FROM dbo.Employees ORDER BY EmployeeID DESC");
            while (rs.next()) empIds.add(rs.getInt(1));
        } catch (Exception e) { /* table may be empty */ }
    }

    private void bootstrapDepartments() {
        Log.info(log, "bootstrapping seed departments");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int i = 0; i < DEPT_CODES.length; i++) {
            try {
                String sql = "INSERT INTO dbo.Departments (DeptCode, DeptName, ParentDeptID, Budget, Headcount, Location, IsActive, CreatedAt) VALUES (?, ?, NULL, ?, ?, ?, 1, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, DEPT_CODES[i]);
                    ps.setString(2, DEPT_NAMES[i]);
                    ps.setBigDecimal(3, BigDecimal.valueOf(500000 + rng.nextDouble() * 5000000)
                            .setScale(2, java.math.RoundingMode.HALF_UP));
                    ps.setInt(4, 5 + rng.nextInt(30));
                    ps.setString(5, LOCATIONS[rng.nextInt(LOCATIONS.length)]);
                    ps.setTimestamp(6, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            Log.info(log, "seed department", "code", DEPT_CODES[i], "id", rs.getInt(1));
                        }
                    }
                }
            } catch (SQLException e) {
                Log.warn(log, "seed department failed", "code", DEPT_CODES[i], "error", e.getMessage());
            }
        }
    }

    // ── tick ────────────────────────────────────────────────────────────────

    private void tick() {
        if (!running) return;
        try {
            int op = rng.nextInt(100);
            if (op < 40) {
                insertEmployee();
            } else if (op < 55) {
                insertDepartment();
            } else if (op < 75) {
                updateRandom();
            } else if (op < 90) {
                deleteRandom();
            } else {
                selectRandom();
            }
        } catch (Exception e) {
            long err = errorCount.incrementAndGet();
            if (err <= 3) Log.warn(log, "tick error", "error", e.getMessage());
        }
    }

    // ── INSERT ──────────────────────────────────────────────────────────────

    private void insertEmployee() {
        String gender = GENDERS[rng.nextInt(GENDERS.length)];
        String firstName = FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)];
        String lastName = LAST_NAMES[rng.nextInt(LAST_NAMES.length)];
        String email = firstName.toLowerCase() + "." + lastName.toLowerCase()
                + rng.nextInt(100) + "@company.com";
        String phone = String.format("1%09d", rng.nextInt(1000000000));
        LocalDate birth = LocalDate.of(1970 + rng.nextInt(30), 1 + rng.nextInt(12), 1 + rng.nextInt(28));
        LocalDate hire = LocalDate.of(2020 + rng.nextInt(6), 1 + rng.nextInt(12), 1 + rng.nextInt(28));
        String jobTitle = JOB_TITLES[rng.nextInt(JOB_TITLES.length)];
        BigDecimal salary = BigDecimal.valueOf(5001 + rng.nextDouble() * 45000)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        String employmentType = EMPLOYMENT_TYPES[rng.nextInt(EMPLOYMENT_TYPES.length)];
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        // FK: dept_id — pick from existing departments or NULL
        Integer deptId = deptIds.isEmpty() ? null
                : deptIds.get(rng.nextInt(deptIds.size()));
        // FK: manager_id — pick from existing employees or NULL
        Integer mgrId = (empIds.isEmpty() || rng.nextBoolean()) ? null
                : empIds.get(rng.nextInt(empIds.size()));

        String sql = """
            INSERT INTO dbo.Employees (FirstName, LastName, Gender,
                BirthDate, HireDate, DepartmentID, JobTitle, ManagerID, Email, Phone,
                Salary, EmploymentType, IsActive, CreatedAt, UpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setString(3, gender);
            ps.setDate(4, java.sql.Date.valueOf(birth));
            ps.setDate(5, java.sql.Date.valueOf(hire));
            if (deptId != null) ps.setInt(6, deptId); else ps.setNull(6, java.sql.Types.INTEGER);
            ps.setString(7, jobTitle);
            if (mgrId != null) ps.setInt(8, mgrId); else ps.setNull(8, java.sql.Types.INTEGER);
            ps.setString(9, email);
            ps.setString(10, phone);
            ps.setBigDecimal(11, salary);
            ps.setString(12, employmentType);
            ps.setTimestamp(13, now);
            ps.setTimestamp(14, now);
            ps.executeUpdate();
            insertEmp.incrementAndGet();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) cacheEmpId(rs.getInt(1));
            }
        } catch (SQLException e) {
            long err = errorCount.incrementAndGet();
            if (err <= 3) Log.warn(log, "insert employee failed", "error", e.getMessage());
        }
    }

    private void insertDepartment() {
        String deptCode = DEPT_CODES[rng.nextInt(DEPT_CODES.length)]
                + String.format("%02d", rng.nextInt(100));
        String deptName = DEPT_NAMES[rng.nextInt(DEPT_NAMES.length)]
                + " " + (rng.nextInt(5) + 1);
        Integer parentId = (!deptIds.isEmpty() && rng.nextInt(3) > 0)
                ? deptIds.get(rng.nextInt(deptIds.size())) : null;
        Integer mgrId = (!empIds.isEmpty() && rng.nextInt(5) == 0)
                ? empIds.get(rng.nextInt(empIds.size())) : null;
        BigDecimal budget = BigDecimal.valueOf(100000 + rng.nextDouble() * 9900000)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        int headcount = 1 + rng.nextInt(50);
        String location = LOCATIONS[rng.nextInt(LOCATIONS.length)];
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        String sql = """
            INSERT INTO dbo.Departments (DeptCode, DeptName, ParentDeptID, ManagerID,
                Budget, Headcount, Location, IsActive, CreatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)""";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, deptCode);
            ps.setString(2, deptName);
            if (parentId != null) ps.setInt(3, parentId); else ps.setNull(3, java.sql.Types.INTEGER);
            if (mgrId != null) ps.setInt(4, mgrId); else ps.setNull(4, java.sql.Types.INTEGER);
            ps.setBigDecimal(5, budget);
            ps.setInt(6, headcount);
            ps.setString(7, location);
            ps.setTimestamp(8, now);
            ps.executeUpdate();
            insertDept.incrementAndGet();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) cacheDeptId(rs.getInt(1));
            }
        } catch (SQLException e) {
            long err = errorCount.incrementAndGet();
            if (err <= 3) Log.warn(log, "insert department failed", "error", e.getMessage());
        }
    }

    // ── UPDATE ──────────────────────────────────────────────────────────────

    private void updateRandom() {
        if (empIds.isEmpty() && deptIds.isEmpty()) return;

        // 70% update employee, 30% update department
        if ((!empIds.isEmpty() && rng.nextInt(10) < 7) || deptIds.isEmpty()) {
            updateEmployee();
        } else {
            updateDepartment();
        }
    }

    private void updateEmployee() {
        if (empIds.isEmpty()) return;
        int empId = empIds.get(rng.nextInt(empIds.size()));

        // Randomly update one of: JobTitle, Salary, Phone, IsActive, ManagerID, EmploymentType
        int choice = rng.nextInt(6);
        try (PreparedStatement ps = switch (choice) {
            case 0 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET JobTitle = ? WHERE EmployeeID = ?");
                p.setString(1, JOB_TITLES[rng.nextInt(JOB_TITLES.length)]);
                p.setInt(2, empId);
                yield p;
            }
            case 1 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET Salary = ? WHERE EmployeeID = ?");
                p.setBigDecimal(1, BigDecimal.valueOf(5001 + rng.nextDouble() * 45000)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
                p.setInt(2, empId);
                yield p;
            }
            case 2 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET Phone = ? WHERE EmployeeID = ?");
                p.setString(1, String.format("1%09d", rng.nextInt(1000000000)));
                p.setInt(2, empId);
                yield p;
            }
            case 3 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET IsActive = ? WHERE EmployeeID = ?");
                p.setBoolean(1, rng.nextBoolean());
                p.setInt(2, empId);
                yield p;
            }
            case 4 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET EmploymentType = ? WHERE EmployeeID = ?");
                p.setString(1, EMPLOYMENT_TYPES[rng.nextInt(EMPLOYMENT_TYPES.length)]);
                p.setInt(2, empId);
                yield p;
            }
            default -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Employees SET ManagerID = ? WHERE EmployeeID = ?");
                if (!empIds.isEmpty() && rng.nextBoolean())
                    p.setInt(1, empIds.get(rng.nextInt(empIds.size())));
                else p.setNull(1, java.sql.Types.INTEGER);
                p.setInt(2, empId);
                yield p;
            }
        }) {
            if (ps.executeUpdate() > 0) updateCount.incrementAndGet();
        } catch (SQLException e) { errorCount.incrementAndGet(); }
    }

    private void updateDepartment() {
        if (deptIds.isEmpty()) return;
        int deptId = deptIds.get(rng.nextInt(deptIds.size()));

        // Randomly update: DeptName, Budget, Headcount, Location, or ManagerID
        int choice = rng.nextInt(5);
        try (PreparedStatement ps = switch (choice) {
            case 0 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Departments SET DeptName = ? WHERE DepartmentID = ?");
                p.setString(1, DEPT_NAMES[rng.nextInt(DEPT_NAMES.length)] + " " + (rng.nextInt(5) + 1));
                p.setInt(2, deptId);
                yield p;
            }
            case 1 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Departments SET Budget = ? WHERE DepartmentID = ?");
                p.setBigDecimal(1, BigDecimal.valueOf(100000 + rng.nextDouble() * 9900000)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
                p.setInt(2, deptId);
                yield p;
            }
            case 2 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Departments SET Headcount = ? WHERE DepartmentID = ?");
                p.setInt(1, 1 + rng.nextInt(50));
                p.setInt(2, deptId);
                yield p;
            }
            case 3 -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Departments SET Location = ? WHERE DepartmentID = ?");
                p.setString(1, LOCATIONS[rng.nextInt(LOCATIONS.length)]);
                p.setInt(2, deptId);
                yield p;
            }
            default -> {
                PreparedStatement p = conn.prepareStatement(
                        "UPDATE dbo.Departments SET ManagerID = ? WHERE DepartmentID = ?");
                if (!empIds.isEmpty() && rng.nextBoolean())
                    p.setInt(1, empIds.get(rng.nextInt(empIds.size())));
                else p.setNull(1, java.sql.Types.INTEGER);
                p.setInt(2, deptId);
                yield p;
            }
        }) {
            if (ps.executeUpdate() > 0) updateCount.incrementAndGet();
        } catch (SQLException e) { errorCount.incrementAndGet(); }
    }

    // ── DELETE ──────────────────────────────────────────────────────────────

    private void deleteRandom() {
        if (empIds.isEmpty() && deptIds.isEmpty()) return;

        // Prefer deleting employees (less disruptive than deleting departments with FK children)
        if (!empIds.isEmpty() && (deptIds.isEmpty() || rng.nextInt(10) < 8)) {
            int empId;
            synchronized (empIds) { empId = empIds.remove(rng.nextInt(empIds.size())); }
            // Only soft-delete: set is_active = 0 (FK references from attendance etc.)
            String sql = "UPDATE dbo.Employees SET IsActive = 0 WHERE EmployeeID = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, empId);
                if (ps.executeUpdate() > 0) deleteCount.incrementAndGet();
            } catch (SQLException e) { errorCount.incrementAndGet(); }
        } else if (!deptIds.isEmpty()) {
            int deptId;
            synchronized (deptIds) { deptId = deptIds.remove(rng.nextInt(deptIds.size())); }
            String sql = "UPDATE dbo.Departments SET IsActive = 0 WHERE DepartmentID = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, deptId);
                if (ps.executeUpdate() > 0) deleteCount.incrementAndGet();
            } catch (SQLException e) { errorCount.incrementAndGet(); }
        }
    }

    // ── SELECT ──────────────────────────────────────────────────────────────

    private void selectRandom() {
        if (empIds.isEmpty() && deptIds.isEmpty()) {
            try (Statement s = conn.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM dbo.Employees");
                if (rs.next()) selectCount.incrementAndGet();
            } catch (SQLException e) { errorCount.incrementAndGet(); }
            return;
        }

        if (!empIds.isEmpty() && (deptIds.isEmpty() || rng.nextBoolean())) {
            int empId;
            synchronized (empIds) { empId = empIds.get(rng.nextInt(empIds.size())); }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT EmployeeID, FirstName, LastName, DepartmentID, JobTitle, Phone FROM dbo.Employees WHERE EmployeeID = ?")) {
                ps.setInt(1, empId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) { /* consume */ }
                selectCount.incrementAndGet();
            } catch (SQLException e) { errorCount.incrementAndGet(); }
        } else {
            int deptId;
            synchronized (deptIds) { deptId = deptIds.get(rng.nextInt(deptIds.size())); }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DepartmentID, DeptCode, DeptName FROM dbo.Departments WHERE DepartmentID = ?")) {
                ps.setInt(1, deptId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) { /* consume */ }
                selectCount.incrementAndGet();
            } catch (SQLException e) { errorCount.incrementAndGet(); }
        }
    }

    // ── ID caching ──────────────────────────────────────────────────────────

    private void cacheEmpId(int id) {
        synchronized (empIds) {
            if (empIds.size() >= MAX_ID_CACHE) empIds.remove(0);
            empIds.add(id);
        }
    }

    private void cacheDeptId(int id) {
        synchronized (deptIds) {
            if (deptIds.size() >= MAX_ID_CACHE) deptIds.remove(0);
            deptIds.add(id);
        }
    }

    // ── progress ───────────────────────────────────────────────────────────

    private void reportProgress() {
        long ide = insertDept.get(), ie = insertEmp.get();
        long upd = updateCount.get(), del = deleteCount.get();
        long sel = selectCount.get(), err = errorCount.get();
        if (ide + ie + upd + del + sel == 0) return;
        Log.info(log, "loadgen stats",
                "insertDept", ide, "insertEmp", ie,
                "updates", upd, "deletes", del,
                "selects", sel, "errors", err);
    }
}
