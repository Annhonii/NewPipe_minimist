package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ThemeHelper;

/**
 * Renders a preference screen as a flat Material 3 list: full-width rows on the plain screen
 * background (no cards, no gaps, no dividers). {@link PreferenceCategory}s are rendered as small
 * primary-coloured section headers (or as plain spacers if they have no title), and a
 * {@link #EXTRA_SEGMENT_BREAK} adds a bit of extra space between two groups of rows.
 */
final class SegmentedPreferenceAdapter extends PreferenceGroupAdapter {

    /**
     * Boolean extra (set with {@code <extra android:name="segment_break" ... />} in the preference
     * xml) that starts a new card group above the preference, without needing a titled category.
     */
    static final String EXTRA_SEGMENT_BREAK = "segment_break";

        private static final int SCREEN_PADDING_DP = 24;
    private static final int ITEM_PADDING_DP = 24;
    private static final int ITEM_VERTICAL_PADDING_DP = 14;
    private static final int ITEM_MIN_HEIGHT_DP = 56;
    private static final int ICON_GAP_DP = 16;
    private static final int SECTION_GAP_DP = 24;
    private static final int SECTION_FIRST_GAP_DP = 8;
    private static final int SECTION_BOTTOM_DP = 8;
    private static final int SUMMARY_TOP_DP = 2;
    private static final float TITLE_SP = 14f;
    private static final float SUMMARY_SP = 12f;
    private static final float HEADER_SP = 12f;

    @NonNull
    private final Context context;
    @Nullable
    private final Typeface regular;
    @Nullable
    private final Typeface medium;

    SegmentedPreferenceAdapter(@NonNull final PreferenceGroup preferenceGroup,
                               @NonNull final Context ctx) {
        super(preferenceGroup);
        this.context = ctx;
        // System font only
        this.regular = Typeface.DEFAULT;
        this.medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder,
                                 final int position) {
        super.onBindViewHolder(holder, position);

        final Preference preference = getItem(position);
        if (preference instanceof PreferenceCategory) {
            styleHeader(holder, position, preference);
        } else {
            styleItem(holder, position);
        }
    }

    //region Items

    private void styleItem(@NonNull final PreferenceViewHolder holder, final int position) {
        final boolean breakAbove = position > 0 && startsGroup(position);

        final View root = holder.itemView;
        root.setBackground(createRipple());
        root.setMinimumHeight(dp(ITEM_MIN_HEIGHT_DP));
        root.setPadding(dp(ITEM_PADDING_DP), 0, dp(ITEM_PADDING_DP), 0);

        final ViewGroup.LayoutParams params = root.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMargins(0,
                    breakAbove && !isHeader(position - 1) ? dp(SECTION_GAP_DP) : 0, 0, 0);
            root.setLayoutParams(margins);
        }

        final View iconFrame = holder.findViewById(androidx.preference.R.id.icon_frame);
        if (iconFrame != null) {
            iconFrame.setMinimumWidth(0);
            iconFrame.setPadding(0, 0, dp(ICON_GAP_DP), 0);
        }

        final TextView title = textView(holder, android.R.id.title);
        final TextView summary = textView(holder, android.R.id.summary);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
            title.setTypeface(regular);
            // The text block carries the vertical padding of the row.
            if (title.getParent() instanceof View) {
                ((View) title.getParent()).setPadding(0, dp(ITEM_VERTICAL_PADDING_DP), 0,
                        dp(ITEM_VERTICAL_PADDING_DP));
            }
        }
        if (summary != null) {
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
            summary.setTypeface(regular);
            summary.setPadding(0, dp(SUMMARY_TOP_DP), 0, 0);
        }
    }

    @NonNull
    private RippleDrawable createRipple() {
        final GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFF000000);

        final int ripple = ThemeHelper.resolveColorFromAttr(context,
                androidx.appcompat.R.attr.colorControlHighlight);
        return new RippleDrawable(ColorStateList.valueOf(ripple), null, mask);
    }

    //endregion

    //region Section headers

    private void styleHeader(@NonNull final PreferenceViewHolder holder, final int position,
                             @NonNull final Preference category) {
        final boolean hasTitle = !TextUtils.isEmpty(category.getTitle());
        final int top = position == 0 ? SECTION_FIRST_GAP_DP : SECTION_GAP_DP;

        final View root = holder.itemView;
        root.setBackground(null);
        root.setMinimumHeight(0);
        root.setPadding(dp(SCREEN_PADDING_DP), dp(top), dp(SCREEN_PADDING_DP),
                hasTitle ? dp(SECTION_BOTTOM_DP) : 0);

        final ViewGroup.LayoutParams params = root.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, 0, 0);
            root.setLayoutParams(params);
        }

        final TextView title = textView(holder, android.R.id.title);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP);
            title.setTypeface(medium);
            title.setTextColor(ThemeHelper.resolveColorFromAttr(context,
                    androidx.appcompat.R.attr.colorPrimary));
            title.setAllCaps(false);
        }
    }

    private boolean isHeader(final int position) {
        return getItem(position) instanceof PreferenceCategory;
    }

    private boolean startsGroup(final int position) {
        if (position < 0 || position >= getItemCount()) {
            return false;
        }
        final Preference preference = getItem(position);
        return preference != null && preference.getExtras().getBoolean(EXTRA_SEGMENT_BREAK);
    }

    //endregion

    @Nullable
    private static TextView textView(@NonNull final PreferenceViewHolder holder,
                                     final int id) {
        final View view = holder.findViewById(id);
        return view instanceof TextView ? (TextView) view : null;
    }

    private int dp(final int value) {
        return DeviceUtils.dpToPx(value, context);
    }
}
