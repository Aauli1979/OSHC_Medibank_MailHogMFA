package au.edu.oshc.smartguide;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class PasskeyMigrationTest {
    @Test void migrationPreservesExistingRowsAndCanBeAppliedTwice() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:passkey-migration", "sa", "")) {
            RunScript.execute(connection, new StringReader("CREATE TABLE app_users(id BIGINT PRIMARY KEY, email VARCHAR(255));"
                    + "INSERT INTO app_users VALUES(1, 'existing@example.test');"));
            String sql = Files.readString(Path.of("migrations/001_passkeys_h2.sql"));
            RunScript.execute(connection, new StringReader(sql));
            RunScript.execute(connection, new StringReader(sql));
            try (var rows = connection.createStatement().executeQuery("SELECT email, passkey_user_handle FROM app_users WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("existing@example.test");
                assertThat(rows.getString(2)).isNull();
            }
            try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM passkey_credentials")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
