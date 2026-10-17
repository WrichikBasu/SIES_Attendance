package in.basulabs.siesattendance.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service.
 * <p>
 * Deliberately almost empty. Phase 2 is the skeleton: it boots, it applies the migrations, and
 * it says whether it can reach the database. There is no API yet, because there is nothing in
 * the database to serve — the importer is Phase 3 and authentication is Phase 5, and an
 * endpoint that returned attendance before either existed would be an endpoint with no caller
 * and no access control.
 * <p>
 * What does exist is the half that can be built and checked without a single row: the
 * PostgreSQL translations of the two query builders, in {@code in.basulabs.siesattendance
 * .server.sql}. They are exercised by dumping every statement they produce and running it
 * against a real PostgreSQL instance beside the SQLite one, row for row.
 */
@SpringBootApplication
public class SiesAttendanceServer {

	public static void main(String[] args) {
		SpringApplication.run(SiesAttendanceServer.class, args);
	}
}
