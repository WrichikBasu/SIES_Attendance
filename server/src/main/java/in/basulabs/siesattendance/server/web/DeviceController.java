package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.UUID;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.server.security.AuditLog;
import in.basulabs.siesattendance.server.security.DeviceRegistry;
import in.basulabs.siesattendance.server.security.Sessions;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Registering a phone, and getting its node number.
 * <p>
 * The node is the bottom sixteen bits of every primary key the install will ever issue, so this
 * endpoint is the moment a device stops being anonymous: afterwards, a row's key says which phone
 * wrote it, and Phase 6's write path can refuse a row whose node is not the node of the device
 * presenting the token.
 *
 * <h3>Called on the token login gave out</h3>
 * A new install has no device to name when it signs in, so it signs in without one, calls this,
 * and the session it called on becomes bound to the device it just registered. One login, not
 * two. {@link Sessions#bindDevice} is what does it.
 *
 * <h3>Idempotent, and that is the whole reason install_id exists</h3>
 * A registration whose response was lost must be safe to retry — otherwise the retry burns a
 * second node, the phone ends up with two, and half its rows are attributed to a device that is
 * really the same one. The install's own identifier is what makes the second call return the
 * first call's answer.
 */
@RestController
@RequestMapping(Api.V1 + "/devices")
public final class DeviceController {

	/**
	 * @param installId        the install's own identifier, generated on the device and the same
	 *                         for ever. Required
	 * @param provisionalNode  the node it had picked for itself before registering, if any. In
	 *                         {@code 32768 .. 65535}: see {@link StructuredId}
	 * @param label            what to call this device in a list of them — "Rao's phone", "Lab
	 *                         tablet". Required, because a list of node numbers helps nobody
	 */
	public record RegisterRequest(@Nullable String installId, @Nullable Integer provisionalNode,
	                              @Nullable String label) {
	}

	/**
	 * @param alreadyRegistered true when this install was registered before and this is the node
	 *                          it already had
	 * @param provisionalNodeClashes how many other installs had picked the same provisional node.
	 *                               Non-zero means the keys this phone wrote before registering
	 *                               cannot be told from another phone's by their node alone —
	 *                               which the importer's {@code id_remap} is what resolves
	 */
	public record RegisterResponse(int node, long deviceId, boolean alreadyRegistered,
	                               long provisionalNodeClashes) {
	}

	@NonNull
	private final DeviceRegistry devices;

	@NonNull
	private final Sessions sessions;

	@NonNull
	private final ActorDatabase database;

	public DeviceController(@NonNull DeviceRegistry devices, @NonNull Sessions sessions,
	                        @NonNull ActorDatabase database) {

		this.devices = devices;
		this.sessions = sessions;
		this.database = database;
	}

	@PostMapping
	@NonNull
	public RegisterResponse register(@RequestBody @NonNull RegisterRequest body,
	                                 @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		if (body.installId() == null || body.installId().isBlank()) {
			throw new ApiException(400, "incomplete", "An install identifier is needed.");
		}

		if (body.label() == null || body.label().isBlank()) {
			throw new ApiException(400, "incomplete", "A label for the device is needed.");
		}

		UUID install = installId(body.installId());
		Integer provisional = provisionalNode(body.provisionalNode());

		DeviceRegistry.Registered device = database.inTransaction(actor, connection -> {

			DeviceRegistry.Registered registered = devices.register(connection, install,
					provisional, body.label().trim(), actor.appVersion(), actor.accountId());

			// Only the registration that actually created the row writes the entry. A retry
			// has nothing new to record, and an entry per retry would make one phone look
			// like several.
			if (!registered.alreadyRegistered()) {

				AuditLog.record(connection, AuditAction.DEVICE_REGISTER, "device_registration",
						AuditLog.detail("device_id", registered.deviceId()),
						AuditLog.detail(
								"install_id", install.toString(),
								"node", registered.node(),
								"provisional_node", provisional,
								"label", body.label().trim(),
								// Recorded on the entry and not only in the response, because
								// this is the fact that stops being discoverable later.
								"provisional_node_clashes", registered.provisionalClashes()));
			}
			return registered;
		});

		// After the transaction: binding a session is not part of registering a device, and a
		// failure here leaves a registered device whose owner can pick the binding up by
		// signing in again with the install id.
		sessions.bindDevice(actor.session().sessionId(), device.deviceId());

		return new RegisterResponse(device.node(), device.deviceId(),
				device.alreadyRegistered(), device.provisionalClashes());
	}

	@NonNull
	private static UUID installId(@NonNull String raw) {

		try {
			return UUID.fromString(raw.trim());

		} catch (IllegalArgumentException e) {
			throw new ApiException(400, "bad_install_id",
					"The install identifier is not a UUID.");
		}
	}

	/**
	 * Checks the provisional node is from the half of the space a device picks for itself.
	 * <p>
	 * The CHECK constraint says the same thing, and this says it in a sentence. A number from
	 * the assignable half would mean the install had already been given one, which is a
	 * different situation and must not be filed as this one.
	 */
	@Nullable
	private static Integer provisionalNode(@Nullable Integer node) {

		if (node == null) {
			return null;
		}

		if (node <= StructuredId.MAX_ASSIGNED_NODE_ID || node > StructuredId.MAX_NODE_ID) {
			throw new ApiException(400, "bad_provisional_node",
					"A self-picked node is between " + (StructuredId.MAX_ASSIGNED_NODE_ID + 1)
							+ " and " + StructuredId.MAX_NODE_ID + "; this one is " + node
							+ ".");
		}
		return node;
	}
}
