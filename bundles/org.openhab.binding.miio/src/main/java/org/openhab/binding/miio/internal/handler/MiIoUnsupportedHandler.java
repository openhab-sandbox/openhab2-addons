/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.miio.internal.handler;

import static org.openhab.binding.miio.internal.MiIoBindingConstants.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.miio.internal.MiIoBindingConfiguration;
import org.openhab.binding.miio.internal.MiIoBindingConstants;
import org.openhab.binding.miio.internal.MiIoCommand;
import org.openhab.binding.miio.internal.MiIoDevices;
import org.openhab.binding.miio.internal.MiIoSendCommand;
import org.openhab.binding.miio.internal.Utils;
import org.openhab.binding.miio.internal.basic.MiIoBasicChannel;
import org.openhab.binding.miio.internal.basic.MiIoBasicDevice;
import org.openhab.binding.miio.internal.basic.MiIoDatabaseWatchService;
import org.openhab.core.OpenHAB;
import org.openhab.core.cache.ExpiringCache;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link MiIoUnsupportedHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marcel Verpaalen - Initial contribution
 */
@NonNullByDefault
public class MiIoUnsupportedHandler extends MiIoAbstractHandler {
    private final Logger logger = LoggerFactory.getLogger(MiIoUnsupportedHandler.class);

    private static final DateTimeFormatter DATEFORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DB_FOLDER_NAME = OpenHAB.getUserDataFolder() + File.separator
            + MiIoBindingConstants.BINDING_ID;
    final MiIoBindingConfiguration conf = getConfigAs(MiIoBindingConfiguration.class);

    private StringBuilder sb = new StringBuilder();
    private String info = "";
    int lastCommand = -1;
    private LinkedHashMap<Integer, MiIoBasicChannel> testChannelList = new LinkedHashMap<>();
    private LinkedHashMap<MiIoBasicChannel, String> supportedChannelList = new LinkedHashMap<>();

    private final ExpiringCache<Boolean> updateDataCache = new ExpiringCache<>(CACHE_EXPIRY, () -> {
        scheduler.schedule(this::updateData, 0, TimeUnit.SECONDS);
        return true;
    });

