package org.schabi.newpipe.fragments.detail;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.fragments.list.comments.CommentsFragment;

/**
 * YouTube-style sheet that shows either the full comments list or the video description
 * on top of the video page.
 */
public class VideoInfoBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_KIND = "kind";
    private static final String ARG_SERVICE_ID = "service_id";
    private static final String ARG_URL = "url";
    private static final String ARG_TITLE = "title";
    private static final String ARG_INFO = "info";
    private static final String KIND_COMMENTS = "comments";
    private static final String KIND_DESCRIPTION = "description";
    private static final String TAG = "VideoInfoBottomSheet";

    private FragmentManager.OnBackStackChangedListener backStackListener;
    private int initialBackStackCount;

    public static void showComments(@NonNull final FragmentManager fm, final int serviceId,
                                    final String url, final String title) {
        final VideoInfoBottomSheet sheet = new VideoInfoBottomSheet();
        final Bundle args = new Bundle();
        args.putString(ARG_KIND, KIND_COMMENTS);
        args.putInt(ARG_SERVICE_ID, serviceId);
        args.putString(ARG_URL, url);
        args.putString(ARG_TITLE, title);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    public static void showDescription(@NonNull final FragmentManager fm,
                                       @NonNull final StreamInfo info) {
        final VideoInfoBottomSheet sheet = new VideoInfoBottomSheet();
        final Bundle args = new Bundle();
        args.putString(ARG_KIND, KIND_DESCRIPTION);
        args.putSerializable(ARG_INFO, info);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View root = inflater.inflate(R.layout.bottom_sheet_video_info, container, false);
        final int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        } else {
            lp.height = height;
        }
        root.setLayoutParams(lp);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Bundle args = requireArguments();
        final boolean comments = KIND_COMMENTS.equals(args.getString(ARG_KIND));

        final TextView titleView = view.findViewById(R.id.video_info_sheet_title);
        titleView.setText(comments
                ? R.string.comments_tab_description : R.string.description_tab_description);

        if (savedInstanceState == null) {
            final androidx.fragment.app.Fragment content;
            if (comments) {
                content = CommentsFragment.getInstance(args.getInt(ARG_SERVICE_ID),
                        args.getString(ARG_URL), args.getString(ARG_TITLE));
            } else {
                final StreamInfo info = (StreamInfo) args.getSerializable(ARG_INFO);
                content = info == null ? new androidx.fragment.app.Fragment()
                        : new DescriptionFragment(info);
            }
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.video_info_sheet_container, content)
                    .commitAllowingStateLoss();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        final Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            final BottomSheetBehavior<FrameLayout> behavior =
                    ((BottomSheetDialog) dialog).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        // Opening a comment's replies pushes a new screen: close the sheet in that case.
        final FragmentManager activityFm = requireActivity().getSupportFragmentManager();
        initialBackStackCount = activityFm.getBackStackEntryCount();
        backStackListener = () -> {
            if (activityFm.getBackStackEntryCount() > initialBackStackCount) {
                dismissAllowingStateLoss();
            }
        };
        activityFm.addOnBackStackChangedListener(backStackListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (backStackListener != null && getActivity() != null) {
            getActivity().getSupportFragmentManager()
                    .removeOnBackStackChangedListener(backStackListener);
            backStackListener = null;
        }
    }
}
