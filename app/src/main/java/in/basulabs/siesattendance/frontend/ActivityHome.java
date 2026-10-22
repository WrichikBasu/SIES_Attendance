package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavGraph;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.HashSet;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.frontend.util.SignInGuard;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The single activity hosting every screen.
 * <p>
 * The app bar is a MaterialToolbar in the layout rather than a window-decor action bar, so it
 * occupies real space above the fragment container instead of drawing over it.
 * <p>
 * It is also where the app is locked. The navigation graph's start destination is chosen from
 * {@code SignInGuard.gate} before any fragment exists, and {@link #onStart} asks again — so a
 * phone with no session shows the sign-in screen and nothing else, rather than each of two dozen
 * destinations having to remember to check.
 * <p>
 * There is one {@link AttendanceViewModel} for the whole app, scoped to this activity. Every
 * fragment reaches it through {@code requireActivity()}, which is what lets a filter chosen on
 * one screen still be in force on the next, and lets work started in a fragment report back
 * after that fragment has gone.
 */
public class ActivityHome extends AppCompatActivity {

	private NavController navController;

	private AppBarLayout appBarLayout;

	private BottomNavigationView bottomNav;

	private AttendanceViewModel viewModel;

	/** Destinations that are top-level tabs; everything else hides the bottom bar. */
	private static final Set<Integer> TOP_LEVEL = new HashSet<>();

	static {
		TOP_LEVEL.add(R.id.fragmentDashboard);
		TOP_LEVEL.add(R.id.fragmentStudents);
		TOP_LEVEL.add(R.id.fragmentProfessors);
		TOP_LEVEL.add(R.id.fragmentCoursesHub);
		TOP_LEVEL.add(R.id.fragmentAttendancePicker);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_home);

		viewModel = new ViewModelProvider(this).get(AttendanceViewModel.class);

		appBarLayout = findViewById(R.id.appBarLayout);
		bottomNav = findViewById(R.id.bottomNav);

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		applyWindowInsets();

		NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
				.findFragmentById(R.id.fragmentContainer);

		if (navHostFragment == null) {
			throw new IllegalStateException("NavHostFragment missing from activity_home");
		}

		navController = navHostFragment.getNavController();

		// The graph is set here rather than in the layout, because where the app starts is a
		// question about this phone: a device with no session shows the sign-in screen and
		// nothing else. Doing it in the layout would create the dashboard first and then
		// navigate away from it, which is a flash of somebody else's data.
		NavGraph graph = navController.getNavInflater().inflate(R.navigation.nav_graph);

		boolean signIn = mustSignIn();

		if (signIn) {
			endUnusableSession();
		}

		graph.setStartDestination(signIn ? R.id.fragmentSignIn : R.id.fragmentDashboard);

		// setGraph restores a saved back stack when there is one, so a rotation returns to the
		// screen the user was on rather than to the start destination chosen above.
		navController.setGraph(graph, null);

		AppBarConfiguration appBarConfiguration =
				new AppBarConfiguration.Builder(TOP_LEVEL).build();

		NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
		NavigationUI.setupWithNavController(bottomNav, navController);

		navController.addOnDestinationChangedListener(this::onDestinationChanged);

		addSyncToTheAppBar();
		observeToasts();

		// A sync that finds the session ended forgets the token and stops. Without this the
		// person stayed where they were, and every Sync now asked a server that had already
		// said no, until the app happened to be reopened.
		viewModel.getSyncRunning().observe(this, running -> {
			if (!Boolean.TRUE.equals(running)) {
				leaveIfSignedOut();
			}
		});
	}

	/**
	 * Pads the app bar down by the status bar height and the bottom bar up by the gesture bar
	 * height. Without this the toolbar title sits under the clock and the navigation labels sit
	 * under the gesture handle.
	 * <p>
	 * This works on every API level without opting into edge-to-edge explicitly. Android 15 and
	 * above force edge-to-edge for apps targeting SDK 35+, and the listeners then report real
	 * inset values; on older releases the window is already inset by the system and the values
	 * come back as zero, leaving the layout unchanged.
	 */
	private void applyWindowInsets() {

		ViewCompat.setOnApplyWindowInsetsListener(appBarLayout, (view, windowInsets) -> {
			Insets bars = windowInsets.getInsets(
					WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			view.setPadding(bars.left, bars.top, bars.right, 0);
			return windowInsets;
		});

		ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (view, windowInsets) -> {
			Insets bars = windowInsets.getInsets(
					WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			view.setPadding(bars.left, 0, bars.right, bars.bottom);
			return windowInsets;
		});

		// The container keeps the side insets only; top and bottom are already accounted for by
		// the two bars above and below it.
		View container = findViewById(R.id.fragmentContainer);
		ViewCompat.setOnApplyWindowInsetsListener(container, (view, windowInsets) -> {
			Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
			view.setPadding(bars.left, 0, bars.right, 0);
			return windowInsets;
		});
	}

	/**
	 * Shows the bottom bar only on the tab destinations. A detail or editor screen is somewhere
	 * the user navigated into and should leave by going back, not by switching tabs.
	 * <p>
	 * The sign-in screen loses the app bar as well. A toolbar there would offer an up arrow to
	 * somewhere there is no going back to, and a tab bar would offer four screens that are not
	 * available — a locked door with the handles still on it.
	 */
	private void onDestinationChanged(@NonNull NavController controller,
	                                  @NonNull NavDestination destination,
	                                  @Nullable Bundle arguments) {

		boolean locked = destination.getId() == R.id.fragmentSignIn;

		appBarLayout.setVisibility(locked ? View.GONE : View.VISIBLE);

		bottomNav.setVisibility(!locked && TOP_LEVEL.contains(destination.getId())
				? View.VISIBLE
				: View.GONE);

		// The app bar's sync item is shown or hidden by where we are, so the menu has to be
		// asked again whenever that changes. Without this it keeps whatever it decided on the
		// screen it was first built for.
		invalidateMenu();
	}

	/**
	 * Locks the app again if the session has gone while it was away.
	 * <p>
	 * The one case this exists for: a background sync met a 401 — the token was revoked, or the
	 * password was changed from another phone — and {@code DeviceIdentity} forgot it. The app
	 * was already running, so the decision made in {@code onCreate} is stale, and without this
	 * the next person to pick the phone up would find it open.
	 * <p>
	 * One exception, and it is not a small one: a register being marked is not interrupted. The
	 * sheet is in memory until it is saved, so ejecting somebody mid-roll would throw away
	 * attendance that has already been taken — which is the one outcome this whole design
	 * refuses. The lock arrives when they leave that screen.
	 */
	@Override
	protected void onStart() {
		super.onStart();
		leaveIfSignedOut();
	}

	/** Goes to the sign-in screen when this phone has a server and no session; see onStart. */
	private void leaveIfSignedOut() {

		if (!mustSignIn()) {
			return;
		}

		NavDestination current = navController.getCurrentDestination();

		if (current == null
				|| current.getId() == R.id.fragmentSignIn
				|| current.getId() == R.id.fragmentTakeAttendance) {
			return;
		}

		endUnusableSession();
		navController.navigate(R.id.action_global_signIn);
	}

	/**
	 * Whether this phone belongs on the sign-in screen: no session, or one the server will not
	 * use until its password is changed. The sign-in screen is where that is asked for, with
	 * the current password just typed; a session left open with nobody asking was an app that
	 * looked signed in and failed every sync.
	 */
	private boolean mustSignIn() {
		return SignInGuard.gate(viewModel.hasServer(), viewModel.isSignedIn())
				== SignInGuard.Gate.SIGN_IN
				|| (viewModel.isSignedIn() && viewModel.passwordChangeRequired());
	}

	/** Ends a session that cannot be used until its password changes; signing in reopens it. */
	private void endUnusableSession() {
		if (viewModel.isSignedIn() && viewModel.passwordChangeRequired()) {
			viewModel.abandonSignIn();
		}
	}

	/**
	 * A way to the sync screen from wherever somebody is.
	 * <p>
	 * On the activity rather than on each fragment, which is the only arrangement that puts it
	 * on all two dozen screens and keeps it there when the twenty-fifth is added. The fragments
	 * that have menus of their own — a student's record, one register — keep them; Android
	 * merges the two, and this item is the activity's, so it sits after theirs.
	 * <p>
	 * Hidden on the sync screen itself, where it would lead nowhere, and on the sign-in screen,
	 * which has no app bar at all and nothing a signed-out phone should reach.
	 */
	private void addSyncToTheAppBar() {

		addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_app_bar, menu);
			}

			@Override
			public void onPrepareMenu(@NonNull Menu menu) {

				NavDestination current = navController.getCurrentDestination();
				int id = current == null ? 0 : current.getId();

				menu.findItem(R.id.action_open_sync).setVisible(
						id != R.id.fragmentSync && id != R.id.fragmentSignIn);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {

				if (item.getItemId() != R.id.action_open_sync) {
					return false;
				}

				navController.navigate(R.id.action_global_sync);
				return true;
			}
		});
	}

	/**
	 * All user-facing messages funnel through the ViewModel so background work started in one
	 * fragment can still report back after that fragment has gone away.
	 * <p>
	 * The message is cleared as soon as it is shown. Without that it would be redelivered on
	 * every configuration change, and a rotation would replay a snackbar the user had already
	 * read and dismissed.
	 */
	private void observeToasts() {
		viewModel.getToastMessage().observe(this, message -> {
			if (message != null && !message.isEmpty()) {
				Snackbar.make(findViewById(R.id.fragmentContainer), message,
						Snackbar.LENGTH_LONG).show();
				viewModel.clearToast();
			}
		});
	}

	@Override
	public boolean onSupportNavigateUp() {
		return navController.navigateUp() || super.onSupportNavigateUp();
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			return navController.navigateUp() || super.onOptionsItemSelected(item);
		}
		return super.onOptionsItemSelected(item);
	}
}
