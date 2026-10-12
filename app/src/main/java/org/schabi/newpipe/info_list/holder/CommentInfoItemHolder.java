package org.schabi.newpipe.info_list.holder;

import static org.schabi.newpipe.util.ServiceHelper.getServiceById;
import static org.schabi.newpipe.util.text.TouchUtils.getOffsetForHorizontalLine;

import android.content.res.ColorStateList;
import android.text.InputType;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.FragmentActivity;

import org.schabi.newpipe.R;
import org.schabi.newpipe.account.YtEngagement;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.image.CoilHelper;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.text.TextEllipsizer;

public class CommentInfoItemHolder extends InfoItemHolder {

    private static final int COMMENT_DEFAULT_LINES = 2;
    private final int commentHorizontalPadding;
    private final int commentVerticalPadding;

    private final RelativeLayout itemRoot;
    private final ImageView itemThumbnailView;
    private final TextView itemContentView;
    private final ImageView itemThumbsUpView;
    private final TextView itemLikesCountView;
    private final TextView itemTitleView;
    private final ImageView itemHeartView;
    private final ImageView itemPinnedView;
    private final Button repliesButton;
    private final ImageView itemThumbsDownView;
    private final ImageView itemMoreButton;
    private CommentsInfoItem boundItem;

    /** Text of comments edited in this session, so a recycled row still shows the new text. */
    private static final java.util.Map<String, String> EDITED_TEXT = new java.util.HashMap<>();

    /** "LIKE" / "DISLIKE" per comment id, kept so the tint survives view recycling. */
    private static final java.util.Map<String, String> RATE_STATE = new java.util.HashMap<>();

    @NonNull
    private final TextEllipsizer textEllipsizer;

    public CommentInfoItemHolder(final InfoItemBuilder infoItemBuilder,
                                 final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_comment_item, parent);

        itemRoot = itemView.findViewById(R.id.itemRoot);
        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemContentView = itemView.findViewById(R.id.itemCommentContentView);
        itemThumbsUpView = itemView.findViewById(R.id.detail_thumbs_up_img_view);
        itemLikesCountView = itemView.findViewById(R.id.detail_thumbs_up_count_view);
        itemTitleView = itemView.findViewById(R.id.itemTitleView);
        itemHeartView = itemView.findViewById(R.id.detail_heart_image_view);
        itemPinnedView = itemView.findViewById(R.id.detail_pinned_view);
        repliesButton = itemView.findViewById(R.id.replies_button);
        itemThumbsDownView = itemView.findViewById(R.id.detail_thumbs_down_img_view);
        itemMoreButton = itemView.findViewById(R.id.comment_more_button);

        commentHorizontalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_horizontal_padding);
        commentVerticalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_vertical_padding);

        textEllipsizer = new TextEllipsizer(itemContentView, COMMENT_DEFAULT_LINES, null);
        textEllipsizer.setStateChangeListener(isEllipsized -> {
            if (Boolean.TRUE.equals(isEllipsized)) {
                denyLinkFocus();
            } else {
                determineMovementMethod();
            }
        });
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof CommentsInfoItem item)) {
            return;
        }

        // load the author avatar
        CoilHelper.INSTANCE.loadAvatar(itemThumbnailView, item.getUploaderAvatars());
        if (ImageStrategy.shouldLoadImages()) {
            itemThumbnailView.setVisibility(View.VISIBLE);
            itemRoot.setPadding(commentVerticalPadding, commentVerticalPadding,
                    commentVerticalPadding, commentVerticalPadding);
        } else {
            itemThumbnailView.setVisibility(View.GONE);
            itemRoot.setPadding(commentHorizontalPadding, commentVerticalPadding,
                    commentHorizontalPadding, commentVerticalPadding);
        }
        itemThumbnailView.setOnClickListener(view -> openCommentAuthor(item));

        // setup the top row, with pinned icon, author name and comment date
        itemPinnedView.setVisibility(item.isPinned() ? View.VISIBLE : View.GONE);
        final String uploaderName = Localization.localizeUserName(item.getUploaderName());
        itemTitleView.setText(Localization.concatenateStrings(
                uploaderName,
                Localization.relativeTimeOrTextual(
                        itemBuilder.getContext(),
                        item.getUploadDate(),
                        item.getTextualUploadDate())));

        boundItem = item;

        // setup bottom row, with likes, dislike, heart and replies button
        applyRateState(item);
        itemThumbsUpView.setOnClickListener(v -> onRate(item, true));
        itemThumbsDownView.setOnClickListener(v -> onRate(item, false));
        itemMoreButton.setOnClickListener(v -> showMoreMenu(item));

        itemHeartView.setVisibility(item.isHeartedByUploader() ? View.VISIBLE : View.GONE);

        final boolean hasReplies = item.getReplies() != null;
        repliesButton.setOnClickListener(hasReplies ? v -> openCommentReplies(item) : null);
        repliesButton.setVisibility(hasReplies ? View.VISIBLE : View.GONE);
        repliesButton.setText(hasReplies
                ? Localization.replyCount(itemBuilder.getContext(), item.getReplyCount()) : "");
        ((RelativeLayout.LayoutParams) itemThumbsUpView.getLayoutParams()).topMargin =
                0;


        // setup comment content and click listeners to expand/ellipsize it
        textEllipsizer.setStreamingService(getServiceById(item.getServiceId()));
        textEllipsizer.setStreamUrl(item.getUrl());
        textEllipsizer.setContent(item.getCommentText());
        textEllipsizer.ellipsize();
        final String edited = EDITED_TEXT.get(item.getCommentId());
        if (edited != null) {
            itemContentView.setText(edited);
        }

        //noinspection ClickableViewAccessibility
        itemContentView.setOnTouchListener((v, event) -> {
            final CharSequence text = itemContentView.getText();
            if (text instanceof Spanned buffer) {
                final int action = event.getAction();

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                    final int offset = getOffsetForHorizontalLine(itemContentView, event);
                    final var links = buffer.getSpans(offset, offset, ClickableSpan.class);

                    if (links.length != 0) {
                        if (action == MotionEvent.ACTION_UP) {
                            links[0].onClick(itemContentView);
                        }
                        // we handle events that intersect links, so return true
                        return true;
                    }
                }
            }
            return false;
        });

        itemView.setOnClickListener(view -> {
            textEllipsizer.toggle();
            if (itemBuilder.getOnCommentsSelectedListener() != null) {
                itemBuilder.getOnCommentsSelectedListener().selected(item);
            }
        });

        itemView.setOnLongClickListener(view -> {
            if (DeviceUtils.isTv(itemBuilder.getContext())) {
                openCommentAuthor(item);
            } else {
                final CharSequence text = itemContentView.getText();
                if (text != null) {
                    ShareUtils.copyToClipboard(itemBuilder.getContext(), text.toString());
                }
            }
            return true;
        });
    }

    // ------------------------------------------------------------ like / dislike / reply

    private static String stateOf(final CommentsInfoItem item) {
        final String s = RATE_STATE.get(item.getCommentId());
        return s == null ? "" : s;
    }

    private void applyRateState(@NonNull final CommentsInfoItem item) {
        final String state = stateOf(item);
        final int accent = ThemeHelper.resolveColorFromAttr(itemBuilder.getContext(),
                androidx.appcompat.R.attr.colorAccent);
        final int normal = androidx.core.content.ContextCompat.getColor(
                itemBuilder.getContext(), R.color.defaultIconTint);
        ImageViewCompat.setImageTintList(itemThumbsUpView,
                ColorStateList.valueOf("LIKE".equals(state) ? accent : normal));
        ImageViewCompat.setImageTintList(itemThumbsDownView,
                ColorStateList.valueOf("DISLIKE".equals(state) ? accent : normal));
        final int base = item.getLikeCount();
        itemLikesCountView.setText(Localization.likeCount(itemBuilder.getContext(),
                base >= 0 && "LIKE".equals(state) ? base + 1 : base));
    }

    /** @return the YouTube video id of the comment, or null (toast shown) if not possible. */
    private String requireYoutubeVideoId(@NonNull final CommentsInfoItem item) {
        final android.content.Context ctx = itemBuilder.getContext();
        if (item.getServiceId() != ServiceList.YouTube.getServiceId()) {
            Toast.makeText(ctx, R.string.comment_not_youtube, Toast.LENGTH_SHORT).show();
            return null;
        }
        if (!YtEngagement.isLoggedIn(ctx)) {
            Toast.makeText(ctx, R.string.yt_sign_in_required, Toast.LENGTH_LONG).show();
            return null;
        }
        try {
            return YoutubeStreamLinkHandlerFactory.getInstance().getId(item.getUrl());
        } catch (final ParsingException e) {
            Toast.makeText(ctx, ctx.getString(R.string.yt_rate_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void onRate(@NonNull final CommentsInfoItem item, final boolean like) {
        final String videoId = requireYoutubeVideoId(item);
        if (videoId == null) {
            return;
        }
        final String id = item.getCommentId();
        final String previous = stateOf(item);
        final String target = like ? "LIKE" : "DISLIKE";
        final String kind;
        if (target.equals(previous)) {
            kind = like ? "unlike" : "undislike";
            RATE_STATE.remove(id);
        } else {
            kind = like ? "like" : "dislike";
            RATE_STATE.put(id, target);
        }
        applyRateState(item);
        final android.content.Context ctx = itemBuilder.getContext();
        YtEngagement.commentAction(ctx, videoId, id, kind).subscribe(() -> { }, e -> {
            // revert
            if (previous.isEmpty()) {
                RATE_STATE.remove(id);
            } else {
                RATE_STATE.put(id, previous);
            }
            if (boundItem == item) {
                applyRateState(item);
            }
            Toast.makeText(ctx, ctx.getString(R.string.yt_rate_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        });
    }

    private void showMoreMenu(@NonNull final CommentsInfoItem item) {
        final PopupMenu menu = new PopupMenu(itemBuilder.getContext(), itemMoreButton);
        menu.getMenu().add(0, 1, 0, R.string.comment_reply);
        menu.getMenu().add(0, 2, 1, R.string.comment_copy);
        final android.content.Context mctx = itemBuilder.getContext();
        if (item.getServiceId() == ServiceList.YouTube.getServiceId()
                && YtEngagement.isLoggedIn(mctx)
                && YtEngagement.isOwnComment(mctx, item.getUploaderName())) {
            menu.getMenu().add(0, 3, 2, R.string.comment_edit);
        }
        menu.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                showReplyDialog(item);
            } else if (mi.getItemId() == 3) {
                showEditDialog(item);
            } else {
                final CharSequence text = itemContentView.getText();
                if (text != null) {
                    ShareUtils.copyToClipboard(itemBuilder.getContext(), text.toString());
                }
            }
            return true;
        });
        menu.show();
    }

    private void showReplyDialog(@NonNull final CommentsInfoItem item) {
        final String videoId = requireYoutubeVideoId(item);
        if (videoId == null) {
            return;
        }
        final android.content.Context ctx = itemBuilder.getContext();
        final EditText input = new EditText(ctx);
        input.setHint(R.string.comment_reply_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        final int pad = DeviceUtils.dpToPx(20, ctx);
        final FrameLayout holder = new FrameLayout(ctx);
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.comment_reply_to,
                        Localization.localizeUserName(item.getUploaderName())))
                .setView(holder)
                .setPositiveButton(R.string.yt_comment_post, (d, w) -> {
                    final String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    YtEngagement.replyToComment(ctx, videoId, item.getCommentId(), text)
                            .subscribe(() -> Toast.makeText(ctx, R.string.comment_reply_posted,
                                    Toast.LENGTH_SHORT).show(),
                                    e -> Toast.makeText(ctx,
                                            ctx.getString(R.string.comment_reply_failed,
                                                    e.getMessage()),
                                            Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDialog(@NonNull final CommentsInfoItem item) {
        final String videoId = requireYoutubeVideoId(item);
        if (videoId == null) {
            return;
        }
        final android.content.Context ctx = itemBuilder.getContext();
        final String original = item.getCommentText() == null ? ""
                : androidx.core.text.HtmlCompat.fromHtml(item.getCommentText().content(),
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim();
        final EditText input = new EditText(ctx);
        input.setText(original);
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        final int pad = DeviceUtils.dpToPx(20, ctx);
        final FrameLayout holder = new FrameLayout(ctx);
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.comment_edit_title)
                .setView(holder)
                .setPositiveButton(R.string.comment_edit_save, (d, w) -> {
                    final String text = input.getText().toString().trim();
                    if (text.isEmpty() || text.equals(original)) {
                        return;
                    }
                    YtEngagement.editComment(ctx, videoId, item.getCommentId(), text)
                            .subscribe(() -> {
                                EDITED_TEXT.put(item.getCommentId(), text);
                                if (boundItem == item) {
                                    itemContentView.setText(text);
                                }
                                Toast.makeText(ctx, R.string.comment_edit_done,
                                        Toast.LENGTH_SHORT).show();
                            }, e -> Toast.makeText(ctx,
                                    ctx.getString(R.string.comment_edit_failed, e.getMessage()),
                                    Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openCommentAuthor(@NonNull final CommentsInfoItem item) {
        NavigationHelper.openCommentAuthorIfPresent((FragmentActivity) itemBuilder.getContext(),
                item);
    }

    private void openCommentReplies(@NonNull final CommentsInfoItem item) {
        final FragmentActivity activity = (FragmentActivity) itemBuilder.getContext();
        // inside the comments sheet the replies open in the sheet, so the video stays visible
        if (!org.schabi.newpipe.fragments.detail.VideoInfoBottomSheet
                .showRepliesIfShown(activity, item)) {
            NavigationHelper.openCommentRepliesFragment(activity, item);
        }
    }

    private void allowLinkFocus() {
        itemContentView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void denyLinkFocus() {
        itemContentView.setMovementMethod(null);
    }

    private boolean shouldFocusLinks() {
        if (itemView.isInTouchMode()) {
            return false;
        }

        final URLSpan[] urls = itemContentView.getUrls();

        return urls != null && urls.length != 0;
    }

    private void determineMovementMethod() {
        if (shouldFocusLinks()) {
            allowLinkFocus();
        } else {
            denyLinkFocus();
        }
    }
}
