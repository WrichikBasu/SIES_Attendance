package in.basulabs.siesattendance.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service.
 * <p>
 * Four endpoints, under {@code /api/v1}: signing in, registering a device, exporting a register,
 * and loading a roster. Every one of them needs a bearer token except the first, and the token is
 * the only way an actor is established — see {@code BearerTokenFilter} and {@code ActorDatabase},
 * which together are the reason the policies written in Phase 4 and the audit entries written in
 * Phase 5 are now reachable by something other than a test.
 * <p>
 * What is deliberately still absent is the sync pair: the write path that drains a device's
 * outbox and the pull that answers "everything since this watermark". Their shape is the
 * outbox's shape, and the outbox is Phase 6's. An endpoint written before its caller exists is an
 * endpoint whose shape is a guess.
 * <p>
 * The half that can be built and checked without a client at all is in
 * {@code in.basulabs.siesattendance.server.sql}: the PostgreSQL translations of the two query
 * builders, exercised by dumping every statement they produce and running it against a real
 * PostgreSQL instance beside the SQLite one, row for row.
 */
@SpringBootApplication
public class SiesAttendanceServer {

	public static void main(String[] args) {
		SpringApplication.run(SiesAttendanceServer.class, args);
	}
}
