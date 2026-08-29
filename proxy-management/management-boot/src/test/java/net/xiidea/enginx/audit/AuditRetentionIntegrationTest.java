package net.xiidea.enginx.audit;

import net.xiidea.enginx.application.audit.AuditPartitionManager;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit retention, against a real partitioned table.
 *
 * <p>This is the only code in the platform that destroys audit history, so it is worth proving
 * three things rather than one: that it drops what it should, that it leaves alone what it should
 * not, and that the default partition is never touched. A retention job that is slightly too eager
 * is discovered during an investigation, which is the worst possible moment.
 */
class AuditRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditPartitionManager partitions;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Three months of history, created directly so the test does not depend on the clock.
        for (String month : List.of("2024_01", "2024_02", "2024_03")) {
            String from = month.replace('_', '-') + "-01";
            String to = nextMonth(from);
            jdbc.execute("create table if not exists audit_logs_" + month
                    + " partition of audit_logs for values from ('" + from + "') to ('" + to + "')");
        }
    }

    private static String nextMonth(String from) {
        return LocalDate.parse(from).plusMonths(1).toString();
    }

    private List<String> partitionNames() {
        return jdbc.queryForList(
                "select c.relname from pg_class c "
                        + "join pg_inherits i on i.inhrelid = c.oid "
                        + "join pg_class p on p.oid = i.inhparent "
                        + "where p.relname = 'audit_logs' order by c.relname",
                String.class);
    }

    @Test
    @DisplayName("partitions entirely before the cutoff are dropped, and the rest are left alone")
    void dropsOnlyWhatIsFullyPastTheCutoff() {
        List<String> dropped = partitions.dropPartitionsBefore(LocalDate.parse("2024-03-01"));

        assertThat(dropped).contains("audit_logs_2024_01", "audit_logs_2024_02");
        // March runs to 2024-04-01, which is after the cutoff, so part of it is still in window.
        assertThat(dropped).doesNotContain("audit_logs_2024_03");
        assertThat(partitionNames()).contains("audit_logs_2024_03");
    }

    /**
     * Rows land in the default partition only when their timestamp falls outside every monthly
     * range — which means something unexpected happened. Those are the last rows that should ever
     * be discarded by a routine job.
     */
    @Test
    @DisplayName("the default partition is never dropped, whatever the cutoff")
    void neverDropsTheDefaultPartition() {
        List<String> dropped = partitions.dropPartitionsBefore(LocalDate.parse("2999-01-01"));

        // Asserted so this test cannot pass against an implementation that drops nothing at all.
        assertThat(dropped).isNotEmpty();
        assertThat(dropped).doesNotContain("audit_logs_default");
        assertThat(partitionNames()).contains("audit_logs_default");
    }

    @Test
    @DisplayName("a cutoff before all history drops nothing")
    void oldCutoffDropsNothing() {
        assertThat(partitions.dropPartitionsBefore(LocalDate.parse("2000-01-01"))).isEmpty();
    }

    @Test
    @DisplayName("dropping is idempotent, so a repeated run is a no-op rather than an error")
    void repeatedRunIsSafe() {
        assertThat(partitions.dropPartitionsBefore(LocalDate.parse("2024-03-01")))
                .describedAs("the first run must actually drop something")
                .isNotEmpty();

        assertThat(partitions.dropPartitionsBefore(LocalDate.parse("2024-03-01"))).isEmpty();
    }

    @Test
    @DisplayName("the current month survives a cutoff computed from a retention window")
    void currentMonthSurvives() {
        partitions.ensureAhead(1);
        String thisMonth = "audit_logs_" + LocalDate.now().toString().substring(0, 7).replace('-', '_');

        // What a 12-month retention would compute today.
        partitions.dropPartitionsBefore(LocalDate.now().withDayOfMonth(1).minusMonths(12));

        assertThat(partitionNames())
                .describedAs("retention must never remove the month currently being written to")
                .contains(thisMonth);
    }
}
