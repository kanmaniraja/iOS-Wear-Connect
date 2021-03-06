package com.codegy.ioswearconnect;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Created by Guiye on 22/4/15.
 */
public class BLEManager {

    private static final String TAG_LOG = "BLEManager";

    private static final String DESCRIPTOR_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
    private static final String SERVICE_BLANK = "00001111-0000-1000-8000-00805f9b34fb";

    public enum BLEManagerState {
        Disconnected,
        Connecting,
        Connected,
        Reconnecting
    }

    public interface BLEManagerCallback {
        void onConnectionStateChange(BLEManagerState state);
        void onIncomingCall(NotificationData notificationData);
        void onCallEnded();
        void onNotificationReceived(NotificationData notificationData);
        void onNotificationCanceled(String notificationId);
        boolean shouldUpdateBatteryLevel();
        void onBatteryLevelChanged(int newBatteryLevel);
        void onMediaDataUpdated(byte[] packet, String attribute);
    }


    private Context mContext;
    private BLEManagerCallback mCallback;

    private PacketProcessor mPacketProcessor;

    private BluetoothLeScanner mScanner;
    private BluetoothGatt bluetoothGatt;
    private BLEManagerState state = BLEManagerState.Disconnected;
    private boolean reconnect = false;
    private int skipCount = 0;
    private int connectionFailedCount = 0;

    private List<String> characteristicsSubscribed = new ArrayList<>();
    private List<Command> pendingCommands = new ArrayList<>();
    private List<NotificationData> pendingNotifications = new ArrayList<>();

    private boolean moto360Fix;


    public BLEManager(Context context, BLEManagerCallback callback) {
        this.mContext = context;
        this.mCallback = callback;

        moto360Fix = Build.MODEL.equals("Moto 360");

        startScanner();
    }


    private void startScanner() {
        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (bluetoothAdapter != null) {
            mScanner = bluetoothAdapter.getBluetoothLeScanner();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
            mScanner.startScan(scanFilters(), settings, mScanCallback);

            Log.d(TAG_LOG, "Scanning started");
        }
        else {
            Log.d(TAG_LOG, "Bluetooth not supported");
        }
    }

    private void stopScanner() {
        if (mScanner != null) {
            mScanner.stopScan(mScanCallback);
            mScanner = null;

            Log.d(TAG_LOG, "Scanning stopped");
        }
    }

    public void close() {
        Log.d(TAG_LOG, "Close manager");

        mNextCommandHandler.removeCallbacks(mNextCommandRunnable);
        mClearOldNotificationsHandler.removeCallbacks(mClearOldNotificationsRunnable);
        mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);

        try {
            if (mScanner != null) {
                stopScanner();
            }

            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        state = BLEManagerState.Disconnected;
        reconnect = false;
        skipCount = 0;

        mPacketProcessor = null;

        pendingCommands.clear();
        pendingNotifications.clear();
        characteristicsSubscribed.clear();
    }

    public void setState(BLEManagerState state) {
        if (state == this.state) {
            return;
        }

        this.state = state;

        mCallback.onConnectionStateChange(state);
    }

    public void addCommandToQueue(Command command) {
        pendingCommands.add(command);

        sendNextCommand();
    }

