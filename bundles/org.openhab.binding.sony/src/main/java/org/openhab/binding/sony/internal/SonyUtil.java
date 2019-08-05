/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
/*
 *
 */
package org.openhab.binding.sony.internal;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.concurrent.Future;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.common.AbstractUID;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.sony.internal.net.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Various utilities used across the sony binding
 *
 * @author Tim Roberts - Initial contribution
 */
@NonNullByDefault
public class SonyUtil {

    /** Bigdecimal hundred (used in scale/unscale methods) */
    public static final BigDecimal BIGDECIMAL_HUNDRED = BigDecimal.valueOf(100);

    /**
     * Creates a channel identifier from the group (if specified) and channel id
     *
     * @param groupId   the possibly null, possibly empty group id
     * @param channelId the non-empty channel id
     * @return a non-null, non-empty channel id
     */
    public static String createChannelId(@Nullable String groupId, String channelId) {
        Validate.notEmpty(channelId, "channelId cannot be empty");
        return groupId == null || StringUtils.isEmpty(groupId) ? channelId : (groupId + "#" + channelId);
    }

    /**
     * This utility function will take a potential channelUID string and return a valid channelUID by removing all
     * invalidate characters (see {@link AbstractUID#SEGMENT_PATTERN})
     *
     * @param channelUIId the non-null, possibly empty channelUID to validate
     * @return a non-null, potentially empty string representing what was valid
     */
    public static String createValidChannelUId(String channelUID) {
        Objects.requireNonNull(channelUID, "channelUID cannot be null");
        return channelUID.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /**
     * Utility function to close a {@link AutoCloseable} and log any exception thrown.
     *
     * @param closeable a possibly null {@link AutoCloseable}. If null, no action is done.
     */
    public static void close(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LoggerFactory.getLogger(SonyUtil.class).debug("Exception closing: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Determines if the current thread has been interrupted or not
     *
     * @return true if interrupted, false otherwise
     */
    public static boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * Checks whether the current thread has been interrupted and throws {@link InterruptedException} if it's been
     * interrupted.
     *
     * @throws InterruptedException the interrupted exception
     */
    public static void checkInterrupt() throws InterruptedException {
        if (isInterrupted()) {
            throw new InterruptedException("thread interrupted");
        }
    }

    /**
     * Cancels the specified {@link Future}.
     *
     * @param future a possibly null future. If null, no action is done
     */
    public static void cancel(@Nullable Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * Returns a new string type or UnDefType.UNDEF if the string is null
     *
     * @param str the possibly null string
     * @return either a StringType or UnDefType.UNDEF is null
     */
    public static State newStringType(@Nullable String str) {
        return str == null ? UnDefType.UNDEF : new StringType(str);
    }

    /**
     * Returns a new decimal type or UnDefType.UNDEF if the integer is null
     *
     * @param itgr the possibly null integer
     * @return either a DecimalType or UnDefType.UNDEF is null
     */
    public static State newDecimalType(@Nullable Integer itgr) {
        return itgr == null ? UnDefType.UNDEF : new DecimalType(itgr);
    }

    /**
     * Returns a new decimal type or UnDefType.UNDEF if the double is null
     *
     * @param dbl the possibly null double
     * @return either a DecimalType or UnDefType.UNDEF is null
     */
    public static State newDecimalType(@Nullable Double dbl) {
        return dbl == null ? UnDefType.UNDEF : new DecimalType(dbl);
    }

    /**
     * Returns a new decimal type or UnDefType.UNDEF if the string representation is null
     *
     * @param nbr the possibly null, possibly empty string decimal
     * @return either a DecimalType or UnDefType.UNDEF is null
     */
    public static State newDecimalType(@Nullable String nbr) {
        return nbr == null || StringUtils.isEmpty(nbr) ? UnDefType.UNDEF : new DecimalType(nbr);
    }

    /**
     * Returns a new percent type or UnDefType.UNDEF if the value is null
     *
     * @param val the possibly null big decimal
     * @return either a PercentType or UnDefType.UNDEF is null
     */
    public static State newPercentType(@Nullable BigDecimal val) {
        return val == null ? UnDefType.UNDEF : new PercentType(val);
    }

    /**
     * Returns a new percent type or UnDefType.UNDEF if the value is null
     *
     * @param val the possibly null big decimal
     * @return either a PercentType or UnDefType.UNDEF is null
     */
    public static State newBooleanType(@Nullable Boolean val) {
        return val == null ? UnDefType.UNDEF : val.booleanValue() ? OnOffType.ON : OnOffType.OFF;
    }

    /**
     * Scales the associated big decimal within the miniumum/maximum defined
     *
     * @param value   a non-null value to scale
     * @param minimum a possibly null minimum value (if null, zero will be used)
     * @param maximum a possibly null maximum value (if null, 100 will be used)
     * @return a scaled big decimal value
     */
    public static BigDecimal scale(BigDecimal value, @Nullable BigDecimal minimum, @Nullable BigDecimal maximum) {
        Objects.requireNonNull(value, "value cannot be null");

        final int initialScale = value.scale();

        final BigDecimal min = minimum == null ? BigDecimal.ZERO : minimum;
        final BigDecimal max = maximum == null ? BIGDECIMAL_HUNDRED : maximum;

        if (min.compareTo(max) > 0) {
            return BigDecimal.ZERO;
        }

        final BigDecimal val = guard(value, min, max);
        final BigDecimal scaled = val.subtract(min).multiply(BIGDECIMAL_HUNDRED).divide(max.subtract(min),
                initialScale + 2, RoundingMode.HALF_UP);
        return guard(scaled.setScale(initialScale, RoundingMode.HALF_UP), BigDecimal.ZERO, BIGDECIMAL_HUNDRED);
    }

    /**
     * Unscales the associated big decimal within the miniumum/maximum defined
     *
     * @param value   a non-null scaled value
     * @param minimum a possibly null minimum value (if null, zero will be used)
     * @param maximum a possibly null maximum value (if null, 100 will be used)
     * @return a scaled big decimal value
     */
    public static BigDecimal unscale(BigDecimal scaledValue, @Nullable BigDecimal minimum,
            @Nullable BigDecimal maximum) {
        Objects.requireNonNull(scaledValue, "scaledValue cannot be null");

        final int initialScale = scaledValue.scale();
        final BigDecimal min = minimum == null ? BigDecimal.ZERO : minimum;
        final BigDecimal max = maximum == null ? BigDecimal.valueOf(100) : maximum;

        if (min.compareTo(max) > 0) {
            return min;
        }

        final BigDecimal scaled = guard(scaledValue, BigDecimal.ZERO, BIGDECIMAL_HUNDRED);
        final BigDecimal val = max.subtract(min)
                .multiply(scaled.divide(BIGDECIMAL_HUNDRED, initialScale + 2, RoundingMode.HALF_UP)).add(min);

        return guard(val.setScale(initialScale, RoundingMode.HALF_UP), min, max);
    }

    /**
     * Provides a guard to value (value must be within the min/max range - if outside, will be set to the min or max)
     *
     * @param value   a non-null value to guard
     * @param minimum a non-null minimum value
     * @param maximum a non-null maximum value
     * @return a big decimal within the min/max range
     */
    public static BigDecimal guard(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(minimum, "minimum cannot be null");
        Objects.requireNonNull(maximum, "maximum cannot be null");
        if (value.compareTo(minimum) < 0) {
            return minimum;
        }
        if (value.compareTo(maximum) > 0) {
            return maximum;
        }
        return value;
    }

    /**
     * Converts a nullable string to a potentially empty string (if null)
     *
     * @param str the potentially null string
     * @return a non-null, potentially empty string
     */
    public static String convertNull(@Nullable String str) {
        return convertNull(str, "");
    }

    /**
     * Converts a nullable string to the default value
     *
     * @param str      the potentially null string
     * @param defValue the default value
     * @return a non-null, potentially empty string
     */
    public static String convertNull(@Nullable String str, String defValue) {
        return str == null ? defValue : str;
    }

    /**
     * Converts a nullable integer to the default value
     *
     * @param ingr     the potentially null integer
     * @param defValue the default value
     * @return a non-null, potentially empty string
     */
    public static String convertNull(@Nullable Integer ingr, String defValue) {
        return ingr == null ? defValue : ingr.toString();
    }

    /**
     * Performs a WOL if there is a configured ip address and mac address.  If either ip address or mac address is null/empty, call is ignored
     * 
     * @param logger the non-null logger to log messages to
     * @param deviceIpAddress the possibly null, possibly empty device ip address
     * @param deviceMacAddress the possibly null, possibly empty device mac address
     */
    public static void sendWakeOnLan(Logger logger, @Nullable String deviceIpAddress,
            @Nullable String deviceMacAddress) {
        Objects.requireNonNull(logger, "logger cannot be null");

        if (deviceIpAddress != null && deviceMacAddress != null && StringUtils.isNotBlank(deviceIpAddress)
                && StringUtils.isNotBlank(deviceMacAddress)) {
            try {
                NetUtil.sendWol(deviceIpAddress, deviceMacAddress);
                logger.debug("WOL packet sent to {}", deviceMacAddress);
            } catch (IOException e) {
                logger.warn("Exception occurred sending WOL packet to {}: {}", deviceMacAddress, e);
            }
        } else {
            logger.debug(
                    "WOL packet is not supported - specify the IP address and mac address in config if you want a WOL packet sent");
        }
    }
    
    /**
     * Creates a folder if it doesn't already exist
     *
     * @param path a non-null, non-empty path
     * @return true if created, false if not (which means it already existed)
     */
    public static boolean createFolder(String path) {
        Validate.notEmpty(path, "path cannot be empty");
        final File tempDbFolder = new File(path);
        if (!tempDbFolder.exists()) {
            tempDbFolder.mkdirs();
            return true;
        }
        return false;
    }

    /**
     * Write a response out to the {@link HttpServletResponse}
     *
     * @param resp the non-null {@link HttpServletResponse}
     * @param str  the possibly null, possibly empty string content to write
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public static void write(HttpServletResponse resp, String str) throws IOException {
        Objects.requireNonNull(resp, "resp cannot be null");

        final Logger logger = LoggerFactory.getLogger(SonyUtil.class);

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        final PrintWriter pw = resp.getWriter();
        if (StringUtils.isEmpty(str)) {
            pw.print("{}");
        } else {
            pw.print(str);
        }

        logger.trace("Sending: {}", str);
        pw.flush();
    }
}
