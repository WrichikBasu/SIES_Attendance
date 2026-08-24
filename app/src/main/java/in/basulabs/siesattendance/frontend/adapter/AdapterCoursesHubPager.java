package in.basulabs.siesattendance.frontend.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import in.basulabs.siesattendance.frontend.FragmentBatchList;
import in.basulabs.siesattendance.frontend.FragmentCourseList;
import in.basulabs.siesattendance.frontend.FragmentLectureList;

/**
 * Pager adapter for the courses hub. Extends FragmentStateAdapter, which is the ViewPager2
 * replacement for the deprecated FragmentPagerAdapter.
 */
public class AdapterCoursesHubPager extends FragmentStateAdapter {

	/** Courses, lectures, batches. Kept in step with the tab titles set by the hub fragment. */
	private static final int TAB_COUNT = 3;

	public AdapterCoursesHubPager(@NonNull Fragment fragment) {
		super(fragment);
	}

	@NonNull
	@Override
	public Fragment createFragment(int position) {
		switch (position) {
			case 0:
				return new FragmentCourseList();
			case 1:
				return new FragmentLectureList();
			case 2:
				return new FragmentBatchList();
			default:
				throw new IllegalArgumentException("Unexpected tab position: " + position);
		}
	}

	@Override
	public int getItemCount() {
		return TAB_COUNT;
	}
}
