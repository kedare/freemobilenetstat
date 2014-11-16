package org.pixmob.freemobile.netstat.ui;

import static org.pixmob.freemobile.netstat.BuildConfig.DEBUG;
import static org.pixmob.freemobile.netstat.Constants.TAG;

import org.pixmob.freemobile.netstat.R;
import org.pixmob.freemobile.netstat.content.Statistics;
import org.pixmob.freemobile.netstat.content.StatisticsLoader;
import org.pixmob.freemobile.netstat.content.NetstatContract.Events;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Application chartFragment.
 * @author gilbsgilbs
 */
public class MobileNetworkChartFragment extends Fragment implements LoaderCallbacks<Statistics> {

    private ContentObserver contentMonitor;
    private View statisticsWrapperLayout;
    private ProgressBar progressBar;
    private MobileNetworkChart mobileNetworkChart;
    private TextView onOrange2GnetworkTextView;
    private TextView onOrange3GnetworkTextView;
    private TextView onFreeMobile3GnetworkTextView;
    private TextView onFreeMobile4GnetworkTextView;


    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Monitor database updates: when new data is available, this fragment
        // is updated with the new values.
        contentMonitor = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);

                Log.i(TAG, "Content updated: refresh statistics");
                refresh();
            }
        };

        // Get widgets.
        final Activity a = getActivity();
        statisticsWrapperLayout = a.findViewById(R.id.statistics_wrapper_layout);
        progressBar = (ProgressBar) a.findViewById(R.id.states_progress);
        mobileNetworkChart = (MobileNetworkChart) a.findViewById(R.id.mobile_network_chart);
        onOrange2GnetworkTextView = (TextView) a.findViewById(R.id.on_orange_2G_network);
        onOrange3GnetworkTextView = (TextView) a.findViewById(R.id.on_orange_3G_network);
        onFreeMobile3GnetworkTextView = (TextView) a.findViewById(R.id.on_free_mobile_3G_network);
        onFreeMobile4GnetworkTextView = (TextView) a.findViewById(R.id.on_free_mobile_4G_network);
        
        // The fields are hidden the first time this fragment is displayed,
        // while statistics data are being loaded.
        statisticsWrapperLayout.setVisibility(View.INVISIBLE);

        getLoaderManager().initLoader(0, null, this);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Monitor database updates if the fragment is displayed.
        final ContentResolver cr = getActivity().getContentResolver();
        cr.registerContentObserver(Events.CONTENT_URI, true, contentMonitor);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop monitoring database updates.
        getActivity().getContentResolver().unregisterContentObserver(contentMonitor);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_network_chart_fragment, container, false);
    }
    
	@Override
	public Loader<Statistics> onCreateLoader(int arg0, Bundle arg1) {
        progressBar.setVisibility(View.VISIBLE);

        return new StatisticsLoader(getActivity());
	}

	@Override
	public void onLoadFinished(Loader<Statistics> loader, Statistics s) {
		Log.i(TAG, "Statistics loaded: " + s);

		mobileNetworkChart.setData(s.orangeUsePercent, s.freeMobileUsePercent,
				s.orange2GUsePercent, s.freeMobile3GUsePercent);
	    onOrange2GnetworkTextView.setText(s.orange2GUsePercent * s.orangeUsePercent / 100 + "%");
	    onOrange3GnetworkTextView.setText(s.orange3GUsePercent * s.orangeUsePercent / 100 + "%");
	    onFreeMobile3GnetworkTextView.setText(s.freeMobile3GUsePercent * s.freeMobileUsePercent / 100 + "%");
	    onFreeMobile4GnetworkTextView.setText(s.freeMobile4GUsePercent * s.freeMobileUsePercent / 100 + "%");

        progressBar.setVisibility(View.INVISIBLE);
        statisticsWrapperLayout.setVisibility(View.VISIBLE);
        statisticsWrapperLayout.invalidate();
        mobileNetworkChart.invalidate();
	}

	@Override
	public void onLoaderReset(Loader<Statistics> loader) {
	}

    private void refresh() {
        if (isDetached()) {
            return;
        }
        if (getLoaderManager().hasRunningLoaders()) {
            if (DEBUG) {
                Log.d(TAG, "Skip statistics refresh: already running");
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Refresh statistics");
        }
        getLoaderManager().restartLoader(0, null, this);
    }
}