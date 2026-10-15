package org.schabi.newpipe.fragments.list.kiosk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

/**
 * A list of videos for a trending category (Gaming, Movies, Podcasts, ...), loaded with a search
 * query. Used as one page of the {@link TrendingFragment}.
 */
public class TrendingSearchFragment extends BaseListInfoFragment<InfoItem, SearchInfo> {
    private static final List<String> VIDEO_FILTER = Collections.singletonList("videos");

    @State
    String query = "";

    public static TrendingSearchFragment getInstance(final int serviceId, final String query,
                                                     final String title) {
        final TrendingSearchFragment instance = new TrendingSearchFragment();
        instance.setInitialData(serviceId, "trending-search:" + query, title);
        instance.query = query;
        return instance;
    }

    public TrendingSearchFragment() {
        super(UserAction.SEARCHED);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_kiosk, container, false);
    }

    @Override
    protected Single<SearchInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.searchFor(serviceId, query, VIDEO_FILTER, "");
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<InfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreSearchItems(serviceId, query, VIDEO_FILTER, "",
                currentNextPage);
    }

    @Override
    public void handleResult(@NonNull final SearchInfo result) {
        super.handleResult(result);
        // the search result's name is the query: keep the category name instead
        name = getString(R.string.trending);
    }
}
