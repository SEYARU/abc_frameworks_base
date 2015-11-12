/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.net.config;

import android.util.Pair;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.X509TrustManager;

/**
 * An application's network security configuration.
 *
 * <p>{@link #getConfigForHostname(String)} provides a means to obtain network security
 * configuration to be used for communicating with a specific hostname.</p>
 *
 * @hide
 */
public final class ApplicationConfig {
    private static ApplicationConfig sInstance;
    private static Object sLock = new Object();

    private Set<Pair<Domain, NetworkSecurityConfig>> mConfigs;
    private NetworkSecurityConfig mDefaultConfig;
    private X509TrustManager mTrustManager;

    private ConfigSource mConfigSource;
    private boolean mInitialized;
    private final Object mLock = new Object();

    public ApplicationConfig(ConfigSource configSource) {
        mConfigSource = configSource;
        mInitialized = false;
    }

    /**
     * @hide
     */
    public boolean hasPerDomainConfigs() {
        ensureInitialized();
        return mConfigs != null && !mConfigs.isEmpty();
    }

    /**
     * Get the {@link NetworkSecurityConfig} corresponding to the provided hostname.
     * When matching the most specific matching domain rule will be used, if no match exists
     * then the default configuration will be returned.
     *
     * {@code NetworkSecurityConfig} objects returned by this method can be safely cached for
     * {@code hostname}. Subsequent calls with the same hostname will always return the same
     * {@code NetworkSecurityConfig}.
     *
     * @return {@link NetworkSecurityConfig} to be used to determine
     * the network security configuration for connections to {@code hostname}.
     */
    public NetworkSecurityConfig getConfigForHostname(String hostname) {
        ensureInitialized();
        if (hostname.isEmpty() || mConfigs == null) {
            return mDefaultConfig;
        }
        if (hostname.charAt(0) ==  '.') {
            throw new IllegalArgumentException("hostname must not begin with a .");
        }
        // Domains are case insensitive.
        hostname = hostname.toLowerCase(Locale.US);
        // Normalize hostname by removing trailing . if present, all Domain hostnames are
        // absolute.
        if (hostname.charAt(hostname.length() - 1) == '.') {
            hostname = hostname.substring(0, hostname.length() - 1);
        }
        // Find the Domain -> NetworkSecurityConfig entry with the most specific matching
        // Domain entry for hostname.
        // TODO: Use a smarter data structure for the lookup.
        Pair<Domain, NetworkSecurityConfig> bestMatch = null;
        for (Pair<Domain, NetworkSecurityConfig> entry : mConfigs) {
            Domain domain = entry.first;
            NetworkSecurityConfig config = entry.second;
            // Check for an exact match.
            if (domain.hostname.equals(hostname)) {
                return config;
            }
            // Otherwise check if the Domain includes sub-domains and that the hostname is a
            // sub-domain of the Domain.
            if (domain.subdomainsIncluded
                    && hostname.endsWith(domain.hostname)
                    && hostname.charAt(hostname.length() - domain.hostname.length() - 1) == '.') {
                if (bestMatch == null) {
                    bestMatch = entry;
                } else if (domain.hostname.length() > bestMatch.first.hostname.length()) {
                    bestMatch = entry;
                }
            }
        }
        if (bestMatch != null) {
            return bestMatch.second;
        }
        // If no match was found use the default configuration.
        return mDefaultConfig;
    }

    /**
     * Returns the {@link X509TrustManager} that implements the checking of trust anchors and
     * certificate pinning based on this configuration.
     */
    public X509TrustManager getTrustManager() {
        ensureInitialized();
        return mTrustManager;
    }

    private void ensureInitialized() {
        synchronized(mLock) {
            if (mInitialized) {
                return;
            }
            mConfigs = mConfigSource.getPerDomainConfigs();
            mDefaultConfig = mConfigSource.getDefaultConfig();
            mConfigSource = null;
            mTrustManager = new RootTrustManager(this);
            mInitialized = true;
        }
    }

    public static void setDefaultInstance(ApplicationConfig config) {
        synchronized (sLock) {
            sInstance = config;
        }
    }

    public static ApplicationConfig getDefaultInstance() {
        synchronized (sLock) {
            return sInstance;
        }
    }

    /** @hide */
    public static ApplicationConfig getPlatformDefault() {
        return new ApplicationConfig(new ConfigSource() {
            @Override
            public NetworkSecurityConfig getDefaultConfig() {
                return NetworkSecurityConfig.DEFAULT;
            }
            @Override
            public Set<Pair<Domain, NetworkSecurityConfig>> getPerDomainConfigs() {
                return null;
            }
        });
    }
}