    private void sendNextCommand() {
        mNextCommandHandler.removeCallbacks(mNextCommandRunnable);

        if (state == BLEManagerState.Disconnected || pendingCommands.size() == 0) {
            return;
        }

        boolean result = false;
        Command command = pendingCommands.get(0);

        try {
            BluetoothGattService service = bluetoothGatt.getService(command.getServiceUUID());

            if (service != null) {
                BluetoothGattCharacteristic bluetoothGattCharacteristic = service.getCharacteristic(UUID.fromString(command.getCharacteristic()));

                if (bluetoothGattCharacteristic != null) {
                    // not being used
                    // bluetoothGattCharacteristic.setWriteType(command.getWriteType());

                    result = bluetoothGattCharacteristic.setValue(command.getPacket());
                    Log.d(TAG_LOG, "Characteristic value set: " + result);

                    result = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
                    Log.d(TAG_LOG, "Started writing command: " + result);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            result = false;
        }

        if (!result) {
            pendingCommands.remove(command);

            if (command.shouldRetryAgain()) {
                pendingCommands.add(command);
            }
        }

        if (pendingCommands.size() > 0) {
            startNextCommandHandler();
        }
    }

    private Handler mMoto360FixHandler = new Handler();
    private Runnable mMoto360FixRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG_LOG, "Trying to keep connection alive");

            bluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            bluetoothGatt.readRemoteRssi();

            // This command should have a response from the iOS device
            Command attributeCommand = new Command(ServicesConstants.UUID_AMS, ServicesConstants.CHARACTERISTIC_ENTITY_ATTRIBUTE, new byte[]{
                    ServicesConstants.EntityIDTrack,
                    ServicesConstants.TrackAttributeIDTitle
            });

            addCommandToQueue(attributeCommand);

            startMoto360FixHandler();

            mMoto360FixHandler.removeCallbacks(mMoto360FixRunnable);
        }
    };

    private void startMoto360FixHandler() {
        if (state == BLEManagerState.Disconnected) {
            return;
        }

        Thread thread = new Thread() {
            public void run() {
                mNextCommandHandler.postDelayed(mNextCommandRunnable, 250000);
            }
        };
        thread.start();
    }

    private Handler mNextCommandHandler = new Handler();
    private Runnable mNextCommandRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG_LOG, "Sending next command");
            sendNextCommand();

            mNextCommandHandler.removeCallbacks(mNextCommandRunnable);
        }
    };

    private void startNextCommandHandler() {
        mNextCommandHandler.removeCallbacks(mNextCommandRunnable);

        if (state == BLEManagerState.Disconnected || pendingCommands.size() == 0) {
            return;
        }

        final long delay = 600 * pendingCommands.get(0).getRetryCount();

        Thread thread = new Thread() {
            public void run() {
                mNextCommandHandler.postDelayed(mNextCommandRunnable, delay);
            }
        };
        thread.start();
    }

    private Handler mClearOldNotificationsHandler = new Handler();
    private Runnable mClearOldNotificationsRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG_LOG, "Clear old notifications");
            mPacketProcessor = null;
            pendingNotifications.clear();

            mClearOldNotificationsHandler.removeCallbacks(mClearOldNotificationsRunnable);
        }
    };

    private void startClearOldNotificationsHandler() {
        Thread thread = new Thread() {
            public void run() {
                mClearOldNotificationsHandler.postDelayed(mClearOldNotificationsRunnable, 700);
            }
        };
        thread.start();
    }

    private Handler mCheckConnectingHandler = new Handler();
    private Runnable mCheckConnectingRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == BLEManagerState.Connecting) {
                Log.w(TAG_LOG, "Connecting is taking too long");

                if (bluetoothGatt != null && bluetoothGatt.getDevice() != null) {
                    BluetoothDevice device = bluetoothGatt.getDevice();
                    int bondState = device.getBondState();

                    // Don't do anything while bonding
                    if (bondState == BluetoothDevice.BOND_BONDING) {
                        Log.w(TAG_LOG, "Waiting for bond...");
                        mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);
                        startCheckConnectingHandler();
                        return;
                    }

                    connectionFailedCount++;

                    if (bondState == BluetoothDevice.BOND_NONE || connectionFailedCount > 1) {
                        connectionFailedCount = 0;
                        unpairDevice(device);
                        device.createBond();

                        // Check if bond is successful
                        startCheckConnectingHandler();
                    }
                    else {
                        bluetoothGatt.disconnect();
                    }
                }
                else {
                    close();
                    reconnect = true;
                    startScanner();
                }
            }

            mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);
        }
    };

    private void startCheckConnectingHandler() {
        Thread thread = new Thread() {
            public void run() {
                mCheckConnectingHandler.postDelayed(mCheckConnectingRunnable, 4000);
            }
        };
        thread.start();
    }


    private void requestMediaUpdates() {
        try {
            Command trackCommand = new Command(ServicesConstants.UUID_AMS, ServicesConstants.CHARACTERISTIC_ENTITY_UPDATE, new byte[] {
                    ServicesConstants.EntityIDTrack,
                    ServicesConstants.TrackAttributeIDTitle,
                    ServicesConstants.TrackAttributeIDArtist
            });

            pendingCommands.add(trackCommand);

            Command playerCommand = new Command(ServicesConstants.UUID_AMS, ServicesConstants.CHARACTERISTIC_ENTITY_UPDATE, new byte[] {
                    ServicesConstants.EntityIDPlayer,
                    ServicesConstants.PlayerAttributeIDPlaybackInfo
            });

            pendingCommands.add(playerCommand);

            sendNextCommand();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<ScanFilter> scanFilters() {
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(SERVICE_BLANK)).build();
        List<ScanFilter> list = new ArrayList<>(1);
        list.add(filter);

        return list;
    }


    private final ScanCallback mScanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
            Log.i(TAG_LOG, "Scan Result: " + result.toString());

            BluetoothDevice device = result.getDevice();

            if (state == BLEManagerState.Disconnected) {
                if (device != null) {
                    if ((!reconnect || skipCount > 5) && device.getName() != null) {
                        stopScanner();

                        Log.d(TAG_LOG, "Connecting...: " + device.getName());

                        skipCount = 0;
                        setState(BLEManagerState.Connecting);
                        bluetoothGatt = device.connectGatt(mContext, false, bluetoothGattCallback);

                        mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);
                        startCheckConnectingHandler();
                    }
                    else {
                        Log.d(TAG_LOG, "skip:: ");
                        skipCount++;
                    }
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.i(TAG_LOG, "Batch Scan Results: " + results.toString());
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.d(TAG_LOG, "Scan Failed: " + errorCode);
        }

    };

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG_LOG, "onConnectionStateChange: " + status + " -> " + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG_LOG, "Connected");

                gatt.discoverServices();

                if (moto360Fix) {
                    startMoto360FixHandler();
                }
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG_LOG, "Disconnected");

                setState(BLEManagerState.Disconnected);

                close();


                reconnect = true;

                startScanner();
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG_LOG, String.format("BluetoothGatt ReadRssi[%d]", rssi));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG_LOG, "onServicesDiscovered: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);
                subscribeCharacteristic(gatt.getService(ServicesConstants.UUID_ANCS), ServicesConstants.CHARACTERISTIC_DATA_SOURCE);
                startCheckConnectingHandler();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG_LOG, "Descriptor write successful: " + descriptor.getCharacteristic().getUuid().toString());

                mCheckConnectingHandler.removeCallbacks(mCheckConnectingRunnable);

                characteristicsSubscribed.add(descriptor.getCharacteristic().getUuid().toString());
                switch (descriptor.getCharacteristic().getUuid().toString()) {
                    case ServicesConstants.CHARACTERISTIC_DATA_SOURCE:
                        subscribeCharacteristic(gatt.getService(ServicesConstants.UUID_ANCS), ServicesConstants.CHARACTERISTIC_NOTIFICATION_SOURCE);
                        startCheckConnectingHandler();
                        break;
                    case ServicesConstants.CHARACTERISTIC_NOTIFICATION_SOURCE:
                        subscribeCharacteristic(gatt.getService(ServicesConstants.UUID_AMS), ServicesConstants.CHARACTERISTIC_REMOTE_COMMAND);
                        startCheckConnectingHandler();
                        break;
                    case ServicesConstants.CHARACTERISTIC_REMOTE_COMMAND:
                        subscribeCharacteristic(gatt.getService(ServicesConstants.UUID_AMS), ServicesConstants.CHARACTERISTIC_ENTITY_UPDATE);
                        startCheckConnectingHandler();
                        break;
                    case ServicesConstants.CHARACTERISTIC_ENTITY_UPDATE:
                        subscribeCharacteristic(gatt.getService(ServicesConstants.UUID_BAS), ServicesConstants.CHARACTERISTIC_BATTERY_LEVEL);
                        startCheckConnectingHandler();
                        break;
                    case ServicesConstants.CHARACTERISTIC_BATTERY_LEVEL:
                        requestMediaUpdates();

                        setState(BLEManagerState.Connected);

                        connectionFailedCount = 0;

                        break;
                }
            }
            else if (status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED) {
                Log.d(TAG_LOG, "status: write not permitted");

                unpairDevice(gatt.getDevice());
                gatt.disconnect();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);

            try {
                Command lastCommand = null;
                if (pendingCommands.size() > 0) {
                    lastCommand = pendingCommands.get(0);
                    pendingCommands.remove(0);
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG_LOG, "Characteristic write successful: " + characteristic.getUuid().toString());

                    // If battery is still unknown try to get its value
                    if (mCallback.shouldUpdateBatteryLevel()) {
                        try {
                            gatt.readCharacteristic(gatt.getService(ServicesConstants.UUID_BAS).getCharacteristic(UUID.fromString(ServicesConstants.CHARACTERISTIC_BATTERY_LEVEL)));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    if (moto360Fix && characteristic.getUuid().toString().equals(ServicesConstants.CHARACTERISTIC_ENTITY_ATTRIBUTE)) {
                        try {
                            gatt.readCharacteristic(gatt.getService(ServicesConstants.UUID_AMS).getCharacteristic(UUID.fromString(ServicesConstants.CHARACTERISTIC_ENTITY_ATTRIBUTE)));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }


                    sendNextCommand();
                }
                else {
                    Log.w(TAG_LOG, "Characteristic write error: " + status + " :: " + characteristic.getUuid().toString());

                    if (lastCommand != null && lastCommand.shouldRetryAgain()) {
                        pendingCommands.add(lastCommand);
                    }

                    startNextCommandHandler();
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.d(TAG_LOG, "onCharacteristicRead status:: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                /*
                String newAttribute = characteristic.getStringValue(0);
                Log.d(TAG_LOG, "onCharacteristicRead value:: " + newAttribute);
                boolean containsOldTitle = newAttribute.indexOf(mediaTitle) == 0;
                boolean containsOldArtist = newAttribute.indexOf(mediaArtist) == 0;

                if (containsOldTitle && !containsOldArtist) {
                    mediaTitle = characteristic.getStringValue(0);

                    updateMetadata();
                }
                else if (!containsOldTitle && containsOldArtist) {
                    mediaArtist = characteristic.getStringValue(0);

                    updateMetadata();
                }
                */
                if (characteristic.getUuid().toString().equals(ServicesConstants.CHARACTERISTIC_BATTERY_LEVEL)) {
                    int batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    Log.d(TAG_LOG, "BAS    CHARACTERISTIC_BATTERY_LEVEL:: " + batteryLevel);
                    mCallback.onBatteryLevelChanged(batteryLevel);
                }
                else if (characteristic.getUuid().toString().equals(ServicesConstants.CHARACTERISTIC_ENTITY_ATTRIBUTE)) {
                    String mediaTitle = characteristic.getStringValue(0);
                    Log.d(TAG_LOG, "AMS    Title:: " + mediaTitle);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Get notification packet from iOS
            byte[] packet = characteristic.getValue();

            switch (characteristic.getUuid().toString().toLowerCase()) {
                case ServicesConstants.CHARACTERISTIC_CURRENT_TIME:
                    Log.d(TAG_LOG, "CTS    CHARACTERISTIC_CURRENT_TIME:: " + characteristic.getStringValue(0));

                    break;
                case ServicesConstants.CHARACTERISTIC_BATTERY_LEVEL:
                    int batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    Log.d(TAG_LOG, "BAS    CHARACTERISTIC_BATTERY_LEVEL:: " + batteryLevel);
                    mCallback.onBatteryLevelChanged(batteryLevel);

                    break;
                case ServicesConstants.CHARACTERISTIC_ENTITY_UPDATE:
                case ServicesConstants.CHARACTERISTIC_ENTITY_ATTRIBUTE:
                    Log.d(TAG_LOG, "AMS    CHARACTERISTIC_ENTITY_UPDATE::");
                    mCallback.onMediaDataUpdated(packet, characteristic.getStringValue(3));
                    break;
                case ServicesConstants.CHARACTERISTIC_DATA_SOURCE:
                    if (mPacketProcessor == null && packet.length >= 5) {
                        byte[] notificationUID = new byte[] { packet[1], packet[2], packet[3], packet[4] };
                        int notificationIndex = -1;

                        for (int i = 0; i < pendingNotifications.size(); i++) {
                            NotificationData notificationData = pendingNotifications.get(i);

                            if (notificationData.compareUID(notificationUID)) {
                                notificationIndex = i;
                                break;
                            }
                        }

                        if (notificationIndex != -1) {
                            mPacketProcessor = new PacketProcessor(pendingNotifications.get(notificationIndex));
                            pendingNotifications.remove(notificationIndex);
                        }
                    }

                    if (mPacketProcessor != null) {
                        // Only remove callback if we are getting useful data
                        mClearOldNotificationsHandler.removeCallbacks(mClearOldNotificationsRunnable);

                        mPacketProcessor.process(packet);

                        if (mPacketProcessor.hasFinishedProcessing()) {
                            NotificationData notificationData = mPacketProcessor.getNotificationData();

                            if (notificationData != null) {
                                NotificationDataManager.updateData(notificationData);

                                if (notificationData.isIncomingCall()) {
                                    mCallback.onIncomingCall(notificationData);
                                }
                                else {
                                    mCallback.onNotificationReceived(notificationData);
                                }
                            }

                            mPacketProcessor = null;
                        }
                    }

                    if (pendingNotifications.size() > 0 || mPacketProcessor == null) {
                        // Clear notifications in case data never arrives
                        startClearOldNotificationsHandler();
                    }

                    break;
                case ServicesConstants.CHARACTERISTIC_NOTIFICATION_SOURCE:
                    try {
                        switch (packet[0]) {
                            case ServicesConstants.EventIDNotificationAdded:
                            case ServicesConstants.EventIDNotificationModified:
                                // Request attributes for the new notification
                                byte[] getAttributesPacket = new byte[] {
                                        ServicesConstants.CommandIDGetNotificationAttributes,

                                        // UID
                                        packet[4], packet[5], packet[6], packet[7],

                                        // App Identifier - NotificationAttributeIDAppIdentifier
                                        ServicesConstants.NotificationAttributeIDAppIdentifier,

                                        // Title - NotificationAttributeIDTitle
                                        // Followed by a 2-bytes max length parameter
                                        ServicesConstants.NotificationAttributeIDTitle,
                                        (byte) 0xff,
                                        (byte) 0xff,

                                        // Message - NotificationAttributeIDMessage
                                        // Followed by a 2-bytes max length parameter
                                        ServicesConstants.NotificationAttributeIDMessage,
                                        (byte) 0xff,
                                        (byte) 0xff,
                                };


                                NotificationData notificationData = new NotificationData(packet);
                                pendingNotifications.add(notificationData);


                                if (notificationData.hasPositiveAction()) {
                                    getAttributesPacket = PacketProcessor.concat(getAttributesPacket, new byte[] {
                                            // Positive Action Label - NotificationAttributeIDPositiveActionLabel
                                            ServicesConstants.NotificationAttributeIDPositiveActionLabel
                                    });
                                }
                                if (notificationData.hasNegativeAction()) {
                                    getAttributesPacket = PacketProcessor.concat(getAttributesPacket, new byte[] {
                                            // Negative Action Label - NotificationAttributeIDNegativeActionLabel
                                            ServicesConstants.NotificationAttributeIDNegativeActionLabel
                                    });
                                }

                                Command getAttributesCommand = new Command(ServicesConstants.UUID_ANCS, ServicesConstants.CHARACTERISTIC_CONTROL_POINT, getAttributesPacket);

                                pendingCommands.add(getAttributesCommand);
                                sendNextCommand();


                                // Clear notifications in case data never arrives
                                startClearOldNotificationsHandler();

                                break;
                            case ServicesConstants.EventIDNotificationRemoved:
                                if (packet[2] == 1) {
                                    // Call ended
                                    mCallback.onCallEnded();
                                }
                                else {
                                    // Cancel notification in watch
                                    String notificationId = new String(Arrays.copyOfRange(packet, 4, 8));
                                    mCallback.onNotificationCanceled(notificationId);
                                }

                                break;
                        }
                    }
                    catch(Exception e) {
                        Log.d(TAG_LOG, "error");
                        e.printStackTrace();
                    }

                    break;
            }
        }

    };

    private void subscribeCharacteristic(BluetoothGattService service, String uuidString) {
        if (service == null || uuidString == null || characteristicsSubscribed.contains(uuidString)) {
            return;
        }

        try {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuidString));

            if (characteristic != null) {
                bluetoothGatt.setCharacteristicNotification(characteristic, true);

                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(DESCRIPTOR_CONFIG));

                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    bluetoothGatt.writeDescriptor(descriptor);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void pairDevice(BluetoothDevice device) {
        Log.d(TAG_LOG, "Pairing...");
        try {
            Method m = device.getClass().getMethod("createBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void unpairDevice(BluetoothDevice device) {
        Log.d(TAG_LOG, "Unpairing...");
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
