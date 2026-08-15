package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.frontend.adapter.AdapterCoursesHubPager;

/**
 * Container for the courses, lectures and batches sub-screens.
 */
public class FragmentCoursesHub extends Fragment {

	private static final int[] TAB_LABELS = {
			R.string.tab_courses, R.string.tab_lectures, R.string.tab_batches
	};

	private TabLayoutMediator mediator;

	private ViewPager2 viewPager;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_courses_hub, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		TabLayout tabLayout = view.findViewById(R.id.tabLayout);
		viewPager = view.findViewById(R.id.viewPager);

		viewPager.setAdapter(new AdapterCoursesHubPager(this));

		mediator = new TabLayoutMediator(tabLayout, viewPager,
				(tab, position) -> tab.setText(TAB_LABELS[position]));
		mediator.attach();
	}

	@Override
	public void onDestroyView() {
		// The mediator holds references to both views; detaching avoids leaking them when the
		// fragment's view is destroyed but the fragment itself is retained on the back stack.
		if (mediator != null) {
			mediator.detach();
			mediator = null;
		}
		if (viewPager != null) {
			viewPager.setAdapter(null);
			viewPager = null;
		}
		super.onDestroyView();
	}
}