    public MiIoUnsupportedHandler(Thing thing, MiIoDatabaseWatchService miIoDatabaseWatchService) {
        super(thing, miIoDatabaseWatchService);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            if (updateDataCache.isExpired()) {
                logger.debug("Refreshing {}", channelUID);
                updateDataCache.getValue();
            } else {
                logger.debug("Refresh {} skipped. Already refreshing", channelUID);
            }
            return;
        }
        if (channelUID.getId().equals(CHANNEL_POWER)) {
            if (command.equals(OnOffType.ON)) {
                sendCommand("set_power[\"on\"]");
            } else {
                sendCommand("set_power[\"off\"]");
            }
        }
        if (channelUID.getId().equals(CHANNEL_COMMAND)) {
            cmds.put(sendCommand(command.toString()), command.toString());
        }
        if (channelUID.getId().equals(CHANNEL_TESTCOMMANDS)) {
            executeExperimentalCommands();
        }
    }

    private LinkedHashMap<String, MiIoBasicChannel> collectProperties(String model) {
        LinkedHashMap<String, MiIoBasicChannel> testChannelsList = new LinkedHashMap<>();
        LinkedHashSet<MiIoDevices> testDeviceList = new LinkedHashSet<>();

        // first add similar devices to test those channels first, then test all others
        final String mm = model.substring(0, model.lastIndexOf("."));
        for (MiIoDevices dev : MiIoDevices.values()) {
            if (dev.getThingType().equals(THING_TYPE_BASIC) && dev.getModel().contains(mm)) {
                testDeviceList.add(dev);
            }
        }
        for (MiIoDevices dev : MiIoDevices.values()) {
            if (dev.getThingType().equals(THING_TYPE_BASIC)) {
                testDeviceList.add(dev);
            }
        }
        for (MiIoDevices dev : testDeviceList) {
            for (MiIoBasicChannel ch : getBasicChannels(dev.getModel())) {
                if (!ch.isMiOt() && !ch.getProperty().isBlank() && !testChannelsList.containsKey(ch.getProperty())) {
                    testChannelsList.put(ch.getProperty(), ch);
                }
            }
        }
        return testChannelsList;
    }

    private List<MiIoBasicChannel> getBasicChannels(String deviceName) {
        logger.debug("Adding Channels from model: {}", deviceName);
        URL fn = miIoDatabaseWatchService.getDatabaseUrl(deviceName);
        if (fn == null) {
            logger.warn("Database entry for model '{}' cannot be found.", deviceName);
            return Collections.emptyList();
        }
        try {
            JsonObject deviceMapping = Utils.convertFileToJSON(fn);
            logger.debug("Using device database: {} for device {}", fn.getFile(), deviceName);
            Gson gson = new GsonBuilder().serializeNulls().create();
            final MiIoBasicDevice device = gson.fromJson(deviceMapping, MiIoBasicDevice.class);
            return device.getDevice().getChannels();
        } catch (JsonIOException | JsonSyntaxException e) {
            logger.warn("Error parsing database Json", e);
        } catch (IOException e) {
            logger.warn("Error reading database file", e);
        } catch (Exception e) {
            logger.warn("Error creating channel structure", e);
        }
        return Collections.emptyList();
    }

    private void executeExperimentalCommands() {
        LinkedHashMap<String, MiIoBasicChannel> channelList = collectProperties(conf.model);
        sendCommand(MiIoCommand.MIIO_INFO);
        sb = new StringBuilder();
        logger.info("Start experimental testing of supported properties for device '{}'. ", miDevice.toString());
        sb.append("Info for ");
        sb.append(conf.model);
        sb.append("\r\n");
        sb.append("Properties: ");
        int lastCommand = -1;
        for (String c : channelList.keySet()) {
            String cmd = "get_prop[" + c + "]";
            sb.append(c);
            sb.append(" -> ");
            lastCommand = sendCommand(cmd);
            sb.append(lastCommand);
            sb.append(", ");
            testChannelList.put(lastCommand, channelList.get(c));
        }
        this.lastCommand = lastCommand - 100;
        sb.append("\r\n");
        logger.info(sb.toString());
    }

    @Override
    protected synchronized void updateData() {
        if (skipUpdate()) {
            return;
        }
        logger.debug("Periodic update for '{}' ({})", getThing().getUID().toString(), getThing().getThingTypeUID());
        try {
            refreshNetwork();
        } catch (Exception e) {
            logger.debug("Error while updating '{}' ({})", getThing().getUID().toString(), getThing().getThingTypeUID(),
                    e);
        }
    }

    @Override
    public void onMessageReceived(MiIoSendCommand response) {
        super.onMessageReceived(response);
        if (MiIoCommand.MIIO_INFO.equals(response.getCommand()) && !response.isError()) {
            JsonObject miioinfo = response.getResult().getAsJsonObject();
            miioinfo.remove("token");
            miioinfo.remove("ap");
            miioinfo.remove("mac");
            info = miioinfo.toString();
            sb.append(info);
            sb.append("\r\n");
        }
        if (lastCommand >= response.getId()) {
            sb.append(response.getCommandString());
            sb.append(" -> ");
            sb.append(response.getResponse());
            sb.append("\r\n");
            String res = response.getResult().toString();
            if (!response.isError() && !res.contentEquals("[null]") && !res.contentEquals("[]")) {
                logger.info("got one Supported prop: {}");
                if (testChannelList.containsKey(response.getId())) {
                    supportedChannelList.put(testChannelList.get(response.getId()), res);
                }
            }
        }
        if (lastCommand >= 0 & lastCommand <= response.getId()) {
            lastCommand = -1;
            sb.append("====================================\r\n");
            sb.append("Responsive properties\r\n");
            sb.append("====================================\r\n");
            sb.append("Device Info: ");
            sb.append(info);
            for (MiIoBasicChannel ch : supportedChannelList.keySet()) {
                sb.append("Property: ");
                sb.append(Utils.minLengthString(ch.getProperty(), 15));
                sb.append(" Friendly Name: ");
                sb.append(Utils.minLengthString(ch.getFriendlyName(), 25));
                sb.append(" Response: ");
                sb.append(supportedChannelList.get(ch));
                sb.append("\r\n");
            }
            logger.info(sb.toString());
            String fileDest = "test-" + (conf.model != null ? conf.model : "") + "-"
                    + LocalDateTime.now().format(DATEFORMATTER);
            Path path = Paths.get(DB_FOLDER_NAME + File.separator + fileDest + ".txt");
            try {
                Files.write(path, sb.toString().getBytes());
                logger.info("Saved device testing file to {}", path);

            } catch (IOException e) {
                logger.debug("Error writing file {}: {}", fileDest, e.getMessage());
            }
            updateState(CHANNEL_TESTCOMMANDS, OnOffType.OFF);
        }
    }

}
