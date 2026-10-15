package org.schabi.newpipe.fragments;

/**
 * A main tab that reloads its content when the selected tab of the floating navigation pill is
 * tapped twice in a row (Home: new recommendations, Shorts: new feed).
 */
public interface Refreshable {
    void refreshFromNavBar();
}
