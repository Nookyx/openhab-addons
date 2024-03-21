/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.somfycul.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TooManyListenersException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortEvent;
import org.openhab.core.io.transport.serial.SerialPortEventListener;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CULHandler} is responsible for handling commands, which are
 * sent via the CUL stick.
 *
 * @author Marc Klasser - Initial contribution
 *
 */
@NonNullByDefault
public class CULHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CULHandler.class);

    private long lastCommandTime = 0;

    @Nullable
    private SerialPortIdentifier portId;
    @Nullable
    private SerialPort serialPort;
    private final SerialPortManager serialPortManager;
    @Nullable
    private OutputStream outputStream;
    @Nullable
    private InputStream inputStream;

    public CULHandler(Bridge bridge, SerialPortManager serialPortManager) {
        super(bridge);
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // the bridge does not have any channels
    }

    /**
     * Executes the given {@link SomfyCommand} for the given {@link Thing} (RTS Device).
     *
     * @param somfyDevice the RTS Device which is the receiver of the command.
     * @param somfyCommand
     * @return
     */
    public boolean executeCULCommand(Thing somfyDevice, SomfyCommand somfyCommand, String rollingCode, String adress) {
        String culCommand = "Ys" + "A1" + somfyCommand.getActionKey() + "0" + rollingCode + adress;
        logger.info("Send message {} for thing {}", culCommand, somfyDevice.getLabel());
        return writeString(culCommand);
    }

    /**
     * Sends a string to the serial port of this device.
     * The writing of the msg is executed synchronized, so it's guaranteed that the device doesn't get
     * multiple messages concurrently.
     *
     * @param msg
     *            the string to send
     * @return true, if the message has been transmitted successfully, otherwise false.
     */
    protected synchronized boolean writeString(final String msg) {
        logger.debug("Trying to write '{}' to serial port {}", msg, portId.getName());

        // TODO Check for status of bridge
        final long earliestNextExecution = lastCommandTime + 100;
        while (earliestNextExecution > System.currentTimeMillis()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                return false;
            }
        }
        try {
            final List<Boolean> listenerResult = new ArrayList<>();
            serialPort.addEventListener(new SerialPortEventListener() {
                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
                        final StringBuilder sb = new StringBuilder();
                        final byte[] readBuffer = new byte[20];
                        try {
                            do {
                                while (inputStream.available() > 0) {
                                    final int bytes = inputStream.read(readBuffer);
                                    sb.append(new String(readBuffer, 0, bytes));
                                }
                                try {
                                    // add wait states around reading the stream, so that interrupted transmissions are
                                    // merged
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    // ignore interruption
                                }
                            } while (inputStream.available() > 0);
                            final String result = sb.toString();
                            if (result.equals(msg)) {
                                listenerResult.add(true);
                            }
                        } catch (IOException e) {
                            logger.debug("Error receiving data on serial port {}: {}", portId.getName(),
                                    e.getMessage());
                        }
                    }
                }
            });
            serialPort.notifyOnDataAvailable(true);
            outputStream.write(msg.getBytes());
            outputStream.flush();
            lastCommandTime = System.currentTimeMillis();
            final long timeout = lastCommandTime + 1000;
            while (listenerResult.isEmpty() && System.currentTimeMillis() < timeout) {
                // Waiting for response
                Thread.sleep(100);
            }
            return !listenerResult.isEmpty();
        } catch (IOException | TooManyListenersException | InterruptedException e) {
            logger.error("Error writing '{}' to serial port {}: {}", msg, portId.getName(), e.getMessage());
        } finally {
            serialPort.removeEventListener();
        }
        return false;
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing!");
        CULConfiguration config = getConfigAs(CULConfiguration.class);
        if (validConfiguration(config)) {
            String port = config.port;
            portId = serialPortManager.getIdentifier(port);
            if (portId == null) {
                String availablePorts = serialPortManager.getIdentifiers().map(id -> id.getName())
                        .collect(Collectors.joining(System.lineSeparator()));
                String description = String.format("Serial port '%s' could not be found. Available ports are:%n%s",
                        port, availablePorts);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, description);
                return;
            }
            logger.info("got port: {}", config.port);
            try {
                serialPort = portId.open("openHAB", 2000);
                // set port parameters
                serialPort.setSerialPortParams(config.baudrate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                        SerialPort.PARITY_NONE);
                inputStream = serialPort.getInputStream();
                outputStream = serialPort.getOutputStream();
                // TODO: Check version of CUL
                updateStatus(ThingStatus.ONLINE);
                logger.debug("Finished initializing!");
            } catch (IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "IO Error: " + e.getMessage());
            } catch (PortInUseException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Port already used: " + port);
            } catch (UnsupportedCommOperationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Unsupported operation on port '" + port + "': " + e.getMessage());
            }
        }
    }

    private boolean validConfiguration(@Nullable CULConfiguration config) {
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "somfycul configuration missing");
            return false;
        }
        if (config.port.isEmpty() || config.baudrate == 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "somfycul port or baudrate not specified");
            return false;
        }
        return true;
    }

    @Override
    public void dispose() {
        super.dispose();
        if (serialPort != null) {
            serialPort.removeEventListener();
        }
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                logger.debug("Error while closing the output stream: {}", e.getMessage());
            }
        }
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                logger.debug("Error while closing the input stream: {}", e.getMessage());
            }
        }
        if (serialPort != null) {
            serialPort.close();
        }
        outputStream = null;
        inputStream = null;
        serialPort = null;
    }
}
