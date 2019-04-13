/*
 * Copyright 2014-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.tesla.gateway.config.eureka.util;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.concurrent.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author liushiming
 * @version InetUtils.java, v 0.0.1 2018年5月4日 下午6:48:04 liushiming
 */
public class InetUtils implements Closeable {
    public static class HostInfo {
        public boolean override;
        private String ipAddress;
        private String hostname;

        public HostInfo() {}

        public HostInfo(String hostname) {
            this.hostname = hostname;
        }

        public String getHostname() {
            return hostname;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public int getIpAddressAsInt() {
            InetAddress inetAddress = null;
            String host = this.ipAddress;
            if (host == null) {
                host = this.hostname;
            }
            try {
                inetAddress = InetAddress.getByName(host);
            } catch (final UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
            return ByteBuffer.wrap(inetAddress.getAddress()).getInt();
        }

        public boolean isOverride() {
            return override;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }
    }

    private static final InetUtils instance = new InetUtils(new InetUtilsProperties());

    /**
     * Convert an internet address to a HostInfo.
     *
     * @deprecated use the non-static convertAddress() instead
     */
    @Deprecated
    public static HostInfo convert(final InetAddress address) {
        return instance.convertAddress(address);
    }

    /**
     * Find the first non-loopback host info. If there were errors return a host info with 'localhost' and '127.0.0.1'
     * for hostname and ipAddress respectively.
     *
     * @deprecated use the non-static findFirstNonLoopbackHostInfo() instead
     */
    @Deprecated
    public static HostInfo getFirstNonLoopbackHostInfo() {
        return instance.findFirstNonLoopbackHostInfo();
    }

    public static int getIpAddressAsInt(String host) {
        return new HostInfo(host).getIpAddressAsInt();
    }

    private final ExecutorService executorService;

    private final InetUtilsProperties properties;

    private final Log log = LogFactory.getLog(InetUtils.class);

    public InetUtils(final InetUtilsProperties properties) {
        this.properties = properties;
        this.executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(InetUtilsProperties.PREFIX);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void close() {
        executorService.shutdown();
    }

    public HostInfo convertAddress(final InetAddress address) {
        HostInfo hostInfo = new HostInfo();
        Future<String> result = executorService.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return address.getHostName();
            }
        });

        String hostname;
        try {
            hostname = result.get(this.properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.info("Cannot determine local hostname");
            hostname = "localhost";
        }
        hostInfo.setHostname(hostname);
        hostInfo.setIpAddress(address.getHostAddress());
        return hostInfo;
    }

    public InetAddress findFirstNonLoopbackAddress() {
        InetAddress result = null;
        try {
            int lowest = Integer.MAX_VALUE;
            for (Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
                nics.hasMoreElements();) {
                NetworkInterface ifc = nics.nextElement();
                if (ifc.isUp()) {
                    log.trace("Testing interface: " + ifc.getDisplayName());
                    if (ifc.getIndex() < lowest || result == null) {
                        lowest = ifc.getIndex();
                    } else if (result != null) {
                        continue;
                    }

                    // @formatter:off
                    if (!ignoreInterface(ifc.getDisplayName())) {
                        for (Enumeration<InetAddress> addrs = ifc
                                .getInetAddresses(); addrs.hasMoreElements(); ) {
                            InetAddress address = addrs.nextElement();
                            if (address instanceof Inet4Address
                                    && !address.isLoopbackAddress()
                                    && !ignoreAddress(address)) {
                                log.trace("Found non-loopback interface: "
                                        + ifc.getDisplayName());
                                result = address;
                            }
                        }
                    }
                    // @formatter:on
                }
            }
        } catch (IOException ex) {
            log.error("Cannot get first non-loopback address", ex);
        }

        if (result != null) {
            return result;
        }

        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            log.warn("Unable to retrieve localhost");
        }

        return null;
    }

    public HostInfo findFirstNonLoopbackHostInfo() {
        InetAddress address = findFirstNonLoopbackAddress();
        if (address != null) {
            return convertAddress(address);
        }
        HostInfo hostInfo = new HostInfo();
        hostInfo.setHostname(this.properties.getDefaultHostname());
        hostInfo.setIpAddress(this.properties.getDefaultIpAddress());
        return hostInfo;
    }

    /**
     * for testing
     */
    boolean ignoreAddress(InetAddress address) {

        if (this.properties.isUseOnlySiteLocalInterfaces() && !address.isSiteLocalAddress()) {
            log.trace("Ignoring address: " + address.getHostAddress());
            return true;
        }

        for (String regex : this.properties.getPreferredNetworks()) {
            if (!address.getHostAddress().matches(regex) && !address.getHostAddress().startsWith(regex)) {
                log.trace("Ignoring address: " + address.getHostAddress());
                return true;
            }
        }
        return false;
    }

    /**
     * for testing
     */
    boolean ignoreInterface(String interfaceName) {
        for (String regex : this.properties.getIgnoredInterfaces()) {
            if (interfaceName.matches(regex)) {
                log.trace("Ignoring interface: " + interfaceName);
                return true;
            }
        }
        return false;
    }
}
