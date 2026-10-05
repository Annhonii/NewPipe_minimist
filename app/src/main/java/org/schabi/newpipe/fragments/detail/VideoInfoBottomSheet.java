package org.schabi.newpipe.fragments.detail;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment;
import org.schabi.newpipe.fragments.list.comments.CommentsFragment;
import org.schabi.newpipe.util.Localization;

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
    private static final String ARG_TOP_OFFSET = "top_offset";
    private static final String KIND_COMMENTS = "comments";
    private static final String KIND_DESCRIPTION = "description";
    private static final String TAG = "VideoInfoBottomSheet";

    private FragmentManager.OnBackStackChangedListener backStackListener;
    private int initialBackStackCount;

    /** Closes the comments / description sheet if one is currently open. */
    public static void dismissIfShown(@NonNull final FragmentManager fm) {
        if (fm.isStateSaved()) {
            return;
        }
        final androidx.fragment.app.Fragment f = fm.findFragmentByTag(TAG);
        if (f instanceof VideoInfoBottomSheet) {
            ((VideoInfoBottomSheet) f).dismissAllowingStateLoss();
        }
    }

    /**
     * Shows the replies of a comment inside the open comments sheet, so the video keeps playing
     * on top (like YouTube). Returns false if no comments sheet is open.
     */
    public static boolean showRepliesIfShown(@NonNull final FragmentActivity activity,
                                             @NonNull final CommentsInfoItem comment) {
        final FragmentManager fm = activity.getSupportFragmentManager();
        final androidx.fragment.app.Fragment f = fm.findFragmentByTag(TAG);
        if (f instanceof VideoInfoBottomSheet && f.isAdded() && !f.isStateSaved()) {
            ((VideoInfoBottomSheet) f).showReplies(comment);
            return true;
        }
        return false;
    }

    private void showReplies(@NonNull final CommentsInfoItem comment) {
        final View root = getView();
        if (root != null) {
            final TextView titleView = root.findViewById(R.id.video_info_sheet_title);
            titleView.setText(Localization.replyCount(requireContext(), comment.getReplyCount()));
        }
        getChildFragmentManager().beginTransaction()
                .replace(R.id.video_info_sheet_container, new CommentRepliesFragment(comment),
                        CommentRepliesFragment.TAG)
                .addToBackStack(CommentRepliesFragment.TAG)
                .commit();
    }

    /** Goes back from replies to the comments list. Returns true if it consumed the back. */
    private boolean popReplies() {
        final FragmentManager child = getChildFragmentManager();
        if (child.getBackStackEntryCount() > 0) {
            child.popBackStack();
            final View root = getView();
            if (root != null && child.getBackStackEntryCount() <= 1) {
                ((TextView) root.findViewById(R.id.video_info_sheet_title))
                        .setText(R.string.comments_tab_description);
            }
            return true;
        }
        return false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        return new BottomSheetDialog(requireContext(), getTheme()) {
            @Override
            public void onBackPressed() {
                if (!popReplies()) {
                    super.onBackPressed();
                }
            }
        };
    }

    public static void showComments(@NonNull final FragmentManager fm, final int serviceId,
                                    final String url, final String title,
                                    final int topOffset) {
        final VideoInfoBottomSheet sheet = new VideoInfoBottomSheet();
        final Bundle args = new Bundle();
        args.putString(ARG_KIND, KIND_COMMENTS);
        args.putInt(ARG_SERVICE_ID, serviceId);
        args.putString(ARG_URL, url);
        args.putString(ARG_TITLE, title);
        args.putInt(ARG_TOP_OFFSET, topOffset);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    public static void showDescription(@NonNull final FragmentManager fm,
                                       @NonNull final StreamInfo info,
                                       final int topOffset) {
        final VideoInfoBottomSheet sheet = new VideoInfoBottomSheet();
        final Bundle args = new Bundle();
        args.putString(ARG_KIND, KIND_DESCRIPTION);
        args.putSerializable(ARG_INFO, info);
        args.putInt(ARG_TOP_OFFSET, topOffset);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        // The dialog has its own (always light) theme: inflate with the theme of the activity so
        // the sheet gets the same colors as the rest of the app, also in dark mode.
        return inflater.cloneInContext(requireActivity())
                .inflate(R.layout.bottom_sheet_video_info, container, false);
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
            setupBelowPlayerWindow((BottomSheetDialog) dialog, behavior);
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

    /**
     * Makes the dialog window only as tall as the area below the video player, without dimming
     * and without swallowing touches outside of it. This way the video stays fully visible
     * (and usable) while reading comments / the description.
     */
    private void setupBelowPlayerWindow(@NonNull final BottomSheetDialog dialog,
                                        @NonNull final BottomSheetBehavior<FrameLayout> behavior) {
        final Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        final int topOffset = requireArguments().getInt(ARG_TOP_OFFSET, 0);
        int height = screenHeight - topOffset;
        if (topOffset <= 0 || height < screenHeight / 4) {
            // fallback (e.g. fullscreen/landscape): classic tall sheet
            height = (int) (screenHeight * 0.8f);
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
        keepBelowPlayer(window, topOffset, height);
        window.setGravity(Gravity.BOTTOM);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        dialog.setCanceledOnTouchOutside(false);
        behavior.setDraggable(true);
        final View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            final ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(lp);
        }
    }

    /**
     * The window can be positioned differently than expected (e.g. because of the navigation bar),
     * so measure where the sheet really starts and shrink it until it no longer covers the video.
     */
    private void keepBelowPlayer(@NonNull final Window window, final int topOffset,
                                 final int height) {
        final View content = getView();
        if (content == null || topOffset <= 0) {
            return;
        }
        final int gap = Math.round(getResources().getDisplayMetrics().density * 4);
        content.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean adjusted;

                    @Override
                    public void onGlobalLayout() {
                        if (adjusted || content.getHeight() == 0) {
                            return;
                        }
                        adjusted = true;
                        final int[] location = new int[2];
                        content.getLocationOnScreen(location);
                        final int overlap = topOffset + gap - location[1];
                        if (overlap > 0) {
                            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                                    height - overlap);
                        }
                    }
                });
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
