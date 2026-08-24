package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
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
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The single activity hosting every screen.
 * <p>
 * The app bar is a MaterialToolbar in the layout rather than a window-decor action bar, so it
 * occupies real space above the fragment container instead of drawing over it.
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

		AppBarConfiguration appBarConfiguration =
				new AppBarConfiguration.Builder(TOP_LEVEL).build();

		NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
		NavigationUI.setupWithNavController(bottomNav, navController);

		navController.addOnDestinationChangedListener(this::onDestinationChanged);

		observeToasts();
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
	 */
	private void onDestinationChanged(@NonNull NavController controller,
	                                  @NonNull NavDestination destination,
	                                  @Nullable Bundle arguments) {

		bottomNav.setVisibility(TOP_LEVEL.contains(destination.getId())
				? View.VISIBLE
				: View.GONE);
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
